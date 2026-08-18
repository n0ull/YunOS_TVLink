package app.tvlink.proto

import app.tvlink.proto.cast.CastController
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Loopback fake TV: after play(), the poller must fetch GET /playback-info and surface
 * state/duration/position via onEvent (real firmware pushes no POST /event).
 */
class CastControllerTest {
    private companion object {
        /** 超大 body 字符数（超 CastController 的 64KB 保留上限）。 */
        const val OVERSIZED_BODY_CHARS = 100_000
    }

    private val server = ServerSocket(0)

    @AfterTest
    fun tearDown() {
        server.close()
    }

    private fun readStartLine(reader: BufferedReader): String? {
        while (true) {
            val line = reader.readLine() ?: return null
            if (line.isNotBlank()) return line
        }
    }

    private fun consumeRest(reader: BufferedReader) {
        var contentLen = 0
        while (true) {
            val h = reader.readLine()
            if (h.isNullOrEmpty()) break
            if (h.lowercase().startsWith("content-length:")) {
                contentLen = h.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        val buf = CharArray(4096)
        var left = contentLen
        while (left > 0) {
            // EOF 返回 -1：不减反增会死循环（left -= -1），必须 break
            val n = reader.read(buf, 0, minOf(buf.size, left))
            if (n < 0) break
            left -= n
        }
    }

    /**
     * Loopback fake TV：playback-info 返回播放 JSON，其余空 body；
     * [oversizedFirst]=true 时首个响应携带 100KB body（超 64KB 保留上限），之后恢复正常。
     */
    private fun serveFakeTv(
        sock: Socket,
        oversizedFirst: Boolean = false,
    ) {
        val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
        val out = sock.getOutputStream()
        var isFirst = true
        while (true) {
            val start = readStartLine(reader) ?: return
            consumeRest(reader)
            val body =
                if (oversizedFirst && isFirst) {
                    "x".repeat(OVERSIZED_BODY_CHARS)
                } else if (start.startsWith("GET /playback-info")) {
                    """{"position":12,"duration":34,"state":"playing","rate":1,"name":"x"}"""
                } else {
                    ""
                }
            isFirst = false
            val bytes = body.toByteArray(Charsets.UTF_8)
            out.write(
                "HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.ISO_8859_1),
            )
            if (bytes.isNotEmpty()) out.write(bytes)
            out.flush()
        }
    }

    @Test
    fun pollingDeliversPlaybackState() {
        thread(isDaemon = true) {
            runCatching { server.accept().use { serveFakeTv(it) } }
        }

        val cc = CastController("127.0.0.1", server.localPort)
        assertTrue(cc.connect(), "connect failed")
        var got: Triple<CastController.PlayState, Long, Long>? = null
        val latch = CountDownLatch(1)
        cc.onEvent = { st, dur, pos, _, _ ->
            got = Triple(st, dur, pos)
            latch.countDown()
        }
        assertTrue(cc.play(), "play() failed")
        assertTrue(latch.await(4, TimeUnit.SECONDS), "no playback-info poll event")
        val g = assertNotNull(got)
        assertEquals(CastController.PlayState.PLAYING, g.first)
        assertEquals(34L, g.second)
        assertEquals(12L, g.third)
        cc.disconnect()
    }

    /**
     * 超大 content-length（>64KB 保留上限）回归：body 被完整消耗（流同步）使本次响应干净交付，
     * 随后通道按「超限即对端异常」关闭——后续请求立即失败而非 10s 超时挂起。
     */
    @Test
    fun oversizedBodyDeliveredThenChannelCloses() {
        thread(isDaemon = true) {
            runCatching { server.accept().use { serveFakeTv(it, oversizedFirst = true) } }
        }
        val cc = CastController("127.0.0.1", server.localPort)
        assertTrue(cc.connect(), "connect failed")
        assertTrue(cc.pause(), "oversized-body request failed")
        // reader 线程交付响应后即关通道，稍等状态落定（回环下为毫秒级）
        var waited = 0L
        while (cc.state != CastController.State.DISCONNECTED && waited < 3_000) {
            Thread.sleep(50)
            waited += 50
        }
        assertEquals(CastController.State.DISCONNECTED, cc.state)
        assertTrue(!cc.play(), "channel should reject requests after oversized body")
        cc.disconnect()
    }

    /**
     * 短 body EOF 回归：对端声明 Content-Length=100 只发 5 字节即断流——
     * 帧已损坏，交付截断体会污染解析；reader 须判帧错误并关通道（而非按截断 body 交付）。
     */
    @Test
    fun shortBodyEofClosesChannel() {
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { sock ->
                    val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
                    val out = sock.getOutputStream()
                    readStartLine(reader)
                    consumeRest(reader)
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    out.write("short".toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                }
            }
        }
        val cc = CastController("127.0.0.1", server.localPort)
        assertTrue(cc.connect(), "connect failed")
        // 后台发请求（其响应永不到达，10s poll 超时由守护线程承担）
        thread(isDaemon = true) { cc.pause() }
        var waited = 0L
        while (cc.state != CastController.State.DISCONNECTED && waited < 3_000) {
            Thread.sleep(50)
            waited += 50
        }
        assertEquals(CastController.State.DISCONNECTED, cc.state)
        cc.disconnect()
    }

    /**
     * 伪造 Content-Length: -1（后随完整伪响应）回归：协议违规快速失败——
     * 交付空 body 解开在途请求后即关通道。伪响应必须是完整消息：旧实现（coerce 为 0 继续解析）
     * 会把它当正常响应吞掉、通道保持存活（状态不落 DISCONNECTED 且后续 play() 成功），
     * 本测试据此区分新旧行为；零散的非法字节在旧实现下同样会因 socket EOF 关通道，无法区分。
     */
    @Test
    fun negativeContentLengthDeliveredThenChannelCloses() {
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { sock ->
                    val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
                    val out = sock.getOutputStream()
                    readStartLine(reader)
                    consumeRest(reader)
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: -1\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    // 完整伪响应（旧实现会误解析并吞掉它，通道不断）
                    out.write("HTTP/1.1 400 JUNK\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                    // 保持通道服务下一个请求：旧实现的 play() 会发到这里并成功（回归即被捕获）
                    readStartLine(reader)
                    consumeRest(reader)
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                }
            }
        }
        val cc = CastController("127.0.0.1", server.localPort)
        assertTrue(cc.connect(), "connect failed")
        assertTrue(cc.pause(), "negative content-length request failed")
        var waited = 0L
        while (cc.state != CastController.State.DISCONNECTED && waited < 3_000) {
            Thread.sleep(50)
            waited += 50
        }
        assertEquals(CastController.State.DISCONNECTED, cc.state)
        assertTrue(!cc.play(), "channel should reject requests after negative content-length")
        cc.disconnect()
    }

    /**
     * Content-Length 超 Int 范围（99999999999）回归：toIntOrNull 失败按 0 处理会把
     * 后随 body 字节留在流中误解析（永久失步且通道不关闭——负 CL 已快速失败，此处对称补齐）。
     * 伪响应同负 CL 测试：旧实现误吞后通道存活、play() 成功（回归即被捕获）。
     */
    @Test
    fun hugeContentLengthBeyondIntRangeClosesChannel() {
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { sock ->
                    val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
                    val out = sock.getOutputStream()
                    readStartLine(reader)
                    consumeRest(reader)
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 99999999999\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    out.write("HTTP/1.1 400 JUNK\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                    readStartLine(reader)
                    consumeRest(reader)
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                }
            }
        }
        val cc = CastController("127.0.0.1", server.localPort)
        assertTrue(cc.connect(), "connect failed")
        assertTrue(cc.pause(), "huge content-length request failed")
        var waited = 0L
        while (cc.state != CastController.State.DISCONNECTED && waited < 3_000) {
            Thread.sleep(50)
            waited += 50
        }
        assertEquals(CastController.State.DISCONNECTED, cc.state)
        assertTrue(!cc.play(), "channel should reject requests after out-of-range content-length")
        cc.disconnect()
    }

    /** 对端杀会话不回响应（TV 双会话归属错乱时杀旧通道的真实行为,2026-07-25 实证）：
     *  旧实现 poll 干等 10s 且 state 停留 CONNECTED——通道半死,后续请求全部同方式失败。
     *  修复后:reader EOF → disconnect 释放在途 poll,请求快速 false 且状态落 DISCONNECTED。 */
    @Test
    fun peerSessionKillFailsFastAndMarksDead() {
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { sock ->
                    // 读一个请求后沉默关闭(不响应)——模拟 TV 杀掉旧会话
                    val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
                    readStartLine(reader)
                    consumeRest(reader)
                }
            }
        }
        val cc = CastController("127.0.0.1", server.localPort)
        assertTrue(cc.connect(), "connect failed")
        val start = System.currentTimeMillis()
        assertTrue(!cc.pause(), "request to killed session must fail")
        val elapsed = System.currentTimeMillis() - start
        assertTrue(elapsed < 3_000, "request hung ${elapsed}ms — in-flight poll not released on disconnect")
        assertEquals(CastController.State.DISCONNECTED, cc.state, "channel must be marked dead after failure")
        cc.disconnect()
    }

    /**
     * 背靠背请求回归：requestRaw 先武装 waitingResp 再写（reader 不会丢弃响应）；
     * 任何响应丢失都表现为 10s poll 超时后的 false，故总耗时须远小于超时。
     */
    @Test
    fun backToBackRequestsLoseNoResponses() {
        thread(isDaemon = true) {
            runCatching { server.accept().use { serveFakeTv(it) } }
        }
        val cc = CastController("127.0.0.1", server.localPort)
        assertTrue(cc.connect(), "connect failed")
        val start = System.currentTimeMillis()
        assertTrue(cc.play(), "play() failed")
        assertTrue(cc.pause(), "pause() failed")
        assertTrue(cc.seek(1_000), "seek() failed")
        assertTrue(cc.volume(5), "volume() failed")
        assertTrue(cc.rate(2), "rate() failed")
        val elapsed = System.currentTimeMillis() - start
        assertTrue(elapsed < 8_000, "requests took ${elapsed}ms — possible lost response (10s timeout path)")
        cc.disconnect()
    }
}

package app.tvlink.proto

import app.tvlink.proto.cast.MediaHttpServer
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MediaHttpServerTest {
    private val server = MediaHttpServer()

    @AfterTest
    fun tearDown() = server.stop()

    @Test
    fun servesRegisteredFileFullAndRange() {
        val tmp = File.createTempFile("tvlink-test", ".bin")
        val content = ByteArray(100_000) { (it % 251).toByte() }
        tmp.writeBytes(content)
        assertTrue(server.start("127.0.0.1", 8192))
        server.register("video-item-1", tmp, "application/octet-stream")

        // full GET
        val full = URL(server.urlFor("video-item-1")).readBytes()
        assertEquals(content.size, full.size)
        assertTrue(content.contentEquals(full))

        // range GET 100..199
        val conn = URL(server.urlFor("video-item-1")).openConnection()
        conn.setRequestProperty("Range", "bytes=100-199")
        val range = conn.getInputStream().readBytes()
        assertEquals(100, range.size)
        assertTrue(content.copyOfRange(100, 200).contentEquals(range))

        // unregistered path -> connection closes without data
        try {
            URL(server.urlFor("nope")).readBytes()
            throw AssertionError("expected failure for unknown path")
        } catch (e: Exception) {
            // server closed connection — expected
            System.err.println("MediaHttpServerTest: unknown path read failed as expected: ${e.message}")
        }
        tmp.delete()
    }

    @Test
    fun servesLargeFileFullAndMidRange() {
        // 大文件路径：pumpFile 的 transferTo 零拷贝分支（内容不等长断言防截断/错位）
        val tmp = File.createTempFile("tvlink-test-large", ".mp3")
        val content = ByteArray(8 * 1024 * 1024 + 777) { (it * 31 % 253).toByte() }
        tmp.writeBytes(content)
        assertTrue(server.start("127.0.0.1", 8192))
        server.register("audio-item-1", tmp, "audio/mpeg")

        val full = URL(server.urlFor("audio-item-1")).readBytes()
        assertEquals(content.size, full.size)
        assertTrue(content.contentEquals(full))

        // 中段 Range（TV 大文件分段拉取）
        val from = 3 * 1024 * 1024 + 11
        val to = 5 * 1024 * 1024 - 1
        val conn = URL(server.urlFor("audio-item-1")).openConnection()
        conn.setRequestProperty("Range", "bytes=$from-$to")
        val range = conn.getInputStream().readBytes()
        assertEquals(to - from + 1, range.size)
        assertTrue(content.copyOfRange(from, to + 1).contentEquals(range))
        tmp.delete()
    }

    @Test
    fun allowedIpClientIsServed() {
        // 正向对照：allowedClientIp=本机回环时正常供片——仅有反向拒绝用例时，
        // IP 比较写反（合法 TV 全被拒）测试照样全绿而无报警
        val tmp = File.createTempFile("tvlink-test-ip-ok", ".bin")
        val content = ByteArray(64) { it.toByte() }
        tmp.writeBytes(content)
        assertTrue(server.start("127.0.0.1", 8192, allowedClientIp = "127.0.0.1"))
        server.register("ok", tmp)
        val conn = URL(server.urlFor("ok")).openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        assertTrue(content.contentEquals(conn.getInputStream().readBytes()))
        conn.disconnect()
        tmp.delete()
    }

    @Test
    fun rejectsClientFromNonAllowedIp() {
        // H1 回归：allowedClientIp 非本机来源时直接关连接，不出数据
        val tmp = File.createTempFile("tvlink-test-ip", ".bin")
        tmp.writeBytes(ByteArray(64) { it.toByte() })
        assertTrue(server.start("127.0.0.1", 8192, allowedClientIp = "192.0.2.1"))
        server.register("guarded", tmp)
        // 显式超时：未来「挂起型」回归（不应答也不关连）表现为失败而非挂死测试
        val conn = URL(server.urlFor("guarded")).openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        try {
            conn.getInputStream().readBytes()
            throw AssertionError("expected rejection for non-allowed client IP")
        } catch (e: Exception) {
            // 服务器不应答直接关连接 —— 预期
            System.err.println("MediaHttpServerTest: non-allowed IP rejected as expected: ${e.message}")
        } finally {
            conn.disconnect()
        }
        tmp.delete()
    }

    @Test
    fun consecutiveFetchesAllServed() {
        // 同一媒体可反复拉取（供片后不再注销——注销时机押在未录包验证的 TV 行为上：
        // 首请求若为完整 GET 渐进嗅探，serve 后注销会断后续 seek/Range）；
        // 暴露窗口收窄改由 CastFeature 下次投屏时清旧条目承担
        val tmp = File.createTempFile("tvlink-test-repeat", ".bin")
        val content = ByteArray(4096) { (it % 251).toByte() }
        tmp.writeBytes(content)
        assertTrue(server.start("127.0.0.1", 8192))
        server.register("repeat", tmp)

        fun fetch(rangeHeader: String? = null): ByteArray {
            val conn = URL(server.urlFor("repeat")).openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            if (rangeHeader != null) conn.setRequestProperty("Range", rangeHeader)
            val bytes = conn.getInputStream().readBytes()
            conn.disconnect()
            return bytes
        }

        assertTrue(content.contentEquals(fetch()), "full fetch failed")
        assertEquals(100, fetch("bytes=0-99").size, "first range fetch failed")
        assertTrue(content.contentEquals(fetch()), "second full fetch failed")
        assertEquals(100, fetch("bytes=500-599").size, "second range fetch failed")
        tmp.delete()
    }

    @Test
    fun malformedRangeGets416() {
        // RFC 7233 §4.4 回归：from>to / from≥total 的 Range 不可满足 → 416 + Content-Range: bytes */total；
        // 旧实现解析出 from>to 会写出负数 Content-Length（协议违规输出）
        val tmp = File.createTempFile("tvlink-test-416", ".bin")
        tmp.writeBytes(ByteArray(1000) { (it % 251).toByte() })
        assertTrue(server.start("127.0.0.1", 8192))
        server.register("ranged", tmp)

        for (bad in listOf("bytes=500-200", "bytes=1000-", "bytes=-0")) {
            val conn = URL(server.urlFor("ranged")).openConnection() as HttpURLConnection
            conn.setRequestProperty("Range", bad)
            assertEquals(416, conn.responseCode, "Range $bad should be 416")
            assertEquals("bytes */1000", conn.getHeaderField("Content-Range"), "Range $bad Content-Range")
            conn.disconnect()
        }
        tmp.delete()
    }

    @Test
    fun requestLineOverLimitConnectionClosed() {
        // LOW-1 回归：请求行超过 8KB 上限时服务器直接关连接，不分配大 StringBuilder
        val tmp = File.createTempFile("tvlink-test-limit", ".bin")
        tmp.writeBytes(ByteArray(64) { it.toByte() })
        assertTrue(server.start("127.0.0.1", 8192)) // 不设 allowedClientIp，任意来源可连
        server.register("ok", tmp)

        Socket("127.0.0.1", server.port).use { socket ->
            val out = socket.getOutputStream()
            val inp = socket.getInputStream()
            // 请求行内容（不含 \r\n）= 8193 字符，超出 MAX_REQUEST_LINE_CHARS
            val overPath = "x".repeat(8193)
            out.write("GET /$overPath HTTP/1.1\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
            socket.soTimeout = 3000
            // 服务器应直接关连接，read 返回 -1
            val r = runCatching { inp.read() }.getOrDefault(-2)
            assertEquals(-1, r, "over-limit request line should close connection without response")
        }
        tmp.delete()
    }

    @Test
    fun requestLineExactlyAtLimitServed() {
        // LOW-1'' 回归：请求行恰好 8KB（= limit）时予以接受，正常供片——
        // 与原 `length > limit` 语义一致（恰好 limit 字符不拒）
        val tmp = File.createTempFile("tvlink-test-exact", ".bin")
        val content = ByteArray(64) { it.toByte() }
        tmp.writeBytes(content)
        assertTrue(server.start("127.0.0.1", 8192))
        // 请求行内容（不含 \r\n）需恰好 8192 字符：
        // "GET /" = 5，path，" HTTP/1.1" = 10 → path = 8192 - 15 = 8177
        val pathLen = 8 * 1024 - "GET /".length - " HTTP/1.1".length
        val path = "x".repeat(pathLen)
        server.register(path, tmp)

        Socket("127.0.0.1", server.port).use { socket ->
            val out = socket.getOutputStream()
            val inp = socket.getInputStream()
            out.write("GET /$path HTTP/1.1\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
            socket.soTimeout = 3000
            val response = runCatching { inp.readBytes() }.getOrNull()
            assertNotNull(response, "exactly-limit request line should be accepted and served")
            val statusLine = String(response, Charsets.US_ASCII).lineSequence().first()
            assertTrue(statusLine.startsWith("HTTP/1.1 200"), "expected 200 OK, got: $statusLine")
            // body 在 headers 之后，校验末尾 64 字节与注册文件内容一致
            val bodyStart = String(response, Charsets.US_ASCII).indexOf("\r\n\r\n") + 4
            assertTrue(response.size >= bodyStart + content.size, "response should include full body")
            val body = response.copyOfRange(bodyStart, response.size)
            assertTrue(content.contentEquals(body), "body should match registered file content")
        }
        tmp.delete()
    }
}

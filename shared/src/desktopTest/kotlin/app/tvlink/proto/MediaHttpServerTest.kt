package app.tvlink.proto

import app.tvlink.proto.cast.MediaHttpServer
import java.io.File
import java.net.URL
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

        // range GET 100..199 先行：完整 200 供片后条目即注销（H1 回归）
        val conn = URL(server.urlFor("video-item-1")).openConnection()
        conn.setRequestProperty("Range", "bytes=100-199")
        val range = conn.getInputStream().readBytes()
        assertEquals(100, range.size)
        assertTrue(content.copyOfRange(100, 200).contentEquals(range))

        // full GET
        val full = URL(server.urlFor("video-item-1")).readBytes()
        assertEquals(content.size, full.size)
        assertTrue(content.contentEquals(full))

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

        // 中段 Range（TV 大文件分段拉取）先行：完整 200 后条目即注销（H1 回归）
        val from = 3 * 1024 * 1024 + 11
        val to = 5 * 1024 * 1024 - 1
        val conn = URL(server.urlFor("audio-item-1")).openConnection()
        conn.setRequestProperty("Range", "bytes=$from-$to")
        val range = conn.getInputStream().readBytes()
        assertEquals(to - from + 1, range.size)
        assertTrue(content.copyOfRange(from, to + 1).contentEquals(range))

        val full = URL(server.urlFor("audio-item-1")).readBytes()
        assertEquals(content.size, full.size)
        assertTrue(content.contentEquals(full))
        tmp.delete()
    }

    @Test
    fun rejectsClientFromNonAllowedIp() {
        // H1 回归：allowedClientIp 非本机来源时直接关连接，不出数据
        val tmp = File.createTempFile("tvlink-test-ip", ".bin")
        tmp.writeBytes(ByteArray(64) { it.toByte() })
        assertTrue(server.start("127.0.0.1", 8192, allowedClientIp = "192.0.2.1"))
        server.register("guarded", tmp)
        try {
            URL(server.urlFor("guarded")).readBytes()
            throw AssertionError("expected rejection for non-allowed client IP")
        } catch (e: Exception) {
            // 服务器不应答直接关连接 —— 预期
            System.err.println("MediaHttpServerTest: non-allowed IP rejected as expected: ${e.message}")
        }
        tmp.delete()
    }

    @Test
    fun unregistersAfterFullServeButKeepsRangeServable() {
        // H1 回归：Range(206) 供片保留条目（分段拉流可续）；完整 200 供片后注销，再取即拒
        val tmp = File.createTempFile("tvlink-test-once", ".bin")
        val content = ByteArray(4096) { (it % 251).toByte() }
        tmp.writeBytes(content)
        assertTrue(server.start("127.0.0.1", 8192))
        server.register("one-shot", tmp)

        val conn = URL(server.urlFor("one-shot")).openConnection()
        conn.setRequestProperty("Range", "bytes=0-99")
        val head = conn.getInputStream().readBytes()
        assertEquals(100, head.size)

        val full = URL(server.urlFor("one-shot")).readBytes()
        assertTrue(content.contentEquals(full))

        try {
            URL(server.urlFor("one-shot")).readBytes()
            throw AssertionError("expected second full fetch to fail after unregister")
        } catch (e: Exception) {
            System.err.println("MediaHttpServerTest: post-serve fetch rejected as expected: ${e.message}")
        }
        tmp.delete()
    }
}

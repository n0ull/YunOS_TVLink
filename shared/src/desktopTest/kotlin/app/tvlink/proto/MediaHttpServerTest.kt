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
}

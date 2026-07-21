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
        var left = contentLen
        while (left > 0) {
            left -= reader.read(CharArray(left.coerceAtMost(4096)))
        }
    }

    private fun serveFakeTv(sock: Socket) {
        val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
        val out = sock.getOutputStream()
        while (true) {
            val start = readStartLine(reader) ?: return
            consumeRest(reader)
            val body =
                if (start.startsWith("GET /playback-info")) {
                    """{"position":12,"duration":34,"state":"playing","rate":1,"name":"x"}"""
                } else {
                    ""
                }
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
        cc.onEvent = { st, dur, pos ->
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
}

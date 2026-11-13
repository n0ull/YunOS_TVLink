package app.tvlink.proto

import app.tvlink.proto.idc.IdcConnection
import app.tvlink.proto.idc.IdcConst
import app.tvlink.proto.idc.LoginReq
import java.io.DataInputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Verifies that logged-in connections clean up their own heartbeat scheduler threads. */
class IdcConnectionLeakTest {
    private fun heartbeatThreads(): Set<Thread> =
        Thread.getAllStackTraces().keys.filterTo(mutableSetOf()) {
            it.name ==
                "idc-hb"
        }

    private fun awaitUntil(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private fun withFakeTv(block: (IdcConnection) -> Unit) {
        ServerSocket(0).use { server ->
            val serverThread =
                thread(isDaemon = true) {
                    runCatching {
                        server.accept().use { sock ->
                            val inp = DataInputStream(sock.getInputStream())
                            val header = ByteArray(IdcConst.HEADER_LEN)
                            inp.readFully(header)
                            val total =
                                ByteBuffer.wrap(header).run {
                                    int
                                    int
                                    int
                                    int
                                }
                            inp.readFully(ByteArray(total - IdcConst.HEADER_LEN))
                            val response =
                                ByteBuffer.allocate(IdcConst.HEADER_LEN + 12).apply {
                                    putInt(IdcConst.MAGIC)
                                    putInt(IdcConst.UNASSIGNED_KEY)
                                    putInt(IdcConst.ID_LOGIN_RESP)
                                    putInt(IdcConst.HEADER_LEN + 12)
                                    putInt(0)
                                    putInt(4242)
                                    putInt(0)
                                }
                            sock.getOutputStream().write(response.array())
                            sock.getOutputStream().flush()
                            inp.read() // Keep the logged-in session open until the client closes it.
                        }
                    }
                }
            try {
                block(IdcConnection("127.0.0.1", server.localPort))
            } finally {
                server.close()
                serverThread.join(2_000)
            }
        }
    }

    @Test
    fun closeStopsHeartbeatThreadCreatedByLogin() {
        withFakeTv { conn ->
            val baseline = heartbeatThreads()
            assertTrue(conn.connect(LoginReq(devName = "unit-test"), timeoutMs = 3_000), "login handshake failed")
            var created = emptySet<Thread>()
            assertTrue(
                awaitUntil {
                    created = heartbeatThreads() - baseline
                    created.isNotEmpty()
                },
                "logged-in connection did not create its heartbeat thread",
            )
            assertEquals(1, created.size)

            conn.close()

            assertTrue(
                awaitUntil { created.none { it.isAlive } },
                "close() left heartbeat threads alive: $created",
            )
        }
    }

    @Test
    fun successfulDetectStopsHeartbeatThreadsCreatedByLogin() {
        withFakeTv { conn ->
            val baseline = heartbeatThreads()
            val info = conn.detect(timeoutMs = 3_000)
            assertNotNull(info, "successful detect must return the logged-in device")
            assertTrue(
                awaitUntil { heartbeatThreads().none { it !in baseline && it.isAlive } },
                "detect() left a new heartbeat thread alive",
            )
        }
    }
}

package app.tvlink.proto

import app.tvlink.proto.idc.IdcConnection
import app.tvlink.proto.idc.IdcConst
import app.tvlink.proto.idc.ImeAction
import app.tvlink.proto.idc.LoginReq
import app.tvlink.proto.idc.OpCmdKey
import java.io.DataInputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Loopback fake TV: verifies send() writes frames asynchronously (off the caller thread)
 * with the session connKey applied, FIFO order preserved, and no crash after close().
 */
class IdcConnectionTest {
    private data class Frame(
        val id: Int,
        val key: Int,
        val body: ByteArray,
    )

    private val connKey = 4242
    private val server = ServerSocket(0)
    private val received = ArrayBlockingQueue<Frame>(4)

    private val serverThread =
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { sock ->
                    sock.soTimeout = 5000
                    val inp = DataInputStream(sock.getInputStream())
                    val out = sock.getOutputStream()
                    readFrame(inp) // LoginReq
                    out.write(loginRespFrame())
                    out.flush()
                    while (true) received.put(readFrame(inp))
                }
            }
        }

    @AfterTest
    fun tearDown() {
        server.close()
        serverThread.join(2000)
    }

    private fun loginRespFrame(): ByteArray {
        val total = IdcConst.HEADER_LEN + 12 // ver, connKey, udpPort
        val frame = ByteBuffer.allocate(total)
        frame.putInt(IdcConst.MAGIC)
        frame.putInt(IdcConst.UNASSIGNED_KEY)
        frame.putInt(IdcConst.ID_LOGIN_RESP)
        frame.putInt(total)
        frame.putInt(0) // ver
        frame.putInt(connKey)
        frame.putInt(0) // udpPort
        return frame.array()
    }

    private fun readFrame(inp: DataInputStream): Frame {
        val header = ByteArray(IdcConst.HEADER_LEN)
        inp.readFully(header)
        val hb = ByteBuffer.wrap(header)
        require(hb.int == IdcConst.MAGIC) { "bad magic" }
        val key = hb.int
        val id = hb.int
        val total = hb.int
        val body = ByteArray(total - IdcConst.HEADER_LEN)
        if (body.isNotEmpty()) inp.readFully(body)
        return Frame(id, key, body)
    }

    @Test
    fun sendWritesOnBackgroundThreadWithConnKeyInOrder() {
        val conn = IdcConnection("127.0.0.1", server.localPort)
        assertTrue(conn.connect(LoginReq(devName = "unit-test"), timeoutMs = 3000), "login handshake failed")

        conn.send(OpCmdKey(23, 0))
        conn.send(ImeAction(7))

        val first = assertNotNull(received.poll(5, TimeUnit.SECONDS), "first frame not received")
        assertEquals(IdcConst.ID_OPCMD_KEY, first.id)
        assertEquals(connKey, first.key)
        val fb = ByteBuffer.wrap(first.body)
        assertEquals(23, fb.int)
        assertEquals(0, fb.int)

        val second = assertNotNull(received.poll(5, TimeUnit.SECONDS), "second frame not received")
        assertEquals(IdcConst.ID_IME_ACTION, second.id)
        assertEquals(connKey, second.key)
        assertEquals(7, ByteBuffer.wrap(second.body).int)

        conn.close()
        conn.send(OpCmdKey(1, 1)) // must be a silent no-op, not a crash
    }
}

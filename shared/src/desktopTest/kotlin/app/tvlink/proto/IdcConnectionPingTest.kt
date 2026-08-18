package app.tvlink.proto

import app.tvlink.proto.idc.IdcConnection
import app.tvlink.proto.idc.IdcConst
import app.tvlink.proto.idc.LoginReq
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * IdcConnection.ping() 主动探活回归(截图应答超时疑似僵尸场景):
 * 半开连接上 reader 无限阻塞,心跳周期最坏 ~60s 才判死;ping 应在 ~3s 内给出死活结论。
 */
class IdcConnectionPingTest {
    private val server = ServerSocket(0)

    @Volatile
    private var sock: Socket? = null
    private val echoHeartbeat = AtomicBoolean(true)

    @AfterTest
    fun tearDown() {
        server.close()
        runCatching { sock?.close() }
    }

    /** 假 TV:回 LoginResp;之后按 [echoHeartbeat] 决定心跳是否回声(seq 原样返回)。 */
    private fun serve() {
        thread(isDaemon = true) {
            runCatching {
                val s = server.accept()
                sock = s
                val inp = DataInputStream(s.getInputStream())
                var isFirst = true
                while (true) {
                    val header = ByteArray(IdcConst.HEADER_LEN)
                    inp.readFully(header)
                    val hb = ByteBuffer.wrap(header)
                    hb.int // magic
                    hb.int // key
                    val packetId = hb.int
                    val total = hb.int
                    val body = ByteArray(total - IdcConst.HEADER_LEN)
                    if (body.isNotEmpty()) inp.readFully(body)
                    if (isFirst) {
                        isFirst = false
                        val resp = ByteBuffer.allocate(IdcConst.HEADER_LEN + 12)
                        resp.putInt(IdcConst.MAGIC)
                        resp.putInt(IdcConst.UNASSIGNED_KEY)
                        resp.putInt(IdcConst.ID_LOGIN_RESP)
                        resp.putInt(IdcConst.HEADER_LEN + 12)
                        resp.putInt(0) // ver
                        resp.putInt(4242) // connKey
                        resp.putInt(0) // udpPort
                        s.getOutputStream().write(resp.array())
                        s.getOutputStream().flush()
                    } else if (packetId == IdcConst.ID_HEARTBEAT && echoHeartbeat.get()) {
                        val resp = ByteBuffer.allocate(total)
                        resp.put(header)
                        resp.put(body)
                        s.getOutputStream().write(resp.array())
                        s.getOutputStream().flush()
                    }
                }
            }
        }
    }

    @Test
    fun pingHealthyConnectionReturnsTrue() {
        serve()
        val conn = IdcConnection("127.0.0.1", server.localPort)
        assertTrue(conn.connect(LoginReq(devName = "t"), timeoutMs = 3000), "connect failed")
        assertTrue(conn.ping(1_500), "ping against live peer must be true")
        conn.shutdown()
    }

    @Test
    fun pingSilentPeerReturnsFalseQuickly() {
        serve()
        val conn = IdcConnection("127.0.0.1", server.localPort)
        assertTrue(conn.connect(LoginReq(devName = "t"), timeoutMs = 3000), "connect failed")
        echoHeartbeat.set(false) // 僵尸化:对端不再回声
        val start = System.currentTimeMillis()
        assertFalse(conn.ping(1_500), "ping against silent peer must be false")
        val elapsed = System.currentTimeMillis() - start
        assertTrue(elapsed < 4_000, "ping took ${elapsed}ms — must conclude within ~timeout, not heartbeat周期")
        conn.shutdown()
    }
}

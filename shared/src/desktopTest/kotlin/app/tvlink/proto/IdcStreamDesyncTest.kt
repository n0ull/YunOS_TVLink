package app.tvlink.proto

import app.tvlink.proto.idc.IdcConnection
import app.tvlink.proto.idc.IdcConst
import app.tvlink.proto.idc.LoginReq
import java.io.DataInputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 跳帧修复（9bc823b）的另一侧护栏：单帧 decode 异常跳过续读，但 magic/total 失步
 * （帧边界不可信）仍必须拆连——防后续改动把「失步」也误并为「跳过」。
 */
class IdcStreamDesyncTest {
    private val server = ServerSocket(0)

    private val serverThread =
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { sock ->
                    sock.soTimeout = 5000
                    val inp = DataInputStream(sock.getInputStream())
                    val header = ByteArray(IdcConst.HEADER_LEN)
                    inp.readFully(header)
                    val total =
                        ByteBuffer
                            .wrap(header)
                            .run {
                                int
                                int
                                int
                                int
                            }
                    if (total > IdcConst.HEADER_LEN) inp.readFully(ByteArray(total - IdcConst.HEADER_LEN))
                    // loginResp
                    val resp = ByteBuffer.allocate(IdcConst.HEADER_LEN + 12)
                    resp.putInt(IdcConst.MAGIC)
                    resp.putInt(IdcConst.UNASSIGNED_KEY)
                    resp.putInt(IdcConst.ID_LOGIN_RESP)
                    resp.putInt(IdcConst.HEADER_LEN + 12)
                    resp.putInt(0)
                    resp.putInt(4242)
                    resp.putInt(0)
                    sock.getOutputStream().write(resp.array())
                    // 失步字节流：magic 错误，帧边界不可信
                    sock.getOutputStream().write(ByteArray(32) { 0x7E })
                    sock.getOutputStream().flush()
                    Thread.sleep(3_000) // 保持连接开放，验证拆连由失步触发而非 EOF
                }
            }
        }

    @AfterTest
    fun tearDown() {
        server.close()
        serverThread.join(2000)
    }

    @Test
    fun streamDesyncStillTearsDown() {
        val conn = IdcConnection("127.0.0.1", server.localPort)
        assertTrue(conn.connect(LoginReq(devName = "unit-test"), timeoutMs = 3000), "login handshake failed")
        var waited = 0L
        while (conn.state != IdcConnection.State.DISCONNECTED && waited < 3_000) {
            Thread.sleep(50)
            waited += 50
        }
        assertEquals(IdcConnection.State.DISCONNECTED, conn.state, "stream desync (bad magic) must tear down the session")
        conn.close()
    }
}

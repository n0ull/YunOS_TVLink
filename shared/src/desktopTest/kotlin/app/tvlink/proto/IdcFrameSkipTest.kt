package app.tvlink.proto

import app.tvlink.proto.idc.IdcConnection
import app.tvlink.proto.idc.IdcConst
import app.tvlink.proto.idc.ImeStartInput
import app.tvlink.proto.idc.LoginReq
import app.tvlink.proto.idc.putLPString
import java.io.DataInputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 单帧解析异常不撕毁会话回归（M7）：TV 发出畸形帧（LPString 长度越界）后，
 * 会话必须存活并继续分发后续正常帧——旧实现 decode 异常被当流损坏拆连，
 * TV 重发怪包 → 重连风暴。帧边界本身完整（total 已校验、整帧读出），仅 body 解析失败。
 */
class IdcFrameSkipTest {
    private val server = ServerSocket(0)
    private val connKey = 4242

    private val serverThread =
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { sock ->
                    sock.soTimeout = 5000
                    val inp = DataInputStream(sock.getInputStream())
                    val out = sock.getOutputStream()
                    readFrame(inp) // LoginReq
                    out.write(loginRespFrame())
                    // 畸形 ImeStartInput：actionLabel 的 LPString 长度声明 Int.MAX_VALUE（越界必抛）
                    out.write(frame(IdcConst.ID_IME_START_INPUT, malformedImeBody()))
                    // 随后一帧完全正常（布局同 IdcConnectionTest 钉死的真实格式）
                    out.write(frame(IdcConst.ID_IME_START_INPUT, validImeBody()))
                    out.flush()
                    while (true) readFrame(inp) // 保持会话，读客户端后续帧
                }
            }
        }

    @AfterTest
    fun tearDown() {
        server.close()
        serverThread.join(2000)
    }

    private fun frame(
        id: Int,
        body: ByteArray,
    ): ByteArray {
        val f = ByteBuffer.allocate(IdcConst.HEADER_LEN + body.size)
        f.putInt(IdcConst.MAGIC)
        f.putInt(connKey)
        f.putInt(id)
        f.putInt(IdcConst.HEADER_LEN + body.size)
        f.put(body)
        return f.array()
    }

    private fun malformedImeBody(): ByteArray {
        val b = ByteBuffer.allocate(16)
        b.putInt(0x20001) // inputType
        b.putInt(0) // options
        b.putInt(6) // actionId
        b.putInt(Int.MAX_VALUE) // actionLabel LPString 长度越界
        return b.array()
    }

    private fun validImeBody(): ByteArray {
        val label = "搜索"
        val hint = "输入片名"
        val existed = "优酷"
        val b = ByteBuffer.allocate(12 + 3 * 4 + labelBytes(label) + labelBytes(hint) + labelBytes(existed))
        b.putInt(0x20001)
        b.putInt(0)
        b.putInt(6)
        b.putLPString(label)
        b.putLPString(hint)
        b.putLPString(existed)
        return b.array()
    }

    private fun labelBytes(s: String) = s.toByteArray(Charsets.UTF_8).size

    private fun loginRespFrame(): ByteArray {
        val f = ByteBuffer.allocate(IdcConst.HEADER_LEN + 12)
        f.putInt(IdcConst.MAGIC)
        f.putInt(IdcConst.UNASSIGNED_KEY)
        f.putInt(IdcConst.ID_LOGIN_RESP)
        f.putInt(IdcConst.HEADER_LEN + 12)
        f.putInt(0) // ver
        f.putInt(connKey)
        f.putInt(0) // udpPort
        return f.array()
    }

    private fun readFrame(inp: DataInputStream) {
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
    }

    @Test
    fun malformedFrameIsSkippedSessionSurvives() {
        val conn = IdcConnection("127.0.0.1", server.localPort)
        val latch = CountDownLatch(1)
        conn.onPacket = { p ->
            if (p is ImeStartInput && p.hint == "输入片名") latch.countDown()
        }
        assertTrue(conn.connect(LoginReq(devName = "unit-test"), timeoutMs = 3000), "login handshake failed")

        assertTrue(latch.await(3, TimeUnit.SECONDS), "valid frame after malformed one was not delivered")
        assertEquals(IdcConnection.State.ESTABLISHED, conn.state, "session torn down by single malformed frame")
        conn.close()
    }
}

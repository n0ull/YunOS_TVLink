package app.tvlink.proto

import app.tvlink.device.DeviceManager
import app.tvlink.proto.idc.IdcConst
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * H3 回归：重叠 connect() 必须单飞串行——后发起者胜出，落败会话被 shutdown（对端见 EOF）。
 * 无 Mutex 时：慢登录的先发连接最后完成，覆盖 connection 并泄漏后发会话（3 线程 + socket）。
 * 假 TV 绑固定 IDC 端口（DeviceManager 写死 IdcConst.TCP_PORT），#1 登录应答延迟制造重叠窗口。
 */
class DeviceManagerConnectTest {
    private val server = ServerSocket(IdcConst.TCP_PORT)
    private val firstAccepted = CountDownLatch(1)
    private val secondLoggedIn = CountDownLatch(1)

    @Volatile
    private var sock1: Socket? = null

    @Volatile
    private var sock2: Socket? = null

    private val serverThread =
        thread(isDaemon = true) {
            runCatching {
                val s1 = server.accept()
                sock1 = s1
                firstAccepted.countDown()
                thread(isDaemon = true) { serveLogin(s1, delayMs = 1500) { } }
                val s2 = server.accept()
                sock2 = s2
                thread(isDaemon = true) { serveLogin(s2, delayMs = 0) { secondLoggedIn.countDown() } }
            }
        }

    @AfterTest
    fun tearDown() {
        server.close()
        runCatching { sock1?.close() }
        runCatching { sock2?.close() }
        serverThread.join(2000)
    }

    /** 读 LoginReq → 延迟 → 回 LoginResp（帧格式与 IdcConnectionTest 假 TV 一致）。 */
    private fun serveLogin(
        s: Socket,
        delayMs: Long,
        onLogin: () -> Unit,
    ) {
        runCatching {
            val inp = DataInputStream(s.getInputStream())
            val header = ByteArray(IdcConst.HEADER_LEN)
            inp.readFully(header)
            val total = ByteBuffer.wrap(header).run { int; int; int; int }
            if (total > IdcConst.HEADER_LEN) inp.readFully(ByteArray(total - IdcConst.HEADER_LEN))
            Thread.sleep(delayMs)
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
            onLogin()
        }
    }

    @Test
    fun overlappingConnectsLoserSessionIsClosed() {
        val dm = DeviceManager()
        try {
            dm.connect("127.0.0.1")
            assertTrue(firstAccepted.await(5, TimeUnit.SECONDS), "first connect never reached fake TV")
            Thread.sleep(200) // 确保 conn1 已进入延迟登录（重叠窗口内发起第二次连接）
            dm.connect("127.0.0.1")

            assertTrue(secondLoggedIn.await(8, TimeUnit.SECONDS), "second connect never logged in")

            // 落败会话（sock1）必须被客户端 shutdown → 服务端读到 EOF
            val s1 = requireNotNull(sock1) { "fake TV never accepted first connection" }
            s1.soTimeout = 5000
            val eof = runCatching { s1.getInputStream().read() }.getOrDefault(-2)
            assertEquals(-1, eof, "loser session socket was not closed (leaked IdcConnection)")

            // 胜方生效断言轮询：secondLoggedIn 在服务线程计数，客户端 success 路径赋值在其后
            val deadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < deadline &&
                (dm.connection == null || dm.connState.value != DeviceManager.ConnState.CONNECTED)
            ) {
                Thread.sleep(50)
            }
            assertTrue(dm.connection != null, "no active connection after overlap")
            assertEquals(DeviceManager.ConnState.CONNECTED, dm.connState.value)
        } finally {
            dm.destroy()
        }
    }
}

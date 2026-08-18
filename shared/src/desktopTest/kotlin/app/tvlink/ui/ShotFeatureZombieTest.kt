package app.tvlink.ui

import app.tvlink.device.DeviceManager
import app.tvlink.device.ScreenshotService
import app.tvlink.proto.idc.IdcConst
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 截屏僵尸连接自愈回归(用户真机:截屏偶发失败,重启应用后正常)。
 * 链路:WiFi 抖动 → TCP 半开(心跳最坏 ~60s 判死) → 截图请求静默消失 →
 * 用户在判死窗口内反复失败只能重启。修复:应答超时主动 ping 探活,
 * 无 ACK 立即断开走自动重连,全程 ~15-20s 自愈。
 * 测试 scope 直接用 Dispatchers（无 DI 框架），故抑制 InjectDispatcher。
 */
@Suppress("InjectDispatcher")
class ShotFeatureZombieTest {
    private val server = ServerSocket(IdcConst.TCP_PORT) // DeviceManager 写死 13510
    private val alive = AtomicBoolean(true)
    private val socks = CopyOnWriteArrayList<Socket>()

    @AfterTest
    fun tearDown() {
        server.close()
        socks.forEach { runCatching { it.close() } }
        runCatching {
            java.util.prefs.Preferences
                .userRoot()
                .node("app/tvlink/device-history")
                .removeNode()
        }
    }

    /** 假 TV:每个连接回 LoginResp + 心跳回声;[alive]=false 时完全静默(半开僵尸)。 */
    private fun serve() {
        thread(isDaemon = true) {
            while (true) {
                val s =
                    try {
                        server.accept()
                    } catch (_: Exception) {
                        return@thread
                    }
                socks.add(s)
                thread(isDaemon = true) { serveConn(s) }
            }
        }
    }

    private fun serveConn(s: Socket) {
        runCatching {
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
                if (!alive.get()) continue // 静默丢弃,模拟半开
                if (isFirst) {
                    isFirst = false
                    val resp = ByteBuffer.allocate(IdcConst.HEADER_LEN + 12)
                    resp.putInt(IdcConst.MAGIC)
                    resp.putInt(IdcConst.UNASSIGNED_KEY)
                    resp.putInt(IdcConst.ID_LOGIN_RESP)
                    resp.putInt(IdcConst.HEADER_LEN + 12)
                    resp.putInt(0)
                    resp.putInt(4242)
                    resp.putInt(0)
                    s.getOutputStream().write(resp.array())
                } else if (packetId == IdcConst.ID_HEARTBEAT) {
                    val resp = ByteBuffer.allocate(total)
                    resp.put(header)
                    resp.put(body)
                    s.getOutputStream().write(resp.array())
                }
                s.getOutputStream().flush()
            }
        }
    }

    @Test
    fun screenshotTimeoutKillsZombieAndReconnects() {
        serve()
        val notices = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dm = DeviceManager()
        val service = ScreenshotService(dm)
        val shot = ShotFeature(scope, service) { notices.add(it) }
        scope.launch { dm.packets.collect { p -> service.handlePacket(p) } }

        dm.connect("127.0.0.1")
        var waited = 0L
        while (dm.connState.value != DeviceManager.ConnState.CONNECTED && waited < 10_000) {
            Thread.sleep(100)
            waited += 100
        }
        assertTrue(dm.connState.value == DeviceManager.ConnState.CONNECTED, "connect failed")

        alive.set(false) // 僵尸化:对端静默
        shot.capture() // 截图请求写入即消失;10s 后兜底超时 → 探活 → 判死 → 重连

        // 先等僵尸被判死断开(10s 超时 + ~3s 探活);期间保持静默,否则探活反而成功
        var hasDisconnect = false
        waited = 0L
        while (waited < 20_000) {
            if (dm.connection == null) {
                hasDisconnect = true
                break
            }
            Thread.sleep(200)
            waited += 200
        }
        assertTrue(hasDisconnect, "zombie connection was never dropped (ping path missing)")

        // 网络恢复 → 自动重连(5s 首重试 + 建连)应恢复 CONNECTED
        alive.set(true)
        waited = 0L
        var isRecovered = false
        while (waited < 15_000) {
            if (dm.connState.value == DeviceManager.ConnState.CONNECTED) {
                isRecovered = true
                break
            }
            Thread.sleep(200)
            waited += 200
        }
        assertTrue(isRecovered, "no auto-reconnect after zombie kill")
        assertTrue(notices.any { it.contains("重连") }, "user not informed about reconnect: $notices")

        dm.destroy()
        scope.cancel()
    }
}

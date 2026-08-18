package app.tvlink.ui

import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * H4 回归：onDisconnected 与在途 connect 竞态——显式断开后在途建连不得把
 * controller 装回/重启媒体服务/复活 UI（幽灵投屏通道 + HTTP 服务残留）。
 * 假投屏服务只 accept（connect 仅 TCP 握手即成）；旧实现 onDisconnected 不进锁，
 * 在途 connect 在其清空后完成装回 → 终态 channelAlive=true（本测试钉死终态不变量）。
 * 测试 scope 直接用 Dispatchers.IO（无 DI 框架），故抑制 InjectDispatcher。
 */
@Suppress("InjectDispatcher")
class CastFeatureDisconnectTest {
    private val server = ServerSocket(0) // 端口由系统分配，onHostConnected 显式传入（不占用真实 13520）
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cast = CastFeature(scope) {}

    private val acceptThread =
        thread(isDaemon = true) {
            runCatching {
                while (true) server.accept()
            }
        }

    @AfterTest
    fun tearDown() {
        cast.destroy()
        scope.cancel()
        server.close()
        acceptThread.join(2000)
    }

    @Test
    fun onDisconnectedWithInflightConnectLeavesNoGhostChannel() {
        cast.onHostConnected("127.0.0.1", server.localPort)
        cast.onDisconnected()

        // 终态断言：等localhost建连(毫秒级)与排队清理均落地的充分余量。
        // 旧实现在途 connect 会在清理后永久装回幽灵通道 → 1.5s 时仍 channelAlive（RED）；
        // 修复后清理在锁内最后执行 → 终态干净（GREEN）。
        Thread.sleep(1_500)
        assertFalse(cast.channelAlive, "ghost cast channel survived onDisconnected")
        assertEquals("", cast.mediaServerUrl, "media server restarted after onDisconnected")
        assertTrue(cast.ui is CastFeature.CastUiState.Unavailable, "cast UI resurrected after onDisconnected")
    }

    @Test
    fun connectFailureLeavesMediaServerStopped() {
        // M3 回归：先成功建连（媒体服务器运行、url 非空），再失败重连——
        // 失败分支必须停媒体服务器并清空 url/info，否则旧 registry 持续运行且
        // 陈旧 mediaServerUrl 绕过 file() 的 isEmpty() 守卫
        cast.onHostConnected("127.0.0.1", server.localPort)
        var waited = 0L
        while (cast.mediaServerUrl.isEmpty() && waited < 3_000) {
            Thread.sleep(50)
            waited += 50
        }
        assertTrue(cast.mediaServerUrl.isNotEmpty(), "media server did not start after successful connect")

        server.close() // 之后 TCP 连接被拒 → 重连必失败
        cast.onHostConnected("127.0.0.1", server.localPort)
        Thread.sleep(1_500) // 拒绝是即时的；等失败分支充分落地

        assertEquals("", cast.mediaServerUrl, "stale mediaServerUrl survived failed reconnect")
        assertTrue(cast.ui is CastFeature.CastUiState.Unavailable)
    }
}

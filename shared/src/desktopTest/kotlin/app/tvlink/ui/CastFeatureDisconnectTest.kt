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
 */
class CastFeatureDisconnectTest {
    private val server = ServerSocket(13520)
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
        cast.onHostConnected("127.0.0.1", 13520)
        cast.onDisconnected()

        // 终态断言：等localhost建连(毫秒级)与排队清理均落地的充分余量。
        // 旧实现在途 connect 会在清理后永久装回幽灵通道 → 1.5s 时仍 channelAlive（RED）；
        // 修复后清理在锁内最后执行 → 终态干净（GREEN）。
        Thread.sleep(1_500)
        assertFalse(cast.channelAlive, "ghost cast channel survived onDisconnected")
        assertEquals("", cast.mediaServerUrl, "media server restarted after onDisconnected")
        assertTrue(cast.ui is CastFeature.CastUiState.Unavailable, "cast UI resurrected after onDisconnected")
    }
}

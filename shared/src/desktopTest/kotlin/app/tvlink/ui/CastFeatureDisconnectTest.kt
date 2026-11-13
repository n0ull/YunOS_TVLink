package app.tvlink.ui

import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
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

    private fun awaitMediaServerStarted(): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            cast.mediaServerUrl.takeIf { it.isNotEmpty() }?.let { return it }
            Thread.sleep(25)
        }
        fail("media server did not start after successful connect")
    }

    private fun awaitFailureCleanup() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (cast.mediaServerUrl.isEmpty() && cast.ui is CastFeature.CastUiState.Unavailable) return
            Thread.sleep(25)
        }
        fail("failed reconnect did not clear cast state")
    }

    /** Loopback refusal is expected; a timeout means the fixture is not exercising the intended failure branch. */
    private fun connectionRefused(port: Int): Boolean {
        try {
            Socket().use { it.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 200) }
            return false
        } catch (_: ConnectException) {
            return true
        } catch (e: SocketTimeoutException) {
            fail("connection to port $port timed out instead of being refused", e)
        }
    }

    private fun assertListenerClosedEventually(port: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (connectionRefused(port)) return
            Thread.sleep(25)
        }
        fail("media listener on port $port still accepts connections")
    }

    @Test
    fun onDisconnectedWithInflightConnectLeavesNoGhostChannel() {
        cast.onHostConnected("127.0.0.1", server.localPort)
        val oldMediaPort = URI(awaitMediaServerStarted()).port
        assertTrue(oldMediaPort > 0)

        val readyToPublish = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        cast.beforeControllerPublication = {
            readyToPublish.countDown()
            assertTrue(releasePublication.await(5, TimeUnit.SECONDS), "publication was not released")
        }
        cast.onHostConnected("127.0.0.1", server.localPort)
        assertTrue(readyToPublish.await(5, TimeUnit.SECONDS), "successful connect did not reach publication")

        cast.onDisconnected()
        releasePublication.countDown()

        // 媒体监听关闭同时证明在途建连和排队清理均已越过发布点，无需固定 sleep 猜竞态终态。
        assertListenerClosedEventually(oldMediaPort)
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
        val mediaPort = URI(awaitMediaServerStarted()).port
        assertTrue(mediaPort > 0)

        server.close()
        assertTrue(connectionRefused(server.localPort), "fake cast service must refuse, not time out")
        cast.onHostConnected("127.0.0.1", server.localPort)
        awaitFailureCleanup()

        assertEquals("", cast.mediaServerUrl, "stale mediaServerUrl survived failed reconnect")
        assertTrue(cast.ui is CastFeature.CastUiState.Unavailable)
        assertListenerClosedEventually(mediaPort)
    }
}

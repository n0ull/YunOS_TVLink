package app.tvlink.ui

import app.tvlink.device.AsrTextService
import app.tvlink.device.DeviceManager
import app.tvlink.device.RcController
import app.tvlink.device.ScreenshotService
import app.tvlink.device.SysPropService
import app.tvlink.proto.ib.RcKey
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** UI 静默失败回归（M9）：断线时截屏/连拍/属性查询/按键必须给用户提示而非静默吞掉。
 *  测试 scope 直接用 Dispatchers.Default（无 DI 框架），故抑制 InjectDispatcher。 */
@Suppress("InjectDispatcher")
class UiSilentFailureTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dm = DeviceManager() // 无连接：所有通道不可用

    @AfterTest
    fun tearDown() {
        dm.destroy()
        scope.cancel()
    }

    @Test
    fun shotCaptureWithoutConnectionNotifies() {
        val notices = mutableListOf<String>()
        val shot = ShotFeature(scope, ScreenshotService(dm)) { notices.add(it) }
        shot.capture()
        shot.captureBurst()
        assertEquals(2, notices.size, "capture/captureBurst should each notify once: $notices")
        assertEquals(ShotFeature.ShotUiState.Idle, shot.state, "must not enter Capturing when unsent")
    }

    @Test
    fun propQueryWithoutConnectionNotifies() {
        val notices = mutableListOf<String>()
        val props = SysPropFeature(scope, SysPropService(dm)) { notices.add(it) }
        props.query("ro.product.model")
        assertEquals(1, notices.size, "query should notify once: $notices")
        assertEquals(SysPropFeature.SysPropUiState.Idle, props.state, "must not stay Loading when unsent")
    }

    @Test
    fun keyClickWithoutConnectionNotifies() {
        val notices = mutableListOf<String>()
        val remote = RemoteFeature(scope, dm, RcController(dm), AsrTextService(dm)) { notices.add(it) }
        remote.keyClick(RcKey.UP)
        assertEquals(1, notices.size, "dropped key press should notify: $notices")
        assertTrue(notices[0].contains("未连接"))
    }
}

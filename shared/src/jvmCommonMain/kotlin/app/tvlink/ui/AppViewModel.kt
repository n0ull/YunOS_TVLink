package app.tvlink.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tvlink.device.AsrTextService
import app.tvlink.device.DeviceManager
import app.tvlink.device.Discovery
import app.tvlink.device.DongleSettingService
import app.tvlink.device.RcController
import app.tvlink.device.RpmService
import app.tvlink.device.ScreenshotService
import app.tvlink.device.SysPropService
import app.tvlink.proto.idc.IdcPacket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * App 级协调者：导航、设备连接生命周期与功能模块的装配。
 * 功能状态由 Cast/Shot/SysProp/Remote Feature 持有。
 */
class AppViewModel : ViewModel() {
    // ---- navigation ----
    sealed interface Screen {
        data object DevicePicker : Screen

        data class Main(
            val tab: MainTab = MainTab.REMOTE,
            val moreSub: MoreSub? = null,
        ) : Screen
    }

    enum class MainTab { REMOTE, CAST, MORE }

    enum class MoreSub { SCREENSHOT, APPS, SETTINGS, DONGLE }

    var screen by mutableStateOf<Screen>(Screen.DevicePicker)
        private set

    fun navTab(tab: MainTab) {
        screen = (screen as? Screen.Main)?.copy(tab = tab, moreSub = null) ?: screen
    }

    fun navMore(sub: MoreSub) {
        screen = (screen as? Screen.Main)?.copy(moreSub = sub) ?: screen
    }

    /** 配网成功后的接管入口：回设备选择页（由调用方配合 startDiscovery）。 */
    fun navToDevicePicker() {
        screen = Screen.DevicePicker
    }

    fun navBack() {
        val s = screen as? Screen.Main ?: return
        screen =
            when {
                s.moreSub != null -> s.copy(moreSub = null)
                s.tab != MainTab.REMOTE -> s.copy(tab = MainTab.REMOTE)
                else -> s
            }
    }

    // ---- services ----
    private val deviceManager = DeviceManager()
    val rc = RcController(deviceManager)
    val rpm = RpmService(deviceManager)
    private val asr = AsrTextService(deviceManager)
    private val screenshot = ScreenshotService(deviceManager)
    private val sysprop = SysPropService(deviceManager)
    val dongleSettings = DongleSettingService(deviceManager)

    val cast = CastFeature(viewModelScope) { notice = it }
    val shot = ShotFeature(viewModelScope, screenshot) { notice = it }
    val props = SysPropFeature(viewModelScope, sysprop) { notice = it }
    val remote = RemoteFeature(viewModelScope, deviceManager, rc, asr) { notice = it }

    fun onResume() {
        val device = connectedDevice ?: return
        if (connState == DeviceManager.ConnState.CONNECTING) return
        if (connState != DeviceManager.ConnState.CONNECTED) {
            deviceManager.connect(device.ip, device.projectionPort, device.ibVer, device.ibSid)
            return
        }
        cast.ensureAlive(device.ip, device.projectionPort)
    }

    var connState by mutableStateOf(DeviceManager.ConnState.IDLE)
    var connectedDevice by mutableStateOf<DeviceManager.ConnectedDevice?>(null)
        private set
    val foundDevices = mutableStateListOf<Discovery.FoundDevice>()

    // ---- dongle settings (module com.ali.ott.dongle.setting) ----
    var dongleOnline by mutableStateOf(false)
    var dongleInfo by mutableStateOf<DongleSettingService.SysInfo?>(null)

    // ---- toast-ish ----
    var notice by mutableStateOf("")

    init {
        viewModelScope.launch(Dispatchers.Default) {
            deviceManager.connState.collect { s ->
                connState = s
                if (s == DeviceManager.ConnState.CONNECTED) {
                    connectedDevice = deviceManager.connected.value
                    onConnected(connectedDevice)
                    // Reconnecting must not reset the user's current page.
                    if (screen !is Screen.Main) screen = Screen.Main()
                } else if (s == DeviceManager.ConnState.IDLE) {
                    connectedDevice = null
                    cast.onDisconnected()
                } else if (s == DeviceManager.ConnState.FAILED) {
                    // 重试窗口内的 FAILED 微秒内回到 CONNECTING；延迟复核仍为 FAILED
                    // 才是重连耗尽的终态——避免重试周期里 snackbar 闪烁
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(600)
                        if (deviceManager.connState.value == DeviceManager.ConnState.FAILED) {
                            notice = "连接失败，请检查网络后重试"
                        }
                    }
                }
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            deviceManager.foundDevices.collect { list ->
                foundDevices.clear()
                foundDevices.addAll(list)
            }
        }
        deviceManager.packets.collectInVm { p -> handlePacket(p) }
        dongleSettings.moduleOnline.collectInVm { online -> dongleOnline = online }
        dongleSettings.sysInfo.collectInVm { info -> dongleInfo = info }
        rpm.installProgress.collectInVm { progress ->
            notice = "安装 ${progress.packageName}: ${progress.progress}%"
        }
        rpm.opResults.collectInVm { result ->
            notice =
                if (result.errorCode == 0) {
                    "${result.op} ${result.packageName} 成功"
                } else {
                    "${result.op} ${result.packageName} 失败 (${result.errorCode})"
                }
            if (result.errorCode == 0) rpm.getAppList()
        }

        // 冷启动直连历史设备（原 App 亮屏/回前台按 SSID 历史直连，docs/re/01 §1；本项目全局一条）
        deviceManager.lastDevice()?.let { d -> deviceManager.connect(d.ip, d.projectionPort) }
    }

    /** 在 viewModelScope(Default) 收集服务层数据流并写入 Compose 状态（数据向上单向流）。 */
    private fun <T> Flow<T>.collectInVm(block: (T) -> Unit) {
        viewModelScope.launch(Dispatchers.Default) { collect { block(it) } }
    }

    private fun onConnected(device: DeviceManager.ConnectedDevice?) {
        rc.attach()
        if (device?.ibOnly == true) return
        rpm.attach()
        dongleSettings.attach()
        if (device != null) cast.onHostConnected(device.ip, device.projectionPort)
    }

    /** 包路由：服务层分派（截图/属性应答）+ 遥控 IME 事件。 */
    private fun handlePacket(p: IdcPacket) {
        screenshot.handlePacket(p)
        sysprop.handlePacket(p)
        remote.onPacket(p)
    }

    // ---- actions ----

    fun startDiscovery() = deviceManager.startDiscovery()

    fun connectTo(d: Discovery.FoundDevice) = deviceManager.connect(d)

    fun connectToIp(ip: String) = deviceManager.connect(ip)

    /** IB-only 连接入口：仅建 IB 通道，不经 IDC。遥控可用；投屏/截图/应用管理不可用。 */
    fun connectIbOnly(
        ip: String,
        ibVer: String = "",
        ibSid: String = "",
    ) = deviceManager.connectIbOnly(ip, ibVer, ibSid)

    fun disconnect() {
        connectedDevice = null
        rc.detach()
        rpm.detach()
        dongleSettings.detach()
        deviceManager.disconnect()
        screen = Screen.DevicePicker
    }

    override fun onCleared() {
        rc.destroy()
        rpm.detach()
        asr.destroy()
        screenshot.destroy()
        cast.destroy()
        deviceManager.destroy()
        super.onCleared()
    }
}

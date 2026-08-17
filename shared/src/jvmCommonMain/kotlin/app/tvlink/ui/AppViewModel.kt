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
 * App 级协调者：导航、设备连接生命周期、服务注册表与各屏幕功能状态持有者
 * （[cast]/[shot]/[props]/[remote]/[apps]，随本 ViewModel 构造与清理）的装配。
 * 功能状态与动作已下沉到各 Feature 类；本类只做跨功能编排。
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
    val deviceManager = DeviceManager()
    val rc = RcController(deviceManager)
    val rpm = RpmService(deviceManager)
    val asr = AsrTextService(deviceManager)
    val screenshot = ScreenshotService(deviceManager)
    val sysprop = SysPropService(deviceManager)
    val dongleSettings = DongleSettingService(deviceManager)

    // ---- feature state holders (per-screen) ----
    val cast = CastFeature(viewModelScope) { notice = it }
    val shot = ShotFeature(viewModelScope, screenshot)
    val props = SysPropFeature(viewModelScope, sysprop)
    val remote =
        RemoteFeature(viewModelScope, deviceManager, rc, asr) { notice = it }
    val apps = AppsFeature(viewModelScope, rpm) { notice = it }

    // ---- auto-reconnect ----

    /** 最近一次成功连接的设备(显式 disconnect 时清空);后台→前台掉线时按需自动重连。 */
    private var lastDevice: DeviceManager.ConnectedDevice? = null

    /**
     * 前台恢复时的兜底复活(事件驱动的 IDC/IB 自动重连之外的第二层):
     * ① 重连重试耗尽(5s→15s×2 全败)后回前台 → 无条件再给一轮(新网络环境=新用户意图);
     * ② cast 通道单独死亡(IDC/IB 活着)→ 唯一补建入口,其他机制看不见这条 TCP。
     * 用户显式断开后 lastDevice=null,不重连。
     */
    fun onResume() {
        val d = lastDevice ?: return
        if (connState == DeviceManager.ConnState.CONNECTING) return
        if (connState != DeviceManager.ConnState.CONNECTED) {
            deviceManager.connect(d.ip, d.projectionPort, d.ibVer, d.ibSid)
            return
        }
        cast.ensureAlive(d.ip, d.projectionPort)
    }

    // ---- connection state ----
    var connState by mutableStateOf(DeviceManager.ConnState.IDLE)
    var connectedName by mutableStateOf("")
    var connectedIp by mutableStateOf("")
    var connectedModel by mutableStateOf("")
    var connectedMac by mutableStateOf("")
    var connectedIbVer by mutableStateOf("")
    var connectedIbSid by mutableStateOf("")
    var connectedIbOnly by mutableStateOf(false)
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
                    val c = deviceManager.connected.value
                    connectedName = c?.name ?: ""
                    connectedIp = c?.ip ?: ""
                    connectedModel = c?.model ?: ""
                    connectedMac = c?.mac ?: ""
                    connectedIbVer = c?.ibVer ?: ""
                    connectedIbSid = c?.ibSid ?: ""
                    connectedIbOnly = c?.ibOnly ?: false
                    lastDevice = c
                    onConnected()
                    // 重连不重置页面：已在 Main（任意 tab）时保持当前页，
                    // 否则自动重连会把用户从投屏/设置等页面弹回默认遥控 tab
                    if (screen !is Screen.Main) screen = Screen.Main()
                } else if (s == DeviceManager.ConnState.IDLE) {
                    connectedName = ""
                    connectedIp = ""
                    connectedModel = ""
                    connectedMac = ""
                    connectedIbVer = ""
                    connectedIbSid = ""
                    connectedIbOnly = false
                    cast.onDisconnected()
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

        // 冷启动直连历史设备（原 App 亮屏/回前台按 SSID 历史直连，docs/re/01 §1；本项目全局一条）
        deviceManager.lastDevice()?.let { d -> deviceManager.connect(d.ip, d.projectionPort) }
    }

    /** 在 viewModelScope(Default) 收集服务层数据流并写入 Compose 状态（数据向上单向流）。 */
    private fun <T> Flow<T>.collectInVm(block: (T) -> Unit) {
        viewModelScope.launch(Dispatchers.Default) { collect { block(it) } }
    }

    private fun onConnected() {
        rc.attach()
        if (!connectedIbOnly) {
            rpm.attach()
            dongleSettings.attach()
            val c = deviceManager.connected.value ?: return
            cast.onHostConnected(c.ip, c.projectionPort)
        }
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
        lastDevice = null
        rc.detach()
        rpm.detach()
        dongleSettings.detach()
        deviceManager.disconnect()
        screen = Screen.DevicePicker
    }

    override fun onCleared() {
        rc.destroy()
        rpm.detach()
        cast.destroy()
        deviceManager.destroy()
        super.onCleared()
    }
}

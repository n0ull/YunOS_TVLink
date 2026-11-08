package app.tvlink.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tvlink.device.AsrTextService
import app.tvlink.device.ConnectionMode
import app.tvlink.device.ConnectionPacket
import app.tvlink.device.DeviceSelection
import app.tvlink.device.DeviceTarget
import app.tvlink.device.Discovery
import app.tvlink.device.DiscoveryState
import app.tvlink.device.DongleSettingService
import app.tvlink.device.IdcProgress
import app.tvlink.device.RcController
import app.tvlink.device.RemoteState
import app.tvlink.device.RpmService
import app.tvlink.device.ScreenshotService
import app.tvlink.device.SelectionSnapshot
import app.tvlink.device.SysPropService
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
    private val selection = DeviceSelection()
    private val discovery = Discovery()
    val rc = RcController(selection)
    val rpm = RpmService(selection)
    private val asr = AsrTextService(selection)
    private val screenshot = ScreenshotService(selection)
    private val sysprop = SysPropService(selection)
    val dongleSettings = DongleSettingService(selection)

    val cast = CastFeature(viewModelScope) { notice = it }
    val shot = ShotFeature(viewModelScope, screenshot) { notice = it }
    val props = SysPropFeature(viewModelScope, sysprop) { notice = it }
    val remote = RemoteFeature(viewModelScope, selection, rc, asr) { notice = it }

    var selectionState by mutableStateOf(SelectionSnapshot())
        private set
    private var remoteState by mutableStateOf(RemoteState())
    var discoveryState by mutableStateOf(DiscoveryState())
        private set

    fun onResume() {
        val snapshot = selectionState
        val device = snapshot.target ?: return
        val canEnsureCast = snapshot.mode == ConnectionMode.FULL && snapshot.idcProgress == IdcProgress.CONNECTED
        if (canEnsureCast) cast.ensureAlive(device.ip, device.projectionPort)
    }

    // ---- dongle settings (module com.ali.ott.dongle.setting) ----
    var dongleOnline by mutableStateOf(false)
    var dongleInfo by mutableStateOf<DongleSettingService.SysInfo?>(null)

    // ---- toast-ish ----
    var notice by mutableStateOf("")

    init {
        viewModelScope.launch(Dispatchers.Default) {
            selection.state.collect { snapshot ->
                selectionState = snapshot
                if (snapshot.idcProgress == IdcProgress.CONNECTED) onConnected(snapshot)
                cast.onSelectionChanged(snapshot)
                updateMainNavigation()
                if (snapshot.idcProgress == IdcProgress.FAILED) {
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(600)
                        if (selection.state.value.idcProgress == IdcProgress.FAILED) {
                            notice = "连接失败，请检查网络后重试"
                        }
                    }
                }
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            discovery.state.collect { discoveryState = it }
        }
        rc.state.collectInVm { remote ->
            remoteState = remote
            updateMainNavigation()
        }
        selection.packets.collectInVm { p -> handlePacket(p) }
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
        selection.lastSelection()?.let { saved -> selection.select(saved.target, saved.mode) }
    }

    /** 在 viewModelScope(Default) 收集服务层数据流并写入 Compose 状态（数据向上单向流）。 */
    private fun <T> Flow<T>.collectInVm(block: (T) -> Unit) {
        viewModelScope.launch(Dispatchers.Default) { collect { block(it) } }
    }

    private fun updateMainNavigation() {
        val snapshot = selectionState
        if (snapshot.target == null || screen is Screen.Main) return
        val ready =
            when (snapshot.mode) {
                ConnectionMode.FULL -> snapshot.idcProgress == IdcProgress.CONNECTED
                ConnectionMode.IB_ONLY ->
                    remoteState.ibReady && remoteState.selectionId == snapshot.selectionId
                null -> false
            }
        if (ready) screen = Screen.Main()
    }

    private fun onConnected(snapshot: SelectionSnapshot) {
        if (snapshot.target == null) return
        if (snapshot.mode == ConnectionMode.IB_ONLY) return
        rpm.attach()
        dongleSettings.attach()
    }

    /** 包路由：服务层分派（截图/属性应答）+ 遥控 IME 事件。 */
    private fun handlePacket(packet: ConnectionPacket) {
        screenshot.handlePacket(packet.binding, packet.value)
        sysprop.handlePacket(packet.binding, packet.value)
        remote.onPacket(packet.binding, packet.value)
    }

    // ---- actions ----

    fun startDiscovery() = discovery.start()

    fun connectTo(d: Discovery.FoundDevice) {
        selection.select(
            DeviceTarget(
                ip = d.ip,
                name = d.name,
                model = d.model,
                uuid = d.uuid,
                mac = d.mac,
                projectionPort = d.projectionPort,
                ibVer = d.ibVer,
                ibSid = d.ibSid,
            ),
        )
    }

    fun connectToIp(ip: String) = selection.select(DeviceTarget(ip = ip))

    /** IB-only 连接入口：仅建 IB 通道，不经 IDC。遥控可用；投屏/截图/应用管理不可用。 */
    fun connectIbOnly(
        ip: String,
        ibVer: String = "",
        ibSid: String = "",
    ) = selection.select(DeviceTarget(ip = ip, ibVer = ibVer, ibSid = ibSid), ConnectionMode.IB_ONLY)

    fun disconnect() {
        rpm.detach()
        dongleSettings.detach()
        selection.disconnect()
        screen = Screen.DevicePicker
    }

    override fun onCleared() {
        rc.destroy()
        rpm.detach()
        asr.destroy()
        screenshot.destroy()
        cast.destroy()
        discovery.stop()
        selection.destroy()
        super.onCleared()
    }
}

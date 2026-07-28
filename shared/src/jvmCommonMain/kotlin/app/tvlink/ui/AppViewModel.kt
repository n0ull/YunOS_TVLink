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
import app.tvlink.proto.cast.CastController
import app.tvlink.proto.cast.MediaHttpServer
import app.tvlink.proto.ib.RcKey
import app.tvlink.proto.idc.IdcPacket
import app.tvlink.proto.idc.ImeAction
import app.tvlink.proto.idc.ImeFinishInput
import app.tvlink.proto.idc.ImeStartInput
import app.tvlink.proto.idc.ImeTextChange
import app.tvlink.proto.mdns.Mdns
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Central app state shared by both platforms. */
class AppViewModel : ViewModel() {
    companion object {
        /** 投屏控制端口兜底候选（ddh/mDNS 均未提供时按序尝试）。
         *  原 App 默认 13520；本 TV 固件实际监听 13521（ddh 实证）。
         *  不同固件端口可能不同，兜底时两个都试，避免写死单一端口。 */
        private val CAST_FALLBACK_PORTS = intArrayOf(13520, 13521)
    }

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
    val mediaServer = MediaHttpServer()
    var cast: CastController? = null
        private set

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
        // IDC 在线但投屏控制通道死亡(后台被杀/请求异常)——单独补建
        if (cast?.state != app.tvlink.proto.cast.CastController.State.CONNECTED) {
            connectCast(d.ip, d.projectionPort)
        }
    }

    var connState by mutableStateOf(DeviceManager.ConnState.IDLE)
    var connectedName by mutableStateOf("")
    var connectedIp by mutableStateOf("")
    var connectedModel by mutableStateOf("")
    var connectedMac by mutableStateOf("")
    var connectedIbVer by mutableStateOf("")
    var connectedIbSid by mutableStateOf("")
    var connectedIbOnly by mutableStateOf(false)
    val foundDevices = mutableStateListOf<Discovery.FoundDevice>()

    // ---- IME (remote text input) ----
    var imeActive by mutableStateOf(false)
    var imeText by mutableStateOf("")
    var imeHint by mutableStateOf("")

    // ---- screenshot ----
    var lastShot by mutableStateOf<ByteArray?>(null)
    var shotBusy by mutableStateOf(false)

    // ---- sysprop ----
    var sysPropResult by mutableStateOf("")
    var sysPropBusy by mutableStateOf(false)

    // ---- TV apps ----
    val tvApps = mutableStateListOf<RpmService.TvApp>()

    // ---- dongle settings (module com.ali.ott.dongle.setting) ----
    var dongleOnline by mutableStateOf(false)
    var dongleInfo by mutableStateOf<DongleSettingService.SysInfo?>(null)

    // ---- casting ----
    var castState by mutableStateOf(CastController.PlayState.UNKNOWN)
    var castDuration by mutableStateOf(0L)
    var castPosition by mutableStateOf(0L)
    var castVolume by mutableStateOf(0)
    var castRate by mutableStateOf(1)
    var castTitle by mutableStateOf("")
    var mediaServerUrl by mutableStateOf("")
    var castServerInfo by mutableStateOf<CastController.ServerInfo?>(null)

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
                    mediaServerUrl = ""
                    castServerInfo = null
                    castRate = 1
                    cast?.disconnect()
                    cast = null
                    mediaServer.stop()
                }
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            deviceManager.foundDevices.collect { list ->
                foundDevices.clear()
                foundDevices.addAll(list)
            }
        }
        deviceManager.onPacket = { p -> handlePacket(p) }
        rpm.onAppList = { apps ->
            viewModelScope.launch(Dispatchers.Default) {
                tvApps.clear()
                tvApps.addAll(apps)
            }
        }
        rpm.onInstallProgress = { pr ->
            viewModelScope.launch(Dispatchers.Default) { notice = "安装 ${pr.packageName}: ${pr.progress}%" }
        }
        rpm.onOpResult = { op, pkg, err ->
            viewModelScope.launch(Dispatchers.Default) {
                notice = if (err == 0) "$op $pkg 成功" else "$op $pkg 失败 ($err)"
                if (err == 0) rpm.getAppList()
            }
        }
        screenshot.onScreenshot = { jpeg ->
            viewModelScope.launch(Dispatchers.Default) {
                lastShot = jpeg
                shotBusy = false
            }
        }
        sysprop.onSysProp = { key, value ->
            viewModelScope.launch(Dispatchers.Default) {
                sysPropBusy = false
                sysPropResult = "$key = ${value.ifEmpty { "(空)" }}"
            }
        }
        rc.onCurrentApp = { app ->
            viewModelScope.launch(Dispatchers.Default) { notice = "电视当前应用: $app" }
        }
        dongleSettings.onModuleState = { online ->
            viewModelScope.launch(Dispatchers.Default) {
                dongleOnline = online
                if (!online) dongleInfo = null
            }
        }
        dongleSettings.onSysInfo = { info ->
            viewModelScope.launch(Dispatchers.Default) { dongleInfo = info }
        }

        // 冷启动直连历史设备（原 App 亮屏/回前台按 SSID 历史直连，docs/re/01 §1；本项目全局一条）
        deviceManager.lastDevice()?.let { d -> deviceManager.connect(d.ip, d.projectionPort) }
    }

    private fun onConnected() {
        rc.attach()
        if (!connectedIbOnly) {
            rpm.attach()
            dongleSettings.attach()
            val c = deviceManager.connected.value ?: return
            connectCast(c.ip, c.projectionPort)
        }
    }

    /**
     * 投屏控制通道建立。先断旧通道再建新——2026-07-25 真机实证:重复 onConnected
     * 不断旧通道会残留双控制会话,TV 侧会话归属错乱致播放/暂停/退出/音量全部失效。
     *
     * port=0 时（ddh/mDNS 均未提供）依次尝试 CAST_FALLBACK_PORTS；
     * 原 App 默认 13520，本 TV 固件实际监听 13521（ddh 实证），不同固件可能不同。
     */
    private fun connectCast(
        ip: String,
        port: Int,
    ) {
        cast?.disconnect()
        cast = null
        viewModelScope.launch(Dispatchers.IO) {
            val candidates = if (port != 0) intArrayOf(port) else CAST_FALLBACK_PORTS
            var cc: CastController? = null
            for (p in candidates) {
                val trial = CastController(ip, p)
                if (trial.connect()) {
                    cc = trial
                    break
                }
            }
            if (cc != null) {
                cc.onEvent = { st, dur, pos, vol, rate ->
                    viewModelScope.launch(Dispatchers.Default) {
                        castState = st
                        if (dur > 0) castDuration = dur
                        castPosition = pos
                        if (vol >= 0) castVolume = vol
                        if (rate > 0) castRate = rate
                    }
                }
                cast = cc
                castServerInfo = cc.serverInfo()
            }
            val localIp = Mdns.localLanAddress()?.hostAddress
            if (localIp != null && mediaServer.start(localIp)) {
                mediaServerUrl = mediaServer.baseUrl
            }
        }
    }

    private fun handlePacket(p: IdcPacket) {
        screenshot.handlePacket(p)
        sysprop.handlePacket(p)
        when (p) {
            is ImeStartInput ->
                viewModelScope.launch(Dispatchers.Default) {
                    imeText = p.initText
                    imeHint = p.hint
                    imeActive = true
                }

            is ImeFinishInput -> viewModelScope.launch(Dispatchers.Default) { imeActive = false }
        }
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

    fun keyClick(k: RcKey) = rc.keyClick(k)

    fun imeCommit() {
        deviceManager.connection?.send(ImeAction(-1))
        imeActive = false
    }

    fun imeChanged(text: String) {
        imeText = text
        deviceManager.connection?.send(ImeTextChange(text, text.length))
    }

    fun takeScreenshot() {
        if (!screenshot.capture()) return
        shotBusy = true
        viewModelScope.launch(Dispatchers.Default) {
            kotlinx.coroutines.delay(10_000)
            shotBusy = false
        }
    }

    fun querySysProp(key: String) {
        val k = key.trim()
        if (k.isEmpty() || !sysprop.getProp(k)) return
        sysPropBusy = true
        sysPropResult = ""
        viewModelScope.launch(Dispatchers.Default) {
            kotlinx.coroutines.delay(10_000)
            sysPropBusy = false
        }
    }

    fun refreshApps() = rpm.getAppList()

    fun castFile(
        path: String,
        title: String,
        type: String,
    ) {
        val file = File(path)
        if (!file.exists() || mediaServerUrl.isEmpty()) {
            notice = "媒体服务未就绪"
            return
        }
        val id =
            when (type) {
                "video" -> "video-item-${System.currentTimeMillis()}"
                "audio" -> "audio-item-${System.currentTimeMillis()}"
                else -> "image-item-${System.currentTimeMillis()}"
            }
        mediaServer.register(id, file)
        val url = mediaServer.urlFor(id)
        viewModelScope.launch(Dispatchers.IO) {
            val cc =
                cast ?: run {
                    notice = "投屏通道未就绪，请稍后重试"
                    return@launch
                }
            castTitle = title
            // 音乐封面（原 App 传 thumbnail_url，docs/re/04 §3）：Android 经 MediaStore 取封面
            // 拷入缓存后按注册制供片（不走原 App 的绝对路径回退），失败则无封面投屏
            val thumbnail =
                if (type == "audio") {
                    app.tvlink.ui.widgets.albumArtFile(path)?.let { cover ->
                        val coverId = "cover-${System.currentTimeMillis()}"
                        mediaServer.register(coverId, cover, "image/jpeg")
                        mediaServer.urlFor(coverId)
                    }
                } else {
                    null
                }
            val ok = cc.setMedia(type, url, title, thumbnail)
            if (ok) cc.play() else notice = "投屏失败"
        }
    }

    fun castSeek(ms: Long) {
        viewModelScope.launch(Dispatchers.IO) { cast?.seek(ms) }
    }

    // 播放控制必须经 IO 线程:CastController 是阻塞 socket,Android 主线程直调
    // 会抛 NetworkOnMainThreadException(message 为 null,曾误判为协议失败)。

    fun castPlay() {
        viewModelScope.launch(Dispatchers.IO) { cast?.play() }
    }

    fun castPause() {
        viewModelScope.launch(Dispatchers.IO) { cast?.pause() }
    }

    fun castStop() {
        viewModelScope.launch(Dispatchers.IO) { cast?.stop() }
    }

    /** 调 TV 音量并乐观更新本地值(轮询随后校准)。 */
    fun castVolumeTo(v: Int) {
        castVolume = v
        viewModelScope.launch(Dispatchers.IO) { cast?.volume(v) }
    }

    /** 播放倍速（POST /rate）：乐观更新本地值，轮询随后校准（与音量同模式）。 */
    fun castRateTo(r: Int) {
        castRate = r
        viewModelScope.launch(Dispatchers.IO) { cast?.rate(r) }
    }

    fun takeScreenshotBurst() {
        shotBusy = true
        screenshot.captureBurst()
    }

    fun voiceText(text: String) = asr.sendText(text)

    override fun onCleared() {
        rc.destroy()
        rpm.detach()
        cast?.disconnect()
        mediaServer.stop()
        deviceManager.destroy()
        super.onCleared()
    }
}

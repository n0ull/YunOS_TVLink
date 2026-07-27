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
        private const val DEFAULT_CAST_PORT = 13520
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
    val mediaServer = MediaHttpServer()
    var cast: CastController? = null
        private set

    // ---- auto-reconnect ----

    /** 最近一次成功连接的设备(显式 disconnect 时清空);后台→前台掉线时按需自动重连。 */
    private var lastDevice: DeviceManager.ConnectedDevice? = null

    /**
     * 前台恢复时按需重连。后台期间 socket 会被 TV 空闲超时/WiFi 省电/ROM 冻结等机制杀死
     * (connState 落 IDLE 后操作静默无效),此处是唯一恢复点;用户显式断开后(lastDevice=null)不重连。
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
            connectCast(d.ip, if (d.projectionPort != 0) d.projectionPort else DEFAULT_CAST_PORT)
        }
    }

    var connState by mutableStateOf(DeviceManager.ConnState.IDLE)
    var connectedName by mutableStateOf("")
    var connectedIp by mutableStateOf("")
    var connectedIbVer by mutableStateOf("")
    var connectedIbSid by mutableStateOf("")
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

    // ---- casting ----
    var castState by mutableStateOf(CastController.PlayState.UNKNOWN)
    var castDuration by mutableStateOf(0L)
    var castPosition by mutableStateOf(0L)
    var castVolume by mutableStateOf(0)
    var castTitle by mutableStateOf("")
    var mediaServerUrl by mutableStateOf("")

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
                    connectedIbVer = c?.ibVer ?: ""
                    connectedIbSid = c?.ibSid ?: ""
                    lastDevice = c
                    onConnected()
                    screen = Screen.Main()
                } else if (s == DeviceManager.ConnState.IDLE) {
                    connectedName = ""
                    connectedIp = ""
                    connectedIbVer = ""
                    connectedIbSid = ""
                    mediaServerUrl = ""
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

        // 冷启动直连历史设备（原 App 亮屏/回前台按 SSID 历史直连，docs/re/01 §1；本项目全局一条）
        deviceManager.lastDevice()?.let { d -> deviceManager.connect(d.ip, d.projectionPort) }
    }

    private fun onConnected() {
        rc.attach()
        rpm.attach()
        val c = deviceManager.connected.value ?: return
        connectCast(c.ip, if (c.projectionPort != 0) c.projectionPort else DEFAULT_CAST_PORT)
    }

    /**
     * 投屏控制通道(13521)建立。先断旧通道再建新——2026-07-25 真机实证:重复 onConnected
     * 不断旧通道会残留双控制会话,TV 侧会话归属错乱致播放/暂停/退出/音量全部失效。
     */
    private fun connectCast(
        ip: String,
        port: Int,
    ) {
        cast?.disconnect()
        cast = null
        viewModelScope.launch(Dispatchers.IO) {
            val cc = CastController(ip, port)
            cc.onEvent = { st, dur, pos, vol ->
                viewModelScope.launch(Dispatchers.Default) {
                    castState = st
                    if (dur > 0) castDuration = dur
                    castPosition = pos
                    if (vol >= 0) castVolume = vol
                }
            }
            if (cc.connect()) cast = cc
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

    fun disconnect() {
        lastDevice = null
        rc.detach()
        rpm.detach()
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
            val cc = cast ?: return@launch
            castTitle = title
            val ok = cc.setMedia(type, url, title)
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

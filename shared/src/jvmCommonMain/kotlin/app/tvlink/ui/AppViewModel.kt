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

        data object Home : Screen

        data object Remote : Screen

        data object Cast : Screen

        data object Screenshot : Screen

        data object Apps : Screen

        data object Settings : Screen

        data object Dongle : Screen
    }

    var screen by mutableStateOf<Screen>(Screen.DevicePicker)
        private set

    fun nav(to: Screen) {
        screen = to
    }

    fun navBack() {
        screen = if (deviceManager.connected.value != null) Screen.Home else Screen.DevicePicker
    }

    // ---- services ----
    val deviceManager = DeviceManager()
    val rc = RcController(deviceManager)
    val rpm = RpmService(deviceManager)
    val asr = AsrTextService(deviceManager)
    val screenshot = ScreenshotService(deviceManager)
    val mediaServer = MediaHttpServer()
    var cast: CastController? = null
        private set

    var connState by mutableStateOf(DeviceManager.ConnState.IDLE)
    var connectedName by mutableStateOf("")
    var connectedIp by mutableStateOf("")
    val foundDevices = mutableStateListOf<Discovery.FoundDevice>()

    // ---- IME (remote text input) ----
    var imeActive by mutableStateOf(false)
    var imeText by mutableStateOf("")

    // ---- screenshot ----
    var lastShot by mutableStateOf<ByteArray?>(null)
    var shotBusy by mutableStateOf(false)

    // ---- TV apps ----
    val tvApps = mutableStateListOf<RpmService.TvApp>()
    var tvSystemInfo by mutableStateOf<Map<String, String>>(emptyMap())

    // ---- casting ----
    var castState by mutableStateOf(CastController.PlayState.UNKNOWN)
    var castDuration by mutableStateOf(0L)
    var castPosition by mutableStateOf(0L)
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
                    onConnected()
                    screen = Screen.Home
                } else if (s == DeviceManager.ConnState.IDLE) {
                    connectedName = ""
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
        rpm.onSystemInfo = { info ->
            viewModelScope.launch(Dispatchers.Default) { tvSystemInfo = info }
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
        rc.onCurrentApp = { app ->
            viewModelScope.launch(Dispatchers.Default) { notice = "电视当前应用: $app" }
        }
    }

    private fun onConnected() {
        rc.attach()
        rpm.attach()
        val c = deviceManager.connected.value ?: return
        val port = if (c.projectionPort != 0) c.projectionPort else DEFAULT_CAST_PORT
        viewModelScope.launch(Dispatchers.IO) {
            val cc = CastController(c.ip, port)
            cc.onEvent = { st, dur, pos ->
                viewModelScope.launch(Dispatchers.Default) {
                    castState = st
                    if (dur > 0) castDuration = dur
                    castPosition = pos
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
        when (p) {
            is ImeStartInput ->
                viewModelScope.launch(Dispatchers.Default) {
                    imeText = p.initText
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

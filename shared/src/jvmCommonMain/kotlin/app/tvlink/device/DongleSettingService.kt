package app.tvlink.device

import app.tvlink.proto.idc.FlatJson
import app.tvlink.proto.idc.parseJsonObject
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Dongle settings over IDC VConn module "com.ali.ott.dongle.setting". See docs/re/03 §B.3. */
class DongleSettingService(
    private val selection: DeviceSelection,
) {
    companion object {
        const val MODULE_NAME = "com.ali.ott.dongle.setting"
        private const val CATEGORY = "immersive"
        const val MSG_SYSINFO_RESP = "SysInfo"
    }

    data class SysInfo(
        val ip: String = "",
        val mac: String = "",
        val sn: String = "",
        val uuid: String = "",
        val firmware: String = "",
        val current: String = "",
        val resolutions: List<String> = emptyList(),
    )

    private class ModuleRuntime(
        val binding: IdcBinding,
        var moduleId: Int? = null,
        var vconnOpen: Boolean = false,
    )

    private val _moduleOnline = MutableStateFlow(false)
    val moduleOnline: StateFlow<Boolean> = _moduleOnline

    private val _sysInfo = MutableStateFlow<SysInfo?>(null)
    val sysInfo: StateFlow<SysInfo?> = _sysInfo

    private val requestId = AtomicInteger(1)
    private val stateLock = Any()
    private var runtime: ModuleRuntime? = null
    private var attached = false

    val available: Boolean
        get() =
            synchronized(stateLock) {
                runtime?.moduleId != null && runtime?.vconnOpen == true
            }

    private val moduleListener: (IdcBinding, String, Int, Boolean) -> Unit = { binding, name, mid, online ->
        if (name == MODULE_NAME) onModule(binding, online, mid)
    }

    private val vConnListener: (IdcBinding, Int, ByteArray) -> Unit = { binding, mid, payload ->
        val info = parseResponse(String(payload, Charsets.UTF_8))
        binding.targetLease.onlyIfActive {
            synchronized(stateLock) {
                if (!attached) return@synchronized
                val current = runtime ?: return@synchronized
                if (current.binding !== binding || current.moduleId != mid) return@synchronized
                if (isCurrent(binding) && info != null) _sysInfo.value = info
            }
        }
    }

    private fun isCurrent(binding: IdcBinding): Boolean =
        binding.isActive &&
            selection.binding.value
                ?.idc
                ?.value === binding

    fun attach() {
        synchronized(stateLock) { attached = true }
        selection.removeVConnListener(vConnListener)
        selection.addVConnListener(vConnListener)
        selection.removeModuleListener(moduleListener)
        selection.addModuleListener(moduleListener)
    }

    fun detach() {
        synchronized(stateLock) {
            attached = false
            runtime = null
            _moduleOnline.value = false
            _sysInfo.value = null
        }
        selection.removeVConnListener(vConnListener)
        selection.removeModuleListener(moduleListener)
    }

    private fun onModule(
        binding: IdcBinding,
        online: Boolean,
        mid: Int,
    ) {
        var shouldOpen = false
        var shouldQuery = false
        binding.targetLease.onlyIfActive {
            synchronized(stateLock) {
                if (!attached || !isCurrent(binding)) return@onlyIfActive
                val current = runtime?.takeIf { it.binding === binding } ?: ModuleRuntime(binding).also { runtime = it }
                if (!online) {
                    runtime = ModuleRuntime(binding)
                    _moduleOnline.value = false
                    _sysInfo.value = null
                    return@onlyIfActive
                }
                current.moduleId = mid
                if (!current.vconnOpen) {
                    current.vconnOpen = true
                    shouldOpen = true
                }
                _moduleOnline.value = true
                shouldQuery = true
            }
        }
        if (shouldOpen) binding.connection.openVConn(mid)
        if (shouldQuery) sendOn(binding, mid, "getSysInfo", "")
    }

    fun getSysInfo(): Boolean = send("getSysInfo", "")

    fun adjustResolution(index: Int): Boolean = send("adjustResolution", ""","resolution":$index""")

    fun adjustSize(largeType: Int): Boolean = send("adjustSize", ""","largeType":$largeType""")

    fun reboot(): Boolean = send("reboot", "")

    fun factoryReset(): Boolean = send("factoryReset", "")

    fun netDiagnosis(): Boolean = send("netDiagnosis", "")

    fun reconnect(): Boolean = send("reconnect", "")

    private fun send(
        messageType: String,
        extra: String,
    ): Boolean {
        var binding: IdcBinding? = null
        var midToSend: Int? = null
        var shouldOpen = false
        synchronized(stateLock) {
            if (!attached) return false
            val current = runtime ?: return false
            val mid = current.moduleId ?: return false
            if (!isCurrent(current.binding)) return false
            binding = current.binding
            midToSend = mid
            if (!current.vconnOpen) {
                current.vconnOpen = true
                shouldOpen = true
            }
        }
        val currentBinding = binding ?: return false
        val currentMid = midToSend ?: return false
        if (shouldOpen) currentBinding.connection.openVConn(currentMid)
        sendOn(currentBinding, currentMid, messageType, extra)
        return true
    }

    private fun sendOn(
        binding: IdcBinding,
        mid: Int,
        messageType: String,
        extra: String,
    ) {
        val json =
            """{"category":"$CATEGORY","messageType":"$messageType",""" +
                """"requestId":${requestId.getAndIncrement()}$extra}"""
        binding.connection.sendVConnData(mid, json.toByteArray(Charsets.UTF_8))
    }

    private fun parseResponse(json: String): SysInfo? {
        val parsed = parseJsonObject(json)
        if (parsed.str("messageType") != MSG_SYSINFO_RESP) return null
        return parseSysInfo(parsed)
    }

    internal fun handle(json: String) {
        _sysInfo.value = parseResponse(json)
    }

    internal fun parseSysInfo(j: FlatJson): SysInfo =
        SysInfo(
            ip = j.str("ip"),
            mac = j.str("mac"),
            sn = j.str("sn"),
            uuid = j.str("uuid"),
            firmware = j.str("firmware"),
            current = j.str("current"),
            resolutions = j.strArray("resolution"),
        )
}

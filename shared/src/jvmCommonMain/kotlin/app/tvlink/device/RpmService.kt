package app.tvlink.device

import app.tvlink.proto.idc.CmdLaunchSth
import app.tvlink.proto.idc.jsonEscape
import app.tvlink.proto.idc.parseJsonObject
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** RPM remote package management over IDC VConn. See docs/re/05 §3. */
class RpmService(
    private val selection: DeviceSelection,
) {
    data class TvApp(
        val packageName: String = "",
        val appName: String = "",
        val versionName: String = "",
        val versionCode: Long = 0,
        val iconUrl: String = "",
        val status: String = "",
    )

    data class InstallProgress(
        val packageName: String,
        val progress: Int,
        val status: String,
    )

    data class OpResult(
        val op: String,
        val packageName: String,
        val errorCode: Int,
    )

    private class ModuleRuntime(
        val binding: IdcBinding,
        var moduleId: Int? = null,
        var vconnOpen: Boolean = false,
        var wakeSent: Boolean = false,
        var pendingRequest: Pair<Int, String>? = null,
    )

    private val _appList = MutableStateFlow<List<TvApp>>(emptyList())
    val appList: StateFlow<List<TvApp>> = _appList

    private val _systemInfo = MutableSharedFlow<Map<String, String>>(extraBufferCapacity = 4)
    val systemInfo: SharedFlow<Map<String, String>> = _systemInfo

    private val _installProgress = MutableSharedFlow<InstallProgress>(extraBufferCapacity = 16)
    val installProgress: SharedFlow<InstallProgress> = _installProgress

    private val _opResults = MutableSharedFlow<OpResult>(extraBufferCapacity = 8)
    val opResults: SharedFlow<OpResult> = _opResults

    private sealed interface RpmEvent {
        data class AppList(
            val value: List<TvApp>,
        ) : RpmEvent

        data class SystemInfo(
            val value: Map<String, String>,
        ) : RpmEvent

        data class Progress(
            val value: InstallProgress,
        ) : RpmEvent

        data class Operation(
            val value: OpResult,
        ) : RpmEvent
    }

    private val requestId = AtomicInteger(1)
    private val stateLock = Any()
    private var runtime: ModuleRuntime? = null
    private var attached = false

    private val vConnListener: (IdcBinding, Int, ByteArray) -> Unit = { binding, _, payload ->
        val event = parse(payload)
        binding.targetLease.onlyIfActive {
            synchronized(stateLock) {
                if (attached && runtime?.binding === binding && isCurrent(binding)) publish(event)
            }
        }
    }

    private val moduleListener: (IdcBinding, String, Int, Boolean) -> Unit = { binding, name, mid, online ->
        if (name == MODULE_NAME) onAppStoreModule(binding, online, mid)
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

    private fun onAppStoreModule(
        binding: IdcBinding,
        online: Boolean,
        mid: Int,
    ) {
        var shouldOpen = false
        var pending: Pair<Int, String>? = null
        binding.targetLease.onlyIfActive {
            synchronized(stateLock) {
                if (!attached || !isCurrent(binding)) return@onlyIfActive
                var current = runtime
                if (current?.binding !== binding) {
                    current = ModuleRuntime(binding)
                    runtime = current
                }
                if (!online) {
                    runtime = ModuleRuntime(binding)
                    return@onlyIfActive
                }
                current.moduleId = mid
                if (!current.vconnOpen) {
                    current.vconnOpen = true
                    shouldOpen = true
                }
                pending = current.pendingRequest
                current.pendingRequest = null
            }
        }
        if (shouldOpen) binding.connection.openVConn(mid)
        pending?.let { (packetId, json) -> sendData(binding, mid, packetId, json) }
    }

    fun detach() {
        synchronized(stateLock) {
            attached = false
            runtime = null
        }
        selection.removeVConnListener(vConnListener)
        selection.removeModuleListener(moduleListener)
    }

    fun getAppList(pageSize: Int = 100) {
        send(ID_GETLIST_REQ, """{"pageSize":$pageSize}""", cacheRequest = true)
    }

    fun getSystemInfo() = send(ID_GET_SYSTEMINFO, "{}")

    fun openApp(packageName: String) = send(ID_OPENAPP_REQ, """{"packageName":"${jsonEscape(packageName)}"}""")

    fun uninstall(packageName: String) = send(ID_UNINSTALL_REQ, """{"packageName":"${jsonEscape(packageName)}"}""")

    fun installByUrl(
        packageName: String,
        apkUrl: String,
        appName: String = "",
        iconUrl: String = "",
        apkSize: String = "",
        versionNeeded: Int = 0,
    ) = send(
        ID_INSTALL_REQ,
        """{"packageName":"${jsonEscape(
            packageName,
        )}","apkUrl":"${jsonEscape(
            apkUrl,
        )}","iconUrl":"${jsonEscape(
            iconUrl,
        )}","appName":"${jsonEscape(appName)}","apkSize":"${jsonEscape(apkSize)}","versionNeeded":$versionNeeded}""",
    )

    private fun send(
        packetId: Int,
        json: String,
        cacheRequest: Boolean = false,
    ): Boolean {
        var binding: IdcBinding? = null
        var midToSend: Int? = null
        var shouldWake = false
        synchronized(stateLock) {
            if (!attached) return false
            binding =
                selection.binding.value
                    ?.idc
                    ?.value
            val currentBinding = binding
            if (currentBinding == null || !currentBinding.isActive) return false

            var current = runtime
            if (current?.binding !== currentBinding) {
                current = ModuleRuntime(currentBinding)
                runtime = current
            }
            val mid = current.moduleId
            if (mid == null) {
                if (cacheRequest) current.pendingRequest = packetId to json
                if (!current.wakeSent) {
                    current.wakeSent = true
                    shouldWake = true
                }
            } else {
                midToSend = mid
            }
        }

        val currentBinding = binding
        if (shouldWake && currentBinding != null) {
            currentBinding.connection.send(CmdLaunchSth(launchType = 1, action = WAKE_ACTION))
        }
        val mid = midToSend
        return if (mid != null && currentBinding != null) {
            sendData(currentBinding, mid, packetId, json)
            true
        } else {
            false
        }
    }

    private fun sendData(
        binding: IdcBinding,
        mid: Int,
        packetId: Int,
        json: String,
    ) {
        val body = json.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(8 + body.size)
        buf.putInt(packetId)
        buf.putInt(requestId.getAndIncrement())
        buf.put(body)
        binding.connection.sendVConnData(mid, buf.array())
    }

    private fun parse(payload: ByteArray): RpmEvent? {
        if (payload.size < 8) return null
        val buf = ByteBuffer.wrap(payload)
        val id = buf.int
        buf.int
        val json = String(payload, 8, payload.size - 8, Charsets.UTF_8)
        val j = parseJsonObject(json)
        return when (id) {
            ID_GETLIST_RESP -> RpmEvent.AppList(parseAppArray(j.str("apps")))
            ID_GETAPPINFO_RESP ->
                if (j.bool("appIsExist")) RpmEvent.SystemInfo(j.toMap()) else null
            ID_SYSTEMINFO -> RpmEvent.SystemInfo(j.toMap())
            ID_INSTALL_STATUS ->
                RpmEvent.Progress(
                    InstallProgress(j.str("packageName"), j.int("progress"), j.str("appStatus")),
                )
            ID_INSTALL_RESP -> RpmEvent.Operation(OpResult("install", j.str("packageName"), j.int("result")))
            ID_UNINSTALL_RESP -> RpmEvent.Operation(OpResult("uninstall", j.str("packageName"), j.int("result")))
            ID_OPENAPP_RESP -> RpmEvent.Operation(OpResult("open", j.str("packageName"), j.int("result")))
            else -> null
        }
    }

    private fun publish(event: RpmEvent?) {
        when (event) {
            is RpmEvent.AppList -> _appList.value = event.value
            is RpmEvent.SystemInfo -> _systemInfo.tryEmit(event.value)
            is RpmEvent.Progress -> _installProgress.tryEmit(event.value)
            is RpmEvent.Operation -> _opResults.tryEmit(event.value)
            null -> return
        }
    }

    internal fun parseAppArray(arr: String): List<TvApp> {
        val out = mutableListOf<TvApp>()
        val body = arr.removePrefix("[").removeSuffix("]")
        var depth = 0
        var start = -1
        for (i in body.indices) {
            when (body[i]) {
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        val o = parseJsonObject(body.substring(start, i + 1))
                        out.add(
                            TvApp(
                                packageName = o.str("packageName"),
                                appName = o.str("appName"),
                                versionName = o.str("versionName"),
                                versionCode = o.str("versionCode").toLongOrNull() ?: 0,
                                iconUrl = o.str("iconUrl"),
                                status = o.str("status"),
                            ),
                        )
                    }
                }
            }
        }
        return out
    }

    companion object {
        const val MODULE_NAME = "com.yunos.idc.appstore"
        private const val WAKE_ACTION = "yunos.appstore.startprocessservice"
        const val ID_GETAPPINFO_REQ = 2
        const val ID_GETAPPINFO_RESP = 3
        const val ID_GETLIST_REQ = 4
        const val ID_GETLIST_RESP = 5
        const val ID_INSTALL_REQ = 7
        const val ID_INSTALL_RESP = 8
        const val ID_INSTALL_STATUS = 9
        const val ID_INSTALL_CANCEL = 10
        const val ID_UNINSTALL_REQ = 11
        const val ID_UNINSTALL_RESP = 12
        const val ID_OPENAPP_REQ = 14
        const val ID_OPENAPP_RESP = 15
        const val ID_UPDATE_REQ = 20
        const val ID_UPDATE_RESP = 21
        const val ID_CONTINUE_DOWNLOAD = 24
        const val ID_CANCEL_GETLIST = 26
        const val ID_GET_SYSTEMINFO = 40
        const val ID_SYSTEMINFO = 41
    }
}

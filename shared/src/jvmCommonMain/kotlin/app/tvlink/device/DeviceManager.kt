package app.tvlink.device

import app.tvlink.proto.idc.IdcConnection
import app.tvlink.proto.idc.IdcConst
import app.tvlink.proto.idc.IdcPacket
import app.tvlink.proto.idc.LoginReq
import app.tvlink.proto.idc.parseJsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Facade over discovery + the active IDC session, exposing StateFlows for the UI.
 * Lives in jvmCommonMain — no Android/desktop-specific code here.
 */
class DeviceManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val discovery = Discovery()

    enum class ConnState { IDLE, SEARCHING, CONNECTING, CONNECTED, FAILED }

    data class ConnectedDevice(
        val ip: String,
        val name: String,
        val model: String,
        val uuid: String,
        val projectionPort: Int,
        /** IB 服务器版本(如 "3.29"),由 3988 探测产出;手动输入 IP 连接时为空。 */
        val ibVer: String = "",
        /** IB hello 响应中的 sid(会话标识),3988 探测产出;手动输入 IP 连接时为空。 */
        val ibSid: String = "",
    )

    private val _connState = MutableStateFlow(ConnState.IDLE)
    val connState: StateFlow<ConnState> = _connState

    private val _foundDevices = MutableStateFlow<List<Discovery.FoundDevice>>(emptyList())
    val foundDevices: StateFlow<List<Discovery.FoundDevice>> = _foundDevices

    private val _connected = MutableStateFlow<ConnectedDevice?>(null)
    val connected: StateFlow<ConnectedDevice?> = _connected

    private val _modules = MutableStateFlow<List<IdcConnection.ModuleInfo>>(emptyList())
    val modules: StateFlow<List<IdcConnection.ModuleInfo>> = _modules

    /** unmatched packets from the TV (IME events, screenshot resp, ...) */
    var onPacket: ((IdcPacket) -> Unit)? = null
    private val vConnListeners = java.util.concurrent.CopyOnWriteArrayList<(Int, ByteArray) -> Unit>()

    /**
     * 指定 module 的在线状态变化回调。service 据此打开 VConn 并补发挂起的请求。
     * [moduleId] 模块 ID(来自 ModuleAvailability 包 body),[online] 是否上线。
     */
    var onModuleAvailability: ((name: String, moduleId: Int, online: Boolean) -> Unit)? = null

    fun addVConnListener(l: (Int, ByteArray) -> Unit) {
        vConnListeners.add(l)
    }

    fun removeVConnListener(l: (Int, ByteArray) -> Unit) {
        vConnListeners.remove(l)
    }

    var connection: IdcConnection? = null
        private set
    private var discoveredProjectionPort = 0

    init {
        discovery.onDeviceFound = { d ->
            _foundDevices.value = discovery.devices.sortedBy { it.ip }
            if (d.projectionPort != 0) discoveredProjectionPort = d.projectionPort
        }
        discovery.onFinished = {
            if (_connState.value == ConnState.SEARCHING) _connState.value = ConnState.IDLE
        }
    }

    fun startDiscovery() {
        _connState.value = ConnState.SEARCHING
        _foundDevices.value = emptyList()
        scope.launch { discovery.start(scanSubnet = true) }
    }

    fun stopDiscovery() = discovery.stop()

    fun connect(device: Discovery.FoundDevice) {
        connect(device.ip, device.projectionPort, device.ibVer, device.ibSid)
    }

    fun connect(
        ip: String,
        projectionPort: Int = 0,
        ibVer: String = "",
        ibSid: String = "",
    ) {
        if (projectionPort != 0) discoveredProjectionPort = projectionPort
        _connState.value = ConnState.CONNECTING
        scope.launch {
            // kill any previous session before replacing it
            connection?.shutdown()
            connection = null
            val conn = IdcConnection(ip, IdcConst.TCP_PORT)
            wireCallbacks(conn)
            val ok = conn.connect(LoginReq(devName = "TVLink-Client"))
            if (ok) {
                connection = conn
                val di = conn.deviceInfo
                // Prefer ddhParams port > mDNS-discovered port > 0 (AppViewModel falls back to DEFAULT_CAST_PORT)
                val ddhPort =
                    di
                        ?.ddhParams
                        ?.get("mediaprojection")
                        ?.let { parseJsonObject(String(it, Charsets.UTF_8)).int("projectionport") } ?: 0
                _connected.value =
                    ConnectedDevice(
                        ip = ip,
                        name = di?.name ?: ip,
                        model = di?.model ?: "",
                        uuid = di?.uuid ?: "",
                        projectionPort = ddhPort.takeIf { it > 0 } ?: discoveredProjectionPort,
                        ibVer = ibVer,
                        ibSid = ibSid,
                    )
                _connState.value = ConnState.CONNECTED
            } else {
                _connState.value = ConnState.FAILED
            }
        }
    }

    private fun wireCallbacks(conn: IdcConnection) {
        conn.onStateChanged = { s ->
            // only react if this conn is still the active one
            if (s == IdcConnection.State.DISCONNECTED &&
                connection === conn &&
                _connState.value == ConnState.CONNECTED
            ) {
                disconnect()
            }
        }
        conn.onModulesChanged = {
            _modules.value = conn.modules.values.toList()
        }
        conn.onModuleChanged = { moduleId, name, online ->
            onModuleAvailability?.invoke(name, moduleId, online)
        }
        conn.onPacket = { p -> onPacket?.invoke(p) }
        conn.onVConnData = { mid, payload -> vConnListeners.forEach { it(mid, payload) } }
    }

    fun disconnect() {
        connection?.shutdown()
        connection = null
        _connected.value = null
        _modules.value = emptyList()
        _connState.value = ConnState.IDLE
    }

    /** Release all resources. Call when the owning ViewModel is cleared. */
    fun destroy() {
        stopDiscovery()
        disconnect()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    /** Convenience: module id by TV-registered name (e.g. "com.yunos.tv.asr:etao"). */
    fun moduleId(name: String): Int? = connection?.moduleIdByName(name)

    fun sendVConnJson(
        moduleId: Int,
        json: String,
    ) = connection?.sendVConnData(moduleId, json.toByteArray(Charsets.UTF_8))
}

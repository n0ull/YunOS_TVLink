package app.tvlink.device

import app.tvlink.proto.idc.IdcConnection
import app.tvlink.proto.idc.IdcConst
import app.tvlink.proto.idc.IdcPacket
import app.tvlink.proto.idc.LoginReq
import app.tvlink.proto.idc.parseJsonObject
import app.tvlink.ui.widgets.KeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Facade over discovery + the active IDC session, exposing StateFlows for the UI.
 * Lives in jvmCommonMain — no Android/desktop-specific code here.
 */
class DeviceManager {
    private companion object {
        /** 原 App retryConnect 策略（docs/re/01 §1）：首试 5s，之后每 15s，上限 2 次。 */
        const val FIRST_RETRY_MS = 5_000L
        const val RETRY_INTERVAL_MS = 15_000L
        const val MAX_RECONNECT = 2
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val discovery = Discovery()

    /** 历史设备持久化（原 App 按 SSID 存 SP JSON 数组；本项目简化为全局一条，见 saveHistory）。 */
    private val history = KeyValueStore("device-history")

    /** 自动重连簿记：仅对「成功连接后异常断开」重试；用户显式 disconnect() 不重连。 */
    @Volatile
    private var explicitDisconnect = false
    private var retries = 0
    private var reconnectJob: Job? = null
    private var reconnectTarget: ConnectedDevice? = null

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
        explicitDisconnect = false
        reconnectJob?.cancel()
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
                val dev =
                    ConnectedDevice(
                        ip = ip,
                        name = di?.name ?: ip,
                        model = di?.model ?: "",
                        uuid = di?.uuid ?: "",
                        projectionPort = ddhPort.takeIf { it > 0 } ?: discoveredProjectionPort,
                        ibVer = ibVer,
                        ibSid = ibSid,
                    )
                retries = 0
                reconnectTarget = dev
                saveHistory(dev)
                _connected.value = dev
                _connState.value = ConnState.CONNECTED
            } else {
                _connState.value = ConnState.FAILED
                // 重连周期内的失败继续排队下一次重试；用户发起的连接失败不自动重试
                if (retries in 1 until MAX_RECONNECT && !explicitDisconnect) scheduleReconnect()
            }
        }
    }

    /** 连接成功即持久化，供冷启动直连。ibVer/ibSid 属探测期会话数据，不持久化（重探测即得）。 */
    private fun saveHistory(d: ConnectedDevice) {
        history.putString("last.ip", d.ip)
        history.putString("last.name", d.name)
        history.putString("last.model", d.model)
        history.putString("last.uuid", d.uuid)
        history.putString("last.projectionPort", d.projectionPort.toString())
    }

    /** 上次成功连接的设备；无历史返回 null。 */
    fun lastDevice(): ConnectedDevice? {
        val ip = history.getString("last.ip") ?: return null
        return ConnectedDevice(
            ip = ip,
            name = history.getString("last.name") ?: ip,
            model = history.getString("last.model") ?: "",
            uuid = history.getString("last.uuid") ?: "",
            projectionPort = history.getString("last.projectionPort")?.toIntOrNull() ?: 0,
        )
    }

    /** 异常断开后按 5s → 15s ×2 自动重连（原 App 策略，docs/re/01 §1）；期间 UI 停在 CONNECTING。 */
    private fun scheduleReconnect() {
        val target = reconnectTarget
        if (target == null || retries >= MAX_RECONNECT) {
            _connState.value = ConnState.FAILED
            return
        }
        retries++
        _connState.value = ConnState.CONNECTING
        reconnectJob =
            scope.launch {
                delay(if (retries == 1) FIRST_RETRY_MS else RETRY_INTERVAL_MS)
                if (!explicitDisconnect) connect(target.ip, target.projectionPort, target.ibVer, target.ibSid)
            }
    }

    private fun wireCallbacks(conn: IdcConnection) {
        conn.onStateChanged = { s ->
            // only react if this conn is still the active one
            if (s == IdcConnection.State.DISCONNECTED &&
                connection === conn &&
                _connState.value == ConnState.CONNECTED
            ) {
                // 异常断开（心跳判死/网络抖动/TV 重启）→ 自动重连；用户显式断开 → 落 IDLE
                val willReconnect = !explicitDisconnect && reconnectTarget != null
                connection = null
                _connected.value = null
                _modules.value = emptyList()
                if (willReconnect) scheduleReconnect() else _connState.value = ConnState.IDLE
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
        explicitDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        retries = 0
        reconnectTarget = null
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

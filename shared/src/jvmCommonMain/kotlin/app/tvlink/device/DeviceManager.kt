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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
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

    /** 以下两个字段在 scope 协程(connect/scheduleReconnect)与 UI 线程(disconnect)间跨线程访问。 */
    @Volatile
    private var retries = 0
    private var reconnectJob: Job? = null

    @Volatile
    private var reconnectTarget: ConnectedDevice? = null

    enum class ConnState { IDLE, SEARCHING, CONNECTING, CONNECTED, FAILED }

    data class ConnectedDevice(
        val ip: String,
        val name: String,
        val model: String,
        val uuid: String,
        /** MAC 来自 mDNS TXT deviceid;扫描/手输 IP 路径无来源,为空。 */
        val mac: String = "",
        val projectionPort: Int,
        /** IB 服务器版本(如 "3.29"),由 3988 探测产出;手动输入 IP 连接时为空。 */
        val ibVer: String = "",
        /** IB hello 响应中的 sid(会话标识),3988 探测产出;手动输入 IP 连接时为空。 */
        val ibSid: String = "",
        /** IB-only 连接:仅 IB 通道(3988)可达,IDC(13510/13511)未开放。
         *  遥控可用,投屏/截图/应用管理/系统属性不可用。 */
        val ibOnly: Boolean = false,
    )

    private val _connState = MutableStateFlow(ConnState.IDLE)
    val connState: StateFlow<ConnState> = _connState

    private val _foundDevices = MutableStateFlow<List<Discovery.FoundDevice>>(emptyList())
    val foundDevices: StateFlow<List<Discovery.FoundDevice>> = _foundDevices

    private val _connected = MutableStateFlow<ConnectedDevice?>(null)
    val connected: StateFlow<ConnectedDevice?> = _connected

    private val _modules = MutableStateFlow<List<IdcConnection.ModuleInfo>>(emptyList())
    val modules: StateFlow<List<IdcConnection.ModuleInfo>> = _modules

    /** Unmatched packets from the TV (IME events, screenshot resp, ...).
     *  读线程 tryEmit 不阻塞；缓冲满时丢弃（IME/截图应答低频，64 足够）。 */
    private val _packets = MutableSharedFlow<IdcPacket>(extraBufferCapacity = 64)
    val packets: SharedFlow<IdcPacket> = _packets
    private val vConnListeners = java.util.concurrent.CopyOnWriteArrayList<(Int, ByteArray) -> Unit>()

    /**
     * 模块在线状态订阅（多 service 并存：RPM、魔投设置…各自订阅，避免单槽位互覆盖）。
     * 参数：模块 TV 注册名、模块 ID（ModuleAvailability body）、是否上线。
     */
    private val moduleListeners =
        java.util.concurrent.CopyOnWriteArrayList<(name: String, moduleId: Int, online: Boolean) -> Unit>()

    fun addModuleListener(l: (name: String, moduleId: Int, online: Boolean) -> Unit) {
        moduleListeners.add(l)
    }

    fun removeModuleListener(l: (name: String, moduleId: Int, online: Boolean) -> Unit) {
        moduleListeners.remove(l)
    }

    fun addVConnListener(l: (Int, ByteArray) -> Unit) {
        vConnListeners.add(l)
    }

    fun removeVConnListener(l: (Int, ByteArray) -> Unit) {
        vConnListeners.remove(l)
    }

    /** scope 协程写入 / reader 线程与 UI 线程读取 — @Volatile 保证跨线程可见性。 */
    @Volatile
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
        connect(device.ip, device.projectionPort, device.ibVer, device.ibSid, device.mac)
    }

    fun connect(
        ip: String,
        projectionPort: Int = 0,
        ibVer: String = "",
        ibSid: String = "",
        mac: String = "",
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
                // Prefer ddhParams port > mDNS-discovered port > 0 (AppViewModel 兜底时依次试 13520/13521)
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
                        mac = mac,
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

    /** IB-only 连接：仅建 IB 通道(3988)，不经 IDC。适用于子网扫描发现 IB 但 IDC 未开放的设备。
     *  遥控可用；投屏/截图/应用管理/系统属性不可用。无自动重连（与原 App 一致，IB 初始失败不重试）。 */
    fun connectIbOnly(
        ip: String,
        ibVer: String = "",
        ibSid: String = "",
    ) {
        explicitDisconnect = false
        reconnectJob?.cancel()
        _connState.value = ConnState.CONNECTING
        scope.launch {
            connection?.shutdown()
            connection = null
            // 不建 IDC，直接置 CONNECTED；RcController.attach() 会据此 ip 建 IB 通道。
            val dev =
                ConnectedDevice(
                    ip = ip,
                    name = "电视 (IB)",
                    model = "",
                    uuid = "",
                    mac = "",
                    projectionPort = 0,
                    ibVer = ibVer,
                    ibSid = ibSid,
                    ibOnly = true,
                )
            retries = 0
            reconnectTarget = dev
            saveHistory(dev)
            _connected.value = dev
            _connState.value = ConnState.CONNECTED
        }
    }

    /** 连接成功即持久化，供冷启动直连。ibVer/ibSid 属探测期会话数据，不持久化（重探测即得）。 */
    private fun saveHistory(d: ConnectedDevice) {
        history.putString("last.ip", d.ip)
        history.putString("last.name", d.name)
        history.putString("last.model", d.model)
        history.putString("last.uuid", d.uuid)
        history.putString("last.mac", d.mac)
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
            mac = history.getString("last.mac") ?: "",
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
                if (!explicitDisconnect) {
                    connect(target.ip, target.projectionPort, target.ibVer, target.ibSid, target.mac)
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
            moduleListeners.forEach { it(name, moduleId, online) }
        }
        conn.onPacket = { p -> _packets.tryEmit(p) }
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
        discoveredProjectionPort = 0
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

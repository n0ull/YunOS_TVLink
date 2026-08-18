package app.tvlink.device

import app.tvlink.proto.idc.IdcConnection
import app.tvlink.proto.idc.IdcConst
import app.tvlink.proto.idc.IdcPacket
import app.tvlink.proto.idc.LoginReq
import app.tvlink.proto.idc.parseJsonObject
import app.tvlink.ui.widgets.KeyValueStore
import java.util.concurrent.atomic.AtomicInteger
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    /** 原子重试计数：connect/scheduleReconnect 协程与 UI 线程(disconnect)跨线程读-改-写，
     *  @Volatile 仅保证可见性不保证原子性，故用 AtomicInteger。 */
    private val retries = AtomicInteger(0)
    private var reconnectJob: Job? = null

    /** 建连单飞（同 CastFeature.connectMutex 范本）：重叠 connect 串行化——后发者等先发者完成后
     *  再 shutdown 旧会话并接替；否则慢登录的落败方最后完成会覆盖 connection，
     *  泄漏胜方整条 IDC 会话（3 线程 + socket）且两台 TV 的包混入同一 UI 流。 */
    private val connectMutex = Mutex()

    /** 生命周期世代号（同 CastFeature 范本）：connect/connectIbOnly/disconnect 发起即递增。
     *  锁内建连（最坏 ~16s 端口兜底）成功后装回前校验世代——期间显式断开/另一次建连
     *  则放弃装回，防「断开后设备自行连回」。请求时取号（非协程内）：取号顺序=请求顺序。 */
    private val generation = AtomicInteger(0)

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
        val gen = generation.incrementAndGet()
        scope.launch {
            // 单飞锁串行化重叠建连（范本：CastFeature.connectMutex）
            connectMutex.withLock {
                // 等锁期间出现更新意图（显式断开/另一次建连）：放弃——不杀更新的会话、
                // 不白跑最坏 ~16s 阻塞登录（同 connectIbOnly 锁顶闸）
                if (generation.get() != gen) return@withLock
                // kill any previous session before replacing it。
                // 先置 null 再 shutdown：旧会话 close() 触发的 DISCONNECTED 回调经
                // `connection === conn` 判据自然落空，不会误判为异常断开而排队自动重连
                val old = connection
                connection = null
                old?.shutdown()
                val conn = IdcConnection(ip, IdcConst.TCP_PORT)
                wireCallbacks(conn)
                val ok = conn.connect(LoginReq(devName = "TVLink-Client"))
                // 成功即装回：reader 回调的 `connection === conn` 判据在此之后才有效——
                // 若装回前 reader 已判死，回调因 connection 仍为 null 而遗漏，UI 停在
                // CONNECTED 但通道已死，需等心跳（最坏 ~60s）才能自愈。早装回后世代守卫
                // 负责撤销（见下），窗口收窄至 conn.connect 内部（reader 启动后至返回前）。
                if (ok) connection = conn
                // 世代守卫不分成败：建连（锁内阻塞最坏 ~16s）期间出现更新意图则放弃——
                // 成功不装回（撤销早装回）；失败也不把 FAILED 覆写到更新状态上（显式断开
                // 已置 IDLE，覆写会让 AppViewModel 复核后误弹"连接失败"）。失败侧 connect
                // 内部已 close，shutdown 幂等
                if (generation.get() != gen) {
                    conn.shutdown()
                    if (connection === conn) connection = null
                    return@withLock
                }
                if (ok) {
                    val dev = buildConnectedDevice(conn, ip, mac, ibVer, ibSid)
                    retries.set(0)
                    reconnectTarget = dev
                    saveHistory(dev)
                    _connected.value = dev
                    _connState.value = ConnState.CONNECTED
                } else {
                    _connState.value = ConnState.FAILED
                    // 重连周期内的失败继续排队下一次重试；用户发起的连接失败不自动重试
                    if (retries.get() in 1 until MAX_RECONNECT && !explicitDisconnect) scheduleReconnect()
                }
            }
        }
    }

    /** 建连成功后组装 ConnectedDevice。投影端口优先 ddhParams > mDNS 发现 > 0（AppViewModel 兜底时依次试 13520/13521）。 */
    private fun buildConnectedDevice(
        conn: IdcConnection,
        ip: String,
        mac: String,
        ibVer: String,
        ibSid: String,
    ): ConnectedDevice {
        val di = conn.deviceInfo
        val ddhPort =
            di
                ?.ddhParams
                ?.get("mediaprojection")
                ?.let { parseJsonObject(String(it, Charsets.UTF_8)).int("projectionport") } ?: 0
        return ConnectedDevice(
            ip = ip,
            name = di?.name ?: ip,
            model = di?.model ?: "",
            uuid = di?.uuid ?: "",
            mac = mac,
            projectionPort = ddhPort.takeIf { it > 0 } ?: discoveredProjectionPort,
            ibVer = ibVer,
            ibSid = ibSid,
        )
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
        val gen = generation.incrementAndGet()
        scope.launch {
            connectMutex.withLock {
                if (generation.get() != gen) return@withLock // 等锁期间出现更新意图，放弃
                val old = connection
                connection = null
                old?.shutdown()
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
                retries.set(0)
                reconnectTarget = dev
                saveHistory(dev)
                _connected.value = dev
                _connState.value = ConnState.CONNECTED
            }
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
        if (target == null || retries.get() >= MAX_RECONNECT) {
            _connState.value = ConnState.FAILED
            return
        }
        retries.incrementAndGet()
        _connState.value = ConnState.CONNECTING
        reconnectJob =
            scope.launch {
                delay(if (retries.get() == 1) FIRST_RETRY_MS else RETRY_INTERVAL_MS)
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
        generation.incrementAndGet() // 使在途建连的装回检查失效（放弃安装）
        reconnectJob?.cancel()
        reconnectJob = null
        retries.set(0)
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

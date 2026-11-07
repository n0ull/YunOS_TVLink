package app.tvlink.device

import app.tvlink.platform.KeyValueStore
import app.tvlink.proto.idc.IdcConnection
import app.tvlink.proto.idc.IdcConst
import app.tvlink.proto.idc.IdcPacket
import app.tvlink.proto.idc.LoginReq
import app.tvlink.proto.idc.parseJsonObject
import java.util.concurrent.CopyOnWriteArrayList
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

/** Owns device discovery and the active TV session. */
class DeviceManager {
    enum class ConnState { IDLE, SEARCHING, CONNECTING, CONNECTED, FAILED }

    data class ConnectedDevice(
        val ip: String,
        val name: String,
        val model: String,
        val uuid: String,
        /** Empty for subnet scans and manual connections, which have no MAC source. */
        val mac: String = "",
        val projectionPort: Int,
        /** Session details obtained by the IB probe; empty for manual connections. */
        val ibVer: String = "",
        val ibSid: String = "",
        /** Only IB is reachable, so IDC-dependent features are unavailable. */
        val ibOnly: Boolean = false,
    )

    private companion object {
        const val FIRST_RETRY_MS = 5_000L
        const val RETRY_INTERVAL_MS = 15_000L
        const val MAX_RECONNECT = 2
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val discovery = Discovery()
    private val history = KeyValueStore("device-history")

    private val _connState = MutableStateFlow(ConnState.IDLE)
    val connState: StateFlow<ConnState> = _connState

    private val _foundDevices = MutableStateFlow<List<Discovery.FoundDevice>>(emptyList())
    val foundDevices: StateFlow<List<Discovery.FoundDevice>> = _foundDevices

    private val _connected = MutableStateFlow<ConnectedDevice?>(null)
    val connected: StateFlow<ConnectedDevice?> = _connected

    private val _modules = MutableStateFlow<List<IdcConnection.ModuleInfo>>(emptyList())
    val modules: StateFlow<List<IdcConnection.ModuleInfo>> = _modules

    private val _packets = MutableSharedFlow<IdcPacket>(extraBufferCapacity = 64)
    val packets: SharedFlow<IdcPacket> = _packets

    private val vConnListeners = CopyOnWriteArrayList<(Int, ByteArray) -> Unit>()
    private val moduleListeners = CopyOnWriteArrayList<(name: String, moduleId: Int, online: Boolean) -> Unit>()

    @Volatile
    var connection: IdcConnection? = null
        private set

    @Volatile
    private var explicitDisconnect = false

    @Volatile
    private var reconnectTarget: ConnectedDevice? = null

    @Volatile
    private var discoveredProjectionPort = 0

    private val retries = AtomicInteger(0)
    private var reconnectJob: Job? = null
    private val connectMutex = Mutex()

    /** Request order defines generation order; stale work must not install a session or state. */
    private val generation = AtomicInteger(0)

    init {
        discovery.onDeviceFound = {
            _foundDevices.value = discovery.devices.sortedBy { it.ip }
        }
        discovery.onFinished = {
            if (_connState.value == ConnState.SEARCHING) _connState.value = ConnState.IDLE
        }
    }

    fun addModuleListener(listener: (name: String, moduleId: Int, online: Boolean) -> Unit) {
        moduleListeners.add(listener)
    }

    fun removeModuleListener(listener: (name: String, moduleId: Int, online: Boolean) -> Unit) {
        moduleListeners.remove(listener)
    }

    fun addVConnListener(listener: (Int, ByteArray) -> Unit) {
        vConnListeners.add(listener)
    }

    fun removeVConnListener(listener: (Int, ByteArray) -> Unit) {
        vConnListeners.remove(listener)
    }

    fun startDiscovery() {
        if (_connState.value == ConnState.IDLE) _connState.value = ConnState.SEARCHING
        _foundDevices.value = emptyList()
        discovery.start(scanSubnet = true)
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
            connectMutex.withLock {
                if (generation.get() != gen) return@withLock

                val old = connection
                connection = null
                old?.shutdown()

                val conn = IdcConnection(ip, IdcConst.TCP_PORT)
                wireCallbacks(conn)
                val ok = conn.connect(LoginReq(devName = "TVLink-Client"))
                // Make the session visible before a reader callback can report its death.
                if (ok) connection = conn
                if (generation.get() != gen) {
                    conn.shutdown()
                    if (connection === conn) connection = null
                    return@withLock
                }
                if (!ok) {
                    _connState.value = ConnState.FAILED
                    if (retries.get() in 1 until MAX_RECONNECT && !explicitDisconnect) scheduleReconnect()
                    return@withLock
                }

                val device = buildConnectedDevice(conn, ip, mac, ibVer, ibSid)
                retries.set(0)
                reconnectTarget = device
                saveHistory(device)
                _connected.value = device
                _connState.value = ConnState.CONNECTED
            }
        }
    }

    private fun buildConnectedDevice(
        conn: IdcConnection,
        ip: String,
        mac: String,
        ibVer: String,
        ibSid: String,
    ): ConnectedDevice {
        val info = conn.deviceInfo
        val ddhPort =
            info
                ?.ddhParams
                ?.get("mediaprojection")
                ?.let { parseJsonObject(String(it, Charsets.UTF_8)).int("projectionport") } ?: 0
        return ConnectedDevice(
            ip = ip,
            name = info?.name ?: ip,
            model = info?.model ?: "",
            uuid = info?.uuid ?: "",
            mac = mac,
            projectionPort = ddhPort.takeIf { it > 0 } ?: discoveredProjectionPort,
            ibVer = ibVer,
            ibSid = ibSid,
        )
    }

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
                if (generation.get() != gen) return@withLock

                val old = connection
                connection = null
                old?.shutdown()
                val device =
                    ConnectedDevice(
                        ip = ip,
                        name = "电视 (IB)",
                        model = "",
                        uuid = "",
                        projectionPort = 0,
                        ibVer = ibVer,
                        ibSid = ibSid,
                        ibOnly = true,
                    )
                retries.set(0)
                reconnectTarget = device
                saveHistory(device)
                _connected.value = device
                _connState.value = ConnState.CONNECTED
            }
        }
    }

    private fun saveHistory(device: ConnectedDevice) {
        history.putString("last.ip", device.ip)
        history.putString("last.name", device.name)
        history.putString("last.model", device.model)
        history.putString("last.uuid", device.uuid)
        history.putString("last.mac", device.mac)
        history.putString("last.projectionPort", device.projectionPort.toString())
    }

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

    /** Retry only after a previously successful session drops: 5s, then 15s. */
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
        conn.onStateChanged = { state ->
            val activeSessionDied =
                state == IdcConnection.State.DISCONNECTED &&
                    connection === conn &&
                    _connState.value == ConnState.CONNECTED
            if (activeSessionDied) {
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
        conn.onPacket = { _packets.tryEmit(it) }
        conn.onVConnData = { mid, payload -> vConnListeners.forEach { it(mid, payload) } }
    }

    fun disconnect() {
        explicitDisconnect = true
        generation.incrementAndGet()
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

    fun destroy() {
        stopDiscovery()
        disconnect()
        scope.coroutineContext[Job]?.cancel()
    }

    fun moduleId(name: String): Int? = connection?.moduleIdByName(name)

    fun sendVConnJson(
        moduleId: Int,
        json: String,
    ) = connection?.sendVConnData(moduleId, json.toByteArray(Charsets.UTF_8))
}

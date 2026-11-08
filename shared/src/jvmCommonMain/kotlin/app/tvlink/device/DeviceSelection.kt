package app.tvlink.device

import app.tvlink.proto.idc.IdcConnection
import app.tvlink.proto.idc.IdcConst
import app.tvlink.proto.idc.IdcPacket
import app.tvlink.proto.idc.LoginReq
import app.tvlink.proto.idc.parseJsonObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class IdcProgress { IDLE, CONNECTING, CONNECTED, FAILED }

data class SelectionSnapshot(
    val selectionId: Long = 0,
    val target: DeviceTarget? = null,
    val mode: ConnectionMode? = null,
    val idcProgress: IdcProgress = IdcProgress.IDLE,
)

internal class ChannelLease(
    val connectionId: Long,
) {
    @Volatile
    var isActive = true
        private set

    fun revoke() {
        isActive = false
    }
}

internal class TargetLease(
    val selectionId: Long,
) {
    private val lock = Any()
    private val cancellation = Job()
    val cancelSignal: Job get() = cancellation

    @Volatile
    var isActive = true
        private set

    fun revoke() {
        val shouldNotify =
            synchronized(lock) {
                if (!isActive) {
                    false
                } else {
                    isActive = false
                    true
                }
            }
        if (shouldNotify) cancellation.complete()
    }

    fun revoke(channel: ChannelLease) {
        synchronized(lock) { channel.revoke() }
    }

    /** A permit is ordered with revocation; socket I/O happens after this lock is released. */
    fun acquire(channel: ChannelLease): Boolean = synchronized(lock) { isActive && channel.isActive }

    /** Runs only for the active target; used for in-memory state publication, never I/O. */
    fun <T> onlyIfActive(block: () -> T): T? = synchronized(lock) { if (isActive) block() else null }
}

internal class IdcBinding(
    val connectionId: Long,
    val targetLease: TargetLease,
    val channelLease: ChannelLease,
    val connection: IdcConnection,
) {
    val isActive: Boolean
        get() = targetLease.isActive && channelLease.isActive

    init {
        connection.writerGuard = { targetLease.acquire(channelLease) }
    }
}

internal data class SelectionBinding(
    val lease: TargetLease,
    val target: DeviceTarget,
    val mode: ConnectionMode,
    val idc: StateFlow<IdcBinding?>,
)

private data class SelectionRecord(
    val snapshot: SelectionSnapshot,
    val binding: SelectionBinding?,
)

internal data class ConnectionPacket(
    val binding: IdcBinding,
    val value: IdcPacket,
)

/** Owns the selected target, its IDC connection, reconnects, and connection-scoped callbacks. */
class DeviceSelection(
    private val history: ConnectionHistory = KeyValueConnectionHistory(),
    private val idcPort: Int = IdcConst.TCP_PORT,
) {
    private companion object {
        const val FIRST_RETRY_MS = 5_000L
        const val RETRY_INTERVAL_MS = 15_000L
        const val MAX_RECONNECT = 2
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closeExecutor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "idc-close").apply { isDaemon = true }
        }
    private val lifecycleLock = Any()
    private val _state = MutableStateFlow(SelectionSnapshot())
    val state: StateFlow<SelectionSnapshot> = _state
    private val bindingState = MutableStateFlow<SelectionBinding?>(null)
    internal val binding: StateFlow<SelectionBinding?>
        get() = bindingState

    @Volatile
    private var current = SelectionRecord(SelectionSnapshot(), null)

    private val _modules = MutableStateFlow<List<IdcConnection.ModuleInfo>>(emptyList())
    val modules: StateFlow<List<IdcConnection.ModuleInfo>> = _modules

    private val packetState = MutableSharedFlow<ConnectionPacket>(extraBufferCapacity = 64)
    internal val packets: SharedFlow<ConnectionPacket>
        get() = packetState

    private val vConnListeners = CopyOnWriteArrayList<(IdcBinding, Int, ByteArray) -> Unit>()
    private val moduleListeners = CopyOnWriteArrayList<(IdcBinding, String, Int, Boolean) -> Unit>()

    @Volatile
    internal var connection: IdcConnection? = null
        private set

    @Volatile
    private var currentIdcBinding: IdcBinding? = null

    @Volatile
    private var explicitDisconnect = false

    private val selectionCounter = AtomicLong(0)
    private val connectionCounter = AtomicLong(0)
    private val retries = AtomicInteger(0)
    private var reconnectJob: Job? = null
    private val connectMutex = Mutex()
    private val generation = AtomicInteger(0)

    /** Test fixture that pauses immediately before the atomic connected-state publication. */
    internal var beforeConnectedPublish: (selectionId: Long) -> Unit = { }

    internal fun addModuleListener(listener: (IdcBinding, String, Int, Boolean) -> Unit) {
        moduleListeners.add(listener)
    }

    internal fun removeModuleListener(listener: (IdcBinding, String, Int, Boolean) -> Unit) {
        moduleListeners.remove(listener)
    }

    internal fun addVConnListener(listener: (IdcBinding, Int, ByteArray) -> Unit) {
        vConnListeners.add(listener)
    }

    internal fun removeVConnListener(listener: (IdcBinding, Int, ByteArray) -> Unit) {
        vConnListeners.remove(listener)
    }

    private fun closeInBackground(connection: IdcConnection) {
        closeExecutor.execute { connection.shutdown() }
    }

    fun select(
        target: DeviceTarget,
        mode: ConnectionMode = ConnectionMode.FULL,
    ) {
        val selectionId = selectionCounter.incrementAndGet()
        val lease = TargetLease(selectionId)
        val binding = SelectionBinding(lease, target, mode, MutableStateFlow(null))
        val progress = if (mode == ConnectionMode.FULL) IdcProgress.CONNECTING else IdcProgress.IDLE
        val snapshot = SelectionSnapshot(selectionId, target, mode, progress)

        val oldConnection =
            synchronized(lifecycleLock) {
                explicitDisconnect = false
                reconnectJob?.cancel()
                retries.set(0)
                current.binding?.lease?.revoke()
                val old = detachCurrentIdc()
                publish(SelectionRecord(snapshot, binding))
                old
            }
        oldConnection?.let(::closeInBackground)

        if (mode == ConnectionMode.FULL) {
            openIdc(target, selectionId)
        } else {
            history.save(SavedSelection(target, mode))
        }
    }

    private fun publish(record: SelectionRecord) {
        current = record
        bindingState.value = record.binding
        _state.value = record.snapshot
    }

    private fun updateSnapshot(transform: (SelectionSnapshot) -> SelectionSnapshot) {
        synchronized(lifecycleLock) {
            val updated = current.copy(snapshot = transform(current.snapshot))
            current = updated
            _state.value = updated.snapshot
        }
    }

    /** Detaches the active entry without performing socket I/O. Caller closes the returned connection. */
    private fun detachCurrentIdc(): IdcConnection? {
        val binding = currentIdcBinding
        val lease = current.binding?.lease
        if (binding != null && lease != null) lease.revoke(binding.channelLease)
        currentIdcBinding = null
        (current.binding?.idc as? MutableStateFlow)?.value = null
        val old = connection
        connection = null
        return old
    }

    private fun nextGeneration(selectionId: Long): Int? =
        synchronized(lifecycleLock) {
            if (current.snapshot.selectionId != selectionId || current.binding?.lease?.isActive != true) {
                null
            } else {
                generation.incrementAndGet()
            }
        }

    private fun openIdc(
        target: DeviceTarget,
        selectionId: Long,
    ) {
        // A stale reconnect must not advance generation for a newer selection.
        val gen = nextGeneration(selectionId) ?: return
        scope.launch {
            connectMutex.withLock {
                val binding = createIdcBinding(target, selectionId, gen) ?: return@withLock
                val conn = binding.connection
                val ok = conn.connect(LoginReq(devName = "TVLink-Client"))
                installEarlyIfCurrent(binding, selectionId, gen, ok)

                val connectedTarget = if (ok) buildConnectedTarget(conn, target) else null
                if (connectedTarget != null) beforeConnectedPublish(selectionId)
                if (connectedTarget != null && publishConnected(binding, selectionId, gen, connectedTarget)) {
                    history.save(SavedSelection(connectedTarget, ConnectionMode.FULL))
                } else {
                    rejectIdcAttempt(binding, selectionId, gen, ok)
                }
            }
        }
    }

    private fun createIdcBinding(
        target: DeviceTarget,
        selectionId: Long,
        gen: Int,
    ): IdcBinding? {
        val result =
            synchronized(lifecycleLock) {
                if (!isCurrent(selectionId, gen)) {
                    null
                } else {
                    val old = detachCurrentIdc()
                    val lease = current.binding?.lease
                    if (lease == null) null else old to lease
                }
            } ?: return null
        result.first?.shutdown()

        val targetLease = result.second
        val connectionId = connectionCounter.incrementAndGet()
        val conn = IdcConnection(target.ip, idcPort)
        return IdcBinding(connectionId, targetLease, ChannelLease(connectionId), conn).also {
            wireCallbacks(it, selectionId)
        }
    }

    private fun installEarlyIfCurrent(
        binding: IdcBinding,
        selectionId: Long,
        gen: Int,
        connected: Boolean,
    ) {
        synchronized(lifecycleLock) {
            if (connected && isCurrent(selectionId, gen)) {
                connection = binding.connection
                currentIdcBinding = binding
            }
        }
    }

    private fun publishConnected(
        binding: IdcBinding,
        selectionId: Long,
        gen: Int,
        connectedTarget: DeviceTarget,
    ): Boolean =
        synchronized(lifecycleLock) {
            val connectionIsActive =
                isCurrent(selectionId, gen) &&
                    currentIdcBinding === binding &&
                    binding.isActive &&
                    binding.connection.state == IdcConnection.State.ESTABLISHED
            if (!connectionIsActive) {
                false
            } else {
                retries.set(0)
                (current.binding?.idc as? MutableStateFlow)?.value = binding
                val updatedBinding = current.binding?.copy(target = connectedTarget)
                publish(
                    SelectionRecord(
                        snapshot =
                            current.snapshot.copy(
                                target = connectedTarget,
                                idcProgress = IdcProgress.CONNECTED,
                            ),
                        binding = updatedBinding,
                    ),
                )
                true
            }
        }

    private fun rejectIdcAttempt(
        binding: IdcBinding,
        selectionId: Long,
        gen: Int,
        connected: Boolean,
    ) {
        binding.targetLease.revoke(binding.channelLease)
        val installed =
            synchronized(lifecycleLock) {
                if (currentIdcBinding === binding) detachCurrentIdc() else null
            }
        installed?.shutdown()
        binding.connection.shutdown()
        if (connected) {
            synchronized(lifecycleLock) {
                if (isCurrent(selectionId, gen) && !explicitDisconnect && reconnectJob?.isActive != true) {
                    scheduleReconnect(selectionId)
                }
            }
            return
        }

        synchronized(lifecycleLock) {
            if (isCurrent(selectionId, gen)) {
                updateSnapshot { it.copy(idcProgress = IdcProgress.FAILED) }
                if (retries.get() in 1 until MAX_RECONNECT && !explicitDisconnect) scheduleReconnect(selectionId)
            }
        }
    }

    private fun isCurrent(
        selectionId: Long,
        gen: Int,
    ): Boolean {
        val lease = current.binding?.lease
        return current.snapshot.selectionId == selectionId &&
            generation.get() == gen &&
            lease?.isActive == true
    }

    private fun buildConnectedTarget(
        conn: IdcConnection,
        selected: DeviceTarget,
    ): DeviceTarget {
        val info = conn.deviceInfo
        val ddhPort =
            info
                ?.ddhParams
                ?.get("mediaprojection")
                ?.let { parseJsonObject(String(it, Charsets.UTF_8)).int("projectionport") } ?: 0
        return selected.copy(
            name =
                info?.name?.ifEmpty { selected.name.ifEmpty { selected.ip } } ?: selected.name.ifEmpty { selected.ip },
            model = info?.model?.ifEmpty { selected.model } ?: selected.model,
            uuid = info?.uuid?.ifEmpty { selected.uuid } ?: selected.uuid,
            projectionPort = ddhPort.takeIf { it > 0 } ?: selected.projectionPort,
        )
    }

    private fun scheduleReconnect(selectionId: Long) {
        val target = current.snapshot.target
        if (target == null || retries.get() >= MAX_RECONNECT) {
            updateSnapshot { it.copy(idcProgress = IdcProgress.FAILED) }
            return
        }
        retries.incrementAndGet()
        updateSnapshot { it.copy(idcProgress = IdcProgress.CONNECTING) }
        reconnectJob =
            scope.launch {
                delay(if (retries.get() == 1) FIRST_RETRY_MS else RETRY_INTERVAL_MS)
                val runningJob = currentCoroutineContext()[Job]
                synchronized(lifecycleLock) {
                    if (reconnectJob === runningJob) reconnectJob = null
                }
                if (!explicitDisconnect && current.snapshot.selectionId == selectionId) openIdc(target, selectionId)
            }
    }

    private fun wireCallbacks(
        binding: IdcBinding,
        selectionId: Long,
    ) {
        val conn = binding.connection
        conn.onStateChanged = { state -> handleConnectionState(binding, selectionId, state) }
        conn.onModulesChanged = { publishModules(binding) }
        conn.onModuleChanged = { moduleId, name, online -> publishModuleEvent(binding, moduleId, name, online) }
        conn.onPacket = { packet -> publishPacket(binding, packet) }
        conn.onVConnData = { mid, payload -> publishVConnData(binding, mid, payload) }
    }

    private fun handleConnectionState(
        binding: IdcBinding,
        selectionId: Long,
        state: IdcConnection.State,
    ) {
        val toClose =
            synchronized(lifecycleLock) {
                val activeSessionDied =
                    state == IdcConnection.State.DISCONNECTED &&
                        currentIdcBinding === binding &&
                        binding.isActive &&
                        current.snapshot.selectionId == selectionId
                if (!activeSessionDied) {
                    null
                } else {
                    val old = detachCurrentIdc()
                    _modules.value = emptyList()
                    if (!explicitDisconnect) {
                        scheduleReconnect(selectionId)
                    } else {
                        updateSnapshot { it.copy(idcProgress = IdcProgress.IDLE) }
                    }
                    old
                }
            }
        toClose?.shutdown()
    }

    private fun publishModules(binding: IdcBinding) {
        synchronized(lifecycleLock) {
            if (currentIdcBinding === binding && binding.isActive) {
                _modules.value =
                    binding.connection.modules.values
                        .toList()
            }
        }
    }

    private fun publishModuleEvent(
        binding: IdcBinding,
        moduleId: Int,
        name: String,
        online: Boolean,
    ) {
        val listeners = activeModuleListeners(binding) ?: return
        listeners.forEach { it(binding, name, moduleId, online) }
    }

    private fun activeModuleListeners(binding: IdcBinding): List<(IdcBinding, String, Int, Boolean) -> Unit>? =
        synchronized(lifecycleLock) {
            if (currentIdcBinding === binding && binding.isActive) moduleListeners.toList() else null
        }

    private fun publishPacket(
        binding: IdcBinding,
        packet: IdcPacket,
    ) {
        synchronized(lifecycleLock) {
            if (currentIdcBinding === binding && binding.isActive) {
                packetState.tryEmit(ConnectionPacket(binding, packet))
            }
        }
    }

    private fun publishVConnData(
        binding: IdcBinding,
        moduleId: Int,
        payload: ByteArray,
    ) {
        val listeners =
            synchronized(lifecycleLock) {
                if (currentIdcBinding === binding && binding.isActive) vConnListeners.toList() else null
            }
        listeners?.forEach { it(binding, moduleId, payload) }
    }

    fun disconnect() {
        val oldConnection =
            synchronized(lifecycleLock) {
                explicitDisconnect = true
                selectionCounter.incrementAndGet()
                generation.incrementAndGet()
                reconnectJob?.cancel()
                reconnectJob = null
                retries.set(0)
                current.binding?.lease?.revoke()
                val old = detachCurrentIdc()
                _modules.value = emptyList()
                publish(SelectionRecord(SelectionSnapshot(selectionId = selectionCounter.get()), null))
                old
            }
        oldConnection?.let(::closeInBackground)
    }

    fun lastSelection(): SavedSelection? = history.load()

    fun destroy() {
        disconnect()
        scope.coroutineContext[Job]?.cancel()
        closeExecutor.shutdown()
    }
}

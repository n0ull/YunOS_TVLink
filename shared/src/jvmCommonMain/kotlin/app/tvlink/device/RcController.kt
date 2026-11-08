package app.tvlink.device

import app.tvlink.proto.ib.IbChannel
import app.tvlink.proto.ib.IbConst
import app.tvlink.proto.ib.RcKey
import app.tvlink.proto.idc.OpCmdKey
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Routes remote input over IB, using the current IDC connection only for supported key fallbacks. */
enum class RemoteMode(
    internal val ibMode: Int,
) {
    KEYPAD(IbConst.CHANGETYPE_DEFAULT),
    TOUCHPAD(IbConst.CHANGETYPE_DEFAULT),
    JOYSTICK(IbConst.CHANGETYPE_JOYSTICK),
    WHEEL(IbConst.CHANGETYPE_DEFAULT),
    MOTION(IbConst.CHANGETYPE_MOTIONPAD),
}

data class RemoteState(
    val selectionId: Long = 0,
    val ibReady: Boolean = false,
    val mode: RemoteMode = RemoteMode.KEYPAD,
    val currentApp: String? = null,
)

/** Routes remote input over IB, using the current IDC connection only for supported key fallbacks. */
class RcController(
    private val selection: DeviceSelection,
    private val ibPort: Int = IbConst.PORT,
) {
    private companion object {
        const val RECONNECT_START_MS = 1_000L
        const val RECONNECT_INTERVAL_MS = 5_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var ib: IbChannel? = null
    private val remoteLock = Any()
    private var connectionJob: Job? = null
    private var bindingJob: Job? = null
    private var activeSelectionId = 0L
    private var initialConnectFailed = false
    private val channelCounter = AtomicLong(0)

    private val _state = MutableStateFlow(RemoteState())
    val state: StateFlow<RemoteState> = _state

    init {
        scope.launch {
            selection.binding.collect { binding ->
                val staleIb =
                    synchronized(remoteLock) {
                        bindingJob?.cancel()
                        bindingJob = null
                        val newSelectionId = binding?.lease?.selectionId ?: 0L
                        val targetChanged = activeSelectionId != newSelectionId
                        val previousIb = if (targetChanged) stopConnectionLoopLocked() else null
                        activeSelectionId = newSelectionId
                        if (targetChanged) {
                            initialConnectFailed = false
                            _state.value =
                                binding?.let { RemoteState(selectionId = it.lease.selectionId) } ?: RemoteState()
                        }
                        if (binding != null) {
                            val selectionId = binding.lease.selectionId
                            if (targetChanged) {
                                binding.lease.cancelSignal.invokeOnCompletion {
                                    scope.launch {
                                        val cancelledIb =
                                            synchronized(remoteLock) {
                                                if (activeSelectionId == selectionId) {
                                                    stopConnectionLoopLocked()
                                                } else {
                                                    null
                                                }
                                            }
                                        closeLater(cancelledIb)
                                    }
                                }
                            }
                            bindingJob =
                                scope.launch {
                                    binding.idc.collect { idc -> reconcile(binding, idc) }
                                }
                        }
                        previousIb
                    }
                closeLater(staleIb)
            }
        }
    }

    private fun reconcile(
        binding: SelectionBinding,
        idcBinding: IdcBinding?,
    ) {
        synchronized(remoteLock) {
            if (activeSelectionId != binding.lease.selectionId || !binding.lease.isActive) return
            if (connectionJob?.isActive == true || initialConnectFailed) return

            if (binding.mode == ConnectionMode.IB_ONLY || idcBinding != null) {
                connectionJob = scope.launch { connectLoop(binding) }
            }
        }
    }

    /** Cancels jobs and detaches IB while holding remoteLock; the caller closes the returned socket later. */
    private fun stopConnectionLoopLocked(): IbChannel? {
        connectionJob?.cancel()
        connectionJob = null
        val staleIb = ib
        ib = null
        _state.update { it.copy(ibReady = false) }
        return staleIb
    }

    private fun closeLater(chan: IbChannel?) {
        if (chan != null) scope.launch { chan.disconnect() }
    }

    private suspend fun connectLoop(binding: SelectionBinding) {
        val lease = binding.lease
        var connectedOnce = false
        while (currentCoroutineContext().isActive && lease.isActive) {
            val connected = runChannel(binding)
            connectedOnce = connectedOnce || connected
            val firstConnectFailed = !connected && !connectedOnce
            if (firstConnectFailed) {
                synchronized(remoteLock) {
                    if (activeSelectionId == lease.selectionId) initialConnectFailed = true
                }
            }
            if (!lease.isActive || firstConnectFailed) break
            delay(if (connected) RECONNECT_START_MS else RECONNECT_INTERVAL_MS)
        }
        synchronized(remoteLock) {
            _state.update { current ->
                if (current.selectionId == lease.selectionId) current.copy(ibReady = false) else current
            }
        }
    }

    private suspend fun runChannel(binding: SelectionBinding): Boolean {
        val lease = binding.lease
        val chan = IbChannel(binding.target.ip, ibPort)
        val channelLease = ChannelLease(channelCounter.incrementAndGet())
        chan.writerGuard = { lease.acquire(channelLease) }
        try {
            val died = CompletableDeferred<Unit>()
            wireChannel(chan, lease, died)
            if (!chan.connect() || !lease.isActive) return false

            val installed =
                synchronized(remoteLock) {
                    if (activeSelectionId == lease.selectionId && lease.isActive) {
                        ib = chan
                        _state.update { current ->
                            if (current.selectionId == lease.selectionId) {
                                current.copy(ibReady = true)
                            } else {
                                current
                            }
                        }
                        true
                    } else {
                        false
                    }
                }
            if (!installed) return false
            val mode = _state.value.mode
            if (mode.ibMode != IbConst.CHANGETYPE_DEFAULT) chan.changeType(mode.ibMode)
            died.await()
            return true
        } finally {
            synchronized(remoteLock) {
                if (ib === chan) ib = null
            }
            lease.revoke(channelLease)
            chan.disconnect()
        }
    }

    private fun wireChannel(
        chan: IbChannel,
        lease: TargetLease,
        died: CompletableDeferred<Unit>,
    ) {
        chan.onCurrentApp = { app ->
            if (ib === chan) updateRemoteState(lease) { it.copy(currentApp = app) }
        }
        chan.onStateChanged = { state ->
            if (ib === chan) {
                updateRemoteState(lease) { it.copy(ibReady = state == IbChannel.State.READY) }
            }
            if (state == IbChannel.State.DISCONNECTED) died.complete(Unit)
        }
    }

    private fun updateRemoteState(
        lease: TargetLease,
        transform: (RemoteState) -> RemoteState,
    ) {
        synchronized(remoteLock) {
            if (!lease.isActive || activeSelectionId != lease.selectionId) return
            _state.update { current ->
                if (current.selectionId == lease.selectionId) transform(current) else current
            }
        }
    }

    private fun ibFor(key: RcKey): IbChannel? {
        val chan = ib
        return chan?.takeIf { it.state == IbChannel.State.READY && (!key.needIb313 || it.serverVer >= 313) }
    }

    fun keyClick(key: RcKey) {
        val chan = ibFor(key)
        if (chan != null) {
            chan.keyClick(key)
        } else if (key.androidVal != 0) {
            selection.connection?.send(OpCmdKey(key.androidVal, 0))
        }
    }

    // IDC fallback sends click only on down; it has no IB-style long-press edge semantics.
    fun keyDown(key: RcKey) {
        val chan = ibFor(key)
        if (chan != null) {
            chan.keyEvent(key, true)
        } else if (key.androidVal != 0) {
            selection.connection?.send(OpCmdKey(key.androidVal, 0))
        }
    }

    fun keyUp(key: RcKey) {
        ibFor(key)?.keyEvent(key, false)
    }

    fun mouseMove(
        dx: Int,
        dy: Int,
    ) {
        ib?.takeIf { it.state == IbChannel.State.READY }?.mouseMove(dx, dy)
    }

    fun mouseClick() {
        ib?.takeIf { it.state == IbChannel.State.READY }?.mouseClick()
    }

    fun joystick(axes: List<Pair<Int, Int>>) {
        ib?.takeIf { it.state == IbChannel.State.READY }?.joystick(axes)
    }

    fun accel(
        x: Int,
        y: Int,
        z: Int,
    ) {
        ib?.takeIf { it.state == IbChannel.State.READY }?.accel(x, y, z)
    }

    fun gyro(
        x: Int,
        y: Int,
        z: Int,
    ) {
        ib?.takeIf { it.state == IbChannel.State.READY }?.gyro(x, y, z)
    }

    fun setMode(mode: RemoteMode) {
        _state.update { it.copy(mode = mode) }
        ib?.takeIf { it.state == IbChannel.State.READY }?.changeType(mode.ibMode)
    }

    fun destroy() {
        val staleIb =
            synchronized(remoteLock) {
                bindingJob?.cancel()
                bindingJob = null
                stopConnectionLoopLocked()
            }
        scope.coroutineContext[Job]?.cancel()
        staleIb?.disconnect()
    }
}

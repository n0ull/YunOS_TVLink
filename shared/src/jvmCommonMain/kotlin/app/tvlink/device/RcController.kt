package app.tvlink.device

import app.tvlink.proto.ib.IbChannel
import app.tvlink.proto.ib.RcKey
import app.tvlink.proto.idc.OpCmdKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Routes remote-control events to the IB fast channel when available,
 * falling back to IDC OpCmd_Key — same policy as the original IbRc.
 */
class RcController(private val deviceManager: DeviceManager) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ib: IbChannel? = null

    private val _ibReady = MutableStateFlow(false)
    val ibReady: StateFlow<Boolean> = _ibReady

    var onCurrentApp: ((String) -> Unit)? = null

    /** Call after IDC is ESTABLISHED. */
    fun attach() {
        val ip = deviceManager.connected.value?.ip ?: return
        scope.launch {
            val chan = IbChannel(ip)
            chan.onCurrentApp = { onCurrentApp?.invoke(it) }
            chan.onStateChanged = { s -> _ibReady.value = (s == IbChannel.State.READY) }
            if (chan.connect()) {
                ib = chan
            } else {
                _ibReady.value = false
            }
        }
    }

    fun detach() {
        ib?.disconnect()
        ib = null
        _ibReady.value = false
    }

    fun keyClick(key: RcKey) {
        val chan = ib
        if (chan != null && chan.state == IbChannel.State.READY && !key.needIb313) {
            chan.keyClick(key)
        } else if (key.androidVal != 0) {
            deviceManager.connection?.send(OpCmdKey(key.androidVal, 0))
        }
    }

    fun keyDown(key: RcKey) {
        val chan = ib
        if (chan != null && chan.state == IbChannel.State.READY && !key.needIb313) {
            chan.keyEvent(key, true)
        } else if (key.androidVal != 0) {
            deviceManager.connection?.send(OpCmdKey(key.androidVal, 1))
        }
    }

    fun keyUp(key: RcKey) {
        val chan = ib
        if (chan != null && chan.state == IbChannel.State.READY && !key.needIb313) {
            chan.keyEvent(key, false)
        } else if (key.androidVal != 0) {
            deviceManager.connection?.send(OpCmdKey(key.androidVal, 2))
        }
    }

    fun mouseMove(dx: Int, dy: Int) {
        ib?.takeIf { it.state == IbChannel.State.READY }?.mouseMove(dx, dy)
    }

    fun mouseClick() {
        ib?.takeIf { it.state == IbChannel.State.READY }?.mouseClick()
    }

    fun joystick(axes: List<Pair<Int, Int>>) {
        ib?.takeIf { it.state == IbChannel.State.READY }?.joystick(axes)
    }

    fun accel(x: Int, y: Int, z: Int) {
        ib?.takeIf { it.state == IbChannel.State.READY }?.accel(x, y, z)
    }

    fun gyro(x: Int, y: Int, z: Int) {
        ib?.takeIf { it.state == IbChannel.State.READY }?.gyro(x, y, z)
    }

    fun setMode(mode: Int) {
        ib?.takeIf { it.state == IbChannel.State.READY }?.changeType(mode)
    }
}

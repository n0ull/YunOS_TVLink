package app.tvlink.device

import app.tvlink.proto.ib.IbChannel
import app.tvlink.proto.ib.RcKey
import app.tvlink.proto.idc.OpCmdKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Routes remote-control events to the IB fast channel when available,
 * falling back to IDC OpCmd_Key — same policy as the original IbRc.
 */
class RcController(
    private val deviceManager: DeviceManager,
) {
    private companion object {
        /** 原 IbConn 重连策略（docs/re/02 §2.1）：断线 1s 后启动，之后每 5s；初始连接失败不自动重试。 */
        const val RECONNECT_START_MS = 1_000L
        const val RECONNECT_INTERVAL_MS = 5_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ib: IbChannel? = null
    private var attachJob: Job? = null

    private val _ibReady = MutableStateFlow(false)
    val ibReady: StateFlow<Boolean> = _ibReady

    /** TV 端当前前台应用（IB 通道推送，事件流）。 */
    private val _currentApp = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val currentApp: SharedFlow<String> = _currentApp

    /** Call after IDC is ESTABLISHED. 重复 attach 先终止上一轮重连循环。 */
    fun attach() {
        detach()
        val ip = deviceManager.connected.value?.ip ?: return
        attachJob =
            scope.launch {
                connectLoop(ip)
            }
    }

    /**
     * IB 通道保活循环（原 IbConn 断线重连策略，docs/re/02 §2.1）：建立成功则驻留至通道死亡，
     * 断线 1s 后起、每 5s 重试；初始连接失败不自动重试（与原 App 一致，避免对无 IB 的设备空转）。
     */
    private suspend fun connectLoop(ip: String) {
        var hadSuccess = false
        while (scope.isActive) {
            val chan = IbChannel(ip)
            try {
                val died = CompletableDeferred<Unit>()
                chan.onCurrentApp = { _currentApp.tryEmit(it) }
                chan.onStateChanged = { s ->
                    _ibReady.value = (s == IbChannel.State.READY)
                    if (s == IbChannel.State.DISCONNECTED) died.complete(Unit)
                }
                if (chan.connect()) {
                    hadSuccess = true
                    ib = chan
                    died.await() // 挂起至通道死亡；detach 取消时此处抛 CancellationException
                    ib = null
                    delay(RECONNECT_START_MS)
                } else if (!hadSuccess) {
                    break
                } else {
                    delay(RECONNECT_INTERVAL_MS)
                }
            } finally {
                chan.disconnect()
            }
        }
        _ibReady.value = false
    }

    fun detach() {
        attachJob?.cancel()
        attachJob = null
        ib?.disconnect()
        ib = null
        _ibReady.value = false
    }

    /** Release all resources. Call when the owning ViewModel is cleared. */
    fun destroy() {
        detach()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    /**
     * Mirror of the original IbRc routing: the IB channel when READY (keys flagged needIb313
     * additionally require server ver >= 313); callers fall back to IDC OpCmd_Key when this
     * returns null and an Android keycode exists.
     */
    private fun ibFor(key: RcKey): IbChannel? {
        val chan = ib
        return chan?.takeIf { it.state == IbChannel.State.READY && (!key.needIb313 || it.serverVer >= 313) }
    }

    fun keyClick(key: RcKey) {
        val chan = ibFor(key)
        if (chan != null) {
            chan.keyClick(key)
        } else if (key.androidVal != 0) {
            deviceManager.connection?.send(OpCmdKey(key.androidVal, 0))
        }
    }

    // 原 IbRc.java:66-73 语义:IB 走 down/up 双边沿;IDC 回退仅在 down 边沿发 click(op=0),
    // up 边沿不发(IDC 无长按语义)。曾误发 op=1/2,以反编译为准修正。
    fun keyDown(key: RcKey) {
        val chan = ibFor(key)
        if (chan != null) {
            chan.keyEvent(key, true)
        } else if (key.androidVal != 0) {
            deviceManager.connection?.send(OpCmdKey(key.androidVal, 0))
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

    fun setMode(mode: Int) {
        ib?.takeIf { it.state == IbChannel.State.READY }?.changeType(mode)
    }
}

package app.tvlink.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.tvlink.device.AsrTextService
import app.tvlink.device.DeviceSelection
import app.tvlink.device.IdcBinding
import app.tvlink.device.RcController
import app.tvlink.proto.ib.RcKey
import app.tvlink.proto.idc.IdcPacket
import app.tvlink.proto.idc.ImeAction
import app.tvlink.proto.idc.ImeFinishInput
import app.tvlink.proto.idc.ImeStartInput
import app.tvlink.proto.idc.ImeTextChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Remote key availability, IME state, current-app notices, and ASR text entry. */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteFeature(
    private val scope: CoroutineScope,
    private val selection: DeviceSelection,
    private val rc: RcController,
    private val asr: AsrTextService,
    private val showNotice: (String) -> Unit,
) {
    var imeActive by mutableStateOf(false)
        private set
    var imeText by mutableStateOf("")
        private set
    var imeHint by mutableStateOf("")
        private set

    private var imeBinding: IdcBinding? = null
    private val imeStateLock = Any()

    init {
        scope.launch(Dispatchers.Default) {
            rc.state
                .map { it.currentApp }
                .distinctUntilChanged()
                .collect { app -> if (app != null) showNotice("电视当前应用: $app") }
        }
        scope.launch(Dispatchers.Default) {
            selection.binding
                .flatMapLatest { binding -> binding?.idc ?: flowOf(null) }
                .collect { current -> reconcileImeBinding(current) }
        }
    }

    internal fun onPacket(
        binding: IdcBinding,
        packet: IdcPacket,
    ) {
        when (packet) {
            is ImeStartInput -> updateImeStart(binding, packet)
            is ImeFinishInput -> updateImeFinish(binding)
        }
    }

    private fun updateImeStart(
        binding: IdcBinding,
        packet: ImeStartInput,
    ) {
        if (!isCurrent(binding)) return
        scope.launch(Dispatchers.Default) {
            binding.targetLease.onlyIfActive {
                synchronized(imeStateLock) {
                    if (!isCurrent(binding)) return@synchronized
                    imeBinding = binding
                    imeText = packet.initText
                    imeHint = packet.hint
                    imeActive = true
                }
            }
        }
    }

    private fun updateImeFinish(binding: IdcBinding) {
        if (imeBinding !== binding || !isCurrent(binding)) return
        scope.launch(Dispatchers.Default) {
            binding.targetLease.onlyIfActive {
                synchronized(imeStateLock) {
                    if (imeBinding === binding && isCurrent(binding)) {
                        imeBinding = null
                        imeActive = false
                    }
                }
            }
        }
    }

    private fun reconcileImeBinding(observed: IdcBinding?) {
        val currentSelection = selection.binding.value
        if (currentSelection?.idc?.value !== observed) return
        if (currentSelection == null) {
            synchronized(imeStateLock) {
                val latestSelection = selection.binding.value
                val currentIme = imeBinding
                if (currentIme != null &&
                    currentIme.targetLease.selectionId == latestSelection?.lease?.selectionId
                ) {
                    return
                }
                imeBinding = null
                imeActive = false
            }
            return
        }
        currentSelection.lease.onlyIfActive {
            synchronized(imeStateLock) {
                val latest = selection.binding.value
                if (latest !== currentSelection || latest.idc.value !== observed) return@synchronized
                if (imeBinding !== observed) {
                    imeBinding = null
                    imeActive = false
                }
            }
        }
    }

    private fun isCurrent(binding: IdcBinding): Boolean =
        binding.isActive &&
            selection.binding.value
                ?.idc
                ?.value === binding

    private fun currentImeBinding(): IdcBinding? = synchronized(imeStateLock) { imeBinding?.takeIf { isCurrent(it) } }

    fun keyClick(key: RcKey) {
        val idcAvailable =
            selection.binding.value
                ?.idc
                ?.value != null
        if (!idcAvailable && !rc.state.value.ibReady) {
            showNotice("未连接电视，按键未发送")
            return
        }
        rc.keyClick(key)
    }

    fun imeCommit() {
        currentImeBinding()?.connection?.send(ImeAction(-1))
        synchronized(imeStateLock) {
            imeBinding = null
            imeActive = false
        }
    }

    fun imeChanged(text: String) {
        imeText = text
        currentImeBinding()?.connection?.send(ImeTextChange(text, text.length))
    }

    fun voiceText(text: String) = asr.sendText(text)
}

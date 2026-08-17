package app.tvlink.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.tvlink.device.AsrTextService
import app.tvlink.device.DeviceManager
import app.tvlink.device.RcController
import app.tvlink.proto.ib.RcKey
import app.tvlink.proto.idc.IdcPacket
import app.tvlink.proto.idc.ImeAction
import app.tvlink.proto.idc.ImeFinishInput
import app.tvlink.proto.idc.ImeStartInput
import app.tvlink.proto.idc.ImeTextChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 遥控功能状态持有者：IME 远程输入状态 + 按键/语音转发。 */
class RemoteFeature(
    private val scope: CoroutineScope,
    private val deviceManager: DeviceManager,
    private val rc: RcController,
    private val asr: AsrTextService,
    showNotice: (String) -> Unit,
) {
    var imeActive by mutableStateOf(false)
        private set
    var imeText by mutableStateOf("")
        private set
    var imeHint by mutableStateOf("")
        private set

    init {
        scope.launch(Dispatchers.Default) {
            rc.currentApp.collect { app -> showNotice("电视当前应用: $app") }
        }
    }

    /** DeviceManager.packets 路由入口：IME 开始/结束输入事件。 */
    fun onPacket(p: IdcPacket) {
        when (p) {
            is ImeStartInput ->
                scope.launch(Dispatchers.Default) {
                    imeText = p.initText
                    imeHint = p.hint
                    imeActive = true
                }

            is ImeFinishInput -> scope.launch(Dispatchers.Default) { imeActive = false }
        }
    }

    fun keyClick(k: RcKey) = rc.keyClick(k)

    fun imeCommit() {
        deviceManager.connection?.send(ImeAction(-1))
        imeActive = false
    }

    fun imeChanged(text: String) {
        imeText = text
        deviceManager.connection?.send(ImeTextChange(text, text.length))
    }

    fun voiceText(text: String) = asr.sendText(text)
}

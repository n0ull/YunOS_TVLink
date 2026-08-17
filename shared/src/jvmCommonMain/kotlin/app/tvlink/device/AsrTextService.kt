package app.tvlink.device

import app.tvlink.proto.idc.IdcConnection
import app.tvlink.proto.idc.jsonEscape
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Voice path without Alibaba ASR cloud: local STT produces text, we forward it to the TV
 * as a finished asr_streaming packet — the same path the original app uses for keyboard input
 * (ASR.sendText). NLU runs on the TV firmware. See docs/re/03 §C.
 */
class AsrTextService(
    private val deviceManager: DeviceManager,
) {
    companion object {
        /** 线上模块名带 `:etao` 类目后缀(AsrDef.ASR_MODULE_NAME;2026-07-22 真机广播证实)。 */
        const val MODULE_NAME = "com.yunos.tv.asr:etao"

        /** 会话三包间隔——按 2026-07-25 真机验证序列复刻;电视端 record 状态机需要逐包注册。 */
        private const val SESSION_GAP_MS = 150L

        /**
         * 键盘输入伪造包,逐字段对齐反编译(ASR.sendText + AsrPacket_out_asrStreaming):
         * asr_name 是模块全名(手机→电视方向;"ASR_COMMAND" 是电视→手机方向的取值);
         * result_code=0;finish 逐字节对齐原 App 的 String.valueOf(bool)。
         */
        internal fun streamingJson(text: String): String =
            """{"asr_name":"$MODULE_NAME","asr_data":{"pk_type":"asr_streaming","pk_content":""" +
                """{"result_code":0,"model":"TVLink","question":"${jsonEscape(text)}","finish":"true"}}}"""

        /** 无参控制包(record_start/record_stop)——pk_type 字面值见各 AsrPacket_out_* 类 super(...)。 */
        internal fun simpleJson(type: String): String =
            """{"asr_name":"$MODULE_NAME","asr_data":{"pk_type":"$type","pk_content":{}}}"""
    }

    /** 已发过 VConn SYN 的连接;原 App 在 acquireModule 构造时必发 SYN(IdcRemoteModule.sendSyn)。 */
    @Volatile
    private var synConn: IdcConnection? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Forward recognized text as a full record session: record_start → asr_streaming(finish) →
     * record_stop。2026-07-25 真机实证:只发 finish 包指令会执行但电视「聆听中」卡片卡死不收
     * (裸 record_stop 无前导 start 也无效);完整会话帧即开即收;ESC 键可手动兜底关卡片。
     * 原 App 真实会话亦此三段时序(ASR.java 状态机)。
     */
    fun sendText(text: String) {
        scope.launch {
            val mid = ensureModuleId() ?: return@launch
            deviceManager.sendVConnJson(mid, simpleJson("record_start"))
            delay(SESSION_GAP_MS)
            deviceManager.sendVConnJson(mid, streamingJson(text))
            delay(SESSION_GAP_MS)
            deviceManager.sendVConnJson(mid, simpleJson("record_stop"))
        }
    }

    /** Notify the TV a recording session started/stopped (UI feedback on TV side). */
    fun startRecord() = sendSimple("record_start")

    fun stopRecord() = sendSimple("record_stop")

    /** 取消内部 scope（AppViewModel.onCleared 链调用，与 RcController.destroy 同模式）。 */
    fun destroy() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private fun sendSimple(type: String) {
        val mid = ensureModuleId() ?: return
        deviceManager.sendVConnJson(mid, simpleJson(type))
    }

    /** module 在线则确保本连接已发 VConn SYN(每连接一次,同一发送线程保序),返回模块 ID。 */
    private fun ensureModuleId(): Int? {
        val conn = deviceManager.connection ?: return null
        val mid = deviceManager.moduleId(MODULE_NAME) ?: return null
        if (synConn !== conn) {
            conn.openVConn(mid)
            synConn = conn
        }
        return mid
    }
}

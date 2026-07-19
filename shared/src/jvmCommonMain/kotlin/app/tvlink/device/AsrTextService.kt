package app.tvlink.device

import app.tvlink.proto.idc.jsonEscape

/**
 * Voice path without Alibaba ASR cloud: local STT produces text, we forward it to the TV
 * as a finished asr_streaming packet — the same path the original app uses for keyboard input
 * (ASR.sendText). NLU runs on the TV firmware. See docs/re/03 §C.
 */
class AsrTextService(private val deviceManager: DeviceManager) {

    companion object {
        const val MODULE_NAME = "com.yunos.tv.asr"
    }

    /** Forward recognized text as a final asr_streaming result. */
    fun sendText(text: String) {
        val mid = deviceManager.moduleId(MODULE_NAME) ?: return
        val esc = jsonEscape(text)
        val json = """{"asr_name":"ASR_COMMAND","asr_data":{"pk_type":"asr_streaming","pk_content":{"result_code":1,"model":"TVLink","question":"$esc","finish":"1"}}}"""
        deviceManager.sendVConnJson(mid, json)
    }

    /** Notify the TV a recording session started/stopped (UI feedback on TV side). */
    fun startRecord() = sendSimple("start_record")
    fun stopRecord() = sendSimple("stop_record")

    private fun sendSimple(type: String) {
        val mid = deviceManager.moduleId(MODULE_NAME) ?: return
        deviceManager.sendVConnJson(mid, """{"asr_name":"ASR_COMMAND","asr_data":{"pk_type":"$type","pk_content":{}}}""")
    }
}

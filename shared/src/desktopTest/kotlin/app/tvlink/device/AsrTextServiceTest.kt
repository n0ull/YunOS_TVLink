package app.tvlink.device

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 语音指令协议钉桩(依据反编译 AsrDef / ASR.sendText / AsrPacket_out_asrStreaming,
 * 模块名经 2026-07-22 真机广播证实):裸 `com.yunos.tv.asr` 查不到模块会静默丢弃。
 */
class AsrTextServiceTest {
    @Test
    fun moduleNameIncludesEtaoCategory() {
        // AsrDef.ASR_MODULE_NAME;真机登录广播即此串
        assertEquals("com.yunos.tv.asr:etao", AsrTextService.MODULE_NAME)
    }

    @Test
    fun streamingJsonMatchesDecompiledKeyboardFake() {
        assertEquals(
            """{"asr_name":"com.yunos.tv.asr:etao","asr_data":{"pk_type":"asr_streaming",""" +
                """"pk_content":{"result_code":0,"model":"TVLink","question":"打开优酷","finish":"true"}}}""",
            AsrTextService.streamingJson("打开优酷"),
        )
    }

    @Test
    fun streamingJsonEscapesQuotes() {
        assertEquals(
            """{"asr_name":"com.yunos.tv.asr:etao","asr_data":{"pk_type":"asr_streaming",""" +
                """"pk_content":{"result_code":0,"model":"TVLink","question":"say \"hi\"","finish":"true"}}}""",
            AsrTextService.streamingJson("say \"hi\""),
        )
    }

    @Test
    fun simpleJsonMatchesDecompiledRecordType() {
        assertEquals(
            """{"asr_name":"com.yunos.tv.asr:etao","asr_data":{"pk_type":"record_start","pk_content":{}}}""",
            AsrTextService.simpleJson("record_start"),
        )
    }
}

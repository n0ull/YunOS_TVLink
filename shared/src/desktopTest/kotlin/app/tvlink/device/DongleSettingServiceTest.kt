package app.tvlink.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 魔投设置模块回归（docs/re/03 §B.3，反编译基准 ui/dongle/rcs/）：
 * 模块名钉桩、SysInfo 应答解析（分辨率下标=id）、非 SysInfo 消息丢弃。
 */
class DongleSettingServiceTest {
    private val service = DongleSettingService(DeviceManager())

    @Test
    fun moduleNameMatchesWireConstant() {
        // RcsPublic.java:17 SETTING_KEY_NAME
        assertEquals("com.ali.ott.dongle.setting", DongleSettingService.MODULE_NAME)
    }

    @Test
    fun sysInfoParsesFieldsAndResolutionList() {
        val json =
            """{"category":"immersive","messageType":"SysInfo","packageName":"com.ali.ott.dongle",""" +
                """"requestId":1,"ip":"10.0.0.5","mac":"AA:BB:CC:DD:EE:FF","sn":"SN01","uuid":"U01",""" +
                """"firmware":"3.0.1","current":"1080P 60Hz","resolution":["720P 60Hz","1080P 60Hz"]}"""
        var got: DongleSettingService.SysInfo? = null
        service.onSysInfo = { got = it }
        service.handle(json)
        val info = got ?: error("SysInfo callback not fired")
        assertEquals("3.0.1", info.firmware)
        assertEquals("10.0.0.5", info.ip)
        assertEquals("1080P 60Hz", info.current)
        assertEquals(listOf("720P 60Hz", "1080P 60Hz"), info.resolutions)
    }

    @Test
    fun nonSysInfoMessagesAreIgnored() {
        var got: DongleSettingService.SysInfo? = null
        service.onSysInfo = { got = it }
        service.handle("""{"category":"immersive","messageType":"netDiagnosis","requestId":2}""")
        assertNull(got)
    }
}

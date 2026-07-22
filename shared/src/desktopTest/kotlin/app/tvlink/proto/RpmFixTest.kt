package app.tvlink.proto

import app.tvlink.device.DeviceManager
import app.tvlink.device.RpmService
import app.tvlink.proto.idc.CmdLaunchSth
import app.tvlink.proto.idc.IdcConst
import app.tvlink.proto.idc.ModuleAvailability
import app.tvlink.proto.idc.getLPString
import app.tvlink.proto.idc.lpStringSize
import app.tvlink.proto.idc.putLPString
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RPM 修复回归(TODO.md P1 R1–R4,依据 docs/re/05 §3 与反编译代码):
 * R2 唤醒包帧格式、R3 ModuleAvailability 的 JSON m_name 分支、R4 apps 单对象兼容。
 * R1(模块名常量)由 [rpmModuleNameMatchesWireConstant] 钉住。
 */
class RpmFixTest {
    @Test
    fun rpmModuleNameMatchesWireConstant() {
        // IdcConstant.java:6 / RpmObserver.java:16 的线上模块名;旧值 com.yunos.tv.appstore 在 APK 内零字面量
        assertEquals("com.yunos.idc.appstore", RpmService.MODULE_NAME)
    }

    @Test
    fun cmdLaunchSthWireFormat() {
        val frame = CmdLaunchSth(launchType = 1, action = "yunos.appstore.startprocessservice").encode()
        assertEquals(IdcConst.MAGIC, frame.int)
        frame.int // key
        assertEquals(IdcConst.ID_CMD_LAUNCH_STH, frame.int)
        val total = frame.int
        assertEquals(total, IdcConst.HEADER_LEN + frame.remaining())
        val json = frame.getLPString()
        // body 为单段 LPString(CmdReqBase 家族的双 LPString 格式不适用于 LaunchSth)
        assertEquals(0, frame.remaining())
        assertTrue(json.contains("\"launch_type\":1")) // service ordinal
        assertTrue(json.contains("\"action\":\"yunos.appstore.startprocessservice\""))
        assertTrue(json.contains("\"extra_str\":\"\""))
    }

    @Test
    fun moduleAvailabilityJsonNameFallback() {
        // m_name 可能是字符串化的 {"name","category"} JSON(IDC.java:360-368 双分支解析)
        val json =
            """{"m_name":"{\"name\":\"com.yunos.idc.appstore\",\"category\":\"rpm\"}",""" +
                """"m_ver":1,"m_extprop":"","m_id":9,"isOnline":true}"""
        val body = ByteBuffer.allocate(lpStringSize(json))
        body.putLPString(json)
        val m = ModuleAvailability()
        m.decodeBody(ByteBuffer.wrap(body.array()))
        assertEquals("com.yunos.idc.appstore", m.moduleName)
        assertEquals(9, m.moduleId)
        assertTrue(m.online)
    }

    @Test
    fun moduleAvailabilityMalformedJsonNameFallsBackToRaw() {
        // IDC.java 语义:JSON 解析失败 → 回退原串(而非丢弃)
        val json = """{"m_name":"{not-valid-json","m_ver":1,"m_extprop":"","m_id":3,"isOnline":true}"""
        val body = ByteBuffer.allocate(lpStringSize(json))
        body.putLPString(json)
        val m = ModuleAvailability()
        m.decodeBody(ByteBuffer.wrap(body.array()))
        assertEquals("{not-valid-json", m.moduleName)
    }

    @Test
    fun parseAppArrayAcceptsArrayAndSingleObject() {
        // IdcPacket_GetListResponse.java:62-65:apps 单应用场景为 JSON 对象而非数组(accumulate 行为)
        val rpm = RpmService(DeviceManager())
        val a = """{"packageName":"com.a","appName":"A","versionName":"1.0","versionCode":"1","appStatus":"0"}"""
        val b = """{"packageName":"com.b","appName":"B","versionName":"2.0","versionCode":"2","appStatus":"0"}"""

        val many = rpm.parseAppArray("[$a,$b]")
        assertEquals(listOf("com.a", "com.b"), many.map { it.packageName })

        val single = rpm.parseAppArray(a)
        assertEquals(1, single.size)
        assertEquals("com.a", single[0].packageName)
        assertEquals("A", single[0].appName)

        assertTrue(rpm.parseAppArray("[]").isEmpty())
    }
}

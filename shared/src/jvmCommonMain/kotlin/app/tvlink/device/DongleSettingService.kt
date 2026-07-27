package app.tvlink.device

import app.tvlink.proto.idc.FlatJson
import app.tvlink.proto.idc.parseJsonObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * 魔投（dongle）设置 —— IDC VConn 模块 "com.ali.ott.dongle.setting"（docs/re/03 §B.3，
 * 反编译基准 ui/dongle/rcs/）。信封为纯 JSON：{"category":"immersive","messageType":T,
 * "requestId":N[,参数]}（BaseRcsPacket.java），区别于 RPM 的 int32 二进制头。
 * 原 App 唯一响应包类型为 "SysInfo"（RcsPacketFactory.createRecvPacket），其余请求
 * fire-and-forget。模块上线即取一次系统信息（原 RcsSetting.onModuleOnline 行为）。
 */
class DongleSettingService(
    private val deviceManager: DeviceManager,
) {
    companion object {
        /** RcsPublic.java:17 SETTING_KEY_NAME —— 模块线上名。 */
        const val MODULE_NAME = "com.ali.ott.dongle.setting"

        private const val CATEGORY = "immersive"

        /** 原 RcsPacket_getSysInfoResult：请求 "getSysInfo"，应答 messageType "SysInfo"。 */
        const val MSG_SYSINFO_RESP = "SysInfo"
    }

    /** resolutions 的数组下标即分辨率 id（原 Resolution.setResolution(i) —— 反编译基准）。 */
    data class SysInfo(
        val ip: String = "",
        val mac: String = "",
        val sn: String = "",
        val uuid: String = "",
        val firmware: String = "",
        val current: String = "",
        val resolutions: List<String> = emptyList(),
    )

    var onSysInfo: ((SysInfo) -> Unit)? = null
    var onModuleState: ((online: Boolean) -> Unit)? = null

    private val requestId = AtomicInteger(1)
    private var moduleId: Int? = null
    private var vconnOpen = false

    val available: Boolean
        get() = moduleId != null && vconnOpen

    private val moduleListener: (String, Int, Boolean) -> Unit = { name, mid, online ->
        if (name == MODULE_NAME) onModule(online, mid)
    }

    private val vConnListener: (Int, ByteArray) -> Unit = { mid, payload ->
        if (moduleId == mid) handle(String(payload, Charsets.UTF_8))
    }

    fun attach() {
        deviceManager.removeVConnListener(vConnListener)
        deviceManager.addVConnListener(vConnListener)
        deviceManager.removeModuleListener(moduleListener)
        deviceManager.addModuleListener(moduleListener)
    }

    fun detach() {
        deviceManager.removeVConnListener(vConnListener)
        deviceManager.removeModuleListener(moduleListener)
        resetModuleState()
    }

    private fun onModule(
        online: Boolean,
        mid: Int,
    ) {
        if (online) {
            moduleId = mid
            if (!vconnOpen) {
                vconnOpen = true
                deviceManager.connection?.openVConn(mid)
            }
            getSysInfo()
        } else {
            resetModuleState()
        }
        onModuleState?.invoke(online)
    }

    private fun resetModuleState() {
        moduleId = null
        vconnOpen = false
    }

    fun getSysInfo(): Boolean = send("getSysInfo", "")

    fun adjustResolution(index: Int): Boolean = send("adjustResolution", ""","resolution":$index""")

    /** largeType 语义不明且原 App UI 无对应入口（仅 ISetting 接口面），保留协议面不接 UI。 */
    fun adjustSize(largeType: Int): Boolean = send("adjustSize", ""","largeType":$largeType""")

    fun reboot(): Boolean = send("reboot", "")

    fun factoryReset(): Boolean = send("factoryReset", "")

    fun netDiagnosis(): Boolean = send("netDiagnosis", "")

    fun reconnect(): Boolean = send("reconnect", "")

    /** module 在线即发（VConn 未开时先补 VConnSyn）；离线返回 false。 */
    private fun send(
        messageType: String,
        extra: String,
    ): Boolean {
        val mid = moduleId ?: return false
        if (!vconnOpen) {
            vconnOpen = true
            deviceManager.connection?.openVConn(mid)
        }
        val json =
            """{"category":"$CATEGORY","messageType":"$messageType",""" +
                """"requestId":${requestId.getAndIncrement()}$extra}"""
        deviceManager.sendVConnJson(mid, json)
        return true
    }

    // internal（非 private）以便 desktopTest 直接回归解析分支；属测试可见性放宽，非公共 API。
    internal fun handle(json: String) {
        val j = parseJsonObject(json)
        if (j.str("messageType") != MSG_SYSINFO_RESP) return
        onSysInfo?.invoke(parseSysInfo(j))
    }

    internal fun parseSysInfo(j: FlatJson): SysInfo =
        SysInfo(
            ip = j.str("ip"),
            mac = j.str("mac"),
            sn = j.str("sn"),
            uuid = j.str("uuid"),
            firmware = j.str("firmware"),
            current = j.str("current"),
            resolutions = j.strArray("resolution"),
        )
}

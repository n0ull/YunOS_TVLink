package app.tvlink.proto.idc

import java.nio.ByteBuffer
import java.nio.charset.Charset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** IDC wire constants — see docs/re/01-device-discovery.md */
object IdcConst {
    const val TCP_PORT = 13510
    const val TCP_PORT_DETECT = 13511
    const val MAGIC = 130311
    const val UNASSIGNED_KEY = -130324
    const val HEADER_LEN = 16

    const val ID_LOGIN_REQ = 10000
    const val ID_LOGIN_ENCRYPTION_RESP = 10090
    const val ID_LOGIN_RESP = 10100
    const val ID_HEARTBEAT = 10200
    const val ID_OPCMD_MOUSE_CLICK = 10400
    const val ID_OPCMD_KEY = 10500
    const val ID_IME_START_INPUT = 10600
    const val ID_IME_FINISH_INPUT = 10700
    const val ID_IME_TEXT_CHANGE = 10800
    const val ID_IME_ACTION = 10900
    const val ID_DEVINFO_UPDATE_NAME = 11000
    const val ID_DEVINFO_UPDATE_DDH = 11100
    const val ID_OPCMD_MULTITOUCH = 11200
    const val ID_MODULE_AVAILABILITY = 20000
    const val ID_VCONN_DATA = 20100
    const val ID_VCONN_SYN = 20200
    const val ID_VCONN_FIN = 20300
    const val ID_CMD_LAUNCH_STH = 20400
    const val ID_CMD_PACKAGE_INFO_REQ = 20500
    const val ID_CMD_PACKAGE_INFO_RESP = 20600
    const val ID_CMD_PATH_INFO_REQ = 20700
    const val ID_CMD_PATH_INFO_RESP = 20800
    const val ID_CMD_SCREENSHOT_REQ = 20900
    const val ID_CMD_SCREENSHOT_RESP = 21000
    const val ID_CMD_SYSPROP_REQ = 21100
    const val ID_CMD_SYSPROP_RESP = 21200
}

private val UTF8: Charset = Charsets.UTF_8

// ---- length-prefixed primitives (ByteBufPacketUtil-compatible) ----

fun ByteBuffer.putLPString(s: String?) {
    val b = s?.toByteArray(UTF8) ?: ByteArray(0)
    putInt(b.size)
    if (b.isNotEmpty()) put(b)
}

fun ByteBuffer.getLPString(): String {
    val n = int
    if (n <= 0) return ""
    require(remaining() >= n) { "LPString len $n exceeds remaining ${remaining()}" }
    val b = ByteArray(n)
    get(b)
    return String(b, UTF8)
}

fun ByteBuffer.putLPBytes(b: ByteArray?) {
    if (b == null || b.isEmpty()) {
        putInt(0)
    } else {
        putInt(b.size)
        put(b)
    }
}

fun ByteBuffer.getLPBytes(): ByteArray {
    val n = int
    if (n <= 0) return ByteArray(0)
    require(remaining() >= n) { "LPBytes len $n exceeds remaining ${remaining()}" }
    val b = ByteArray(n)
    get(b)
    return b
}

fun lpStringSize(s: String?) = 4 + (s?.toByteArray(UTF8)?.size ?: 0)

fun lpBytesSize(b: ByteArray?) = 4 + (b?.size ?: 0)

// ---- packets ----

abstract class IdcPacket(
    val packetId: Int,
) {
    var key: Int = IdcConst.UNASSIGNED_KEY

    protected abstract fun encodeBody(): ByteArray

    open fun decodeBody(buf: ByteBuffer) {}

    // ponytail: 加密路径不可达(真机全 ver=0 明文),移除 AES 分支。
    fun encode(): ByteBuffer {
        val body = encodeBody()
        val payload: ByteArray = body
        val total = IdcConst.HEADER_LEN + payload.size
        val out = ByteBuffer.allocate(total)
        out.putInt(IdcConst.MAGIC)
        out.putInt(key)
        out.putInt(packetId)
        out.putInt(total)
        out.put(payload)
        out.flip()
        return out
    }

    companion object {
        /** Parse one frame from [buf] positioned at frame start. Returns null on unknown id. */
        fun decode(frame: ByteBuffer): IdcPacket? {
            val magic = frame.int
            if (magic != IdcConst.MAGIC) return null
            val key = frame.int
            val id = frame.int
            val total = frame.int
            if (total < IdcConst.HEADER_LEN || total > frame.capacity()) return null
            val body = frame.slice()
            val p = create(id) ?: RawIdcPacket(id)
            p.key = key
            p.decodeBody(body)
            return p
        }

        fun create(id: Int): IdcPacket? =
            sessionPacket(id) ?: modulePacket(id) ?: inputPacket(id) ?: commandPacket(id) ?: miscPacket(id)

        private fun sessionPacket(id: Int): IdcPacket? =
            when (id) {
                IdcConst.ID_LOGIN_REQ -> LoginReq()
                IdcConst.ID_LOGIN_ENCRYPTION_RESP -> LoginEncryptionResp()
                IdcConst.ID_LOGIN_RESP -> LoginResp()
                IdcConst.ID_HEARTBEAT -> HeartBeat()
                IdcConst.ID_DEVINFO_UPDATE_NAME -> DevNameUpdate()
                IdcConst.ID_DEVINFO_UPDATE_DDH -> DevInfoUpdateDdhParam()
                else -> null
            }

        private fun modulePacket(id: Int): IdcPacket? =
            when (id) {
                IdcConst.ID_MODULE_AVAILABILITY -> ModuleAvailability()
                IdcConst.ID_VCONN_SYN -> VConnSyn()
                IdcConst.ID_VCONN_DATA -> VConnData()
                IdcConst.ID_VCONN_FIN -> VConnFin()
                else -> null
            }

        private fun inputPacket(id: Int): IdcPacket? =
            when (id) {
                IdcConst.ID_OPCMD_KEY -> OpCmdKey()
                IdcConst.ID_OPCMD_MOUSE_CLICK -> OpCmdMouseClick()
                IdcConst.ID_OPCMD_MULTITOUCH -> OpCmdMultitouch()
                IdcConst.ID_IME_START_INPUT -> ImeStartInput()
                IdcConst.ID_IME_FINISH_INPUT -> ImeFinishInput()
                IdcConst.ID_IME_TEXT_CHANGE -> ImeTextChange()
                IdcConst.ID_IME_ACTION -> ImeAction()
                else -> null
            }

        private fun commandPacket(id: Int): IdcPacket? =
            when (id) {
                IdcConst.ID_CMD_PACKAGE_INFO_REQ -> CmdPackageInfoReq()
                IdcConst.ID_CMD_PACKAGE_INFO_RESP -> CmdPackageInfoResp()
                IdcConst.ID_CMD_PATH_INFO_REQ -> CmdPathInfoReq()
                IdcConst.ID_CMD_PATH_INFO_RESP -> CmdPathInfoResp()
                IdcConst.ID_CMD_SCREENSHOT_REQ -> ScreenShotReq()
                IdcConst.ID_CMD_SCREENSHOT_RESP -> ScreenShotResp()
                IdcConst.ID_CMD_SYSPROP_REQ -> SysPropReq()
                IdcConst.ID_CMD_SYSPROP_RESP -> SysPropResp()
                else -> null
            }

        private fun miscPacket(id: Int): IdcPacket? = if (id == IdcConst.ID_CMD_LAUNCH_STH) CmdLaunchSth() else null
    }
}

class RawIdcPacket(
    id: Int,
    var payload: ByteArray = ByteArray(0),
) : IdcPacket(id) {
    override fun encodeBody() = payload

    override fun decodeBody(buf: ByteBuffer) {
        payload = ByteArray(buf.remaining())
        buf.get(payload)
    }
}

enum class IdcLoginType { UNKNOWN, NORMAL, DETECT, QRCODE }

@Suppress("LongParameterList") // 协议字段一一对应，拆包需动全线调用点
class LoginReq(
    var name: String = "app.tvlink",
    var appVerCode: Int = 1,
    var clientType: String = "Android",
    var devName: String = "TVLink",
    var loginType: IdcLoginType = IdcLoginType.NORMAL,
    var loginMagicNumber: Int = 0,
    var encryptionAlgorithmVer: Int = 0,
    var encryptionAlgorithmDetail: String = "",
) : IdcPacket(IdcConst.ID_LOGIN_REQ) {
    private fun json() =
        """{"name":"${
            jsonEscape(
                name,
            )
        }","app_ver_code":$appVerCode,"client_type":"${
            jsonEscape(
                clientType,
            )
        }","dev_name":"${
            jsonEscape(
                devName,
            )
        }","login_type":${loginType.ordinal},"login_magic_number":$loginMagicNumber,""" +
            """"encryption_algorithm_ver":$encryptionAlgorithmVer,""" +
            """"encryption_algorithm_detail":"${
                jsonEscape(
                    encryptionAlgorithmDetail,
                )
            }"}"""

    override fun encodeBody(): ByteArray {
        val b = ByteBuffer.allocate(lpStringSize(json()))
        b.putLPString(json())
        return b.array()
    }
}

class LoginEncryptionResp(
    // IdcPacket_LoginEncryptionResp: JSON {"encryption_algorithm_ver":N,"encryption_algorithm_detail":"…"}。
    // 该路径当前不可达(协商 ver=0 明文),保留字段以备 ver≠0 会话。
    var encryptionAlgorithmVer: Int = 0,
    var encryptionAlgorithmDetail: String = "",
) : IdcPacket(IdcConst.ID_LOGIN_ENCRYPTION_RESP) {
    override fun encodeBody() = ByteArray(0)

    override fun decodeBody(buf: ByteBuffer) {
        if (!buf.hasRemaining()) return
        val j = parseJsonObject(buf.getLPString())
        encryptionAlgorithmVer = j.int("encryption_algorithm_ver")
        encryptionAlgorithmDetail = j.str("encryption_algorithm_detail")
    }
}

@Suppress("LongParameterList") // 协议字段一一对应，拆包需动全线调用点
class LoginResp(
    var ver: Int = 0,
    var connKey: Int = 0,
    var udpPort: Int = 0,
    var devName: String = "",
    var devModel: String = "",
    var devUuid: String = "",
    var devOs: String = "",
    var devOsVer: String = "",
    var ddhParams: MutableMap<String, ByteArray> = mutableMapOf(),
) : IdcPacket(IdcConst.ID_LOGIN_RESP) {
    override fun encodeBody() = ByteArray(0)

    override fun decodeBody(buf: ByteBuffer) {
        ver = buf.int
        connKey = buf.int
        udpPort = buf.int
        if (!buf.hasRemaining()) return
        val j = parseJsonObject(buf.getLPString())
        devName = j.str("dev_name")
        devModel = j.str("dev_model")
        devUuid = j.str("dev_uuid")
        devOs = j.str("dev_os")
        devOsVer = j.str("dev_os_ver")
        for (k in j.strArray("dev_ddhparamkeys")) {
            ddhParams[k] = buf.getLPBytes()
        }
    }
}

class HeartBeat(
    var seq: Int = 0,
) : IdcPacket(IdcConst.ID_HEARTBEAT) {
    override fun encodeBody(): ByteArray {
        val b = ByteBuffer.allocate(4)
        b.putInt(seq)
        return b.array()
    }

    override fun decodeBody(buf: ByteBuffer) {
        seq = buf.int
    }
}

class ModuleAvailability(
    var moduleName: String = "",
    var moduleVer: Int = 0,
    var moduleExtProp: String = "",
    var moduleId: Int = 0,
    var online: Boolean = false,
) : IdcPacket(IdcConst.ID_MODULE_AVAILABILITY) {
    override fun encodeBody() = ByteArray(0)

    override fun decodeBody(buf: ByteBuffer) {
        val j = parseJsonObject(buf.getLPString())
        // m_name 可能是裸模块名,也可能是字符串化的 {"name":…,"category":…} JSON——原 App 双分支解析:
        // 尝试按 JSON 取 name,失败则回退原串(IDC.java:360-368 parseModuleKeyFromFullName)
        val rawName = j.str("m_name")
        val jsonName = if (rawName.startsWith("{")) parseJsonObject(rawName).str("name") else ""
        moduleName = jsonName.ifEmpty { rawName }
        moduleVer = j.int("m_ver")
        moduleExtProp = j.str("m_extprop")
        moduleId = j.int("m_id")
        online = j.bool("isOnline")
    }
}

class VConnSyn(
    var moduleId: Int = 0,
) : IdcPacket(IdcConst.ID_VCONN_SYN) {
    override fun encodeBody(): ByteArray {
        val s = """{"mid":$moduleId}"""
        val b = ByteBuffer.allocate(lpStringSize(s))
        b.putLPString(s)
        return b.array()
    }

    override fun decodeBody(buf: ByteBuffer) {
        moduleId = parseJsonObject(buf.getLPString()).int("mid")
    }
}

class VConnData(
    var moduleId: Int = 0,
    var payload: ByteArray = ByteArray(0),
) : IdcPacket(IdcConst.ID_VCONN_DATA) {
    override fun encodeBody(): ByteArray {
        val s = """{"mid":$moduleId}"""
        val b = ByteBuffer.allocate(lpStringSize(s) + payload.size)
        b.putLPString(s)
        b.put(payload)
        return b.array()
    }

    override fun decodeBody(buf: ByteBuffer) {
        moduleId = parseJsonObject(buf.getLPString()).int("mid")
        payload = ByteArray(buf.remaining())
        buf.get(payload)
    }
}

class VConnFin(
    var moduleId: Int = 0,
) : IdcPacket(IdcConst.ID_VCONN_FIN) {
    override fun encodeBody(): ByteArray {
        val s = """{"mid":$moduleId}"""
        val b = ByteBuffer.allocate(lpStringSize(s))
        b.putLPString(s)
        return b.array()
    }

    override fun decodeBody(buf: ByteBuffer) {
        moduleId = parseJsonObject(buf.getLPString()).int("mid")
    }
}

/** keyOp: 0=click, 1=down, 2=up */
class OpCmdKey(
    var keyCode: Int = 0,
    var keyOp: Int = 0,
) : IdcPacket(IdcConst.ID_OPCMD_KEY) {
    override fun encodeBody(): ByteArray {
        val b = ByteBuffer.allocate(8)
        b.putInt(keyCode)
        b.putInt(keyOp)
        return b.array()
    }
}

class ImeStartInput(
    var hint: String = "",
    var inputType: Int = 0,
    var initText: String = "",
    /** EditorInfo.imeOptions(EditorInfo 位掩码)。原 App 用 IME 动作按钮渲染,本项目暂未消费。 */
    var options: Int = 0,
    /** EditorInfo.actionId(IME 动作按钮 ID)。暂未消费。 */
    var actionId: Int = -1,
    /** EditorInfo.actionLabel(IME 动作按钮文案)。暂未消费。 */
    var actionLabel: String = "",
) : IdcPacket(IdcConst.ID_IME_START_INPUT) {
    override fun encodeBody() = ByteArray(0)

    // IdcRawPacket_Ime_StartInput: int inputType | int options | int actionId |
    // LPString actionLabel | LPString hintText | LPString existedText
    override fun decodeBody(buf: ByteBuffer) {
        inputType = buf.int
        options = buf.int
        actionId = buf.int
        actionLabel = buf.getLPString()
        hint = buf.getLPString()
        initText = buf.getLPString()
    }
}

class ImeFinishInput : IdcPacket(IdcConst.ID_IME_FINISH_INPUT) {
    override fun encodeBody() = ByteArray(0)
}

class ImeTextChange(
    var text: String = "",
    var cursorPos: Int = 0,
) : IdcPacket(IdcConst.ID_IME_TEXT_CHANGE) {
    override fun encodeBody(): ByteArray {
        val b = ByteBuffer.allocate(lpStringSize(text) + 4)
        b.putLPString(text)
        b.putInt(cursorPos)
        return b.array()
    }
}

class ImeAction(
    var actionId: Int = -1,
) : IdcPacket(IdcConst.ID_IME_ACTION) {
    override fun encodeBody(): ByteArray {
        val b = ByteBuffer.allocate(4)
        b.putInt(actionId)
        return b.array()
    }
}

/**
 * Cmd_LaunchSth(20400) — 唤醒电视端模块/服务。launchType 取枚举 ordinal:
 * 0=activity, 1=service, 2=activity_new(IdcPacket_Cmd_LaunchSth.java:17-21)。
 * body 为单段 LPString JSON(CmdReqBase 家族的双 LPString 格式不适用,LaunchSth 是例外)。
 * 真机实证(2026-07-25,launch_probe):extra_str 被 TV 侧当 **intent data URI**(Uri.parse)
 * 而非包名/组件——lt=2 + VIEW/MAIN + URI 弹「以什么应用打开」选择框;
 * ACTION_DIAGNOSTIC 在 M638_ALI 固件无对应 activity(空操作)。
 */
class CmdLaunchSth(
    var launchType: Int = 1,
    var action: String = "",
    var extraStr: String = "",
) : IdcPacket(IdcConst.ID_CMD_LAUNCH_STH) {
    override fun encodeBody(): ByteArray {
        val s =
            """{"launch_type":$launchType,"action":"${jsonEscape(action)}","extra_str":"${jsonEscape(extraStr)}"}"""
        val b = ByteBuffer.allocate(lpStringSize(s))
        b.putLPString(s)
        return b.array()
    }
}

// ---- 反编译存在但本项目未消费的命令(保留结构,无调用方) ----

/** OpCmd_MouseClick(10400) — 已废弃(IdcRawPacket_OpCmd_MouseClick.java:param_length=0,"deprecated")。 */
class OpCmdMouseClick : IdcPacket(IdcConst.ID_OPCMD_MOUSE_CLICK) {
    override fun encodeBody() = ByteArray(0)
}

/**
 * OpCmd_Multitouch(11200) — 多点触控(IdcRawPacket_OpCmd_Multitouch.java)。
 * body = LPString(JSON {"evts":[{x_scale,y_scale,id,act}]}),act∈[0,2]。本项目无 UI 调用方(原 App 同)。
 */
class OpCmdMultitouch(
    var touchEvts: List<TouchEvt> = emptyList(),
) : IdcPacket(IdcConst.ID_OPCMD_MULTITOUCH) {
    data class TouchEvt(
        val xScale: Int = 0,
        val yScale: Int = 0,
        val id: Int = 0,
        val act: Int = 0,
    )

    override fun encodeBody(): ByteArray {
        val evts =
            touchEvts.joinToString(",", "[", "]") {
                """{"x_scale":${it.xScale},"y_scale":${it.yScale},"id":${it.id},"act":${it.act}}"""
            }
        val s = """{"evts":$evts}"""
        val b = ByteBuffer.allocate(lpStringSize(s))
        b.putLPString(s)
        return b.array()
    }

    override fun decodeBody(buf: ByteBuffer) {
        // 结构保留;完整解析待消费时补充。
    }
}

/**
 * Cmd_PackageInfo(20500/20600) — 查询包信息(IdcPacket_Cmd_PackageInfo_Req.java,CmdReqBase 家族双 LPString)。
 * 阳性对照有效(2026-07-22,com.youku.taitan.tv→existed=True)。本项目 UI 未暴露。
 */
class CmdPackageInfoReq(
    var packageName: String = "",
    var cmdReqId: Int = 1,
) : IdcPacket(IdcConst.ID_CMD_PACKAGE_INFO_REQ) {
    override fun encodeBody(): ByteArray {
        val req = """{"cmdReqID":$cmdReqId}"""
        val s = """{"pkg":"${jsonEscape(packageName)}"}"""
        val b = ByteBuffer.allocate(lpStringSize(req) + lpStringSize(s))
        b.putLPString(req)
        b.putLPString(s)
        return b.array()
    }
}

class CmdPackageInfoResp(
    var packageName: String = "",
    // IdcPacket_GetAppInfoResponse 用 "appIsExist"(KEY_APPISEXIST),非 "existed"。
    var appIsExist: Boolean = false,
    var cmdReqId: Int = 0,
) : IdcPacket(IdcConst.ID_CMD_PACKAGE_INFO_RESP) {
    override fun encodeBody() = ByteArray(0)

    override fun decodeBody(buf: ByteBuffer) {
        val req = parseJsonObject(buf.getLPString())
        cmdReqId = req.int("cmdReqID")
        val j = parseJsonObject(buf.getLPString())
        packageName = j.str("packageName")
        appIsExist = j.bool("appIsExist")
    }
}

/**
 * Cmd_PathInfo(20700/20800) — 查询路径信息(IdcPacket_Cmd_PathInfo_Req.java,CmdReqBase 家族双 LPString)。
 * 本项目 UI 未暴露。
 */
class CmdPathInfoReq(
    var path: String = "",
    var cmdReqId: Int = 1,
) : IdcPacket(IdcConst.ID_CMD_PATH_INFO_REQ) {
    override fun encodeBody(): ByteArray {
        val req = """{"cmdReqID":$cmdReqId}"""
        val s = """{"path":"${jsonEscape(path)}"}"""
        val b = ByteBuffer.allocate(lpStringSize(req) + lpStringSize(s))
        b.putLPString(req)
        b.putLPString(s)
        return b.array()
    }
}

class CmdPathInfoResp(
    var path: String = "",
    var cmdReqId: Int = 0,
) : IdcPacket(IdcConst.ID_CMD_PATH_INFO_RESP) {
    override fun encodeBody() = ByteArray(0)

    override fun decodeBody(buf: ByteBuffer) {
        val req = parseJsonObject(buf.getLPString())
        cmdReqId = req.int("cmdReqID")
        val j = parseJsonObject(buf.getLPString())
        path = j.str("path")
    }
}

class ScreenShotReq(
    var resizeRatio: Int = 0,
    var resizeW: Int = 1280,
    var resizeH: Int = 720,
    var compressQuality: Int = 90,
    var cmdReqId: Int = 1,
) : IdcPacket(IdcConst.ID_CMD_SCREENSHOT_REQ) {
    // IdcPacket_CmdReqBase.param_encode: body = LPString({"cmdReqID":N}) + LPString({params})
    override fun encodeBody(): ByteArray {
        val req = """{"cmdReqID":$cmdReqId}"""
        val s =
            """{"resize_ratio":$resizeRatio,"resize_w":$resizeW,"resize_h":$resizeH,""" +
                """"compress_quality":$compressQuality}"""
        val b = ByteBuffer.allocate(lpStringSize(req) + lpStringSize(s))
        b.putLPString(req)
        b.putLPString(s)
        return b.array()
    }
}

class ScreenShotResp(
    var imgData: ByteArray = ByteArray(0),
) : IdcPacket(IdcConst.ID_CMD_SCREENSHOT_RESP) {
    override fun encodeBody() = ByteArray(0)

    // IdcPacket_CmdRespBase + ScreenShot_Resp.param_decode: LPString({"cmdReqID":N}) + LPString({"dummy":0}) + LPBytes(jpeg)
    override fun decodeBody(buf: ByteBuffer) {
        buf.getLPString() // {"cmdReqID":N}
        buf.getLPString() // {"dummy":0}
        imgData = buf.getLPBytes()
    }
}

/**
 * Cmd_SysProp(21100/21200) — 读写 TV 系统属性(IdcPacket_Cmd_SysProp_Req/Resp.java)。
 * is_get_prop=true 读、false 写;Resp 无 dummy 段(CmdRespBase 仅 cmdReqID,dummy 是 ScreenShot 私有)。
 */
class SysPropReq(
    var isGetProp: Boolean = true,
    var propKey: String = "",
    var propVal: String = "",
    var cmdReqId: Int = 1,
) : IdcPacket(IdcConst.ID_CMD_SYSPROP_REQ) {
    // CmdReqBase: LPString({"cmdReqID":N}) + LPString({"is_get_prop":B,"prop_key":K,"prop_val":V})
    override fun encodeBody(): ByteArray {
        val req = """{"cmdReqID":$cmdReqId}"""
        val s =
            """{"is_get_prop":$isGetProp,"prop_key":"${jsonEscape(propKey)}",""" +
                """"prop_val":"${jsonEscape(propVal)}"}"""
        val b = ByteBuffer.allocate(lpStringSize(req) + lpStringSize(s))
        b.putLPString(req)
        b.putLPString(s)
        return b.array()
    }
}

class SysPropResp(
    var propKey: String = "",
    var propVal: String = "",
) : IdcPacket(IdcConst.ID_CMD_SYSPROP_RESP) {
    override fun encodeBody() = ByteArray(0)

    override fun decodeBody(buf: ByteBuffer) {
        buf.getLPString() // {"cmdReqID":N}
        val j = parseJsonObject(buf.getLPString())
        propKey = j.str("prop_key")
        propVal = j.str("prop_val")
    }
}

class DevNameUpdate(
    var devName: String = "",
) : IdcPacket(IdcConst.ID_DEVINFO_UPDATE_NAME) {
    // IdcRawPacket_DevInfoUpdate_DevName: body = LPString(JSON {"dev_name":"…"})
    override fun encodeBody(): ByteArray {
        val s = """{"dev_name":"${jsonEscape(devName)}"}"""
        val b = ByteBuffer.allocate(lpStringSize(s))
        b.putLPString(s)
        return b.array()
    }

    override fun decodeBody(buf: ByteBuffer) {
        if (!buf.hasRemaining()) return
        val j = parseJsonObject(buf.getLPString())
        devName = j.str("dev_name")
    }
}

/**
 * DevInfoUpdate_DdhParam(11100) — TV 侧 DDH 参数更新(IdcRawPacket_DevInfoUpdate_DdhParam.java)。
 * body = LPString(JSON {"ddhparamkey":K}) + LPBytes(param)。
 * 当前仅解析保留 key/param,不接入 DeviceInfo.ddhParams(无消费路径)。
 */
class DevInfoUpdateDdhParam(
    var ddhKey: String = "",
    var param: ByteArray = ByteArray(0),
) : IdcPacket(IdcConst.ID_DEVINFO_UPDATE_DDH) {
    override fun encodeBody(): ByteArray {
        val s = """{"ddhparamkey":"${jsonEscape(ddhKey)}"}"""
        val b = ByteBuffer.allocate(lpStringSize(s) + param.size)
        b.putLPString(s)
        b.put(param)
        return b.array()
    }

    override fun decodeBody(buf: ByteBuffer) {
        if (!buf.hasRemaining()) return
        val j = parseJsonObject(buf.getLPString())
        ddhKey = j.str("ddhparamkey")
        param = buf.getLPBytes()
    }
}

// ---- JSON utilities (kotlinx.serialization-backed; FlatJson facade keeps call sites stable) ----

fun jsonEscape(s: String): String =
    buildString(s.length + 8) {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }

private val tvJson =
    Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

class FlatJson(
    private val obj: JsonObject,
) {
    fun str(k: String): String =
        obj[k]?.let {
            if (it is JsonPrimitive) it.contentOrNull ?: "" else it.toString()
        } ?: ""

    fun int(k: String): Int = (obj[k] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

    fun bool(k: String): Boolean = (obj[k] as? JsonPrimitive)?.content?.toBoolean() ?: false

    fun toMap(): Map<String, String> =
        obj.entries.associate { (k, v) ->
            k to (if (v is JsonPrimitive) v.contentOrNull ?: "" else v.toString())
        }

    fun strArray(k: String): List<String> =
        (obj[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
}

fun parseJsonObject(s: String): FlatJson =
    try {
        FlatJson(tvJson.parseToJsonElement(s).jsonObject)
    } catch (_: Exception) {
        FlatJson(JsonObject(emptyMap()))
    }

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

    /** Full wire frame; [sessionKey] non-null encrypts the body (AES/CBC, key=IV). */
    fun encode(sessionKey: ByteArray? = null): ByteBuffer {
        val body = encodeBody()
        val payload: ByteArray =
            if (sessionKey == null) {
                body
            } else {
                val enc = if (body.isNotEmpty()) IdcCrypto.aesEncrypt(body, sessionKey) else ByteArray(0)
                val buf = ByteBuffer.allocate(4 + enc.size)
                buf.putLPBytes(enc)
                buf.array()
            }
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
        fun decode(
            frame: ByteBuffer,
            sessionKey: ByteArray? = null,
        ): IdcPacket? {
            val magic = frame.int
            if (magic != IdcConst.MAGIC) return null
            val key = frame.int
            val id = frame.int
            val total = frame.int
            if (total < IdcConst.HEADER_LEN || total > frame.capacity()) return null
            var body = frame.slice()
            if (sessionKey != null) {
                val enc = body.getLPBytes()
                body = ByteBuffer.wrap(if (enc.isNotEmpty()) IdcCrypto.aesDecrypt(enc, sessionKey) else ByteArray(0))
            }
            val p = create(id) ?: RawIdcPacket(id)
            p.key = key
            p.decodeBody(body)
            return p
        }

        fun create(id: Int): IdcPacket? = sessionPacket(id) ?: modulePacket(id) ?: inputPacket(id) ?: commandPacket(id)

        private fun sessionPacket(id: Int): IdcPacket? =
            when (id) {
                IdcConst.ID_LOGIN_REQ -> LoginReq()
                IdcConst.ID_LOGIN_ENCRYPTION_RESP -> LoginEncryptionResp()
                IdcConst.ID_LOGIN_RESP -> LoginResp()
                IdcConst.ID_HEARTBEAT -> HeartBeat()
                IdcConst.ID_DEVINFO_UPDATE_NAME -> DevNameUpdate()
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
                IdcConst.ID_IME_START_INPUT -> ImeStartInput()
                IdcConst.ID_IME_FINISH_INPUT -> ImeFinishInput()
                IdcConst.ID_IME_TEXT_CHANGE -> ImeTextChange()
                IdcConst.ID_IME_ACTION -> ImeAction()
                else -> null
            }

        private fun commandPacket(id: Int): IdcPacket? =
            when (id) {
                IdcConst.ID_CMD_SCREENSHOT_REQ -> ScreenShotReq()
                IdcConst.ID_CMD_SCREENSHOT_RESP -> ScreenShotResp()
                else -> null
            }
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
    var encryptionAlgorithmDetail: String = "",
) : IdcPacket(IdcConst.ID_LOGIN_ENCRYPTION_RESP) {
    override fun encodeBody() = ByteArray(0)

    override fun decodeBody(buf: ByteBuffer) {
        if (buf.hasRemaining()) encryptionAlgorithmDetail = buf.getLPString()
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
        moduleName = j.str("m_name")
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
) : IdcPacket(IdcConst.ID_IME_START_INPUT) {
    override fun encodeBody() = ByteArray(0)

    override fun decodeBody(buf: ByteBuffer) {
        // fields per IdcRawPacket_Ime_StartInput: best-effort parse
        if (buf.remaining() >= 4) {
            hint = buf.getLPString()
            if (buf.remaining() >= 4) inputType = buf.int
            if (buf.remaining() >= 4) initText = buf.getLPString()
        }
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

class ScreenShotReq(
    var resizeRatio: Int = 0,
    var resizeW: Int = 1280,
    var resizeH: Int = 720,
    var compressQuality: Int = 90,
) : IdcPacket(IdcConst.ID_CMD_SCREENSHOT_REQ) {
    override fun encodeBody(): ByteArray {
        val s =
            """{"resize_ratio":$resizeRatio,"resize_w":$resizeW,"resize_h":$resizeH,""" +
                """"compress_quality":$compressQuality}"""
        val b = ByteBuffer.allocate(lpStringSize(s))
        b.putLPString(s)
        return b.array()
    }
}

class ScreenShotResp(
    var imgData: ByteArray = ByteArray(0),
) : IdcPacket(IdcConst.ID_CMD_SCREENSHOT_RESP) {
    override fun encodeBody() = ByteArray(0)

    override fun decodeBody(buf: ByteBuffer) {
        // best-effort: image bytes may be raw remainder or LP-prefixed
        if (buf.remaining() >= 4) {
            val mark = buf.position()
            val n = buf.int
            imgData =
                if (n > 0 && n == buf.remaining()) {
                    val b = ByteArray(n)
                    buf.get(b)
                    b
                } else {
                    buf.position(mark)
                    val b = ByteArray(buf.remaining())
                    buf.get(b)
                    b
                }
        }
    }
}

class DevNameUpdate(
    var devName: String = "",
) : IdcPacket(IdcConst.ID_DEVINFO_UPDATE_NAME) {
    override fun encodeBody() = ByteArray(0)

    override fun decodeBody(buf: ByteBuffer) {
        if (buf.hasRemaining()) devName = buf.getLPString()
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

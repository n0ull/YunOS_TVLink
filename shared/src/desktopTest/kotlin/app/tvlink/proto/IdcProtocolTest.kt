package app.tvlink.proto

import app.tvlink.proto.idc.HeartBeat
import app.tvlink.proto.idc.IdcConst
import app.tvlink.proto.idc.IdcCrypto
import app.tvlink.proto.idc.IdcLoginType
import app.tvlink.proto.idc.IdcPacket
import app.tvlink.proto.idc.LoginReq
import app.tvlink.proto.idc.LoginResp
import app.tvlink.proto.idc.ModuleAvailability
import app.tvlink.proto.idc.OpCmdKey
import app.tvlink.proto.idc.VConnData
import app.tvlink.proto.idc.getLPString
import app.tvlink.proto.idc.lpBytesSize
import app.tvlink.proto.idc.lpStringSize
import app.tvlink.proto.idc.putLPBytes
import app.tvlink.proto.idc.putLPString
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdcProtocolTest {
    @Test
    fun loginReqWireFormat() {
        val p = LoginReq(name = "app.tvlink", appVerCode = 1, devName = "Test", loginType = IdcLoginType.NORMAL)
        val frame = p.encode()
        assertEquals(IdcConst.MAGIC, frame.int)
        assertEquals(IdcConst.UNASSIGNED_KEY, frame.int)
        assertEquals(IdcConst.ID_LOGIN_REQ, frame.int)
        val total = frame.int
        assertEquals(total, IdcConst.HEADER_LEN + frame.remaining())
        val json = frame.getLPString()
        assertTrue(json.contains("\"login_type\":1")) // NORMAL.ordinal
        assertTrue(json.contains("\"encryption_algorithm_ver\":0"))
        assertTrue(json.contains("\"name\":\"app.tvlink\""))
    }

    @Test
    fun heartBeatRoundTrip() {
        val hb = HeartBeat(seq = 42)
        val frame = hb.encode()
        val decoded = IdcPacket.decode(frame) as HeartBeat
        assertEquals(42, decoded.seq)
    }

    @Test
    fun loginRespDecode() {
        val json =
            """{"dev_name":"LivingRoom","dev_model":"Box1","dev_uuid":"u-1","dev_os":"YunOS",""" +
                """"dev_os_ver":"3.0","dev_ddhparamkeys":["mediaprojection"]}"""
        val ddh = """{"projectionport":13599}""".toByteArray()
        val body = ByteBuffer.allocate(12 + lpStringSize(json) + lpBytesSize(ddh))
        body.putInt(2) // ver
        body.putInt(777) // connKey
        body.putInt(5354) // udpPort
        body.putLPString(json)
        body.putLPBytes(ddh)
        val resp = LoginResp()
        resp.decodeBody(ByteBuffer.wrap(body.array()))
        assertEquals(777, resp.connKey)
        assertEquals("LivingRoom", resp.devName)
        assertEquals("u-1", resp.devUuid)
        assertTrue(resp.ddhParams.containsKey("mediaprojection"))
    }

    @Test
    fun vconnDataRoundTrip() {
        val payload = byteArrayOf(0, 0, 0, 7, 0, 0, 0, 1, 0x7B, 0x7D)
        val p = VConnData(moduleId = 5, payload = payload)
        val frame = p.encode()
        val decoded = IdcPacket.decode(frame) as VConnData
        assertEquals(5, decoded.moduleId)
        assertTrue(payload.contentEquals(decoded.payload))
    }

    @Test
    fun aesRoundTripAndDerivation() {
        val key = IdcCrypto.deriveAesSecret(123456, 654321)
        assertEquals(16, key.size)
        val data = "hello-idc".toByteArray()
        val enc = IdcCrypto.aesEncrypt(data, key)
        assertTrue(enc.size % 16 == 0)
        val dec = IdcCrypto.aesDecrypt(enc, key)
        assertTrue(data.contentEquals(dec))
        // digest is hex of 32-byte HMAC
        assertEquals(64, IdcCrypto.seedDigest(123456).length)
        // derivation is deterministic
        assertTrue(key.contentEquals(IdcCrypto.deriveAesSecret(123456, 654321)))
    }

    @Test
    fun opCmdKeyFormat() {
        val frame = OpCmdKey(keyCode = 23, keyOp = 0).encode()
        frame.int
        frame.int
        frame.int
        frame.int // header
        assertEquals(23, frame.int)
        assertEquals(0, frame.int)
    }

    @Test
    fun moduleAvailabilityDecode() {
        val json = """{"m_name":"com.yunos.tv.asr","m_ver":2100300001,"m_extprop":"etao","m_id":7,"isOnline":true}"""
        val body = ByteBuffer.allocate(lpStringSize(json))
        body.putLPString(json)
        val m = ModuleAvailability()
        m.decodeBody(ByteBuffer.wrap(body.array()))
        assertEquals("com.yunos.tv.asr", m.moduleName)
        assertEquals(7, m.moduleId)
        assertTrue(m.online)
    }
}

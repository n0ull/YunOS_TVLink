package app.tvlink.device

import app.tvlink.proto.idc.IdcPacket
import app.tvlink.proto.idc.SysPropReq
import app.tvlink.proto.idc.SysPropResp

/**
 * TV 系统属性读写 over the IDC command channel (21100 -> 21200).
 * 反编译: IdcPacket_Cmd_SysProp_Req/Resp.java。Resp 会回显 prop_key,据此配对挂起请求。
 */
class SysPropService(
    private val deviceManager: DeviceManager,
) {
    var onSysProp: ((key: String, value: String) -> Unit)? = null

    @Volatile
    private var pendingKey: String? = null

    /** Wire into DeviceManager.onPacket (compose with other consumers at the call site). */
    fun handlePacket(p: IdcPacket) {
        val resp = p as? SysPropResp ?: return
        val expected = pendingKey ?: return
        if (resp.propKey != expected) return
        pendingKey = null
        onSysProp?.invoke(resp.propKey, resp.propVal)
    }

    fun getProp(key: String): Boolean = send(isGet = true, key = key, value = "")

    fun setProp(
        key: String,
        value: String,
    ): Boolean = send(isGet = false, key = key, value = value)

    private fun send(
        isGet: Boolean,
        key: String,
        value: String,
    ): Boolean {
        val conn = deviceManager.connection ?: return false
        pendingKey = key
        conn.send(SysPropReq(isGetProp = isGet, propKey = key, propVal = value))
        return true
    }
}

package app.tvlink.device

import app.tvlink.proto.idc.IdcPacket
import app.tvlink.proto.idc.SysPropReq
import app.tvlink.proto.idc.SysPropResp
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * TV 系统属性读写 over the IDC command channel (21100 -> 21200).
 * 反编译: IdcPacket_Cmd_SysProp_Req/Resp.java。Resp 会回显 prop_key,据此配对挂起请求。
 */
class SysPropService(
    private val deviceManager: DeviceManager,
) {
    data class SysPropValue(
        val key: String,
        val value: String,
    )

    /** 每次查询应答一个元素（prop_key 配对成功后）。tryEmit 不阻塞读线程。 */
    private val _values = MutableSharedFlow<SysPropValue>(extraBufferCapacity = 4)
    val values: SharedFlow<SysPropValue> = _values

    /** 在途查询 key 集合（并发查询各自按回显 prop_key 配对响应；同 key 并发合并共享响应）。 */
    private val pendingKeys =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()

    /** Wire into DeviceManager.packets (compose with other consumers at the call site). */
    fun handlePacket(p: IdcPacket) {
        val resp = p as? SysPropResp ?: return
        if (!pendingKeys.remove(resp.propKey)) return
        _values.tryEmit(SysPropValue(resp.propKey, resp.propVal))
    }

    fun getProp(key: String): Boolean = send(isGet = true, key = key, value = "")

    private fun send(
        isGet: Boolean,
        key: String,
        value: String,
    ): Boolean {
        val conn = deviceManager.connection ?: return false
        pendingKeys.add(key)
        conn.send(SysPropReq(isGetProp = isGet, propKey = key, propVal = value))
        return true
    }
}

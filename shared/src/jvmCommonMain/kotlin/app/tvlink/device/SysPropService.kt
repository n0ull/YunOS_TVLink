package app.tvlink.device

import app.tvlink.proto.idc.IdcPacket
import app.tvlink.proto.idc.SysPropReq
import app.tvlink.proto.idc.SysPropResp
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** TV system property queries over IDC 21100 → 21200. */
class SysPropService(
    private val selection: DeviceSelection,
) {
    data class SysPropValue(
        val key: String,
        val value: String,
    )

    private val _values = MutableSharedFlow<SysPropValue>(extraBufferCapacity = 4)
    val values: SharedFlow<SysPropValue> = _values

    private val pendingKeys =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()

    @Volatile
    private var pendingBinding: IdcBinding? = null
    private val stateLock = Any()

    internal fun handlePacket(
        binding: IdcBinding,
        packet: IdcPacket,
    ) {
        binding.targetLease.onlyIfActive {
            synchronized(stateLock) {
                if (!binding.isActive) return@synchronized
                val response = packet as? SysPropResp ?: return@synchronized
                if (pendingBinding !== binding ||
                    selection.binding.value
                        ?.idc
                        ?.value !== binding
                ) {
                    return@synchronized
                }
                if (!pendingKeys.remove(response.propKey)) return@synchronized
                _values.tryEmit(SysPropValue(response.propKey, response.propVal))
            }
        }
    }

    fun getProp(key: String): Boolean = send(isGet = true, key = key, value = "")

    private fun send(
        isGet: Boolean,
        key: String,
        value: String,
    ): Boolean {
        val binding =
            selection.binding.value
                ?.idc
                ?.value ?: return false
        var accepted = false
        binding.targetLease.onlyIfActive {
            synchronized(stateLock) {
                if (!binding.isActive) return@synchronized
                if (selection.binding.value
                        ?.idc
                        ?.value !== binding
                ) {
                    return@synchronized
                }
                if (pendingBinding !== binding) pendingKeys.clear()
                pendingBinding = binding
                pendingKeys.add(key)
                accepted = true
            }
        }
        if (accepted) binding.connection.send(SysPropReq(isGetProp = isGet, propKey = key, propVal = value))
        return accepted
    }
}

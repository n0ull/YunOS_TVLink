package app.tvlink.device

import app.tvlink.proto.idc.IdcPacket
import app.tvlink.proto.idc.ScreenShotReq
import app.tvlink.proto.idc.ScreenShotResp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/** TV screenshot over IDC command channel 20900 → 21000. See docs/re/04 §6. */
class ScreenshotService(
    private val selection: DeviceSelection,
) {
    private val _screenshots = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8)
    val screenshots: SharedFlow<ByteArray> = _screenshots

    private val pending =
        java.util.concurrent.atomic
            .AtomicInteger(0)

    @Volatile
    private var pendingBinding: IdcBinding? = null
    private val stateLock = Any()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal fun handlePacket(
        binding: IdcBinding,
        packet: IdcPacket,
    ) {
        binding.targetLease.onlyIfActive {
            synchronized(stateLock) {
                if (!binding.isActive) return@synchronized
                if (packet !is ScreenShotResp || pendingBinding !== binding) return@synchronized
                if (selection.binding.value
                        ?.idc
                        ?.value !== binding
                ) {
                    return@synchronized
                }
                if (pending.get() <= 0 || packet.imgData.isEmpty()) return@synchronized
                pending.decrementAndGet()
                _screenshots.tryEmit(packet.imgData)
            }
        }
    }

    fun capture(
        width: Int = 1280,
        height: Int = 720,
        quality: Int = 90,
    ): Boolean {
        val binding =
            selection.binding.value
                ?.idc
                ?.value ?: return false
        return captureOn(binding, width, height, quality)
    }

    private fun captureOn(
        binding: IdcBinding,
        width: Int,
        height: Int,
        quality: Int,
    ): Boolean {
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
                if (pendingBinding !== binding) pending.set(0)
                pendingBinding = binding
                pending.incrementAndGet()
                accepted = true
            }
        }
        if (accepted) {
            binding.connection.send(
                ScreenShotReq(resizeW = width, resizeH = height, compressQuality = quality),
            )
        }
        return accepted
    }

    fun captureBurst(
        count: Int = 5,
        intervalMs: Long = 300,
    ): Boolean {
        val binding =
            selection.binding.value
                ?.idc
                ?.value ?: return false
        scope.launch {
            repeat(count) { index ->
                if (!captureOn(binding, width = 1280, height = 720, quality = 90)) return@launch
                if (index < count - 1) delay(intervalMs)
            }
        }
        return true
    }

    /** Active probe after a response timeout; only the current connection may be torn down here. */
    fun dropConnectionIfUnresponsive(): Boolean {
        val binding =
            selection.binding.value
                ?.idc
                ?.value ?: return false
        if (!binding.isActive || binding.connection.ping()) return false
        binding.connection.shutdown()
        return true
    }

    fun destroy() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}

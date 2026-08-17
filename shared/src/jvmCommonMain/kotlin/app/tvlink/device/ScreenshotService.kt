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

/**
 * TV screenshot over the IDC command channel (20900 -> 21000). See docs/re/04 §6.
 */
class ScreenshotService(
    private val deviceManager: DeviceManager,
) {
    /** 每张 TV 截图应答一个元素（连拍逐张）。tryEmit 不阻塞读线程。 */
    private val _screenshots = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8)
    val screenshots: SharedFlow<ByteArray> = _screenshots

    /** 在途请求数：连拍时 >1，应答按 TCP 序到达逐张转发（docs/re/04 §6 原 App 长按连拍语义）。 */
    private val pending =
        java.util.concurrent.atomic
            .AtomicInteger(0)

    /** Wire into DeviceManager.packets (compose with other consumers at the call site). */
    fun handlePacket(p: IdcPacket) {
        if (p is ScreenShotResp && pending.get() > 0 && p.imgData.isNotEmpty()) {
            pending.decrementAndGet()
            _screenshots.tryEmit(p.imgData)
        }
    }

    fun capture(
        width: Int = 1280,
        height: Int = 720,
        quality: Int = 90,
    ): Boolean {
        val conn = deviceManager.connection ?: return false
        pending.incrementAndGet()
        conn.send(ScreenShotReq(resizeW = width, resizeH = height, compressQuality = quality))
        return true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 连拍（原 App 长按 300ms/帧 + 上限，docs/re/04 §6）：顺序触发，应答逐张到逐张发射。
     *  返回 false = 无连接未发出（调用方据此提示）；发出后单帧失败仅截断后续帧。 */
    fun captureBurst(
        count: Int = 5,
        intervalMs: Long = 300,
    ): Boolean {
        if (deviceManager.connection == null) return false
        scope.launch {
            repeat(count) { i ->
                if (!capture()) return@launch
                if (i < count - 1) delay(intervalMs)
            }
        }
        return true
    }

    /** 取消内部 scope（AppViewModel.onCleared 链调用）。 */
    fun destroy() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}

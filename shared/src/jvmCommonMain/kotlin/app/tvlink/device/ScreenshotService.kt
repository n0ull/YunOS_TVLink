package app.tvlink.device

import app.tvlink.proto.idc.IdcPacket
import app.tvlink.proto.idc.ScreenShotReq
import app.tvlink.proto.idc.ScreenShotResp

/**
 * TV screenshot over the IDC command channel (20900 -> 21000). See docs/re/04 §6.
 */
class ScreenshotService(
    private val deviceManager: DeviceManager,
) {
    var onScreenshot: ((jpeg: ByteArray) -> Unit)? = null

    /** 在途请求数：连拍时 >1，应答按 TCP 序到达逐张转发（docs/re/04 §6 原 App 长按连拍语义）。 */
    private val pending =
        java.util.concurrent.atomic
            .AtomicInteger(0)

    /** Wire into DeviceManager.onPacket (compose with other consumers at the call site). */
    fun handlePacket(p: IdcPacket) {
        if (p is ScreenShotResp && pending.get() > 0 && p.imgData.isNotEmpty()) {
            pending.decrementAndGet()
            onScreenshot?.invoke(p.imgData)
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

    /** 连拍（原 App 长按 300ms/帧 + 上限，docs/re/04 §6）：顺序触发，应答逐张到逐张回调。 */
    fun captureBurst(
        count: Int = 5,
        intervalMs: Long = 300,
    ) {
        Thread(
            {
                repeat(count) { i ->
                    if (!capture()) return@Thread
                    if (i < count - 1) Thread.sleep(intervalMs)
                }
            },
            "shot-burst",
        ).apply {
            isDaemon = true
            start()
        }
    }
}

package app.tvlink.device

import app.tvlink.proto.idc.IdcPacket
import app.tvlink.proto.idc.ScreenShotReq
import app.tvlink.proto.idc.ScreenShotResp

/**
 * TV screenshot over the IDC command channel (20900 -> 21000). See docs/re/04 §6.
 */
class ScreenshotService(private val deviceManager: DeviceManager) {

    var onScreenshot: ((jpeg: ByteArray) -> Unit)? = null

    @Volatile private var pending = false

    /** Wire into DeviceManager.onPacket (compose with other consumers at the call site). */
    fun handlePacket(p: IdcPacket) {
        if (p is ScreenShotResp && pending && p.imgData.isNotEmpty()) {
            pending = false
            onScreenshot?.invoke(p.imgData)
        }
    }

    fun capture(width: Int = 1280, height: Int = 720, quality: Int = 90) {
        val conn = deviceManager.connection ?: return
        pending = true
        conn.send(ScreenShotReq(resizeW = width, resizeH = height, compressQuality = quality))
    }
}

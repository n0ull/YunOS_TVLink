package app.tvlink.ui.widgets

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import java.io.File
import org.jetbrains.skia.Image as SkiaImage

@Composable
actual fun ByteArrayImage(
    bytes: ByteArray,
    contentDescription: String?,
    modifier: Modifier,
) {
    val bitmap =
        remember(bytes) {
            try {
                SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
    }
}

actual fun saveShot(bytes: ByteArray): String? {
    val dir = File(System.getProperty("user.home"), "Pictures/TVLink").apply { mkdirs() }
    val f = File(dir, "tv-shot-${System.currentTimeMillis()}.jpg")
    return try {
        f.writeBytes(bytes)
        "已保存到 ${f.absolutePath}"
    } catch (e: Exception) {
        "保存失败: ${e.message}"
    }
}

package app.tvlink.ui.widgets

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale

/** Holds the application context for platform services. Initialized by MainActivity. */
object AndroidPlatform {
    lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}

@Composable
actual fun ByteArrayImage(
    bytes: ByteArray,
    contentDescription: String?,
    modifier: Modifier,
) {
    val bitmap =
        remember(bytes) {
            try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
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
    return try {
        val resolver = AndroidPlatform.appContext.contentResolver
        val values =
            android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "tv-shot-${System.currentTimeMillis()}.jpg")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TVLink")
            }
        val uri =
            resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return "保存失败"
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
        "已保存到相册 Pictures/TVLink"
    } catch (e: Exception) {
        "保存失败: ${e.message}"
    }
}

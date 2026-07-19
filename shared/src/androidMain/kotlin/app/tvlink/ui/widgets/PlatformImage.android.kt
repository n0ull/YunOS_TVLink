package app.tvlink.ui.widgets

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale

// Compose 约定可组合函数为 PascalCase；expect/actual 及各调用点均依赖此名
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
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
                Log.w("ByteArrayImage", "decodeByteArray failed", e)
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

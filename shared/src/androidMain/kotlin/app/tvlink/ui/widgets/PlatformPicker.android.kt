package app.tvlink.ui.widgets

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Android media picker via SAF. Copies the picked content to app cache so the
 * embedded HTTP server can serve it as a plain file.
 */
@Composable
actual fun pickMediaFile(
    type: String,
    onResult: (String?) -> Unit,
) {
    val context = LocalContext.current
    // 大文件(几十 MB 音乐/视频)拷贝放 IO 线程：Activity Result 回调跑在主线程，
    // 同步 copyTo 会冻结 UI 直到拷完
    val scope = rememberCoroutineScope()
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) {
                onResult(null)
                return@rememberLauncherForActivityResult
            }
            scope.launch(Dispatchers.IO) {
                try {
                    val name = queryDisplayName(context, uri) ?: "media-${System.currentTimeMillis()}"
                    val out = File(context.cacheDir, "cast/$name")
                    out.parentFile?.mkdirs()
                    context.contentResolver.openInputStream(uri)?.use { inp ->
                        FileOutputStream(out).use { f -> inp.copyTo(f, bufferSize = COPY_BUFFER_SIZE) }
                    }
                    onResult(out.absolutePath)
                } catch (e: Exception) {
                    Log.w("PlatformPicker", "copy picked media failed", e)
                    onResult(null)
                }
            }
        }
    LaunchedEffect(type) {
        launcher.launch(
            when (type) {
                "image" -> "image/*"
                "video" -> "video/*"
                else -> "audio/*"
            },
        )
    }
}

private const val COPY_BUFFER_SIZE = 256 * 1024

private fun queryDisplayName(
    context: android.content.Context,
    uri: Uri,
): String? {
    val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
    context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
        if (c.moveToFirst()) return c.getString(0)
    }
    return null
}

actual fun albumArtFile(audioPath: String): File? {
    return try {
        val ctx = AndroidPlatform.appContext
        // SAF 选取的文件已拷入缓存（cast/<原名>），按显示名回查 MediaStore 拿 album id
        val albumId = queryAlbumId(ctx, File(audioPath).name) ?: return null
        val artUri =
            android.content.ContentUris.withAppendedId(
                android.provider.MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                albumId,
            )
        val out = File(ctx.cacheDir, "cast/cover-${System.currentTimeMillis()}.jpg")
        out.parentFile?.mkdirs()
        ctx.contentResolver.openInputStream(artUri)?.use { inp ->
            FileOutputStream(out).use { inp.copyTo(it) }
        } ?: return null
        out.takeIf { it.length() > 0 }
    } catch (e: Exception) {
        Log.w("PlatformPicker", "album art lookup failed", e)
        null
    }
}

private fun queryAlbumId(
    context: android.content.Context,
    displayName: String,
): Long? {
    val uri = android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(android.provider.MediaStore.Audio.Media.ALBUM_ID)
    val selection = "${android.provider.MediaStore.Audio.Media.DISPLAY_NAME} = ?"
    context.contentResolver.query(uri, projection, selection, arrayOf(displayName), null)?.use { c ->
        if (c.moveToFirst()) return c.getLong(0)
    }
    return null
}

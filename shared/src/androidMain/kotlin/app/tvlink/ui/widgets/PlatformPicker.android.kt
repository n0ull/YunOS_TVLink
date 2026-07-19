package app.tvlink.ui.widgets

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.io.FileOutputStream

/**
 * Android media picker via SAF. Copies the picked content to app cache so the
 * embedded HTTP server can serve it as a plain file.
 */
@Composable
actual fun pickMediaFile(type: String, onResult: (String?) -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) {
            onResult(null)
            return@rememberLauncherForActivityResult
        }
        try {
            val name = queryDisplayName(context, uri) ?: "media-${System.currentTimeMillis()}"
            val out = File(context.cacheDir, "cast/$name")
            out.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { inp ->
                FileOutputStream(out).use { inp.copyTo(it) }
            }
            onResult(out.absolutePath)
        } catch (e: Exception) {
            onResult(null)
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

private fun queryDisplayName(context: android.content.Context, uri: Uri): String? {
    val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
    context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
        if (c.moveToFirst()) return c.getString(0)
    }
    return null
}

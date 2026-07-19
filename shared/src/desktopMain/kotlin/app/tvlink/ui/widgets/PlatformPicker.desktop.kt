package app.tvlink.ui.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FilenameFilter

@Composable
actual fun pickMediaFile(type: String, onResult: (String?) -> Unit) {
    LaunchedEffect(type) {
        val exts = when (type) {
            "image" -> listOf("jpg", "jpeg", "png", "gif", "webp")
            "video" -> listOf("mp4", "mkv", "avi", "mov", "ts")
            else -> listOf("mp3", "flac", "wav", "m4a", "aac")
        }
        val dialog = FileDialog(null as Frame?, "选择媒体文件", FileDialog.LOAD)
        dialog.filenameFilter = FilenameFilter { _, name ->
            exts.any { name.lowercase().endsWith(".$it") }
        }
        dialog.isVisible = true
        val file = dialog.file
        val dir = dialog.directory
        onResult(if (file != null && dir != null) File(dir, file).absolutePath else null)
    }
}

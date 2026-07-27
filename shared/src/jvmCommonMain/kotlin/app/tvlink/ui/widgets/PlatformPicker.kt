package app.tvlink.ui.widgets

import androidx.compose.runtime.Composable
import java.io.File

/**
 * Platform media file picker.
 * Invoke as: pickMediaFile(type) { path -> ... } inside composition; it launches the
 * platform picker immediately (or shows a dialog) and calls back with a filesystem path.
 */
@Composable
expect fun pickMediaFile(
    type: String,
    onResult: (String?) -> Unit,
)

/**
 * 音频文件的专辑封面（尽力而为，docs/re/04 §3 音乐 thumbnail_url 的替代实现）：
 * Android 查 MediaStore 并把封面拷进缓存返回文件；桌面无媒体库返回 null。
 * 任何失败（无权限/无封面/查询异常）返回 null，投屏退化为无封面。
 */
expect fun albumArtFile(audioPath: String): File?

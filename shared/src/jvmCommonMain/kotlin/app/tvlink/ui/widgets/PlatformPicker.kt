package app.tvlink.ui.widgets

import androidx.compose.runtime.Composable

/**
 * Platform media file picker.
 * Invoke as: pickMediaFile(type) { path -> ... } inside composition; it launches the
 * platform picker immediately (or shows a dialog) and calls back with a filesystem path.
 */
@Composable
expect fun pickMediaFile(type: String, onResult: (String?) -> Unit)

package app.tvlink.ui.widgets

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale

/** Platform image rendering for a JPEG/PNG byte array. */
@Composable
expect fun ByteArrayImage(bytes: ByteArray, contentDescription: String?, modifier: Modifier = Modifier)

/** Platform save of screenshot bytes; returns a human-readable result message. */
expect fun saveShot(bytes: ByteArray): String?

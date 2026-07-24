// Compose 约定可组合函数为 PascalCase，本文件含多个可组合函数，统一文件级抑制
@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package app.tvlink.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.tvlink.proto.cast.CastController
import app.tvlink.ui.AppViewModel
import app.tvlink.ui.widgets.pickMediaFile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastScreen(vm: AppViewModel) {
    var pendingType by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("本地投屏") })
        Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MediaTypeCard("照片", Icons.Filled.Photo) { pendingType = "image" }
                MediaTypeCard("视频", Icons.Filled.Movie) { pendingType = "video" }
                MediaTypeCard("音乐", Icons.Filled.MusicNote) { pendingType = "audio" }
            }

            pendingType?.let { type ->
                pickMediaFile(type) { path ->
                    pendingType = null
                    if (path != null) {
                        val title = path.substringAfterLast('/').substringAfterLast('\\')
                        vm.castFile(path, title, type)
                    }
                }
            }

            Spacer(Modifier.padding(12.dp))

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (vm.castTitle.isEmpty()) "未在投屏" else vm.castTitle,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.padding(4.dp))
                    AssistChip(onClick = {}, label = { Text("状态: ${vm.castState}") })

                    Spacer(Modifier.padding(8.dp))
                    SeekBar(vm)
                    Text(
                        "${fmtMs(vm.castPosition)} / ${fmtMs(vm.castDuration)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.padding(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (vm.castState == CastController.PlayState.PLAYING) {
                            Button(onClick = { vm.cast?.pause() }) {
                                Icon(Icons.Filled.Pause, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("暂停")
                            }
                        } else {
                            Button(onClick = { vm.cast?.play() }) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("播放")
                            }
                        }
                        OutlinedButton(onClick = { vm.cast?.stop() }) {
                            Icon(Icons.Filled.Stop, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("退出")
                        }
                    }

                    Spacer(Modifier.padding(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.VolumeUp,
                            contentDescription = "音量",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        var vol by remember { mutableStateOf(10f) }
                        Slider(
                            value = vol,
                            onValueChange = { vol = it },
                            onValueChangeFinished = { vm.cast?.volume(vol.toInt()) },
                            valueRange = 0f..30f,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaTypeCard(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    ElevatedCard(onClick = onClick) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.padding(4.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 可拖动进度条：拖动中显示本地值，松手 seek。 */
@Composable
private fun SeekBar(vm: AppViewModel) {
    var dragging by remember { mutableStateOf(false) }
    var dragPos by remember { mutableStateOf(0f) }
    val duration = vm.castDuration.toFloat().coerceAtLeast(1f)
    Slider(
        value = if (dragging) dragPos else vm.castPosition.toFloat().coerceIn(0f, duration),
        onValueChange = {
            dragging = true
            dragPos = it
        },
        onValueChangeFinished = {
            dragging = false
            vm.castSeek(dragPos.toLong())
        },
        valueRange = 0f..duration,
        enabled = vm.castDuration > 0,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun fmtMs(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}

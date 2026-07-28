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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.tvlink.ui.icons.AppIcons
import app.tvlink.ui.widgets.pickMediaFile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastScreen(vm: AppViewModel) {
    var pendingType by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("本地投屏") })
        Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MediaTypeCard("照片", AppIcons.Photo) { pendingType = "image" }
                MediaTypeCard("视频", AppIcons.Movie) { pendingType = "video" }
                MediaTypeCard("音乐", AppIcons.MusicNote) { pendingType = "audio" }
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

                    Spacer(Modifier.padding(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (vm.castState == CastController.PlayState.PLAYING) {
                            Button(onClick = { vm.castPause() }) {
                                Icon(AppIcons.Pause, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("暂停")
                            }
                        } else {
                            Button(onClick = { vm.castPlay() }) {
                                Icon(AppIcons.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("播放")
                            }
                        }
                        OutlinedButton(onClick = { vm.castStop() }) {
                            Icon(AppIcons.Stop, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("退出")
                        }
                    }

                    Spacer(Modifier.padding(8.dp))
                    // 倍速（POST /rate，docs/re/04 §4）：选中态随轮询校准
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1f, 1.5f, 2f, 3f).forEach { r ->
                            FilterChip(
                                selected = vm.castRate == r,
                                onClick = { vm.castRateTo(r) },
                                label = { Text(if (r == r.toLong().toFloat()) "${r.toLong()}x" else "${r}x") },
                            )
                        }
                    }

                    Spacer(Modifier.padding(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            AppIcons.VolumeUp,
                            contentDescription = "音量",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        // 值随轮询同步 TV 音量;拖动中显示本地值,松手下发
                        var dragging by remember { mutableStateOf(false) }
                        var dragVol by remember { mutableStateOf(0f) }
                        Slider(
                            value = if (dragging) dragVol else vm.castVolume.toFloat(),
                            onValueChange = {
                                dragging = true
                                dragVol = it
                            },
                            onValueChangeFinished = {
                                dragging = false
                                vm.castVolumeTo(dragVol.toInt())
                            },
                            valueRange = 0f..30f,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        // 拖动中预览目标音量,平时显示 TV 当前音量
                        Text(
                            "${if (dragging) dragVol.toInt() else vm.castVolume}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/**
 * 进度条：拖动中显示目标预览;松手后短暂保持目标值等待轮询确认(≤2.5s),
 * TV 实际进度到位(±3s)则交还轮询,超时未到位则回退到实际进度。
 */
@Composable
private fun SeekBar(vm: AppViewModel) {
    var dragging by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    var override by remember { mutableStateOf(0f) }
    val duration = vm.castDuration.toFloat().coerceAtLeast(1f)
    val showOverride = dragging || pending

    LaunchedEffect(pending, override) {
        if (!pending) return@LaunchedEffect
        val targetMs = override.toLong()
        var waited = 0L
        while (waited < 2500L) {
            if (kotlin.math.abs(vm.castPosition - targetMs) <= 3000L) break
            kotlinx.coroutines.delay(200)
            waited += 200
        }
        pending = false
    }

    Slider(
        value = if (showOverride) override else vm.castPosition.toFloat().coerceIn(0f, duration),
        onValueChange = {
            dragging = true
            override = it
        },
        onValueChangeFinished = {
            dragging = false
            pending = true
            vm.castSeek(override.toLong())
        },
        valueRange = 0f..duration,
        enabled = vm.castDuration > 0,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "${fmtMs((if (showOverride) override.toLong() else vm.castPosition))} / ${fmtMs(vm.castDuration)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun fmtMs(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}

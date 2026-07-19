package app.tvlink.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tvlink.proto.cast.CastController
import app.tvlink.ui.AppViewModel
import app.tvlink.ui.theme.TvColors
import app.tvlink.ui.widgets.pickMediaFile

@Composable
fun CastScreen(vm: AppViewModel) {
    var pendingType by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.navBack() }) { Text("‹ 返回") }
            Spacer(Modifier.weight(1f))
            Text("本地投屏", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.padding(24.dp))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            OutlinedButton(onClick = { pendingType = "image" }) { Text("📷 照片") }
            OutlinedButton(onClick = { pendingType = "video" }) { Text("🎬 视频") }
            OutlinedButton(onClick = { pendingType = "audio" }) { Text("🎵 音乐") }
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

        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    if (vm.castTitle.isEmpty()) "未在投屏" else vm.castTitle,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "状态: ${vm.castState}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TvColors.TextSecondary,
                )
                Spacer(Modifier.padding(6.dp))
                val progress = if (vm.castDuration > 0) (vm.castPosition.toFloat() / vm.castDuration).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${fmtMs(vm.castPosition)} / ${fmtMs(vm.castDuration)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TvColors.TextSecondary,
                )
                Spacer(Modifier.padding(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.cast?.play() }) { Text("播放") }
                    Button(onClick = { vm.cast?.pause() }) { Text("暂停") }
                    Button(onClick = { vm.cast?.stop() }) { Text("退出") }
                }
                Spacer(Modifier.padding(8.dp))
                Text("音量", style = MaterialTheme.typography.bodySmall)
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

private fun fmtMs(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}

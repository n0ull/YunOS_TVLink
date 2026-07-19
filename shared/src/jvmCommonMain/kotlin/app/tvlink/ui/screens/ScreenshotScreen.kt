package app.tvlink.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tvlink.ui.AppViewModel
import app.tvlink.ui.theme.TvColors

@Composable
fun ScreenshotScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.navBack() }) { Text("‹ 返回") }
            Spacer(Modifier.weight(1f))
            Text("电视截屏", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.padding(24.dp))
        }
        Spacer(Modifier.height(16.dp))

        Card(Modifier.weight(1f).fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val shot = vm.lastShot
                if (shot == null) {
                    Text("还没有截图，点击下方按钮截取电视画面", color = TvColors.TextSecondary)
                } else {
                    app.tvlink.ui.widgets.ByteArrayImage(
                        bytes = shot,
                        contentDescription = "电视截图",
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.takeScreenshot() }, enabled = !vm.shotBusy) {
                Text(if (vm.shotBusy) "截取中…" else "截屏")
            }
            if (vm.lastShot != null) {
                Button(onClick = {
                    vm.notice = app.tvlink.ui.widgets
                        .saveShot(vm.lastShot!!) ?: "已保存"
                }) {
                    Text("保存")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

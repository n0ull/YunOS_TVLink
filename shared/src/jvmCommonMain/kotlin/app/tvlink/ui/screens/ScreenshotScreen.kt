// Compose 约定可组合函数为 PascalCase，本文件含多个可组合函数，统一文件级抑制
@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tvlink.ui.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenshotScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("电视截屏") },
            navigationIcon = {
                IconButton(onClick = { vm.navBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )
        Column(
            Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ElevatedCard(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val shot = vm.lastShot
                    if (shot == null) {
                        Text(
                            "还没有截图，点击下方按钮截取电视画面",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                FilledTonalButton(onClick = { vm.takeScreenshot() }, enabled = !vm.shotBusy) {
                    Text(if (vm.shotBusy) "截取中…" else "截屏")
                }
                if (vm.lastShot != null) {
                    Button(
                        onClick = {
                            vm.notice =
                                app.tvlink.ui.widgets
                                    .saveShot(vm.lastShot!!) ?: "已保存"
                        },
                    ) {
                        Text("保存")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

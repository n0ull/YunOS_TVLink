package app.tvlink.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.tvlink.device.DeviceManager
import app.tvlink.ui.AppViewModel
import app.tvlink.ui.theme.TvColors

@Composable
fun DevicePickerScreen(vm: AppViewModel) {
    var manualIp by remember { mutableStateOf("") }
    var showManual by remember { mutableStateOf(false) }
    val searching = vm.connState == DeviceManager.ConnState.SEARCHING

    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(48.dp))
        Text("TVLink", style = MaterialTheme.typography.headlineLarge)
        Text("用手机玩电视", style = MaterialTheme.typography.bodyMedium, color = TvColors.TextSecondary)
        Spacer(Modifier.height(32.dp))

        SearchPulse(active = searching)

        Spacer(Modifier.height(24.dp))
        when (vm.connState) {
            DeviceManager.ConnState.SEARCHING -> Text("正在搜索同一 Wi-Fi 下的电视设备…", color = TvColors.TextSecondary)
            DeviceManager.ConnState.CONNECTING -> Text("正在连接…", color = TvColors.TextSecondary)
            DeviceManager.ConnState.FAILED -> Text("连接失败，请确认设备在线后重试", color = TvColors.Red)
            else -> Text(if (vm.foundDevices.isEmpty()) "点击下方按钮搜索设备" else "发现 ${vm.foundDevices.size} 个设备", color = TvColors.TextSecondary)
        }

        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.foundDevices, key = { it.ip }) { d ->
                Card(
                    Modifier.fillMaxWidth().clickable { vm.connectTo(d) },
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(40.dp).background(TvColors.accentBrush, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Text("TV", color = androidx.compose.ui.graphics.Color.White) }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(d.name.ifEmpty { "未命名设备" }, style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(
                                    d.ip,
                                    d.model.takeIf { it.isNotEmpty() },
                                    d.mac.takeIf { it.isNotEmpty() },
                                ).joinToString("  ·  "),
                                style = MaterialTheme.typography.bodySmall,
                                color = TvColors.TextSecondary,
                            )
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.startDiscovery() }, enabled = !searching) {
                Text(if (searching) "搜索中…" else "重新扫描")
            }
            TextButton(onClick = { showManual = !showManual }) { Text("手动输入 IP") }
        }

        if (showManual) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = manualIp,
                onValueChange = { manualIp = it },
                label = { Text("电视 IP 地址") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (manualIp.isNotBlank()) vm.connectToIp(manualIp.trim()) }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SearchPulse(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val a by transition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(
        Modifier.size(72.dp)
            .alpha(if (active) a else 0.25f)
            .background(TvColors.accentBrush, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("搜", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.titleLarge)
    }
}

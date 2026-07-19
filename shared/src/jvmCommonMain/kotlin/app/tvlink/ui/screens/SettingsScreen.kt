package app.tvlink.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
fun SettingsScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.navBack() }) { Text("‹ 返回") }
            Spacer(Modifier.weight(1f))
            Text("设置", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.padding(24.dp))
        }

        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("当前连接", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.padding(4.dp))
                Text("设备: ${vm.connectedName}")
                Text("IP: ${vm.connectedIp}", color = TvColors.TextSecondary)
                Text("媒体服务: ${vm.mediaServerUrl.ifEmpty { "未启动" }}", color = TvColors.TextSecondary)
                Text("IB 快速通道: ${if (vm.rc.ibReady.value) "已连接" else "未连接（使用回退通道）"}", color = TvColors.TextSecondary)
            }
        }

        Spacer(Modifier.padding(10.dp))

        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("关于", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.padding(4.dp))
                Text("TVLink 1.0")
                Text(
                    "开源的 YunOS 电视局域网控制客户端（发现/遥控/投屏/截屏/应用管理/语音转发）",
                    color = TvColors.TextSecondary,
                )
            }
        }
    }
}

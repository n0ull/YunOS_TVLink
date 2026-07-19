package app.tvlink.ui.screens

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
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
import app.tvlink.ui.widgets.platformName

private data class Feature(
    val title: String,
    val desc: String,
    val emoji: String,
    val screen: AppViewModel.Screen,
)

private fun features(): List<Feature> =
    buildList {
        add(Feature("遥控", "按键 / 触屏 / 手柄 / 体感", "🎮", AppViewModel.Screen.Remote))
        add(Feature("本地投屏", "照片·视频·音乐上电视", "📺", AppViewModel.Screen.Cast))
        add(Feature("电视截屏", "截取电视当前画面", "📸", AppViewModel.Screen.Screenshot))
        add(Feature("应用管理", "查看/打开/卸载电视应用", "📦", AppViewModel.Screen.Apps))
        if (platformName == "android") {
            add(Feature("魔投配网", "MagicCast 蓝牙配网", "📡", AppViewModel.Screen.Dongle))
        }
        add(Feature("设置", "连接信息与关于", "⚙️", AppViewModel.Screen.Settings))
    }

@Composable
fun HomeScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        // device bar
        Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(TvColors.Green, CircleShape))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(vm.connectedName, style = MaterialTheme.typography.titleMedium)
                    Text(vm.connectedIp, style = MaterialTheme.typography.bodySmall, color = TvColors.TextSecondary)
                }
                TextButton(onClick = { vm.disconnect() }) { Text("断开", color = TvColors.Red) }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("功能", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(features()) { f ->
                Card(
                    Modifier.fillMaxWidth().height(110.dp).clickable {
                        if (f.screen == AppViewModel.Screen.Apps) vm.refreshApps()
                        vm.nav(f.screen)
                    },
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Column(Modifier.padding(14.dp).fillMaxSize(), verticalArrangement = Arrangement.Center) {
                        Text(f.emoji, style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(f.title, style = MaterialTheme.typography.titleMedium)
                        Text(f.desc, style = MaterialTheme.typography.bodySmall, color = TvColors.TextSecondary)
                    }
                }
            }
        }

        // now bar: remote entry
        Card(
            shape = RoundedCornerShape(24.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        vertical = 8.dp,
                    ).clickable { vm.nav(AppViewModel.Screen.Remote) },
        ) {
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🕹️", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(12.dp))
                Text("打开遥控面板", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("›", style = MaterialTheme.typography.titleLarge, color = TvColors.TextSecondary)
            }
        }
    }
}

// Compose 约定可组合函数为 PascalCase，本文件含多个可组合函数，统一文件级抑制
@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package app.tvlink.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.tvlink.ui.AppViewModel
import app.tvlink.ui.widgets.platformName

private data class MoreItem(
    val title: String,
    val desc: String,
    val icon: ImageVector,
    val sub: AppViewModel.MoreSub,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(vm: AppViewModel) {
    val items =
        buildList {
            add(MoreItem("电视截屏", "截取电视当前画面", Icons.Filled.PhotoCamera, AppViewModel.MoreSub.SCREENSHOT))
            add(MoreItem("应用管理", "查看 / 打开 / 卸载电视应用", Icons.Filled.Apps, AppViewModel.MoreSub.APPS))
            if (platformName == "android") {
                add(MoreItem("魔投配网", "MagicCast 蓝牙配网", Icons.Filled.Bluetooth, AppViewModel.MoreSub.DONGLE))
            }
            add(MoreItem("设置", "连接信息与关于", Icons.Filled.Settings, AppViewModel.MoreSub.SETTINGS))
        }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("更多") })
        LazyColumn {
            items(items, key = { it.title }) { item ->
                ListItem(
                    headlineContent = { Text(item.title) },
                    supportingContent = { Text(item.desc) },
                    leadingContent = { Icon(item.icon, contentDescription = null) },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    },
                    modifier =
                        Modifier.clickable {
                            if (item.sub == AppViewModel.MoreSub.APPS) vm.refreshApps()
                            vm.navMore(item.sub)
                        },
                )
            }
        }
    }
}

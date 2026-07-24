// Compose 约定可组合函数为 PascalCase，本文件含多个可组合函数，统一文件级抑制
@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package app.tvlink.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.tvlink.ui.screens.AppsScreen
import app.tvlink.ui.screens.CastScreen
import app.tvlink.ui.screens.MoreScreen
import app.tvlink.ui.screens.RemoteScreen
import app.tvlink.ui.screens.ScreenshotScreen
import app.tvlink.ui.screens.SettingsScreen
import app.tvlink.ui.widgets.DongleScreen

private data class TabDest(
    val tab: AppViewModel.MainTab,
    val label: String,
    val icon: ImageVector,
)

private val tabs =
    listOf(
        TabDest(AppViewModel.MainTab.REMOTE, "遥控", Icons.Filled.SportsEsports),
        TabDest(AppViewModel.MainTab.CAST, "投屏", Icons.Filled.Cast),
        TabDest(AppViewModel.MainTab.MORE, "更多", Icons.Filled.MoreHoriz),
    )

@Composable
fun MainShell(
    vm: AppViewModel,
    main: AppViewModel.Screen.Main,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        Row(Modifier.fillMaxSize()) {
            if (wide) {
                NavigationRail {
                    tabs.forEach { d ->
                        NavigationRailItem(
                            selected = main.tab == d.tab,
                            onClick = { vm.navTab(d.tab) },
                            icon = { Icon(d.icon, contentDescription = d.label) },
                            label = { Text(d.label) },
                        )
                    }
                }
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                bottomBar = {
                    if (!wide) {
                        NavigationBar {
                            tabs.forEach { d ->
                                NavigationBarItem(
                                    selected = main.tab == d.tab,
                                    onClick = { vm.navTab(d.tab) },
                                    icon = { Icon(d.icon, contentDescription = d.label) },
                                    label = { Text(d.label) },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    when (main.moreSub) {
                        AppViewModel.MoreSub.SCREENSHOT -> ScreenshotScreen(vm)
                        AppViewModel.MoreSub.APPS -> AppsScreen(vm)
                        AppViewModel.MoreSub.SETTINGS -> SettingsScreen(vm)
                        AppViewModel.MoreSub.DONGLE -> DongleScreen(vm)
                        null ->
                            when (main.tab) {
                                AppViewModel.MainTab.REMOTE -> RemoteScreen(vm)
                                AppViewModel.MainTab.CAST -> CastScreen(vm)
                                AppViewModel.MainTab.MORE -> MoreScreen(vm)
                            }
                    }
                }
            }
        }
    }
}

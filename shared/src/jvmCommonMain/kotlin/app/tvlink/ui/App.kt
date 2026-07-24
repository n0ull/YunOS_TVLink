package app.tvlink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tvlink.ui.screens.DevicePickerScreen
import app.tvlink.ui.theme.TvTheme
import app.tvlink.ui.widgets.BackHandler

@Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose 约定可组合函数为 PascalCase
@Composable
fun App() {
    val fallbackOwner =
        remember {
            object : ViewModelStoreOwner {
                override val viewModelStore: ViewModelStore = ViewModelStore()
            }
        }
    val owner = LocalViewModelStoreOwner.current ?: fallbackOwner
    DisposableEffect(owner) {
        onDispose { if (owner === fallbackOwner) owner.viewModelStore.clear() }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        val vm: AppViewModel = viewModel { AppViewModel() }
        val screen = vm.screen
        // 遥控 tab 恒深（品类惯例）；其余跟随系统
        val remoteActive = screen is AppViewModel.Screen.Main && screen.tab == AppViewModel.MainTab.REMOTE
        TvTheme(dark = remoteActive || isSystemInDarkTheme()) {
            // Main 根（遥控 tab）不拦截返回键，交系统默认（退出/最小化）
            val canBack =
                screen is AppViewModel.Screen.Main &&
                    (screen.moreSub != null || screen.tab != AppViewModel.MainTab.REMOTE)
            BackHandler(enabled = canBack) { vm.navBack() }
            val snackbar = remember { SnackbarHostState() }
            LaunchedEffect(vm.notice) {
                if (vm.notice.isNotEmpty()) {
                    snackbar.showSnackbar(vm.notice)
                    vm.notice = ""
                }
            }
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
            ) { padding ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(padding),
                ) {
                    when (screen) {
                        AppViewModel.Screen.DevicePicker -> DevicePickerScreen(vm)
                        is AppViewModel.Screen.Main -> MainShell(vm, screen)
                    }
                }
            }
        }
    }
}

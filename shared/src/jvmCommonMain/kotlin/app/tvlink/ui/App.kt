package app.tvlink.ui

import androidx.compose.foundation.background
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
import app.tvlink.ui.screens.AppsScreen
import app.tvlink.ui.screens.CastScreen
import app.tvlink.ui.screens.DevicePickerScreen
import app.tvlink.ui.screens.HomeScreen
import app.tvlink.ui.screens.RemoteScreen
import app.tvlink.ui.screens.ScreenshotScreen
import app.tvlink.ui.screens.SettingsScreen
import app.tvlink.ui.theme.TvTheme
import app.tvlink.ui.widgets.DongleScreen

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
        TvTheme(dark = vm.screen == AppViewModel.Screen.Remote) {
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
                    when (vm.screen) {
                        AppViewModel.Screen.DevicePicker -> DevicePickerScreen(vm)
                        AppViewModel.Screen.Home -> HomeScreen(vm)
                        AppViewModel.Screen.Remote -> RemoteScreen(vm)
                        AppViewModel.Screen.Cast -> CastScreen(vm)
                        AppViewModel.Screen.Screenshot -> ScreenshotScreen(vm)
                        AppViewModel.Screen.Apps -> AppsScreen(vm)
                        AppViewModel.Screen.Settings -> SettingsScreen(vm)
                        AppViewModel.Screen.Dongle -> DongleScreen(vm)
                    }
                }
            }
        }
    }
}

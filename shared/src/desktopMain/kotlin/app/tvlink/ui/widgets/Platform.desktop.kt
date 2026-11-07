package app.tvlink.ui.widgets

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import app.tvlink.ui.AppViewModel

actual val platformName: String = "desktop"

// 桌面端无系统返回键，有意空实现。
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
actual fun BackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    // no-op: desktop has no system back button
}

actual class MotionSensor actual constructor() {
    actual fun start(
        onAccel: (x: Int, y: Int, z: Int) -> Unit,
        onGyro: (x: Int, y: Int, z: Int) -> Unit,
    ) {
        // 桌面端无运动传感器，有意空实现
    }

    actual fun stop() {
        // 桌面端无运动传感器，有意空实现
    }
}

// 桌面端无震动马达，有意空实现
actual fun keyVibrate() {
    // no-op
}

// Compose 约定可组合函数为 PascalCase；expect/actual 及各调用点均依赖此名
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
actual fun DongleScreen(vm: AppViewModel) {
    AlertDialog(
        onDismissRequest = { vm.navBack() },
        title = { Text("魔投配网") },
        text = { Text("BLE 配网仅 Android 端支持") },
        confirmButton = { TextButton(onClick = { vm.navBack() }) { Text("知道了") } },
    )
}

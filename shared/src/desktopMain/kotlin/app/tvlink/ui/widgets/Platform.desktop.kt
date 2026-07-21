package app.tvlink.ui.widgets

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

// Compose 约定可组合函数为 PascalCase；expect/actual 及各调用点均依赖此名
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
actual fun VoiceButton(onText: (String) -> Unit) {
    var isTextDialogShown by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    Button(onClick = { isTextDialogShown = true }) { Text("🎤 语音指令") }
    if (isTextDialogShown) {
        AlertDialog(
            onDismissRequest = { isTextDialogShown = false },
            title = { Text("语音指令") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("输入指令文本，如“打开优酷”") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (text.isNotBlank()) onText(text.trim())
                        text = ""
                        isTextDialogShown = false
                    },
                ) { Text("发送") }
            },
            dismissButton = { TextButton(onClick = { isTextDialogShown = false }) { Text("取消") } },
        )
    }
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

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

actual class MotionSensor actual constructor() {
    actual fun start(
        onAccel: (x: Int, y: Int, z: Int) -> Unit,
        onGyro: (x: Int, y: Int, z: Int) -> Unit,
    ) {}

    actual fun stop() {}
}

@Composable
actual fun VoiceButton(onText: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    Button(onClick = { show = true }) { Text("🎤 语音指令") }
    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
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
                TextButton(onClick = {
                    if (text.isNotBlank()) onText(text.trim())
                    text = ""
                    show = false
                }) { Text("发送") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("取消") } },
        )
    }
}

@Composable
actual fun DongleScreen(vm: AppViewModel) {
    AlertDialog(
        onDismissRequest = { vm.navBack() },
        title = { Text("魔投配网") },
        text = { Text("BLE 配网仅 Android 端支持") },
        confirmButton = { TextButton(onClick = { vm.navBack() }) { Text("知道了") } },
    )
}

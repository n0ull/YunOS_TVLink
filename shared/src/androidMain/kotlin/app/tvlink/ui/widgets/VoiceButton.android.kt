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

/**
 * 语音指令 = 文字输入对话框(2026-07-25 决策:放弃设备侧 STT)。
 * 考古:系统 SpeechRecognizer 在测试机上不可用——识别服务是 GoogleRecognitionService,
 * 无网/缺语言包/报 ERROR_INSUFFICIENT_PERMISSIONS(9) 与 ERROR_LANGUAGE_NOT_SUPPORTED(12),
 * 语言枚举 getVoiceDetailsIntent 返回 null(已修 NPE);备选 sherpa-onnx 离线(+25MB)与
 * 讯飞在线(需账号)经用户决策不采纳。文本经 asr_streaming 会话帧下发电视,
 * NLU 在电视固件侧,链路已真机验证(docs/re/03 §C)。
 */
@Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose 约定可组合函数为 PascalCase；expect/actual 及各调用点均依赖此名
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

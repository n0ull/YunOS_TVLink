@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

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

/** Sends a text command to the TV's ASR module; speech recognition is not performed locally. */
@Composable
fun VoiceButton(onText: (String) -> Unit) {
    var isDialogShown by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    Button(onClick = { isDialogShown = true }) { Text("🎤 语音指令") }
    if (!isDialogShown) return

    AlertDialog(
        onDismissRequest = { isDialogShown = false },
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
                    isDialogShown = false
                },
            ) { Text("发送") }
        },
        dismissButton = { TextButton(onClick = { isDialogShown = false }) { Text("取消") } },
    )
}

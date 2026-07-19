package app.tvlink.ui.widgets

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Voice button: system SpeechRecognizer when available + permission granted,
 * text-input dialog otherwise (the TV does NLU either way).
 */
@Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose 约定可组合函数为 PascalCase；expect/actual 及各调用点均依赖此名
@Composable
actual fun VoiceButton(onText: (String) -> Unit) {
    val context = LocalContext.current
    var isListening by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    var isTextDialogShown by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }

    val recognizer =
        remember {
            if (SpeechRecognizer.isRecognitionAvailable(
                    context,
                )
            ) {
                SpeechRecognizer.createSpeechRecognizer(context)
            } else {
                null
            }
        }
    DisposableEffect(recognizer) {
        onDispose { recognizer?.destroy() }
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted && recognizer != null) {
                startListening(
                    recognizer,
                    { partial = it },
                    { t ->
                        isListening = false
                        if (t != null) onText(t)
                    },
                    { isListening = false },
                )
            } else {
                isTextDialogShown = true
            }
        }

    Column {
        Button(
            onClick = {
                if (recognizer == null) {
                    isTextDialogShown = true
                    return@Button
                }
                val isGranted =
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                if (!isGranted) {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else if (!isListening) {
                    isListening = true
                    startListening(
                        recognizer,
                        { partial = it },
                        { t ->
                            isListening = false
                            if (t != null) onText(t)
                        },
                        { isListening = false },
                    )
                } else {
                    recognizer.stopListening()
                    isListening = false
                }
            },
        ) {
            Text(if (isListening) "⏹ 停止（$partial）" else "🎤 语音指令")
        }
    }

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

private fun startListening(
    sr: SpeechRecognizer,
    onPartial: (String) -> Unit,
    onFinal: (String?) -> Unit,
    onError: () -> Unit,
) {
    sr.setRecognitionListener(
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                // RecognitionListener 接口要求实现，本场景无需处理
            }

            override fun onBeginningOfSpeech() {
                // RecognitionListener 接口要求实现，本场景无需处理
            }

            override fun onRmsChanged(rmsdB: Float) {
                // RecognitionListener 接口要求实现，本场景无需处理
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                // RecognitionListener 接口要求实现，本场景无需处理
            }

            override fun onEndOfSpeech() {
                // RecognitionListener 接口要求实现，本场景无需处理
            }

            override fun onError(error: Int) = onError()

            override fun onResults(results: Bundle) {
                val texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                onFinal(texts?.firstOrNull())
            }

            override fun onPartialResults(partialResults: Bundle) {
                val texts = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                texts?.firstOrNull()?.let(onPartial)
            }

            override fun onEvent(
                eventType: Int,
                params: Bundle?,
            ) {
                // RecognitionListener 接口要求实现，本场景无需处理
            }
        },
    )
    val intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    sr.startListening(intent)
}

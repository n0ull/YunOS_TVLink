package app.tvlink.ui.widgets

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
@Composable
actual fun VoiceButton(onText: (String) -> Unit) {
    val context = LocalContext.current
    var listening by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    var showTextDialog by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }

    val recognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null
    }
    DisposableEffect(recognizer) {
        onDispose { recognizer?.destroy() }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && recognizer != null) startListening(recognizer, { partial = it }, { t ->
            listening = false
            if (t != null) onText(t)
        }, { listening = false })
        else showTextDialog = true
    }

    Column {
        Button(onClick = {
            if (recognizer == null) {
                showTextDialog = true
                return@Button
            }
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else if (!listening) {
                listening = true
                startListening(recognizer, { partial = it }, { t ->
                    listening = false
                    if (t != null) onText(t)
                }, { listening = false })
            } else {
                recognizer.stopListening()
                listening = false
            }
        }) {
            Text(if (listening) "⏹ 停止（$partial）" else "🎤 语音指令")
        }
    }

    if (showTextDialog) {
        AlertDialog(
            onDismissRequest = { showTextDialog = false },
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
                    showTextDialog = false
                }) { Text("发送") }
            },
            dismissButton = { TextButton(onClick = { showTextDialog = false }) { Text("取消") } },
        )
    }
}

private fun startListening(
    sr: SpeechRecognizer,
    onPartial: (String) -> Unit,
    onFinal: (String?) -> Unit,
    onError: () -> Unit,
) {
    sr.setRecognitionListener(object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) = onError()
        override fun onResults(results: Bundle) {
            val texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            onFinal(texts?.firstOrNull())
        }
        override fun onPartialResults(partialResults: Bundle) {
            val texts = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            texts?.firstOrNull()?.let(onPartial)
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    })
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    sr.startListening(intent)
}

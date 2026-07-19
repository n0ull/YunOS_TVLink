package app.tvlink.ui.widgets

import androidx.compose.runtime.Composable
import app.tvlink.ui.AppViewModel

/** "android" or "desktop" */
expect val platformName: String

/** Motion sensor feed for the remote's motion mode. Desktop actual is a no-op. */
expect class MotionSensor() {
    fun start(onAccel: (x: Int, y: Int, z: Int) -> Unit, onGyro: (x: Int, y: Int, z: Int) -> Unit)
    fun stop()
}

/** Voice capture button. Android: hold-to-talk via SpeechRecognizer; desktop: text dialog. */
@Composable
expect fun VoiceButton(onText: (String) -> Unit)

/** Dongle (MagicCast) BLE pairing screen — Android actual implements it, desktop shows unsupported. */
@Composable
expect fun DongleScreen(vm: AppViewModel)

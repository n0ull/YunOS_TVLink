package app.tvlink.ui.widgets

import androidx.compose.runtime.Composable
import app.tvlink.ui.AppViewModel

/** "android" or "desktop" */
expect val platformName: String

/**
 * 拦截系统返回键。Android actual 接入 [androidx.activity.compose.BackHandler]，
 * 桌面端无系统返回键，为空实现。
 */
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
expect fun BackHandler(
    enabled: Boolean = true,
    onBack: () -> Unit,
)

/** Motion sensor feed for the remote's motion mode. Desktop actual is a no-op. */
expect class MotionSensor() {
    fun start(
        onAccel: (x: Int, y: Int, z: Int) -> Unit,
        onGyro: (x: Int, y: Int, z: Int) -> Unit,
    )

    fun stop()
}

/** Voice capture button. Android: hold-to-talk via SpeechRecognizer; desktop: text dialog. */
@Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose 约定可组合函数为 PascalCase
@Composable
expect fun VoiceButton(onText: (String) -> Unit)

/** Dongle (MagicCast) BLE pairing screen — Android actual implements it, desktop shows unsupported. */
@Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose 约定可组合函数为 PascalCase
@Composable
expect fun DongleScreen(vm: AppViewModel)

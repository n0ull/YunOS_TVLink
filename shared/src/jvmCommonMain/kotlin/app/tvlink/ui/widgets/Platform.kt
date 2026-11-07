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

/**
 * 遥控按键震动。Android 走 Vibrator 直振(API 29+ EFFECT_CLICK),不受系统
 * 「触摸反馈」开关抑制——LocalHapticFeedback/VirtualKey 被该开关静默吞掉
 * (2026-08-18 真机无震动回归);桌面 no-op。
 */
expect fun keyVibrate()

/** Dongle (MagicCast) BLE pairing screen — Android actual implements it, desktop shows unsupported. */
@Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose 约定可组合函数为 PascalCase
@Composable
expect fun DongleScreen(vm: AppViewModel)

package app.tvlink.ui.widgets

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable

actual val platformName: String = "android"

// 以完全限定名调用 androidx BackHandler，避免与自身 actual 函数同名递归。
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
@Composable
actual fun BackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    androidx.activity.compose.BackHandler(enabled = enabled, onBack = onBack)
}

actual class KeyValueStore actual constructor(
    name: String,
) {
    private val prefs = AndroidPlatform.appContext.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)

    actual fun getString(key: String): String? = prefs.getString(key, null)

    actual fun putString(
        key: String,
        value: String,
    ) {
        prefs.edit().putString(key, value).apply()
    }

    actual fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}

/** Vibrator 直振:EFFECT_CLICK(29+)/oneShot 40ms(26+)/legacy(21+)。需 VIBRATE 权限(androidApp manifest)。 */
@Suppress("DEPRECATION") // API 26- 的 legacy vibrate(long) 与旧 getSystemService 路径
actual fun keyVibrate() {
    if (!AndroidPlatform.isInitialized) return // MainActivity 未 init(单元测试等)——静默跳过
    val ctx = AndroidPlatform.appContext
    val vibrator =
        if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            ctx.getSystemService(android.content.Context.VIBRATOR_SERVICE) as Vibrator
        }
    when {
        Build.VERSION.SDK_INT >= 29 ->
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))

        Build.VERSION.SDK_INT >= 26 ->
            vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))

        else -> vibrator.vibrate(40)
    }
}

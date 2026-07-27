package app.tvlink.ui.widgets

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

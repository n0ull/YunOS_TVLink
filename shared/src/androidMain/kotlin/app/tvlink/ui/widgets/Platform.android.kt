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

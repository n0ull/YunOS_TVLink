package app.tvlink.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightScheme =
    lightColorScheme(
        primary = Color(0xFF0E7490),
        onPrimary = Color.White,
        background = TvColors.BgLight,
        surface = TvColors.Card,
        onBackground = TvColors.TextPrimary,
        onSurface = TvColors.TextPrimary,
        secondary = TvColors.TextSecondary,
    )

private val DarkScheme =
    darkColorScheme(
        primary = TvColors.AccentStart,
        onPrimary = Color(0xFF06232B),
        background = TvColors.RcDark,
        surface = TvColors.RcDarkBar,
        onBackground = TvColors.TextOnDark,
        onSurface = TvColors.TextOnDark,
        secondary = TvColors.TextOnDarkSecondary,
    )

@Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose 约定可组合函数为 PascalCase
@Composable
fun TvTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        content = content,
    )
}

package app.tvlink.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object TvColors {
    val BgLight = Color(0xFFF0F3F5)
    val Card = Color(0xFFFFFFFF)
    val Pressed = Color(0xEFEFF4FF)
    val RcDark = Color(0xFF252529)
    val RcDarkBar = Color(0xFF28282C)
    val AccentStart = Color(0xFF37E8FF)
    val AccentEnd = Color(0xFFF586FF)
    val Orange = Color(0xFFFF9500)
    val Green = Color(0xFF24D870)
    val Red = Color(0xFFC92E30)
    val TextPrimary = Color(0xFF1B1B1F)
    val TextSecondary = Color(0xFF6B6B73)
    val TextOnDark = Color(0xFFEAEAF0)
    val TextOnDarkSecondary = Color(0xFF9A9AA5)

    val accentBrush = Brush.horizontalGradient(listOf(AccentStart, AccentEnd))
}

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0E7490),
    onPrimary = Color.White,
    background = TvColors.BgLight,
    surface = TvColors.Card,
    onBackground = TvColors.TextPrimary,
    onSurface = TvColors.TextPrimary,
    secondary = TvColors.TextSecondary,
)

private val DarkScheme = darkColorScheme(
    primary = TvColors.AccentStart,
    onPrimary = Color(0xFF06232B),
    background = TvColors.RcDark,
    surface = TvColors.RcDarkBar,
    onBackground = TvColors.TextOnDark,
    onSurface = TvColors.TextOnDark,
    secondary = TvColors.TextOnDarkSecondary,
)

@Composable
fun TvTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        content = content,
    )
}

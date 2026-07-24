package app.tvlink.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val LightScheme =
    lightColorScheme(
        primary = mdLightPrimary,
        onPrimary = mdLightOnPrimary,
        primaryContainer = mdLightPrimaryContainer,
        onPrimaryContainer = mdLightOnPrimaryContainer,
        secondary = mdLightSecondary,
        onSecondary = mdLightOnSecondary,
        secondaryContainer = mdLightSecondaryContainer,
        onSecondaryContainer = mdLightOnSecondaryContainer,
        tertiary = mdLightTertiary,
        onTertiary = mdLightOnTertiary,
        tertiaryContainer = mdLightTertiaryContainer,
        onTertiaryContainer = mdLightOnTertiaryContainer,
        error = mdLightError,
        onError = mdLightOnError,
        errorContainer = mdLightErrorContainer,
        onErrorContainer = mdLightOnErrorContainer,
        background = mdLightBackground,
        onBackground = mdLightOnBackground,
        surface = mdLightSurface,
        onSurface = mdLightOnSurface,
        surfaceVariant = mdLightSurfaceVariant,
        onSurfaceVariant = mdLightOnSurfaceVariant,
        outline = mdLightOutline,
        outlineVariant = mdLightOutlineVariant,
        surfaceContainerLowest = mdLightSurfaceContainerLowest,
        surfaceContainerLow = mdLightSurfaceContainerLow,
        surfaceContainer = mdLightSurfaceContainer,
        surfaceContainerHigh = mdLightSurfaceContainerHigh,
        surfaceContainerHighest = mdLightSurfaceContainerHighest,
        inverseSurface = mdLightInverseSurface,
        inverseOnSurface = mdLightInverseOnSurface,
        inversePrimary = mdLightInversePrimary,
        surfaceTint = mdLightPrimary,
    )

private val DarkScheme =
    darkColorScheme(
        primary = mdDarkPrimary,
        onPrimary = mdDarkOnPrimary,
        primaryContainer = mdDarkPrimaryContainer,
        onPrimaryContainer = mdDarkOnPrimaryContainer,
        secondary = mdDarkSecondary,
        onSecondary = mdDarkOnSecondary,
        secondaryContainer = mdDarkSecondaryContainer,
        onSecondaryContainer = mdDarkOnSecondaryContainer,
        tertiary = mdDarkTertiary,
        onTertiary = mdDarkOnTertiary,
        tertiaryContainer = mdDarkTertiaryContainer,
        onTertiaryContainer = mdDarkOnTertiaryContainer,
        error = mdDarkError,
        onError = mdDarkOnError,
        errorContainer = mdDarkErrorContainer,
        onErrorContainer = mdDarkOnErrorContainer,
        background = mdDarkBackground,
        onBackground = mdDarkOnBackground,
        surface = mdDarkSurface,
        onSurface = mdDarkOnSurface,
        surfaceVariant = mdDarkSurfaceVariant,
        onSurfaceVariant = mdDarkOnSurfaceVariant,
        outline = mdDarkOutline,
        outlineVariant = mdDarkOutlineVariant,
        surfaceContainerLowest = mdDarkSurfaceContainerLowest,
        surfaceContainerLow = mdDarkSurfaceContainerLow,
        surfaceContainer = mdDarkSurfaceContainer,
        surfaceContainerHigh = mdDarkSurfaceContainerHigh,
        surfaceContainerHighest = mdDarkSurfaceContainerHighest,
        inverseSurface = mdDarkInverseSurface,
        inverseOnSurface = mdDarkInverseOnSurface,
        inversePrimary = mdDarkInversePrimary,
        surfaceTint = mdDarkPrimary,
    )

private val TvShapes =
    Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )

@Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose 约定可组合函数为 PascalCase
@Composable
fun TvTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        shapes = TvShapes,
        content = content,
    )
}

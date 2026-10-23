package app.vazie.keep.ui.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

internal val LocalKeepColors = staticCompositionLocalOf { DarkKeepColors }
internal val LocalKeepType = staticCompositionLocalOf { DefaultKeepType }

object KeepDesign {
    val colors: KeepColors
        @Composable @ReadOnlyComposable get() = LocalKeepColors.current
    val type: KeepType
        @Composable @ReadOnlyComposable get() = LocalKeepType.current
}

val SecretTextStyle: TextStyle get() = DefaultKeepType.secret

private fun KeepColors.toMaterial() = if (isDark) {
    darkColorScheme(
        primary = accent,
        onPrimary = Color(0xFF2B1F47),
        primaryContainer = accentContainer,
        onPrimaryContainer = onAccentContainer,
        secondary = caution,
        onSecondary = Color(0xFF3B2016),
        secondaryContainer = monograms[0].container,
        onSecondaryContainer = monograms[0].content,
        tertiary = action,
        onTertiary = onAction,
        tertiaryContainer = surfaceRaised,
        onTertiaryContainer = text,
        background = background,
        onBackground = text,
        surface = background,
        onSurface = text,
        surfaceVariant = surfaceField,
        onSurfaceVariant = textSecondary,
        surfaceContainerLowest = background,
        surfaceContainerLow = surfaceList,
        surfaceContainer = surfaceField,
        surfaceContainerHigh = surfaceRaised,
        surfaceContainerHighest = surfaceToast,
        outline = textTertiary,
        outlineVariant = surfaceRaised,
        error = danger,
        onError = Color(0xFF3B0F0B),
        errorContainer = Color(0xFF4A2320),
        onErrorContainer = danger,
        scrim = Color.Black,
    )
} else {
    lightColorScheme(
        primary = accent,
        onPrimary = Color.White,
        primaryContainer = accentContainer,
        onPrimaryContainer = onAccentContainer,
        secondary = caution,
        onSecondary = Color.White,
        secondaryContainer = monograms[0].container,
        onSecondaryContainer = monograms[0].content,
        tertiary = action,
        onTertiary = onAction,
        tertiaryContainer = surfaceRaised,
        onTertiaryContainer = text,
        background = background,
        onBackground = text,
        surface = background,
        onSurface = text,
        surfaceVariant = surfaceField,
        onSurfaceVariant = textSecondary,
        surfaceContainerLowest = surfaceList,
        surfaceContainerLow = surfaceList,
        surfaceContainer = surfaceField,
        surfaceContainerHigh = surfaceRaised,
        surfaceContainerHighest = surfaceToast,
        outline = textTertiary,
        outlineVariant = surfaceRaised,
        error = danger,
        onError = Color.White,
        errorContainer = Color(0xFFF6DAD6),
        onErrorContainer = danger,
        scrim = Color.Black,
    )
}

private val MaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private fun KeepType.toMaterial() = Typography(
    displayLarge = display,
    displayMedium = display,
    displaySmall = headline,
    headlineLarge = headline,
    headlineMedium = headline,
    headlineSmall = title,
    titleLarge = title,
    titleMedium = rowTitle,
    titleSmall = label,
    bodyLarge = body,
    bodyMedium = body.copy(fontSize = rowSubtitle.fontSize, lineHeight = rowSubtitle.lineHeight),
    bodySmall = caption,
    labelLarge = button,
    labelMedium = label,
    labelSmall = badge,
)

@Composable
fun KeepTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkKeepColors else LightKeepColors
    CompositionLocalProvider(LocalKeepColors provides colors, LocalKeepType provides DefaultKeepType) {
        MaterialTheme(
            colorScheme = colors.toMaterial(),
            shapes = MaterialShapes,
            typography = DefaultKeepType.toMaterial(),
            content = content,
        )
    }
}

package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.example.model.ThemePreference

private val StudioLightColorScheme = lightColorScheme(
    primary = StudioJadePrimary,
    onPrimary = StudioJadeOnPrimary,
    primaryContainer = StudioJadeContainer,
    onPrimaryContainer = StudioJadeOnContainer,
    secondary = StudioSlateSecondary,
    onSecondary = StudioSlateOnSecondary,
    secondaryContainer = StudioSlateContainer,
    onSecondaryContainer = StudioSlateOnContainer,
    tertiary = StudioAmberTertiary,
    onTertiary = StudioAmberOnTertiary,
    tertiaryContainer = StudioAmberContainer,
    onTertiaryContainer = StudioAmberOnContainer,
    error = StudioRecordingCrimson,
    onError = StudioRecordingOnError,
    errorContainer = StudioRecordingContainer,
    onErrorContainer = StudioRecordingOnContainer,
    background = StudioLightBackground,
    onBackground = StudioLightInk,
    surface = StudioLightSurface,
    onSurface = StudioLightInk,
    surfaceVariant = StudioLightSurfaceVariant,
    onSurfaceVariant = StudioLightInkMuted,
    surfaceContainerLowest = StudioLightSurface,
    surfaceContainerLow = StudioLightSurfaceContainerLow,
    surfaceContainer = StudioLightSurfaceVariant,
    surfaceContainerHigh = StudioLightSurfaceContainerHigh,
    outline = StudioLightOutline,
    outlineVariant = StudioLightOutlineVariant
)

private val StudioDarkColorScheme = darkColorScheme(
    primary = StudioDarkJadePrimary,
    onPrimary = StudioDarkJadeOnPrimary,
    primaryContainer = StudioDarkJadeContainer,
    onPrimaryContainer = StudioDarkJadeOnContainer,
    secondary = StudioDarkSlateSecondary,
    onSecondary = StudioDarkSlateOnSecondary,
    secondaryContainer = StudioDarkSlateContainer,
    onSecondaryContainer = StudioDarkSlateOnContainer,
    tertiary = StudioDarkAmberTertiary,
    onTertiary = StudioDarkAmberOnTertiary,
    tertiaryContainer = StudioDarkAmberContainer,
    onTertiaryContainer = StudioDarkAmberOnContainer,
    error = StudioDarkRecordingCoral,
    onError = StudioDarkRecordingOnError,
    errorContainer = StudioDarkRecordingContainer,
    onErrorContainer = StudioDarkRecordingOnContainer,
    background = StudioDarkBackground,
    onBackground = StudioDarkInk,
    surface = StudioDarkSurface,
    onSurface = StudioDarkInk,
    surfaceVariant = StudioDarkSurfaceVariant,
    onSurfaceVariant = StudioDarkInkMuted,
    surfaceContainerLowest = StudioDarkBackground,
    surfaceContainerLow = StudioDarkSurfaceContainerLow,
    surfaceContainer = StudioDarkSurfaceVariant,
    surfaceContainerHigh = StudioDarkSurfaceContainerHigh,
    outline = StudioDarkOutline,
    outlineVariant = StudioDarkOutlineVariant
)

@Composable
fun CantoScribeTheme(
    themePreference: ThemePreference = ThemePreference.SYSTEM,
    content: @Composable () -> Unit
) {
    val useDark = when (themePreference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }
    val colorScheme = if (useDark) StudioDarkColorScheme else StudioLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

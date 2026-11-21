package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

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
    tertiaryContainer = StudioAmberContainer,
    onTertiaryContainer = StudioAmberOnContainer,
    error = StudioRecordingCrimson,
    errorContainer = StudioRecordingContainer,
    onErrorContainer = StudioRecordingOnContainer,
    background = StudioLightBackground,
    onBackground = StudioLightInk,
    surface = StudioLightSurface,
    onSurface = StudioLightInk,
    surfaceVariant = StudioLightSurfaceVariant,
    onSurfaceVariant = StudioLightInkMuted,
    outline = StudioLightOutline,
    outlineVariant = StudioLightSurfaceContainer
)

private val StudioDarkColorScheme = darkColorScheme(
    primary = StudioDarkJadePrimary,
    onPrimary = StudioDarkJadeOnPrimary,
    primaryContainer = StudioDarkJadeContainer,
    onPrimaryContainer = StudioDarkJadeOnContainer,
    secondary = StudioSlateContainer,
    onSecondary = StudioLightInk,
    secondaryContainer = StudioDarkSurfaceVariant,
    onSecondaryContainer = StudioDarkInk,
    tertiary = StudioAmberContainer,
    tertiaryContainer = StudioDarkSurfaceVariant,
    onTertiaryContainer = StudioAmberContainer,
    error = StudioDarkRecordingCoral,
    errorContainer = StudioDarkRecordingContainer,
    onErrorContainer = StudioDarkRecordingOnContainer,
    background = StudioDarkBackground,
    onBackground = StudioDarkInk,
    surface = StudioDarkSurface,
    onSurface = StudioDarkInk,
    surfaceVariant = StudioDarkSurfaceVariant,
    onSurfaceVariant = StudioDarkInkMuted,
    outline = StudioDarkOutline,
    outlineVariant = StudioDarkSurfaceVariant
)

@Composable
fun CantoScribeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) StudioDarkColorScheme else StudioLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

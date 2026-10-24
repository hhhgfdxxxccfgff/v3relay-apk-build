package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.example.relay.data.ThemeMode

private fun darkScheme(n: CyberNeutrals): ColorScheme = darkColorScheme(
    primary = n.accent,
    onPrimary = n.onAccent,
    primaryContainer = n.surfaceVariant,
    onPrimaryContainer = n.accentBright,
    secondary = n.info,
    onSecondary = n.onAccent,
    tertiary = n.violet,
    onTertiary = n.onAccent,
    background = n.dark,
    onBackground = n.textPrimary,
    surface = n.surface,
    onSurface = n.textPrimary,
    surfaceVariant = n.surfaceVariant,
    onSurfaceVariant = n.textSecondary,
    outline = n.border,
    error = n.danger,
    onError = n.dark
)

private fun lightScheme(n: CyberNeutrals): ColorScheme = lightColorScheme(
    primary = n.accent,
    onPrimary = n.onAccent,
    primaryContainer = n.surfaceVariant,
    onPrimaryContainer = n.accentSoft,
    secondary = n.info,
    onSecondary = n.dark,
    tertiary = n.violet,
    onTertiary = n.dark,
    background = n.dark,
    onBackground = n.textPrimary,
    surface = n.surface,
    onSurface = n.textPrimary,
    surfaceVariant = n.surfaceVariant,
    onSurfaceVariant = n.textSecondary,
    outline = n.border,
    error = n.danger,
    onError = n.dark
)

@Composable
fun MyApplicationTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val neutrals = if (darkTheme) DarkNeutrals else LightNeutrals
    CompositionLocalProvider(LocalCyberNeutrals provides neutrals) {
        MaterialTheme(
            colorScheme = if (darkTheme) darkScheme(neutrals) else lightScheme(neutrals),
            typography = Typography,
            content = content
        )
    }
}

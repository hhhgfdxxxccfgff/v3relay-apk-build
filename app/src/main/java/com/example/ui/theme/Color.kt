package com.example.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Refined graphite design system: calm neutral surfaces + one graphite brand
// accent, plus a small semantic palette. Every accent resolves from the
// active theme so contrast stays right on black and on white.
data class CyberNeutrals(
    val dark: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val accentBright: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val danger: Color,
    val warning: Color,
    val info: Color,
    val violet: Color
)

val DarkNeutrals = CyberNeutrals(
    dark = Color(0xFF000000),
    surface = Color(0xFF101012),
    surfaceVariant = Color(0xFF1A1A1E),
    border = Color(0xFF2E2E33),
    textPrimary = Color(0xFFFAFAFA),
    textSecondary = Color(0xFFA5A5AD),
    textMuted = Color(0xFF6F6F78),
    accent = Color(0xFFE5E5EA),
    accentBright = Color(0xFFFAFAFA),
    accentSoft = Color(0xFFC7C7CE),
    onAccent = Color(0xFF141417),
    danger = Color(0xFFFB7185),
    warning = Color(0xFFFBBF24),
    info = Color(0xFF22D3EE),
    violet = Color(0xFFA78BFA)
)

val LightNeutrals = CyberNeutrals(
    dark = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF4F4F5),
    border = Color(0xFFE4E4E7),
    textPrimary = Color(0xFF0A0A0B),
    textSecondary = Color(0xFF52525B),
    textMuted = Color(0xFF8A8A93),
    accent = Color(0xFF26262B),
    accentBright = Color(0xFF3F3F46),
    accentSoft = Color(0xFF52525B),
    onAccent = Color(0xFFFFFFFF),
    danger = Color(0xFFE11D48),
    warning = Color(0xFFD97706),
    info = Color(0xFF0891B2),
    violet = Color(0xFF7C3AED)
)

val LocalCyberNeutrals = staticCompositionLocalOf { DarkNeutrals }

// Neutral tokens flip with the theme
val CyberDark: Color
    @Composable @ReadOnlyComposable
    get() = LocalCyberNeutrals.current.dark

val CyberSurface: Color
    @Composable @ReadOnlyComposable
    get() = LocalCyberNeutrals.current.surface

val CyberSurfaceVariant: Color
    @Composable @ReadOnlyComposable
    get() = LocalCyberNeutrals.current.surfaceVariant

val CyberBorder: Color
    @Composable @ReadOnlyComposable
    get() = LocalCyberNeutrals.current.border

val CyberTextPrimary: Color
    @Composable @ReadOnlyComposable
    get() = LocalCyberNeutrals.current.textPrimary

val CyberTextSecondary: Color
    @Composable @ReadOnlyComposable
    get() = LocalCyberNeutrals.current.textSecondary

val CyberTextMuted: Color
    @Composable @ReadOnlyComposable
    get() = LocalCyberNeutrals.current.textMuted

// Accent + semantic tokens
val CyberAccent: Color
    @Composable @ReadOnlyComposable
    get() = LocalCyberNeutrals.current.accent

val CyberAccentGlow: Color
    @Composable @ReadOnlyComposable
    get() = LocalCyberNeutrals.current.accentBright

val CyberOnAccent: Color
    @Composable @ReadOnlyComposable
    get() = LocalCyberNeutrals.current.onAccent

val CyberCyan: Color
    @Composable @ReadOnlyComposable
    get() = LocalCyberNeutrals.current.info

val CyberPurple: Color
    @Composable @ReadOnlyComposable
    get() = LocalCyberNeutrals.current.violet

val CyberWarning: Color
    @Composable @ReadOnlyComposable
    get() = LocalCyberNeutrals.current.warning

val CyberError: Color
    @Composable @ReadOnlyComposable
    get() = LocalCyberNeutrals.current.danger

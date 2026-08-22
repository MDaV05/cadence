package com.cadence.music.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Cadence brand theme. Single source of truth for colors and shapes so a
 * future plugin/theme-pack can swap this spec wholesale.
 */

// Iris violet — sharp, blue-leaning; never lavender-soft.
private val IrisLight = Color(0xFF6B4EE8)
private val IrisDark = Color(0xFF9D8BFF)

// Ink & paper
private val Ink = Color(0xFF14121A)
private val Paper = Color(0xFFFAFAFC)

private val DarkBg = Color(0xFF0E0E13)
private val DarkSurface = Color(0xFF16161D)
private val DarkSurfaceHigh = Color(0xFF1F1F28)

val LightColors = lightColorScheme(
    primary = IrisLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7DFFF),
    onPrimaryContainer = Color(0xFF22005D),
    secondary = Color(0xFF5B5769),
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEAE7F2),
    onSurfaceVariant = Color(0xFF494552),
    outline = Color(0xFF7A7585),
)

val DarkColors = darkColorScheme(
    primary = IrisDark,
    onPrimary = Color(0xFF2A1069),
    primaryContainer = Color(0xFF432A8F),
    onPrimaryContainer = Color(0xFFE7DFFF),
    secondary = Color(0xFFC6C3D4),
    background = DarkBg,
    onBackground = Color(0xFFE8E6EF),
    surface = DarkSurface,
    onSurface = Color(0xFFE8E6EF),
    surfaceVariant = DarkSurfaceHigh,
    onSurfaceVariant = Color(0xFFC9C4D4),
    outline = Color(0xFF4A4557),
)

@Composable
fun CadenceTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}

package com.cadence.music.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.cadence.music.AppContainer
import com.cadence.music.data.db.CustomThemeEntity

/**
 * A theme: accent + background colors for light and dark. Everything else in
 * the Material scheme is derived, so a theme only needs these four colors.
 */
data class ThemeSpec(
    val id: String,
    val name: String,
    val accentLight: Int,
    val accentDark: Int,
    val bgLight: Int,
    val bgDark: Int,
)

fun customToSpec(e: CustomThemeEntity) = ThemeSpec(
    id = "custom:${e.name}", name = e.name,
    accentLight = e.accentLight, accentDark = e.accentDark,
    bgLight = e.bgLight, bgDark = e.bgDark,
)

// Built-in presets. Iris is the Cadence brand (sharp, blue-leaning violet).
val BUILTIN_THEMES = listOf(
    ThemeSpec("iris", "Iris", 0xFF6B4EE8.toInt(), 0xFF9D8BFF.toInt(), 0xFFFAFAFC.toInt(), 0xFF0E0E13.toInt()),
    ThemeSpec("applemusic", "Apple Music", 0xFFFA2D48.toInt(), 0xFFFC5163.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt()),
    ThemeSpec("spotify", "Spotify", 0xFF1DB954.toInt(), 0xFF1ED760.toInt(), 0xFFFFFFFF.toInt(), 0xFF121212.toInt()),
    ThemeSpec("ocean", "Ocean", 0xFF1B74D3.toInt(), 0xFF7FB2F0.toInt(), 0xFFFAFBFC.toInt(), 0xFF0C1116.toInt()),
    ThemeSpec("rose", "Rose", 0xFFC6406E.toInt(), 0xFFEF9BB6.toInt(), 0xFFFCFAFB.toInt(), 0xFF140D10.toInt()),
    ThemeSpec("forest", "Forest", 0xFF2E7D4F.toInt(), 0xFF93CBA9.toInt(), 0xFFFAFBF9.toInt(), 0xFF0C120E.toInt()),
    ThemeSpec("amber", "Amber", 0xFFB4690E.toInt(), 0xFFE5B258.toInt(), 0xFFFCFBF8.toInt(), 0xFF12100B.toInt()),
    ThemeSpec("mono", "Mono", 0xFF3D3D3D.toInt(), 0xFFC9C9C9.toInt(), 0xFFFAFAFA.toInt(), 0xFF101010.toInt()),
)

private fun lighten(c: Color, f: Float) = lerp(c, Color.White, f)
private fun darken(c: Color, f: Float) = lerp(c, Color.Black, f)

private fun schemeFor(spec: ThemeSpec, dark: Boolean): ColorScheme {
    val accent = Color(if (dark) spec.accentDark else spec.accentLight)
    val bg = Color(if (dark) spec.bgDark else spec.bgLight)
    val onAccent = if (accent.luminance() > 0.55f) Color.Black else Color.White
    val onBg = if (dark) lighten(bg, 0.9f) else darken(bg, 0.9f)
    return if (dark) {
        darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = darken(accent, 0.55f),
            onPrimaryContainer = lighten(accent, 0.8f),
            secondary = lighten(bg, 0.7f),
            background = bg,
            onBackground = onBg,
            surface = lighten(bg, 0.04f),
            onSurface = onBg,
            surfaceVariant = lighten(bg, 0.07f),
            onSurfaceVariant = lighten(bg, 0.72f),
            outline = lighten(bg, 0.3f),
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = lighten(accent, 0.82f),
            onPrimaryContainer = darken(accent, 0.65f),
            secondary = darken(bg, 0.55f),
            background = bg,
            onBackground = onBg,
            surface = bg,
            onSurface = onBg,
            surfaceVariant = lerp(lighten(bg, 0.04f), accent, 0.06f),
            onSurfaceVariant = darken(bg, 0.62f),
            outline = darken(bg, 0.52f),
        )
    }
}

/**
 * Applies the theme selected in Settings. [container] may be null in
 * previews/tests — falls back to the Iris brand theme.
 */
@Composable
fun CadenceTheme(container: AppContainer? = null, content: @Composable () -> Unit) {
    val prefs = container?.prefs
    val dark = if (prefs?.themeFollowSystem != false) {
        isSystemInDarkTheme()
    } else {
        prefs.themeDarkOverride
    }
    val id = prefs?.themeId ?: "iris"
    // themeTick bumps on every selection/custom-theme change.
    val tick = container?.themeTick?.intValue ?: 0
    val spec = remember(id, tick) {
        BUILTIN_THEMES.firstOrNull { it.id == id }
            ?: container?.customThemes
                ?.firstOrNull { it.name == id.removePrefix("custom:") }
                ?.let { customToSpec(it) }
            ?: BUILTIN_THEMES.first()
    }
    MaterialTheme(
        colorScheme = schemeFor(spec, dark),
        typography = Typography(),
        content = content,
    )
}

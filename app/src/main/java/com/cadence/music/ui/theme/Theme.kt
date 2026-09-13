@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.cadence.music.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cadence.music.AppContainer
import com.cadence.music.R
import com.cadence.music.data.db.CustomThemeEntity

/**
 * Type personality of a skin. DEFAULT is the platform sans; GEOMETRIC is a
 * full geometric grotesque (Outfit); SERIF_DISPLAY pairs an expressive serif
 * (Fraunces) for display/title text with the platform sans for dense UI.
 */
enum class SkinFont { DEFAULT, GEOMETRIC, SERIF_DISPLAY }

/**
 * Corner personality of a skin, delivered through MaterialTheme.shapes so
 * every component and clipped surface picks it up.
 */
enum class SkinCorners { SOFT, PILL, SHARP }

/**
 * Layout skin. STANDARD keeps the single shared composition; TURNTABLE swaps
 * the bottom navigation for a floating pill and renders Now Playing as a
 * spinning record. Screens branch only on this — everything else stays shared.
 */
enum class SkinLayout { STANDARD, TURNTABLE }

/**
 * A theme: accent + background colors for light and dark, plus optional skin
 * personality (type, corners, layout). Custom user themes only pick colors.
 */
data class ThemeSpec(
    val id: String,
    val name: String,
    val accentLight: Int,
    val accentDark: Int,
    val bgLight: Int,
    val bgDark: Int,
    val font: SkinFont = SkinFont.DEFAULT,
    val corners: SkinCorners = SkinCorners.SOFT,
    val layout: SkinLayout = SkinLayout.STANDARD,
)

fun customToSpec(e: CustomThemeEntity) = ThemeSpec(
    id = "custom:${e.name}", name = e.name,
    accentLight = e.accentLight, accentDark = e.accentDark,
    bgLight = e.bgLight, bgDark = e.bgDark,
)

// Built-in presets. Iris is the Cadence brand (sharp, blue-leaning violet).
// Two presets are full skins: Spotify (geometric type, pill corners) and
// Analog (serif display, sharp corners, turntable layout).
val BUILTIN_THEMES = listOf(
    ThemeSpec("iris", "Iris", 0xFF6B4EE8.toInt(), 0xFF9D8BFF.toInt(), 0xFFFAFAFC.toInt(), 0xFF0E0E13.toInt()),
    ThemeSpec("applemusic", "Apple Music", 0xFFFA2D48.toInt(), 0xFFFC5163.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt()),
    ThemeSpec(
        "spotify", "Spotify", 0xFF1DB954.toInt(), 0xFF1ED760.toInt(), 0xFFFFFFFF.toInt(), 0xFF121212.toInt(),
        font = SkinFont.GEOMETRIC, corners = SkinCorners.PILL,
    ),
    ThemeSpec("ocean", "Ocean", 0xFF1B74D3.toInt(), 0xFF7FB2F0.toInt(), 0xFFFAFBFC.toInt(), 0xFF0C1116.toInt()),
    ThemeSpec("rose", "Rose", 0xFFC6406E.toInt(), 0xFFEF9BB6.toInt(), 0xFFFCFAFB.toInt(), 0xFF140D10.toInt()),
    ThemeSpec("forest", "Forest", 0xFF2E7D4F.toInt(), 0xFF93CBA9.toInt(), 0xFFFAFBF9.toInt(), 0xFF0C120E.toInt()),
    ThemeSpec("amber", "Amber", 0xFFB4690E.toInt(), 0xFFE5B258.toInt(), 0xFFFCFBF8.toInt(), 0xFF12100B.toInt()),
    ThemeSpec("mono", "Mono", 0xFF3D3D3D.toInt(), 0xFFC9C9C9.toInt(), 0xFFFAFAFA.toInt(), 0xFF101010.toInt()),
)

/** The ThemeSpec active in CadenceTheme — lets screens branch on skin layout. */
val LocalSkin = compositionLocalOf {
    ThemeSpec("iris", "Iris", 0xFF6B4EE8.toInt(), 0xFF9D8BFF.toInt(), 0xFFFAFAFC.toInt(), 0xFF0E0E13.toInt())
}

// Outfit — variable weight axis mapped per FontWeight so all text styles get
// real bold/semibold cuts.
private val GeometricFamily = FontFamily(
    Font(R.font.outfit, FontWeight.Normal),
    Font(R.font.outfit, FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.outfit, FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.outfit, FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

// Fraunces — display sizes want a large optical size axis; the SOFT/WONK
// axes keep their defaults (soft edges, no wonk).
private val SerifDisplayFamily = FontFamily(
    Font(R.font.fraunces, FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400), FontVariation.Setting("opsz", 44f))),
    Font(R.font.fraunces, FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500), FontVariation.Setting("opsz", 44f))),
    Font(R.font.fraunces, FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600), FontVariation.Setting("opsz", 44f))),
    Font(R.font.fraunces, FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700), FontVariation.Setting("opsz", 44f))),
)

private fun TextStyle.withFamily(family: FontFamily) = copy(fontFamily = family)

private fun typographyFor(font: SkinFont): Typography {
    val base = Typography()
    return when (font) {
        SkinFont.DEFAULT -> base
        SkinFont.GEOMETRIC -> Typography(
            displayLarge = base.displayLarge.withFamily(GeometricFamily),
            displayMedium = base.displayMedium.withFamily(GeometricFamily),
            displaySmall = base.displaySmall.withFamily(GeometricFamily),
            headlineLarge = base.headlineLarge.withFamily(GeometricFamily),
            headlineMedium = base.headlineMedium.withFamily(GeometricFamily),
            headlineSmall = base.headlineSmall.withFamily(GeometricFamily),
            titleLarge = base.titleLarge.withFamily(GeometricFamily),
            titleMedium = base.titleMedium.withFamily(GeometricFamily),
            titleSmall = base.titleSmall.withFamily(GeometricFamily),
            bodyLarge = base.bodyLarge.withFamily(GeometricFamily),
            bodyMedium = base.bodyMedium.withFamily(GeometricFamily),
            bodySmall = base.bodySmall.withFamily(GeometricFamily),
            labelLarge = base.labelLarge.withFamily(GeometricFamily),
            labelMedium = base.labelMedium.withFamily(GeometricFamily),
            labelSmall = base.labelSmall.withFamily(GeometricFamily),
        )
        SkinFont.SERIF_DISPLAY -> Typography(
            displayLarge = base.displayLarge.withFamily(SerifDisplayFamily),
            displayMedium = base.displayMedium.withFamily(SerifDisplayFamily),
            displaySmall = base.displaySmall.withFamily(SerifDisplayFamily),
            headlineLarge = base.headlineLarge.withFamily(SerifDisplayFamily),
            headlineMedium = base.headlineMedium.withFamily(SerifDisplayFamily),
            headlineSmall = base.headlineSmall.withFamily(SerifDisplayFamily),
            titleLarge = base.titleLarge.withFamily(SerifDisplayFamily),
            titleMedium = base.titleMedium.withFamily(SerifDisplayFamily),
        )
    }
}

/**
 * Corner packs. SOFT closely reproduces the app's original ad-hoc radii
 * (8–24dp; a couple of large surfaces land 2dp off), PILL gives fully
 * rounded buttons and chips via shapes.small, SHARP flattens everything.
 */
private fun shapesFor(corners: SkinCorners): Shapes = when (corners) {
    SkinCorners.SOFT -> Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(14.dp),
        large = RoundedCornerShape(18.dp),
        extraLarge = RoundedCornerShape(24.dp),
    )
    SkinCorners.PILL -> Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(50),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )
    SkinCorners.SHARP -> Shapes(
        extraSmall = RoundedCornerShape(2.dp),
        small = RoundedCornerShape(4.dp),
        medium = RoundedCornerShape(6.dp),
        large = RoundedCornerShape(8.dp),
        extraLarge = RoundedCornerShape(10.dp),
    )
}

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
            secondaryContainer = accent.copy(alpha = 0.20f),
            onSecondaryContainer = lighten(accent, 0.85f),
            background = bg,
            onBackground = onBg,
            surface = lighten(bg, 0.04f),
            onSurface = onBg,
            surfaceVariant = lighten(bg, 0.07f),
            onSurfaceVariant = lighten(bg, 0.72f),
            surfaceContainer = lighten(bg, 0.04f),
            surfaceContainerLow = bg,
            surfaceContainerHigh = lighten(bg, 0.07f),
            surfaceContainerHighest = lighten(bg, 0.10f),
            outline = lighten(bg, 0.3f),
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = lighten(accent, 0.82f),
            onPrimaryContainer = darken(accent, 0.65f),
            secondary = darken(bg, 0.55f),
            secondaryContainer = accent.copy(alpha = 0.15f),
            onSecondaryContainer = darken(accent, 0.65f),
            background = bg,
            onBackground = onBg,
            surface = bg,
            onSurface = onBg,
            surfaceVariant = lerp(lighten(bg, 0.04f), accent, 0.06f),
            onSurfaceVariant = darken(bg, 0.62f),
            surfaceContainer = darken(bg, 0.03f),
            surfaceContainerLow = bg,
            surfaceContainerHigh = darken(bg, 0.06f),
            surfaceContainerHighest = darken(bg, 0.10f),
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
    val spec = remember(id, tick, container?.customThemes) {
        BUILTIN_THEMES.firstOrNull { it.id == id }
            ?: container?.customThemes
                ?.firstOrNull { it.name == id.removePrefix("custom:") }
                ?.let { customToSpec(it) }
            ?: BUILTIN_THEMES.first()
    }
    CompositionLocalProvider(LocalSkin provides spec) {
        MaterialTheme(
            colorScheme = schemeFor(spec, dark),
            typography = typographyFor(spec.font),
            shapes = shapesFor(spec.corners),
            content = content,
        )
    }
}

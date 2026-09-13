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
enum class SkinFont { DEFAULT, GEOMETRIC, SERIF_DISPLAY, SANS, MONO }

/**
 * Corner personality of a skin, delivered through MaterialTheme.shapes so
 * every component and clipped surface picks it up.
 */
enum class SkinCorners { SOFT, PILL, SHARP }

/**
 * Layout skin. STANDARD keeps the single shared composition; TURNTABLE swaps
 * the bottom navigation for a floating pill and renders Now Playing as a
 * spinning record; SPOTIFY adapts the nav, mini player, and now playing sheet;
 * APPLE adapts the floating mini player, ambient blur, and Cupertino controls.
 */
enum class SkinLayout { STANDARD, TURNTABLE, SPOTIFY, APPLE }

/**
 * A theme: accent + background colors for light and dark, plus optional skin
 * personality (type, corners, layout). Custom user themes also specify these.
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
    id = "custom:${e.name}",
    name = e.name,
    accentLight = e.accentLight,
    accentDark = e.accentDark,
    bgLight = e.bgLight,
    bgDark = e.bgDark,
    font = runCatching { SkinFont.valueOf(e.font) }.getOrDefault(SkinFont.DEFAULT),
    corners = runCatching { SkinCorners.valueOf(e.corners) }.getOrDefault(SkinCorners.SOFT),
    layout = runCatching { SkinLayout.valueOf(e.layout) }.getOrDefault(SkinLayout.STANDARD),
)

// Exactly 5 curated built-in presets with distinct identities:
// 1. Iris: signature Cadence modern brand violet
// 2. Spotify: streaming dark, geometric type, pill controls, Spotify layout
// 3. Apple Music: Cupertino crisp red, clean sans, soft corners, Apple layout
// 4. Analog: warm sienna/cream, Fraunces serif display, sharp corners, turntable vinyl
// 5. Studio: electric cyan, JetBrains Mono typography, sharp corners, hardware aesthetic
val BUILTIN_THEMES = listOf(
    ThemeSpec(
        id = "iris",
        name = "Iris",
        accentLight = 0xFF6B4EE8.toInt(),
        accentDark = 0xFF9D8BFF.toInt(),
        bgLight = 0xFFFAFAFC.toInt(),
        bgDark = 0xFF0E0E13.toInt(),
        font = SkinFont.DEFAULT,
        corners = SkinCorners.SOFT,
        layout = SkinLayout.STANDARD,
    ),
    ThemeSpec(
        id = "spotify",
        name = "Spotify",
        accentLight = 0xFF1DB954.toInt(),
        accentDark = 0xFF1ED760.toInt(),
        bgLight = 0xFFFFFFFF.toInt(),
        bgDark = 0xFF121212.toInt(),
        font = SkinFont.GEOMETRIC,
        corners = SkinCorners.PILL,
        layout = SkinLayout.SPOTIFY,
    ),
    ThemeSpec(
        id = "applemusic",
        name = "Apple Music",
        accentLight = 0xFFFA2D48.toInt(),
        accentDark = 0xFFFC5163.toInt(),
        bgLight = 0xFFFFFFFF.toInt(),
        bgDark = 0xFF000000.toInt(),
        font = SkinFont.SANS,
        corners = SkinCorners.SOFT,
        layout = SkinLayout.APPLE,
    ),
    ThemeSpec(
        id = "analog",
        name = "Analog",
        accentLight = 0xFFA8431C.toInt(),
        accentDark = 0xFFE5734A.toInt(),
        bgLight = 0xFFF5EFE5.toInt(),
        bgDark = 0xFF171310.toInt(),
        font = SkinFont.SERIF_DISPLAY,
        corners = SkinCorners.SHARP,
        layout = SkinLayout.TURNTABLE,
    ),
    ThemeSpec(
        id = "studio",
        name = "Studio",
        accentLight = 0xFF0099B8.toInt(),
        accentDark = 0xFF00E5FF.toInt(),
        bgLight = 0xFFF4F6F8.toInt(),
        bgDark = 0xFF0A0D10.toInt(),
        font = SkinFont.MONO,
        corners = SkinCorners.SHARP,
        layout = SkinLayout.STANDARD,
    ),
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

// Inter — clean, optical grotesque sans for Apple Music and modern UI
private val SansFamily = FontFamily(
    Font(R.font.inter, FontWeight.Normal),
    Font(R.font.inter, FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.inter, FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.inter, FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

// JetBrains Mono — precision monospace for Studio / synth interfaces
private val MonoFamily = FontFamily(
    Font(R.font.jetbrainsmono, FontWeight.Normal),
    Font(R.font.jetbrainsmono, FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.jetbrainsmono, FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.jetbrainsmono, FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

private fun TextStyle.withFamily(family: FontFamily) = copy(fontFamily = family)

private fun Typography.withAllFamily(family: FontFamily) = Typography(
    displayLarge = displayLarge.withFamily(family),
    displayMedium = displayMedium.withFamily(family),
    displaySmall = displaySmall.withFamily(family),
    headlineLarge = headlineLarge.withFamily(family),
    headlineMedium = headlineMedium.withFamily(family),
    headlineSmall = headlineSmall.withFamily(family),
    titleLarge = titleLarge.withFamily(family),
    titleMedium = titleMedium.withFamily(family),
    titleSmall = titleSmall.withFamily(family),
    bodyLarge = bodyLarge.withFamily(family),
    bodyMedium = bodyMedium.withFamily(family),
    bodySmall = bodySmall.withFamily(family),
    labelLarge = labelLarge.withFamily(family),
    labelMedium = labelMedium.withFamily(family),
    labelSmall = labelSmall.withFamily(family),
)

internal fun typographyFor(font: SkinFont): Typography {
    val base = Typography()
    return when (font) {
        SkinFont.DEFAULT -> base
        SkinFont.GEOMETRIC -> base.withAllFamily(GeometricFamily)
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
        SkinFont.SANS -> base.withAllFamily(SansFamily)
        SkinFont.MONO -> base.withAllFamily(MonoFamily)
    }
}

/**
 * Corner packs. SOFT closely reproduces the app's original ad-hoc radii
 * (8–24dp; a couple of large surfaces land 2dp off), PILL gives fully
 * rounded buttons and chips via shapes.small, SHARP flattens everything.
 */
internal fun shapesFor(corners: SkinCorners): Shapes = when (corners) {
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

internal fun schemeFor(spec: ThemeSpec, dark: Boolean): ColorScheme {
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

package dev.jtiisto.wellness.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

/**
 * The Graphite Signal theme.
 *
 * Two things are provided, and the split is the whole design contract:
 *
 * - a coherently populated M3 [ColorScheme], so every stock component that was
 *   never restyled still lands on a graphite surface with legible text;
 * - [LocalWellnessPalette] and [LocalModuleAccent], which every bespoke
 *   semantic callsite reads directly.
 *
 * The `primary` slot is monochrome on purpose. Four modules cannot share one
 * primary, so nothing about a module's identity is expressed through the M3
 * scheme — a component that wants the accent asks [LocalModuleAccent] for it.
 */
@Composable
fun WellnessTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    val colorScheme = remember(palette) { palette.toColorScheme() }
    CompositionLocalProvider(
        LocalWellnessPalette provides palette,
        LocalWellnessTypography provides WellnessType,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = WellnessM3Typography,
            shapes = WellnessM3Shapes,
            content = content,
        )
    }
}

/**
 * Token access from a composable: `WellnessTheme.palette.card`.
 *
 * Mirrors `MaterialTheme`'s object-beside-function shape so the two read alike
 * at a callsite that needs both.
 */
object WellnessTheme {
    val palette: WellnessPalette
        @Composable @ReadOnlyComposable get() = LocalWellnessPalette.current

    val type: WellnessTypography
        @Composable @ReadOnlyComposable get() = LocalWellnessTypography.current

    /** The current module's accent, already resolved against the theme. */
    val accent: AccentColors
        @Composable @ReadOnlyComposable get() =
            LocalModuleAccent.current.colors(LocalWellnessPalette.current)
}

/**
 * The M3 adapter.
 *
 * Every slot is filled — an unset one falls back to baseline Material purple,
 * which would show up as a violet cursor or a lilac ripple the first time an
 * un-restyled component was rendered. The surface-container ladder maps onto
 * the four-layer stack rather than onto tonal elevation, and `surfaceTint` is
 * cleared so an elevated `Surface` keeps the exact token colour it was given.
 */
private fun WellnessPalette.toColorScheme(): ColorScheme =
    (if (isDark) darkColorScheme() else lightColorScheme()).copy(
        // Monochrome: the accents live in LocalModuleAccent, never here.
        primary = textPrimary,
        onPrimary = if (isDark) inkOnAccent else Color.White,
        primaryContainer = band,
        onPrimaryContainer = textPrimary,
        inversePrimary = if (isDark) LightPalette.textPrimary else DarkPalette.textPrimary,
        secondary = mutedInk,
        onSecondary = if (isDark) inkOnAccent else Color.White,
        secondaryContainer = band,
        onSecondaryContainer = textPrimary,
        tertiary = mutedInk,
        onTertiary = if (isDark) inkOnAccent else Color.White,
        tertiaryContainer = band,
        onTertiaryContainer = textPrimary,
        background = canvas,
        onBackground = textPrimary,
        surface = canvas,
        onSurface = textPrimary,
        surfaceVariant = band,
        onSurfaceVariant = textSecondary,
        surfaceTint = Color.Transparent,
        // Snackbars invert: a light slab in the dark theme, graphite in the light one.
        inverseSurface = if (isDark) LightPalette.band else DarkPalette.card,
        inverseOnSurface = if (isDark) LightPalette.textPrimary else DarkPalette.textPrimary,
        error = error,
        onError = onSemanticFill,
        errorContainer = band,
        onErrorContainer = textPrimary,
        outline = line,
        outlineVariant = line,
        scrim = Color.Black,
        surfaceBright = band,
        surfaceDim = canvas,
        surfaceContainer = card,
        surfaceContainerHigh = band,
        surfaceContainerHighest = band,
        surfaceContainerLow = chrome,
        surfaceContainerLowest = canvas,
        // M3's "fixed" roles hold one value across both themes, which in a
        // hueless system means one graphite pair: the band as the container,
        // the line as its dimmed variant, and the dark theme's ink on top —
        // legible against both. Left at their defaults they would be the
        // baseline purple, one expressive component away from showing up.
        primaryFixed = DarkPalette.band,
        primaryFixedDim = DarkPalette.line,
        onPrimaryFixed = DarkPalette.textPrimary,
        onPrimaryFixedVariant = DarkPalette.textSecondary,
        secondaryFixed = DarkPalette.band,
        secondaryFixedDim = DarkPalette.line,
        onSecondaryFixed = DarkPalette.textPrimary,
        onSecondaryFixedVariant = DarkPalette.textSecondary,
        tertiaryFixed = DarkPalette.band,
        tertiaryFixedDim = DarkPalette.line,
        onTertiaryFixed = DarkPalette.textPrimary,
        onTertiaryFixedVariant = DarkPalette.textSecondary,
    )

/**
 * A solid stand-in for [WellnessPalette.textSecondary].
 *
 * The M3 `secondary`/`tertiary` slots can end up as a container fill, and an
 * alpha'd token over an unknown backdrop is not a colour you can predict.
 */
private val WellnessPalette.mutedInk: Color
    get() = if (isDark) Color(0xFFA8ABB0) else Color(0xFF66686B)

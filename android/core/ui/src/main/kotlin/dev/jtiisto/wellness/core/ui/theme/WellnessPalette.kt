package dev.jtiisto.wellness.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Graphite Signal's surface, text and semantic tokens.
 *
 * The chrome carries zero hue on purpose: the four module accents and the
 * semantic colours are the only colour on screen, so anything coloured is
 * saying something. Dark is the flagship and light derives from it by rule —
 * both are first-class, and neither uses Material dynamic colour.
 *
 * Read it through [LocalWellnessPalette]; the M3 `ColorScheme` built alongside
 * it in [WellnessTheme] exists only so stock components adapt, never as the
 * source of truth for a semantic decision.
 */
@Immutable
data class WellnessPalette(
    val isDark: Boolean,
    /** The page. Everything else sits on it. */
    val canvas: Color,
    /** Nav bar, headers, strips — the frame around the content. */
    val chrome: Color,
    /** The content plane: cards, rows, tiles. */
    val card: Color,
    /** The ONE chromatic surface: welded-card headers, pills at rest, selected-adjacent. */
    val band: Color,
    /** Hairline. Always drawn 1dp, never thicker. */
    val line: Color,
    /** Text fields and set-grid cells. */
    val input: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textFaint: Color,
    /** Text/glyph colour on a saturated accent fill. */
    val inkOnAccent: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    /**
     * Avoided-and-met: a real colour, never an alpha'd text token.
     *
     * A tracker you are trying to avoid has no good day to celebrate, and a
     * translucent grey over an unknown backdrop is not a colour you can trust
     * on a solid 8dp dot.
     */
    val avoided: Color,
    /** The sync dot at rest — offline/unknown, not an error. */
    val syncIdle: Color,
    /**
     * Ink for text sitting on a solid semantic fill.
     *
     * Derived rather than specced: the light theme's semantics are dark colours
     * (`#15803D` and friends), so [inkOnAccent] — which works because accents
     * keep their saturated base hue in both themes — would fail on them.
     */
    val onSemanticFill: Color,
)

// Declared ahead of the palettes: top-level properties initialise in file order,
// and a token read before its own initialiser runs is a black Color, not an error.
private val Ink = Color(0xFF0C0D10)
private val DarkInk = Color(0xFFF2F4F7)
private val LightInk = Color(0xFF171A1F)
private val Grey500 = Color(0xFF6B7280)

val DarkPalette = WellnessPalette(
    isDark = true,
    canvas = Color(0xFF0E0F12),
    chrome = Color(0xFF14171C),
    card = Color(0xFF1A1E25),
    band = Color(0xFF232936),
    line = Color(0xFF2A303C),
    input = Color(0xFF171B21),
    textPrimary = DarkInk,
    textSecondary = DarkInk.copy(alpha = 0.66f),
    textFaint = DarkInk.copy(alpha = 0.42f),
    inkOnAccent = Ink,
    success = Color(0xFF4ADE80),
    warning = Color(0xFFFBBF24),
    // Red-400 rather than red-500: #EF4444 measures 4.44:1 on `card` and
    // 3.87:1 on `band`, so the one semantic that has to be readable as words
    // ("Sync failed", "Delete") was the only token missing 4.5 in dark.
    error = Color(0xFFF87171),
    avoided = Grey500,
    syncIdle = Grey500,
    onSemanticFill = Ink,
)

val LightPalette = WellnessPalette(
    isDark = false,
    canvas = Color(0xFFFAFBFC),
    chrome = Color(0xFFF4F6F9),
    card = Color(0xFFFFFFFF),
    band = Color(0xFFE8EDF5),
    line = Color(0xFFD9DEE7),
    input = Color(0xFFFFFFFF),
    textPrimary = LightInk,
    textSecondary = LightInk.copy(alpha = 0.66f),
    // 42-45% falls under 3:1 on every light surface; 50% is the first step that clears it.
    textFaint = LightInk.copy(alpha = 0.50f),
    inkOnAccent = Ink,
    // A tier darker than the first draft: `band` is the darkest light surface,
    // and #15803D / #A16207 measured 4.27 and 4.19 on it.
    success = Color(0xFF166534),
    warning = Color(0xFF854D0E),
    error = Color(0xFFB91C1C),
    avoided = Grey500,
    syncIdle = Grey500,
    onSemanticFill = Color(0xFFFFFFFF),
)

/**
 * The four modules' accents, plus a neutral for Tools.
 *
 * One source of truth: the shell provides the enum at each tab's root and
 * components read it through [LocalModuleAccent]. No component hardcodes a
 * module colour, and the M3 `primary` slot is never repurposed to carry one —
 * it cannot mean four things at once.
 */
enum class ModuleAccent(val base: Color, val onLight: Color) {
    // The on-light variants are one Tailwind step darker than the amber and
    // teal the board used: both had to clear 4.5:1 as text on `band`, the
    // darkest light surface, and on a 15% wash of themselves.
    JOURNAL(base = Color(0xFFF59E0B), onLight = Color(0xFF92400E)),
    COACH(base = Color(0xFF2DD4BF), onLight = Color(0xFF115E59)),
    TRENDS(base = Color(0xFF38BDF8), onLight = Color(0xFF0369A1)),
    ANALYSIS(base = Color(0xFFA78BFA), onLight = Color(0xFF6D28D9)),

    /** Tools hosts the chart demo and needs an accent, but owns no data. */
    TOOLS(base = Color(0xFF94A3B8), onLight = Color(0xFF475569)),
}

/**
 * A module accent resolved against a theme.
 *
 * Fills and borders keep the base hue in both themes — a 15% amber wash reads
 * as amber on white as well as on graphite. The darker variant substitutes
 * only where the accent is text or a thin stroke on a light surface, which is
 * the one place the bright hue cannot hold contrast.
 */
@Immutable
data class AccentColors(
    /** Solid accent fill: primary buttons, selected day cells, checked boxes. */
    val fill: Color,
    /** The accent as text, an icon tint, or a 1-2dp stroke. */
    val text: Color,
    /** 15% — selection washes, chips, exposure pills. */
    val softFill: Color,
    /** 40% — the border that pairs with [softFill]. */
    val border: Color,
    /** 12% — label chips inside an already-tinted group. */
    val chipFill: Color,
    /** 6% — the superset group's background. */
    val wash: Color,
    /** What to write on [fill]. */
    val ink: Color,
)

fun ModuleAccent.colors(palette: WellnessPalette): AccentColors = AccentColors(
    fill = base,
    text = if (palette.isDark) base else onLight,
    softFill = base.copy(alpha = 0.15f),
    border = base.copy(alpha = 0.40f),
    chipFill = base.copy(alpha = 0.12f),
    wash = base.copy(alpha = 0.06f),
    ink = palette.inkOnAccent,
)

val LocalWellnessPalette = staticCompositionLocalOf { DarkPalette }

/** Provided at each tab's root; defaults to Journal, the start destination. */
val LocalModuleAccent = staticCompositionLocalOf { ModuleAccent.JOURNAL }

package dev.jtiisto.wellness.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Logbook's paper, ink and plate tokens.
 *
 * The system is a coach's paper logbook: one surface, ink on it, and colour
 * spent on exactly one thing. There are no semantic success/warning/error
 * tokens — a failed hook is an ink glyph and a mono label, not a red one — so
 * the only chromatic tokens here are the four [plates], and they mean *tier*
 * and nothing else.
 *
 * Read it through [LocalLogbookPalette]. The M3 `ColorScheme` built alongside
 * it in `LogbookTheme` exists so un-restyled stock components still land on
 * paper; it is never the source of truth for a design decision.
 *
 * Every value is measured rather than eyeballed — `LogbookPaletteTest` holds
 * the contract, and the two values the design board could not ship as drawn
 * (light [plateYellow], dark [plateBlue]) are pinned there.
 */
@Immutable
data class LogbookPalette(
    val isDark: Boolean,
    /** The page. The only surface in the system — nothing nests on top of it. */
    val paper: Color,
    /** Primary text, tally marks, section underlines, drawn brackets. */
    val ink: Color,
    /** Secondary text: schemes, meta labels, eyebrows. */
    val inkSoft: Color,
    /**
     * Ghost values, empty states, table headers, unfilled marks.
     *
     * A **documented WCAG exemption**: ghosts measure ≈2.3:1 on paper, under
     * the 3:1 graphical floor, because "not yet yours" has to recede — a ghost
     * that reads as strongly as a logged value is a lie about what is in the
     * log. The palette test asserts a 2:1 floor and the ramp's ordering
     * instead, so the exemption stays deliberate and bounded rather than drifting.
     */
    val inkFaint: Color,
    /** Hairline between rows. Always drawn 1dp. */
    val rule: Color,
    /** Group boundaries, marginalia rails, popup borders. Also 1dp. */
    val ruleStrong: Color,
    val plateRed: Color,
    val plateBlue: Color,
    val plateYellow: Color,
    val plateGreen: Color,
) {
    /**
     * The plates in assignment order: red → blue → yellow → green.
     *
     * Assignment is **positional, never semantic**: each distinct `exposure`
     * string in a workout takes the next colour in this order by first
     * appearance, and a 5th distinct exposure falls back to a solid [ink] dot
     * rather than repeating one. Nothing may reorder this list — the legend
     * that makes the dots readable walks it in the same order.
     *
     * Built once at construction so the identity is stable across
     * recompositions, and never handed out as anything mutable — which is the
     * whole content of the class's `@Immutable` promise for this property.
     */
    val plates: List<Color> = listOf(plateRed, plateBlue, plateYellow, plateGreen)
}

val LogbookLight = LogbookPalette(
    isDark = false,
    paper = Color(0xFFFBFAF7),
    ink = Color(0xFF17191B),
    // The board's #71757B measures 4.44:1 on paper — a near miss that would
    // have shipped every scheme and meta label just under the text floor.
    inkSoft = Color(0xFF6A6D73),
    inkFaint = Color(0xFFA6A9AD),
    rule = Color(0xFFE7E5DE),
    ruleStrong = Color(0xFFC7C5BB),
    plateRed = Color(0xFFB92D3A),
    plateBlue = Color(0xFF2A5FA8),
    // The board's #C99A2A is a dark-paper yellow: 2.47:1 here, well under the
    // 3:1 dot floor. Darkened within the same hue family to 3.61:1.
    plateYellow = Color(0xFFA87C1F),
    plateGreen = Color(0xFF2E7D4F),
)

val LogbookDark = LogbookPalette(
    isDark = true,
    paper = Color(0xFF141517),
    ink = Color(0xFFEDECE7),
    // Dark needs its own soft/faint pair rather than the light values: reused
    // verbatim, #71757B misses 4.5:1 here too (3.94) and #A6A9AD measures
    // 7.74 — brighter than the soft tier it is supposed to sit under, which
    // inverts the ramp. Both are re-cut so ink > inkSoft > inkFaint holds.
    inkSoft = Color(0xFF7D8288),
    // 2.56:1 — the same "recede" intent as light's 2.26, lifted slightly
    // because a low-contrast mark on a dark ground loses more to halation.
    inkFaint = Color(0xFF55585D),
    rule = Color(0xFF2A2C2F),
    ruleStrong = Color(0xFF3A3C40),
    plateRed = Color(0xFFB92D3A),
    // The board's #2A5FA8 measures 2.87:1 on dark paper. Lightened along its
    // own hue to 3.44:1, landing in the same band as the other three dark
    // plates rather than scraping the floor.
    plateBlue = Color(0xFF2F6BBC),
    plateYellow = Color(0xFFC99A2A),
    plateGreen = Color(0xFF2E7D4F),
)

/** Defaults to light — the metaphor is paper; `LogbookTheme` always provides the real one. */
val LocalLogbookPalette = staticCompositionLocalOf { LogbookLight }

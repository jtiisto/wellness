package dev.jtiisto.wellness.core.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.pow

/**
 * Logbook's contrast contract, computed rather than eyeballed.
 *
 * The design board was drawn on a good monitor in a bright room; these
 * assertions are the part of it that survives contact with a phone at night.
 * Three of the board's values did not — light `plateYellow`, dark `plateBlue`
 * and the `inkSoft` both modes were meant to share — and each one is kept here
 * as a failing counter-example next to the value that replaced it, so the next
 * person to "restore the original" measures it first.
 *
 * Every colour is composited **per sRGB channel against its actual backdrop
 * first** and only then linearised. No Logbook token carries an alpha today,
 * but scaling luminance by alpha instead is the classic way to compute a
 * passing number for a combination that fails on screen, so the maths is
 * written the way it has to work if one ever does.
 */
class LogbookPaletteTest {

    private val light = LogbookLight
    private val dark = LogbookDark

    private val palettes = listOf(light, dark)

    private fun LogbookPalette.name() = if (isDark) "dark" else "light"

    private fun LogbookPalette.tokens(): List<Pair<String, Color>> = listOf(
        "paper" to paper,
        "ink" to ink,
        "inkSoft" to inkSoft,
        "inkFaint" to inkFaint,
        "rule" to rule,
        "ruleStrong" to ruleStrong,
        "plateRed" to plateRed,
        "plateBlue" to plateBlue,
        "plateYellow" to plateYellow,
        "plateGreen" to plateGreen,
    )

    private fun LogbookPalette.namedPlates(): List<Pair<String, Color>> = listOf(
        "plateRed" to plateRed,
        "plateBlue" to plateBlue,
        "plateYellow" to plateYellow,
        "plateGreen" to plateGreen,
    )

    // ---- ink ------------------------------------------------------------

    @Test
    @DisplayName("ink and inkSoft clear 4.5:1 on paper, both modes")
    fun textInkIsReadable() {
        for (palette in palettes) {
            assertContrast(palette.ink, palette.paper, 4.5, "${palette.name()} ink on paper")
            assertContrast(palette.inkSoft, palette.paper, 4.5, "${palette.name()} inkSoft on paper")
        }
    }

    /**
     * The one deliberate WCAG shortfall in the system.
     *
     * Ghost values, unfilled tally marks and table headers are *supposed* to
     * recede — a ghost that reads as strongly as a logged value misstates what
     * is actually in the log — so `inkFaint` sits at ≈2.3:1 (light) and
     * ≈2.6:1 (dark), under the 3:1 graphical floor and far under the 4.5:1
     * text floor. The exemption is bounded by this 2:1 assertion and by
     * [inkRampDescends] rather than being open-ended: it may stay quiet, it may
     * not disappear, and it may never outrank the tier above it.
     */
    @Test
    @DisplayName("inkFaint clears its documented 2:1 ghost floor on paper, both modes")
    fun inkFaintClearsTheGhostFloor() {
        for (palette in palettes) {
            assertContrast(palette.inkFaint, palette.paper, 2.0, "${palette.name()} inkFaint on paper")
        }
    }

    /**
     * The ordering the dark theme lost when it tried to reuse the light values
     * verbatim: `#A6A9AD` measures 7.74:1 on dark paper against `#71757B`'s
     * 3.94:1, so the faint tier would have read *stronger* than the soft one
     * and every ghost would have shouted over the label next to it.
     */
    @Test
    @DisplayName("the ink ramp descends: ink > inkSoft > inkFaint on paper, both modes")
    fun inkRampDescends() {
        for (palette in palettes) {
            val ink = contrast(palette.ink, palette.paper)
            val soft = contrast(palette.inkSoft, palette.paper)
            val faint = contrast(palette.inkFaint, palette.paper)
            assertTrue(
                ink > soft && soft > faint,
                "${palette.name()} ramp is not descending: ink %.2f, inkSoft %.2f, inkFaint %.2f"
                    .format(ink, soft, faint),
            )
        }
    }

    @Test
    @DisplayName("inkSoft is cut per mode: one shared value misses 4.5:1 in both")
    fun inkSoftIsCutPerMode() {
        // The board specified #71757B for light and "light value holds" for
        // dark. It holds in neither.
        val shared = Color(0xFF71757B)
        for (palette in palettes) {
            val measured = contrast(shared, palette.paper)
            assertTrue(
                measured < 4.5,
                "the shared inkSoft measures %.2f:1 on ${palette.name()} paper — kept only as the counter-example"
                    .format(measured),
            )
        }
    }

    // ---- plates ---------------------------------------------------------

    @Test
    @DisplayName("every plate clears 3:1 on its own paper — dots are graphics, not text")
    fun platesClearTheGraphicalFloor() {
        for (palette in palettes) {
            for ((name, plate) in palette.namedPlates()) {
                assertContrast(plate, palette.paper, 3.0, "${palette.name()} $name dot on paper")
            }
        }
    }

    /**
     * The two values the board could not ship as drawn, pinned so a later
     * "restore the design doc's colour" is a test failure rather than a
     * silently unreadable dot.
     */
    @Test
    @DisplayName("the two re-cut plate values are pinned, and the board's originals fail")
    fun recutPlatesArePinned() {
        assertEquals(Color(0xFFA87C1F), light.plateYellow, "light plateYellow")
        assertEquals(Color(0xFF2F6BBC), dark.plateBlue, "dark plateBlue")

        val boardYellow = Color(0xFFC99A2A)
        val boardBlue = Color(0xFF2A5FA8)
        assertTrue(
            contrast(boardYellow, light.paper) < 3.0,
            "the board's light plateYellow measures %.2f:1 — kept only as the counter-example"
                .format(contrast(boardYellow, light.paper)),
        )
        assertTrue(
            contrast(boardBlue, dark.paper) < 3.0,
            "the board's dark plateBlue measures %.2f:1 — kept only as the counter-example"
                .format(contrast(boardBlue, dark.paper)),
        )
    }

    /**
     * The rest of the measured cuts, pinned like the two plates above: dark
     * `plateRed` passes its floor with 0.045 to spare (the thinnest margin in
     * the palette — pinned, not headroom), and the four per-mode soft/faint
     * values are what keeps the ramp honest in each mode. The light `inkFaint`
     * is the one board value in this set that survived measurement; it is
     * pinned so the exemption stays anchored to the number that was measured.
     */
    @Test
    @DisplayName("every measured token cut is pinned, not just the plates")
    fun measuredTokenCutsArePinned() {
        assertEquals(Color(0xFFB92D3A), dark.plateRed, "dark plateRed")
        assertEquals(Color(0xFF6A6D73), light.inkSoft, "light inkSoft")
        assertEquals(Color(0xFF7D8288), dark.inkSoft, "dark inkSoft")
        assertEquals(Color(0xFFA6A9AD), light.inkFaint, "light inkFaint")
        assertEquals(Color(0xFF55585D), dark.inkFaint, "dark inkFaint")
    }

    /**
     * Plate assignment is positional — the *n*th distinct exposure in a workout
     * takes the *n*th colour — so this order is not a preference, it is the
     * thing that makes a dot mean the same as the legend line beside it.
     */
    @Test
    @DisplayName("plates list is exactly red, blue, yellow, green")
    fun platesAreOrdered() {
        for (palette in palettes) {
            assertEquals(4, palette.plates.size, "${palette.name()} plate count")
            assertEquals(
                listOf(palette.plateRed, palette.plateBlue, palette.plateYellow, palette.plateGreen),
                palette.plates,
                "${palette.name()} plate order",
            )
        }
    }

    // ---- the tokens themselves ------------------------------------------

    @Test
    @DisplayName("every token is fully opaque: paper is a backdrop and ink is ink")
    fun tokensAreOpaque() {
        for (palette in palettes) {
            for ((name, token) in palette.tokens()) {
                assertEquals(1f, token.alpha, "${palette.name()} $name is translucent")
            }
        }
    }

    // ---- helpers --------------------------------------------------------

    private fun assertContrast(foreground: Color, background: Color, minimum: Double, what: String) {
        val measured = contrast(foreground, background)
        assertTrue(
            measured >= minimum,
            "$what measured %.2f:1, needs %.1f:1".format(measured, minimum),
        )
    }

    /** WCAG 2.x contrast, with [foreground] composited over an opaque [background]. */
    private fun contrast(foreground: Color, background: Color): Double {
        val fg = luminance(flatten(foreground, background))
        val bg = luminance(background)
        val lighter = maxOf(fg, bg)
        val darker = minOf(fg, bg)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /** Alpha compositing, per channel, in sRGB — before any linearisation. */
    private fun flatten(foreground: Color, background: Color): Color {
        val a = foreground.alpha
        return Color(
            red = foreground.red * a + background.red * (1 - a),
            green = foreground.green * a + background.green * (1 - a),
            blue = foreground.blue * a + background.blue * (1 - a),
        )
    }

    private fun luminance(color: Color): Double =
        0.2126 * linearise(color.red) + 0.7152 * linearise(color.green) + 0.0722 * linearise(color.blue)

    private fun linearise(channel: Float): Double {
        val c = channel.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
}

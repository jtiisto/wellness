package dev.jtiisto.wellness.core.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.pow

/**
 * The live-signal exception's contrast contract, computed rather than eyeballed.
 *
 * These four colours are what survived `WellnessPaletteTest` when Graphite
 * Signal retired: the semantic tokens the sync dot and the HR tone dot still
 * need. The rest of that test retired with the palette it measured, but these
 * assertions had to be re-taken rather than merely moved — the tokens were
 * measured against Graphite's five surfaces and now sit on Logbook paper, which
 * is a different backdrop in both modes.
 *
 * The 4.5:1 assertions did **not** come across, and that is deliberate: under
 * Graphite these colours were also drawn as words ("Sync failed" in error red).
 * On paper they are only ever dots — the words beside them are ink — so the
 * graphical 3:1 floor is the whole contract, and asserting a text floor would
 * be pinning a use that no longer exists.
 */
class LiveSignalColorsTest {

    private val papers = listOf(
        "light paper" to LogbookLight.paper,
        "dark paper" to LogbookDark.paper,
    )

    private fun LiveSignalColors.all(): Map<String, Color> =
        mapOf("live" to live, "waiting" to waiting, "attention" to attention, "idle" to idle)

    @Test
    @DisplayName("every live-signal dot clears 3:1 on the paper it is drawn on")
    fun dotsClearTheGraphicalFloor() {
        for ((mode, colors) in listOf("light" to LiveSignalLight, "dark" to LiveSignalDark)) {
            val paper = if (mode == "dark") LogbookDark.paper else LogbookLight.paper
            for ((name, color) in colors.all()) {
                assertContrast(color, paper, 3.0, "$mode $name dot on paper")
            }
        }
    }

    @Test
    @DisplayName("each mode's colours are cut for its own paper, and would not survive the other's")
    fun eachModeIsCutForItsOwnPaper() {
        // The reason there are two sets rather than one: the light inks are
        // dark greens and reds, and reading them on dark paper is the failure
        // the pairing exists to prevent. Kept as the counter-example, so a
        // future "why not one set?" has a number attached.
        val measured = contrast(LiveSignalLight.live, LogbookDark.paper)
        assertTrue(
            measured < 3.0,
            "light `live` measures %.2f:1 on dark paper — kept only as the counter-example".format(measured),
        )
    }

    @Test
    @DisplayName("the four tones are four distinct colours in both modes")
    fun tonesAreDistinct() {
        // Distinctness is asserted as inequality, not as a contrast ratio
        // between them: green and amber differ by hue and barely at all by
        // luminance, which is exactly why colour is never the only channel —
        // both consumers also spell the state into their content description.
        for (colors in listOf(LiveSignalLight, LiveSignalDark)) {
            val values = colors.all().values.toList()
            assertEquals(values.size, values.toSet().size, "two tones share a colour: $colors")
        }
    }

    @Test
    @DisplayName("every live-signal colour is opaque: a dot has no surface behind it to blend with")
    fun colorsAreOpaque() {
        for (colors in listOf(LiveSignalLight, LiveSignalDark)) {
            for ((name, color) in colors.all()) {
                assertEquals(1f, color.alpha, "$name is translucent")
            }
        }
    }

    @Test
    @DisplayName("idle is one grey across both modes — nothing to report is not a mode-dependent state")
    fun idleIsShared() {
        assertEquals(LiveSignalLight.idle, LiveSignalDark.idle)
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

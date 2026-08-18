package dev.jtiisto.wellness.core.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.pow

/**
 * The palette's contrast contract, computed rather than eyeballed.
 *
 * A design board is reviewed on a good monitor in a bright room; these
 * assertions are the part of it that survives contact with a phone at night.
 * Every alpha'd token is composited **per sRGB channel against its actual
 * backdrop first**, and only then linearised — averaging or scaling luminance
 * by alpha instead is the classic way to compute a passing number for a
 * combination that fails on screen.
 */
class WellnessPaletteTest {

    private val dark = DarkPalette
    private val light = LightPalette

    private fun WellnessPalette.surfaces(): List<Pair<String, Color>> = listOf(
        "canvas" to canvas,
        "chrome" to chrome,
        "card" to card,
        "band" to band,
        "input" to input,
    )

    // ---- body text ------------------------------------------------------

    @Test
    @DisplayName("textPrimary and textSecondary clear 4.5:1 on every surface, both themes")
    fun bodyTextIsReadable() {
        for (palette in listOf(dark, light)) {
            for ((name, surface) in palette.surfaces()) {
                assertContrast(palette.textPrimary, surface, 4.5, "textPrimary on $name")
                assertContrast(palette.textSecondary, surface, 4.5, "textSecondary on $name")
            }
        }
    }

    @Test
    @DisplayName("textFaint clears 3:1 on every surface, both themes")
    fun faintTextClearsTheGraphicalFloor() {
        for (palette in listOf(dark, light)) {
            for ((name, surface) in palette.surfaces()) {
                assertContrast(palette.textFaint, surface, 3.0, "textFaint on $name")
            }
        }
    }

    @Test
    @DisplayName("light textFaint sits at 50%: the 42% dark value fails 3:1 on light surfaces")
    fun lightFaintNeedsTheHeavierAlpha() {
        // Colour packs to 8 bits per channel, so the alphas round on the way in.
        assertEquals(0.42f, dark.textFaint.alpha, ALPHA_TOLERANCE)
        assertEquals(0.50f, light.textFaint.alpha, ALPHA_TOLERANCE)
        // The value the dark theme uses, applied to light ink: this is the
        // measurement that forced the two themes apart.
        val tooFaint = light.textPrimary.copy(alpha = 0.42f)
        assertTrue(
            contrast(tooFaint, light.card) < 3.0,
            "42% light ink measures ${contrast(tooFaint, light.card)} on card — kept only as the counter-example",
        )
    }

    // ---- accents --------------------------------------------------------

    @Test
    @DisplayName("accent-as-text clears 4.5:1 on every surface, both themes")
    fun accentTextIsReadable() {
        for (palette in listOf(dark, light)) {
            for (module in ModuleAccent.entries) {
                val accent = module.colors(palette)
                for ((name, surface) in palette.surfaces()) {
                    assertContrast(accent.text, surface, 4.5, "$module accent text on $name")
                }
            }
        }
    }

    /**
     * Accent text on a wash of its own accent — chips, selected days, exposure
     * pills — measured against what is actually behind the wash.
     *
     * `band` is absent from the list on purpose, and not as a concession: no
     * soft fill is ever drawn on the band tier. Chips sit on `card`, the date
     * strip's cells on `chrome`, form chips on the sheet's `card`. If a soft
     * fill ever lands on a band, this list is the thing to extend — and dark
     * violet then measures 4.18, so it would need a real answer first.
     */
    @Test
    @DisplayName("accent-as-text on its own soft fill clears 4.5:1 wherever soft fills are drawn")
    fun accentTextOnSoftFillIsReadable() {
        for (palette in listOf(dark, light)) {
            for (module in ModuleAccent.entries) {
                val accent = module.colors(palette)
                for ((name, surface) in palette.surfaces().filterNot { it.first == "band" }) {
                    val soft = flatten(accent.softFill, surface)
                    assertContrast(accent.text, soft, 4.5, "$module accent text on softFill over $name")
                    assertContrast(palette.textPrimary, soft, 4.5, "textPrimary on $module softFill over $name")
                }
            }
        }
    }

    @Test
    @DisplayName("inkOnAccent clears 4.5:1 on every accent fill")
    fun inkOnAccentIsReadable() {
        for (palette in listOf(dark, light)) {
            for (module in ModuleAccent.entries) {
                val accent = module.colors(palette)
                assertContrast(accent.ink, accent.fill, 4.5, "inkOnAccent on $module")
            }
        }
    }

    // ---- semantics ------------------------------------------------------

    @Test
    @DisplayName("onSemanticFill clears 4.5:1 on success, warning and error fills")
    fun semanticFillsCarryTheirInk() {
        for (palette in listOf(dark, light)) {
            val theme = if (palette.isDark) "dark" else "light"
            assertContrast(palette.onSemanticFill, palette.success, 4.5, "$theme success fill")
            assertContrast(palette.onSemanticFill, palette.warning, 4.5, "$theme warning fill")
            assertContrast(palette.onSemanticFill, palette.error, 4.5, "$theme error fill")
        }
    }

    @Test
    @DisplayName("success, warning and error clear 4.5:1 as text on every surface")
    fun semanticTextIsReadable() {
        for (palette in listOf(dark, light)) {
            val theme = if (palette.isDark) "dark" else "light"
            for ((markName, mark) in mapOf(
                "success" to palette.success,
                "warning" to palette.warning,
                "error" to palette.error,
            )) {
                for ((name, surface) in palette.surfaces()) {
                    assertContrast(mark, surface, 4.5, "$theme $markName text on $name")
                }
            }
        }
    }

    @Test
    @DisplayName("semantic dots and rails clear 3:1 on every surface — they are graphics, not text")
    fun semanticMarksAreDistinguishable() {
        for (palette in listOf(dark, light)) {
            val marks = mapOf(
                "success" to palette.success,
                "warning" to palette.warning,
                "error" to palette.error,
                "syncIdle" to palette.syncIdle,
            )
            for ((markName, mark) in marks) {
                for ((name, surface) in palette.surfaces()) {
                    assertContrast(mark, surface, 3.0, "$markName dot on $name")
                }
            }
        }
    }

    // ---- the surfaces themselves ----------------------------------------

    @Test
    @DisplayName("every surface token is opaque: they are backdrops, and a backdrop cannot be translucent")
    fun surfacesAreOpaque() {
        for (palette in listOf(dark, light)) {
            for ((name, surface) in palette.surfaces()) {
                assertEquals(1f, surface.alpha, "$name is translucent")
            }
            assertEquals(1f, palette.line.alpha)
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

    private companion object {
        const val ALPHA_TOLERANCE = 0.005f
    }
}

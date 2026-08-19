package dev.jtiisto.wellness.feature.trends.ui

import dev.jtiisto.wellness.core.ui.theme.LogbookDark
import dev.jtiisto.wellness.core.ui.theme.LogbookLight
import dev.jtiisto.wellness.core.ui.theme.LogbookPalette
import dev.jtiisto.wellness.feature.trends.chart.ChartInk
import dev.jtiisto.wellness.feature.trends.chart.PlotTone
import dev.jtiisto.wellness.feature.trends.chart.SeriesInk
import dev.jtiisto.wellness.feature.trends.chart.seriesStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Ink tokens as real colours, and the guard that makes every chart readable.
 *
 * `ChartInkTest` holds what each series *means*; this holds what it comes out
 * as on paper — and, most importantly, the collision guard: two series on one
 * chart resolving to the same colour is the failure mode a per-chart palette
 * invites, and it is invisible in a diff.
 */
class PlotColorsTest {

    private val light = PlotColors(LogbookLight)
    private val dark = PlotColors(LogbookDark)
    private val palettes = listOf(LogbookLight, LogbookDark)

    // ---- the collision guard ----------------------------------------------

    @Test
    @DisplayName("no two series on one chart resolve to the same colour, in either mode")
    fun everyPlanIsDistinguishable() {
        // Walks every declared plan rather than the one chart that happened to
        // break: Cardio stacks STACK_0 under SECONDARY, so a resolution giving
        // SECONDARY the first plate would collapse its two segments into one
        // bar — and a plan added later could do the same anywhere.
        for (palette in palettes) {
            val colors = PlotColors(palette)
            for (chart in ChartInk.entries) {
                val tones = chart.series.keys.toList()
                val resolved = tones.map { colors.of(it, chart) }
                assertEquals(
                    tones.size,
                    resolved.toSet().size,
                    "${chart.name} on ${paletteName(palette)}: $tones -> $resolved",
                )
            }
        }
    }

    @Test
    @DisplayName("every plan's plates come from the palette's own four, in its own order")
    fun platesAreTheSharedTokens() {
        // Dark mode gets the same plate tokens, not a chart-specific fork: a
        // plate has to mean the same thing on a coach tier dot and a trends
        // series or it stops being one language.
        val plates = setOf(SeriesInk.PLATE_1, SeriesInk.PLATE_2, SeriesInk.PLATE_3)
        for (palette in palettes) {
            val colors = PlotColors(palette)
            for (chart in ChartInk.entries) {
                for ((tone, style) in chart.series) {
                    if (style.ink !in plates) continue
                    assertTrue(
                        colors.of(tone, chart) in palette.plates,
                        "${chart.name}.$tone on ${paletteName(palette)}",
                    )
                }
            }
        }
    }

    @Test
    @DisplayName("no judgment resolves to a plate colour, on any chart, in either mode")
    fun judgmentsAreNeverPlates() {
        val judgments = listOf(
            PlotTone.MET,
            PlotTone.PARTIAL,
            PlotTone.MISSED,
            PlotTone.IN_PROGRESS,
            PlotTone.WARN,
            PlotTone.MUTED,
        )
        for (palette in palettes) {
            val colors = PlotColors(palette)
            for (chart in listOf<ChartInk?>(null) + ChartInk.entries) {
                for (tone in judgments) {
                    assertTrue(
                        colors.of(tone, chart).copy(alpha = 1f) !in palette.plates,
                        "$tone on ${chart?.name ?: "default"} / ${paletteName(palette)}",
                    )
                }
            }
        }
    }

    // ---- the token table ---------------------------------------------------

    @Test
    @DisplayName("each ink token is the palette entry it names")
    fun tokensResolveToTheirPaletteEntry() {
        assertEquals(LogbookLight.ink, light.of(SeriesInk.INK))
        assertEquals(LogbookLight.inkSoft, light.of(SeriesInk.INK_SOFT))
        assertEquals(LogbookLight.inkFaint, light.of(SeriesInk.INK_FAINT))
        assertEquals(LogbookLight.rule, light.of(SeriesInk.RULE))
        assertEquals(LogbookLight.plates[0], light.of(SeriesInk.PLATE_1))
        assertEquals(LogbookLight.plates[1], light.of(SeriesInk.PLATE_2))
        assertEquals(LogbookLight.plates[2], light.of(SeriesInk.PLATE_3))
        assertEquals(LogbookDark.ink, dark.of(SeriesInk.INK))
        assertEquals(LogbookDark.plates[0], dark.of(SeriesInk.PLATE_1))
    }

    @Test
    @DisplayName("the week in progress is a 45% ink lid, not a colour")
    fun inProgressIsALid() {
        assertEquals(LogbookLight.ink.copy(alpha = 0.45f), light.of(PlotTone.IN_PROGRESS))
        assertEquals(0.45f, dark.of(PlotTone.IN_PROGRESS).alpha, 0.001f)
    }

    @Test
    @DisplayName("the adherence ramp lands on ink, ink-soft and rule")
    fun adherenceRampIsInk() {
        assertEquals(LogbookLight.ink, light.of(PlotTone.MET))
        assertEquals(LogbookLight.inkSoft, light.of(PlotTone.PARTIAL))
        assertEquals(LogbookLight.rule, light.of(PlotTone.MISSED))
        assertEquals(LogbookDark.ink, dark.of(PlotTone.MET))
        assertEquals(LogbookDark.rule, dark.of(PlotTone.MISSED))
    }

    @Test
    @DisplayName("a band is a rule wash with its own hairline edge to bound it")
    fun bandsAreWashes() {
        assertEquals(LogbookLight.rule, light.of(PlotTone.BAND))
        assertEquals(LogbookLight.ruleStrong, light.bandEdge)
        assertEquals(LogbookDark.ruleStrong, dark.bandEdge)
    }

    @Test
    @DisplayName("an open mark is filled with the page, so nothing shows through it")
    fun openMarksAreFilledWithPaper() {
        assertEquals(LogbookLight.paper, light.paper)
        assertEquals(LogbookDark.paper, dark.paper)
    }

    @Test
    @DisplayName("every tone resolves opaque, except the one lid that is deliberately not")
    fun everyToneResolves() {
        for (palette in palettes) {
            val colors = PlotColors(palette)
            for (chart in listOf<ChartInk?>(null) + ChartInk.entries) {
                for (tone in PlotTone.entries) {
                    val expected =
                        if (seriesStyle(tone, chart).ink == SeriesInk.INK_LID) 0.45f else 1f
                    assertEquals(
                        expected,
                        colors.of(tone, chart).alpha,
                        0.001f,
                        "$tone on ${chart?.name ?: "default"} / ${paletteName(palette)}",
                    )
                }
            }
        }
    }

    private fun paletteName(palette: LogbookPalette) = if (palette.isDark) "dark" else "light"
}

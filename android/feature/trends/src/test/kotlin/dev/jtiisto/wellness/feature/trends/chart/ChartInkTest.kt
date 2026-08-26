package dev.jtiisto.wellness.feature.trends.chart

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The per-chart tone plans, pinned.
 *
 * These are the design's own worked examples held mechanically: the spec names
 * what each chart's series are drawn in, and a plan that drifted from it would
 * otherwise only be caught by eye on a device.
 */
class ChartInkTest {

    // ---- the worked examples ----------------------------------------------

    @Test
    @DisplayName("progression: top set is ink, e1RM plate 1, RPE plate 2 — three real series, no mean")
    fun progressionAssignsPlatesPositionally() {
        // The case the global default cannot express: PRIMARY here is the
        // chart's subject rather than a rolling mean, so it is solid ink and the
        // two series added after it take plates in the order they arrived.
        val plan = ChartInk.PROGRESSION
        assertEquals(
            SeriesStyle(SeriesInk.INK, SeriesMark.LINE),
            seriesStyle(PlotTone.PRIMARY, plan),
        )
        assertEquals(
            SeriesStyle(SeriesInk.PLATE_1, SeriesMark.LINE),
            seriesStyle(PlotTone.ALT, plan),
        )
        assertEquals(
            SeriesStyle(SeriesInk.PLATE_2, SeriesMark.LINE),
            seriesStyle(PlotTone.SECONDARY, plan),
        )
    }

    @Test
    @DisplayName("body: DEXA takes plate 1 as rings, over ink-faint readings and their dashed mean")
    fun bodyRingsTheScans() {
        val plan = ChartInk.BODY
        assertEquals(SeriesStyle(SeriesInk.PLATE_1, SeriesMark.RING), seriesStyle(PlotTone.SCAN, plan))
        assertEquals(
            SeriesStyle(SeriesInk.INK_FAINT, SeriesMark.DOT),
            seriesStyle(PlotTone.VALUE, plan),
        )
        assertEquals(
            SeriesStyle(SeriesInk.INK_SOFT, SeriesMark.DASHED_LINE),
            seriesStyle(PlotTone.PRIMARY, plan),
        )
    }

    @Test
    @DisplayName("sleep: hours are ink-soft bars, and the score is THIS chart's first extra series")
    fun sleepScoreTakesTheFirstPlate() {
        // Positional per chart is the whole rule: the score is SECONDARY, which
        // the global default gives plate 2 because on Cardio it is the *second*
        // segment — here it is the first extra series, so it is plate 1.
        assertEquals(
            SeriesStyle(SeriesInk.INK_SOFT, SeriesMark.BAR),
            seriesStyle(PlotTone.PRIMARY, ChartInk.SLEEP),
        )
        assertEquals(
            SeriesStyle(SeriesInk.PLATE_1, SeriesMark.DOT),
            seriesStyle(PlotTone.SECONDARY, ChartInk.SLEEP),
        )
        assertEquals(SeriesInk.PLATE_2, seriesStyle(PlotTone.SECONDARY).ink)
    }

    @Test
    @DisplayName("sleep need: the same ink-soft bars, and a target line that must not read as a mean")
    fun sleepNeedIsBarsUnderASolidTarget() {
        // The bars are the sleep card's, unchanged — a night has to look the
        // same weight on both charts. The need is plate 1 as a SOLID line: it
        // is a computed target, and the dash every other PRIMARY/SECONDARY line
        // carries would say "rolling mean", which is exactly what it is not.
        assertEquals(
            SeriesStyle(SeriesInk.INK_SOFT, SeriesMark.BAR),
            seriesStyle(PlotTone.PRIMARY, ChartInk.SLEEP_NEED),
        )
        assertEquals(
            SeriesStyle(SeriesInk.PLATE_1, SeriesMark.LINE),
            seriesStyle(PlotTone.SECONDARY, ChartInk.SLEEP_NEED),
        )
        assertFalse(isDashedMark(seriesStyle(PlotTone.SECONDARY, ChartInk.SLEEP_NEED).mark))
        assertEquals(LegendSwatch.LINE, legendSwatch(PlotTone.SECONDARY, ChartInk.SLEEP_NEED))

        // The debt chart below it declares no plan, so its two series fall to
        // the default: a solid ink line, and the reset as an open ring.
        assertEquals(SeriesStyle(SeriesInk.INK, SeriesMark.LINE), seriesStyle(PlotTone.SCAN))
        assertEquals(SeriesStyle(SeriesInk.INK, SeriesMark.RING), seriesStyle(PlotTone.WARN))
    }

    @Test
    @DisplayName("the aerobic proxy plots raw sessions, so its PRIMARY is ink and solid, not a mean")
    fun aerobicSubjectIsInk() {
        assertEquals(
            SeriesStyle(SeriesInk.INK, SeriesMark.DOT),
            seriesStyle(PlotTone.PRIMARY, ChartInk.AEROBIC),
        )
    }

    @Test
    @DisplayName("usage draws a plain ink bar: one series, no legend, so no plate")
    fun usageSpendsNoColour() {
        // `usageCardModel` tags its one key BAR rather than STACK_0 for exactly
        // this reason — the card renders no legend, and a plate nobody can
        // decode is a colour identifying nothing.
        assertEquals(SeriesInk.INK, seriesStyle(PlotTone.BAR).ink)
    }

    // ---- the default -------------------------------------------------------

    @Test
    @DisplayName("by default the means are quiet and dashed, and the readings under them quieter still")
    fun defaultsAreTheMeanRules() {
        assertEquals(
            SeriesStyle(SeriesInk.INK_SOFT, SeriesMark.DASHED_LINE),
            defaultSeriesStyle(PlotTone.PRIMARY),
        )
        assertEquals(
            SeriesStyle(SeriesInk.INK_FAINT, SeriesMark.FAINT_DASHED_LINE),
            defaultSeriesStyle(PlotTone.ALT),
        )
        assertEquals(SeriesInk.INK_FAINT, defaultSeriesStyle(PlotTone.VALUE).ink)
    }

    @Test
    @DisplayName("a chart with no plan of its own takes the default for every tone")
    fun noPlanMeansDefault() {
        for (tone in PlotTone.entries) {
            assertEquals(defaultSeriesStyle(tone), seriesStyle(tone, chart = null), "$tone")
        }
    }

    @Test
    @DisplayName("a plan answers only what it declares, and defers on everything else")
    fun plansOverrideOnlyWhatTheyDeclare() {
        // Sleep declares no BAND, so a guide band on that card still reads as
        // the wash every other chart draws.
        assertEquals(defaultSeriesStyle(PlotTone.BAND), seriesStyle(PlotTone.BAND, ChartInk.SLEEP))
        assertEquals(defaultSeriesStyle(PlotTone.WARN), seriesStyle(PlotTone.WARN, ChartInk.SLEEP))
    }

    @Test
    @DisplayName("every tone has a style, so a new tone cannot appear unanswered")
    fun everyToneIsAnswered() {
        assertEquals(PlotTone.entries.size, PlotTone.entries.map { defaultSeriesStyle(it) }.size)
    }

    // ---- judgments ---------------------------------------------------------

    @Test
    @DisplayName("no judgment takes a plate, on any chart")
    fun judgmentsNeverTakeAPlate() {
        // The round's governing rule, asserted across every plan rather than
        // once against the default: met/partial/missed, a flagged reading, a
        // below-floor morning, an off-plan session, a paused week and the week
        // in progress are verdicts, and a verdict that took a hue would be the
        // app grading the user in colour.
        val plates = setOf(SeriesInk.PLATE_1, SeriesInk.PLATE_2, SeriesInk.PLATE_3)
        val judgments = listOf(
            PlotTone.MET,
            PlotTone.PARTIAL,
            PlotTone.MISSED,
            PlotTone.IN_PROGRESS,
            PlotTone.WARN,
            PlotTone.MUTED,
        )
        for (chart in listOf<ChartInk?>(null) + ChartInk.entries) {
            for (tone in judgments) {
                assertFalse(seriesStyle(tone, chart).ink in plates, "$tone on $chart")
            }
        }
    }

    @Test
    @DisplayName("the adherence ramp is sequential ink: met, then partial, then the base under both")
    fun adherenceIsASequentialRamp() {
        assertEquals(SeriesInk.INK, defaultSeriesStyle(PlotTone.MET).ink)
        assertEquals(SeriesInk.INK_SOFT, defaultSeriesStyle(PlotTone.PARTIAL).ink)
        assertEquals(SeriesInk.RULE, defaultSeriesStyle(PlotTone.MISSED).ink)
        assertEquals(SeriesInk.INK_LID, defaultSeriesStyle(PlotTone.IN_PROGRESS).ink)
    }

    // ---- marks and their keys ---------------------------------------------

    @Test
    @DisplayName("a swatch is the mark its series is actually drawn with, on that chart")
    fun swatchesFollowTheChartsOwnDrawing() {
        // Progression's top set is a solid line here because it is a solid line
        // there — the legend and the chart read the same plan, so they cannot
        // drift apart.
        assertEquals(LegendSwatch.LINE, legendSwatch(PlotTone.PRIMARY, ChartInk.PROGRESSION))
        assertEquals(LegendSwatch.DASH, legendSwatch(PlotTone.PRIMARY))
        assertEquals(LegendSwatch.FAINT_DASH, legendSwatch(PlotTone.ALT))
        assertEquals(LegendSwatch.LINE, legendSwatch(PlotTone.ALT, ChartInk.PROGRESSION))
        // On Body a scan is a ring; on a composition or lab strip — where a scan
        // series is the only thing drawn and its points are joined — it is the
        // line it is drawn as.
        assertEquals(LegendSwatch.OPEN_DOT, legendSwatch(PlotTone.SCAN, ChartInk.BODY))
        assertEquals(LegendSwatch.LINE, legendSwatch(PlotTone.SCAN))
        assertEquals(LegendSwatch.SQUARE, legendSwatch(PlotTone.PRIMARY, ChartInk.SLEEP))
        assertEquals(LegendSwatch.BAND, legendSwatch(PlotTone.BAND))
    }

    @Test
    @DisplayName("a flagged reading keys as the open dot it is drawn as")
    fun warnKeysAsAnOpenDot() {
        assertEquals(LegendSwatch.OPEN_DOT, legendSwatch(PlotTone.WARN))
        assertTrue(isOpenMark(seriesStyle(PlotTone.WARN).mark))
        assertTrue(isOpenMark(seriesStyle(PlotTone.SCAN, ChartInk.BODY).mark))
        assertFalse(isOpenMark(seriesStyle(PlotTone.SCAN).mark))
    }

    @Test
    @DisplayName("the muted entries are notes, and a note gets no key")
    fun mutedHasNoSwatch() {
        // MUTED's four uses are all absences — off-plan, paused, an interval
        // count, "no scans in range". A key for an absence is a key to nothing,
        // and the off-plan label draws its own ○ in the text.
        assertEquals(LegendSwatch.NONE, legendSwatch(PlotTone.MUTED))
    }

    @Test
    @DisplayName("only a derived line dashes")
    fun onlyMeansDash() {
        assertTrue(isDashedMark(seriesStyle(PlotTone.PRIMARY).mark))
        assertTrue(isDashedMark(seriesStyle(PlotTone.ALT).mark))
        assertFalse(isDashedMark(seriesStyle(PlotTone.PRIMARY, ChartInk.PROGRESSION).mark))
        assertFalse(isDashedMark(seriesStyle(PlotTone.SCAN).mark))
    }

    @Test
    @DisplayName("every mark keys to something, so a tenth mark cannot appear unkeyed")
    fun everyMarkHasASwatch() {
        assertEquals(SeriesMark.entries.size, SeriesMark.entries.map { mark -> mark.name }.size)
        for (tone in PlotTone.entries) {
            for (chart in listOf<ChartInk?>(null) + ChartInk.entries) {
                legendSwatch(tone, chart)
            }
        }
    }
}

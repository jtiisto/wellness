package dev.jtiisto.wellness.feature.trends.chart

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The screen rules the PWA inlines into its components.
 *
 * These are the decisions a chart makes before it is a chart: how big a delta
 * is, which slugs get a colour, which tests are worth plotting, and what to do
 * when the thing that was selected last time is gone.
 */
class TrendsScreenLogicTest {

    // ---- stat tile delta ---------------------------------------------------

    @Test
    @DisplayName("statTileDelta is the percentage change from the four-week average")
    fun statTileDeltaBasics() {
        assertEquals(25, statTileDelta(value = 125.0, avg = 100.0))
        assertEquals(-20, statTileDelta(value = 80.0, avg = 100.0))
        assertEquals(0, statTileDelta(value = 100.0, avg = 100.0))
    }

    @Test
    @DisplayName("statTileDelta breaks ties upward, the way JavaScript rounds")
    fun statTileDeltaTiesGoUp() {
        // Eighths, so these really are exact ties rather than floating-point
        // near-misses: 9/8 is +12.5% and 5/8 is −37.5%. Rounding half up toward
        // +infinity answers 13 and −37; `kotlin.math.round`'s ties-to-even would
        // answer 12 and −38, and both would be wrong on a tile someone reads.
        assertEquals(13, statTileDelta(value = 9.0, avg = 8.0))
        assertEquals(-37, statTileDelta(value = 5.0, avg = 8.0))
        assertEquals(-12, statTileDelta(value = 7.0, avg = 8.0))
        assertEquals(63, statTileDelta(value = 13.0, avg = 8.0))
    }

    @Test
    @DisplayName("statTileDelta is null without both a value and a usable average")
    fun statTileDeltaNulls() {
        assertNull(statTileDelta(value = null, avg = 100.0))
        assertNull(statTileDelta(value = 100.0, avg = null))
        // A zero average would divide the headline into infinity.
        assertNull(statTileDelta(value = 100.0, avg = 0.0))
    }

    // ---- volume stacks -----------------------------------------------------

    private val volumeWeeks = listOf(
        volumeWeek(
            "2026-07-06",
            byExercise = listOf(
                tonnage("fixture-press", "Fixture Press", 4000.0),
                tonnage("fixture-squat", "Fixture Squat", 3200.0),
                tonnage("fixture-row", "Fixture Row", 1500.0),
                tonnage("fixture-curl", "Fixture Curl", 500.0),
            ),
        ),
        volumeWeek(
            "2026-07-13",
            byExercise = listOf(
                tonnage("fixture-press", "Fixture Press", 4200.0),
                tonnage("fixture-squat", "Fixture Squat", 2800.0),
            ),
        ),
    )

    @Test
    @DisplayName("foldVolumeStacks keeps the top three slugs and folds the rest into 'other'")
    fun foldVolumeTopThree() {
        val stacks = foldVolumeStacks(volumeWeeks)

        assertEquals(
            listOf("fixture-press", "fixture-squat", "fixture-row", OTHER_KEY),
            stacks.keys,
        )
        // Ranking is over the WHOLE range, so a slug keeps its colour week to week.
        assertEquals(500.0, stacks.weeks[0].values[OTHER_KEY])
        assertEquals(0.0, stacks.weeks[1].values[OTHER_KEY])
        assertTrue(stacks.hasOther)
        assertEquals("Fixture Press", stacks.names["fixture-press"])
    }

    @Test
    @DisplayName("the 'other' legend appears only when something actually folded into it")
    fun foldVolumeWithoutOther() {
        val stacks = foldVolumeStacks(listOf(volumeWeeks[1]))

        assertFalse(stacks.hasOther)
        assertEquals(0.0, stacks.weeks.single().values[OTHER_KEY])
    }

    @Test
    @DisplayName("an empty range folds to nothing but still names the 'other' key")
    fun foldVolumeEmpty() {
        val stacks = foldVolumeStacks(emptyList())

        assertEquals(listOf(OTHER_KEY), stacks.keys)
        assertTrue(stacks.weeks.isEmpty())
        assertFalse(stacks.hasOther)
    }

    @Test
    @DisplayName("the volume axis collapses thousands into tonnes at one decimal")
    fun volumeAxisLabels() {
        assertEquals("999", volumeYLabel(999.0))
        assertEquals("1t", volumeYLabel(1000.0))
        assertEquals("9.3t", volumeYLabel(9250.0))
        assertEquals("12.5t", volumeYLabel(12_500.0))
    }

    // ---- pickers -----------------------------------------------------------

    @Test
    @DisplayName("pickerLabels suffixes a slug only where two entries share a display name")
    fun pickerLabelsDisambiguate() {
        val options = pickerLabels(
            listOf(
                NamedItem("fixture-press", "Fixture Press"),
                NamedItem("fixture-pulldown-assisted", "Fixture Pulldown"),
                NamedItem("fixture-pulldown-cable", "Fixture Pulldown"),
            ),
        )

        assertEquals(
            listOf(
                "Fixture Press",
                "Fixture Pulldown (fixture-pulldown-assisted)",
                "Fixture Pulldown (fixture-pulldown-cable)",
            ),
            options.map { it.label },
        )
        assertEquals("fixture-press", options.first().value)
    }

    @Test
    @DisplayName("the tracker picker names the unit instead, and tolerates a missing one")
    fun trackerPickerLabels() {
        val options = trackerPickerOptions(
            listOf(
                tracker(id = "fixture-a", name = "Fixture Water", unit = "ml"),
                tracker(id = "fixture-b", name = "Fixture Walk", unit = null),
                tracker(id = "fixture-c", name = "Fixture Empty", unit = ""),
            ),
        )

        assertEquals(listOf("Fixture Water (ml)", "Fixture Walk", "Fixture Empty"), options.map { it.label })
    }

    // ---- selection fallback -------------------------------------------------

    @Test
    @DisplayName("a remembered selection survives when it is still on offer")
    fun selectionKept() {
        assertEquals("b", resolveSelection("b", listOf("a", "b", "c")))
    }

    @Test
    @DisplayName("a stale selection falls back to the first item, silently")
    fun selectionFallsBack() {
        assertEquals("a", resolveSelection("gone", listOf("a", "b")))
        assertEquals("a", resolveSelection(null, listOf("a", "b")))
    }

    @Test
    @DisplayName("an empty list selects nothing rather than the id that is no longer there")
    fun selectionCleared() {
        assertNull(resolveSelection("gone", emptyList()))
        assertNull(resolveSelection(null, emptyList()))
    }

    // ---- constant series ---------------------------------------------------

    @Test
    @DisplayName("a series that never moves collapses to its value and a count")
    fun constantSeries() {
        assertEquals("the only entry in range", constantSeriesNote(listOf(5.0)))
        assertEquals("same value for all 4 entries in range", constantSeriesNote(List(4) { 5.0 }))
    }

    @Test
    @DisplayName("a series that moves at all is not collapsed")
    fun nonConstantSeries() {
        assertNull(constantSeriesNote(listOf(5.0, 5.0, 6.0)))
        assertNull(constantSeriesNote(emptyList()))
    }

    // ---- labs partition ----------------------------------------------------

    @Test
    @DisplayName("labsPartition charts a test with two numeric results and tabulates the rest")
    fun labsPartitionSplit() {
        val tests = listOf(
            labTest(
                "Fixture Test Alpha",
                "ng/mL",
                listOf(labObs("2026-01-14", value = 42.0), labObs("2026-07-11", value = 28.5)),
            ),
            labTest("Fixture Test Beta", null, listOf(labObs("2026-07-11", text = "Not Detected"))),
            labTest("Fixture Test Gamma", "%", listOf(labObs("2026-04-18", value = 5.2))),
        )

        val partition = labsPartition(tests)

        assertEquals(listOf("Fixture Test Alpha"), partition.chartable.map { it.name })
        assertEquals(listOf(42.0, 28.5), partition.chartable.single().obs.map { it.num })
        assertEquals(listOf("Fixture Test Beta", "Fixture Test Gamma"), partition.tabular.map { it.name })
        // Complete: every test lands on exactly one side.
        assertEquals(tests.size, partition.chartable.size + partition.tabular.size)
    }

    @Test
    @DisplayName("a test whose numbers are words is never chartable however many there are")
    fun labsPartitionTextOnly() {
        val partition = labsPartition(
            listOf(
                labTest(
                    "Fixture Test Delta",
                    null,
                    listOf(labObs("2026-01-14", text = "Negative"), labObs("2026-07-11", text = "Negative")),
                ),
            ),
        )

        assertTrue(partition.chartable.isEmpty())
        assertEquals(1, partition.tabular.size)
    }

    @Test
    @DisplayName("a tabular row reads its words, or its number with the report's own prefix")
    fun labRowText() {
        assertEquals("Not Detected", labValueText(labObs("2026-07-11", text = "Not Detected"), null))
        assertEquals("<5 mg/dL", labValueText(labObs("2026-07-11", value = 5.0, prefix = "<"), "mg/dL"))
        assertEquals("42 ng/mL", labValueText(labObs("2026-07-11", value = 42.0), "ng/mL"))
        assertEquals("— %", labValueText(labObs("2026-07-11"), "%"))
    }

    // ---- day-series scaffolding ----------------------------------------------

    @Test
    @DisplayName("dayChart measures the days that have a value, from the FULL array's origin")
    fun dayChartFrame() {
        val days = listOf(
            recoveryDay("2026-07-01"),
            recoveryDay("2026-07-03", hrv = 30.0),
            recoveryDay("2026-07-08", hrv = 34.0),
        )

        val frame = requireNotNull(dayChart(days, { it.date }, { it.hrv }))

        // The origin is the first day of the whole array, not the first present
        // one — every series on the card has to measure from the same zero.
        assertEquals("2026-07-01", frame.origin)
        assertEquals(2, frame.present.size)
        assertEquals(2.0, frame.xMin)
        assertEquals(7.0, frame.xMax)
    }

    @Test
    @DisplayName("dayChart is null when nothing is present, and when there are no days at all")
    fun dayChartEmpty() {
        assertNull(dayChart(emptyList<Nothing>(), { "" }, { null }))
        assertNull(dayChart(listOf(recoveryDay("2026-07-01")), { it.date }, { it.hrv }))
    }

    @Test
    @DisplayName("dateTicks spread across the series in day-index space")
    fun dateTicksSpread() {
        val days = (1..9).map { recoveryDay("2026-07-0$it", hrv = 30.0) }

        val ticks = dateTicks(days, { it.date }, "2026-07-01", n = 5)

        assertEquals(listOf(0.0, 2.0, 4.0, 6.0, 8.0), ticks.map { it.x })
        assertEquals(listOf("07-01", "07-03", "07-05", "07-07", "07-09"), ticks.map { it.label })
    }

    @Test
    @DisplayName("scan axes read year and month — a DEXA scan's day is noise")
    fun yearMonthTicks() {
        val scans = listOf(scan("2026-01-14"), scan("2026-07-11"))

        val ticks = dateTicks(scans, { it.date }, "2026-01-14", n = 4, format = ::yearMonth)

        assertEquals(listOf("26-01", "26-07"), ticks.map { it.label })
    }

    // ---- adherence layering --------------------------------------------------

    @Test
    @DisplayName("an ordinary week paints missed, then partial+met, then met on top")
    fun adherenceLayering() {
        val cells = ribbonCells(
            listOf(RibbonWeek("2026-07-06", scheduledDays = 4, met = 2, partialDays = 1, missed = 1, paused = false)),
            { 100.0 },
            20.0,
        )

        val rects = adherenceRects(cells, listOf(false), top = 8.0, height = 28.0)

        assertEquals(listOf(PlotTone.MISSED, PlotTone.PARTIAL, PlotTone.MET), rects.map { it.tone })
        // Each fill is drawn OVER the one it outranks, so its height is its own
        // share plus everything below it.
        assertEquals(8.0, rects[0].y)
        assertEquals(28.0, rects[0].h)
        assertEquals(8.0 + 28.0 * 0.25, rects[1].y)
        assertEquals(28.0 * 0.75, rects[1].h)
        assertEquals(8.0 + 28.0 * 0.5, rects[2].y)
        assertEquals(28.0 * 0.5, rects[2].h)
    }

    @Test
    @DisplayName("a paused week is muted, not missed — nothing was asked of it")
    fun adherenceMuted() {
        val cells = ribbonCells(
            listOf(RibbonWeek("2026-07-13", scheduledDays = 0, met = 0, partialDays = 0, missed = 0, paused = true)),
            { 100.0 },
            20.0,
        )

        val rects = adherenceRects(cells, listOf(false), top = 8.0, height = 28.0)

        assertEquals(listOf(PlotTone.MUTED), rects.map { it.tone })
    }

    @Test
    @DisplayName("the week in progress gets a lid over whatever it managed so far")
    fun adherenceInProgressOverlay() {
        val cells = ribbonCells(
            listOf(RibbonWeek("2026-07-20", scheduledDays = 3, met = 3, partialDays = 0, missed = 0, paused = false)),
            { 100.0 },
            20.0,
        )

        val rects = adherenceRects(cells, listOf(true), top = 8.0, height = 28.0)

        assertEquals(PlotTone.IN_PROGRESS, rects.last().tone)
        assertEquals(28.0, rects.last().h, "the overlay covers the whole cell")
    }

    @Test
    @DisplayName("a fully met week has no partial rect to draw")
    fun adherenceNoPartial() {
        val cells = ribbonCells(
            listOf(RibbonWeek("2026-07-06", scheduledDays = 2, met = 2, partialDays = 0, missed = 0, paused = false)),
            { 100.0 },
            20.0,
        )

        val rects = adherenceRects(cells, listOf(false), top = 8.0, height = 28.0)

        assertEquals(listOf(PlotTone.MISSED, PlotTone.PARTIAL, PlotTone.MET), rects.map { it.tone })
        assertEquals(28.0, rects[1].h, "partial+met is the whole cell when everything was met")
    }

    // ---- stale badge -----------------------------------------------------------

    @Test
    @DisplayName("the badge names the age of the OLDEST stale slice on screen")
    fun staleBadgeOldestWins() {
        val now = 10_000_000L

        assertEquals(
            "cached · 45m ago",
            staleBadgeText(listOf(now - 30 * 60_000, now - 45 * 60_000), now),
        )
        // Two hours is two hours even when a fresher slice sits beside it.
        assertEquals(
            "cached · 2h ago",
            staleBadgeText(listOf(now - 30 * 60_000, now - 120 * 60_000), now),
        )
    }

    @Test
    @DisplayName("the badge switches to hours past an hour, and never reads zero minutes")
    fun staleBadgeUnits() {
        val now = 10_000_000L

        assertEquals("cached · 1m ago", staleBadgeText(listOf(now - 1_000), now))
        assertEquals("cached · 59m ago", staleBadgeText(listOf(now - 59 * 60_000), now))
        assertEquals("cached · 1h ago", staleBadgeText(listOf(now - 60 * 60_000), now))
        assertEquals("cached · 3h ago", staleBadgeText(listOf(now - 190 * 60_000), now))
    }

    @Test
    @DisplayName("nothing stale, no badge")
    fun staleBadgeAbsent() {
        assertNull(staleBadgeText(emptyList(), 10_000_000L))
    }
}

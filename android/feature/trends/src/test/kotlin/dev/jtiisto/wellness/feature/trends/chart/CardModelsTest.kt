package dev.jtiisto.wellness.feature.trends.chart

import androidx.compose.ui.unit.dp
import dev.jtiisto.wellness.core.data.trends.CardioDto
import dev.jtiisto.wellness.core.data.trends.CardioWeek
import dev.jtiisto.wellness.core.data.trends.ExerciseDetailDto
import dev.jtiisto.wellness.core.data.trends.ExerciseInfo
import dev.jtiisto.wellness.core.data.trends.SessionPoint
import dev.jtiisto.wellness.core.data.trends.SteadySession
import dev.jtiisto.wellness.core.data.trends.TopSet
import dev.jtiisto.wellness.core.data.trends.UsageWeek
import dev.jtiisto.wellness.core.data.trends.WeightPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The fourteen card builders.
 *
 * Each card is a pure function from a payload to a [PlotModel], so what is
 * tested here is exactly what the PWA's components decide inline: which
 * domain, which padding, which dot is warned about, which row a tooltip shows.
 * The drawing on top of them has no decisions left to make.
 */
class CardModelsTest {

    // ---- weight ------------------------------------------------------------

    private fun weightSeries(vararg pairs: Pair<String, Double>) =
        pairs.map { WeightPoint(it.first, it.second) }

    @Test
    @DisplayName("no readings, no weight card")
    fun weightEmpty() {
        assertNull(weightCardModel(emptyList()))
    }

    @Test
    @DisplayName("every card but resting HR draws its dots over its lines")
    fun onlyRhrPutsDotsUnderneath() {
        val weight = requireNotNull(
            weightCardModel(weightSeries("2026-07-01" to 80.0, "2026-07-02" to 79.0)),
        )
        val hrv = requireNotNull(
            hrvCardModel(
                listOf(
                    recoveryDay("2026-07-01", hrv = 34.0, band = band(28.0, 42.0)),
                    recoveryDay("2026-07-02", hrv = 31.0, band = band(28.0, 42.0)),
                ),
            ),
        )

        assertFalse(weight.plot.dotsBelowLines)
        assertFalse(hrv.dotsBelowLines)
    }

    @Test
    @DisplayName("the weight card headlines the most recent reading")
    fun weightLatest() {
        val card = requireNotNull(
            weightCardModel(weightSeries("2026-07-01" to 80.0, "2026-07-05" to 79.6)),
        )

        assertEquals("79.6 kg", card.latest)
    }

    @Test
    @DisplayName("a flat series still gets half a kilo of padding, so it is not drawn on the axis")
    fun weightFlatSeriesPadding() {
        val card = requireNotNull(
            weightCardModel(
                weightSeries("2026-07-01" to 80.0, "2026-07-02" to 80.0, "2026-07-03" to 80.0),
            ),
        )

        // Padding ±0.5 around a constant puts every dot in the vertical middle
        // of the plot area; a proportional pad would have divided by zero.
        val middle = (200.0 - 22.0 + 10.0) / 2
        assertTrue(card.plot.dots.all { it.y == middle }, card.plot.dots.toString())
    }

    @Test
    @DisplayName("one anchor per day, carrying the reading and whichever means exist")
    fun weightAnchors() {
        val series = (1..30).map { WeightPoint("2026-07-%02d".format(it), 80.0 + it * 0.1) }

        val card = requireNotNull(weightCardModel(series))

        assertEquals(30, card.plot.anchors.size)
        assertEquals(series.map { it.date }, card.plot.anchors.map { it.key })
        // Day one has a reading and a one-day mean of itself, but no 28-day one
        // yet only in the sense that it equals the reading — both rows are real.
        assertEquals(listOf("kg", "7d", "28d"), card.plot.anchors.first().rows.map { it.label })
        assertEquals("07-01", card.plot.anchors.first().label)
    }

    @Test
    @DisplayName("both rolling means draw once there are two days of them")
    fun weightMeanLines() {
        val card = requireNotNull(
            weightCardModel(weightSeries("2026-07-01" to 80.0, "2026-07-02" to 79.0)),
        )

        assertEquals(listOf(PlotTone.ALT, PlotTone.PRIMARY), card.plot.lines.map { it.tone })
    }

    // ---- progression -------------------------------------------------------

    private fun session(
        date: String,
        weight: Double,
        reps: Int = 5,
        e1rm: Double = weight * 1.1,
        rpe: Double? = null,
        offPlan: Boolean = false,
    ) = SessionPoint(
        date = date,
        topSet = TopSet(weight = weight, reps = reps, assistance = null),
        e1rm = e1rm,
        topSetRpe = rpe,
        setCount = 4,
        offPlan = offPlan,
    )

    private fun detail(equipment: String? = "barbell", sessions: List<SessionPoint>) = ExerciseDetailDto(
        exercise = ExerciseInfo("fixture-press", "Fixture Press", equipment, "fixture-upper"),
        unit = "kg",
        sessions = sessions,
    )

    @Test
    @DisplayName("assisted equipment says what the plotted number actually is")
    fun progressionAssistedSubtitle() {
        val plain = progressionCardModel(detail(sessions = listOf(session("2026-07-01", 60.0))), true)
        val assisted = progressionCardModel(
            detail(equipment = "assisted", sessions = listOf(session("2026-07-01", 60.0))),
            true,
        )

        assertEquals("top set · e1RM (kg)", plain.subtitle)
        assertEquals("effective load (bw − assist) · e1RM (kg)", assisted.subtitle)
    }

    @Test
    @DisplayName("an exercise with no sessions in range keeps its title and loses its chart")
    fun progressionEmpty() {
        val card = progressionCardModel(detail(sessions = emptyList()), true)

        assertEquals("Fixture Press", card.title)
        assertNull(card.plot)
        assertTrue(card.legend.isEmpty())
    }

    @Test
    @DisplayName("off-plan sessions carry their own muted flag onto both series")
    fun progressionMutedPoints() {
        val card = progressionCardModel(
            detail(
                sessions = listOf(
                    session("2026-07-01", 60.0),
                    session("2026-07-08", 62.5, offPlan = true),
                    session("2026-07-15", 65.0),
                ),
            ),
            true,
        )
        val plot = requireNotNull(card.plot)

        assertEquals(listOf(false, true, false), plot.dots.filter { it.tone == PlotTone.PRIMARY }.map { it.muted })
        assertEquals(listOf(false, true, false), plot.dots.filter { it.tone == PlotTone.ALT }.map { it.muted })
    }

    @Test
    @DisplayName("the RPE overlay appears only when asked for, and only for sessions that have one")
    fun progressionRpeToggle() {
        val sessions = listOf(
            session("2026-07-01", 60.0, rpe = 8.0),
            session("2026-07-08", 62.5),
            session("2026-07-15", 65.0, rpe = 7.5),
        )

        val shown = requireNotNull(progressionCardModel(detail(sessions = sessions), true).plot)
        val hidden = requireNotNull(progressionCardModel(detail(sessions = sessions), false).plot)

        assertEquals(2, shown.lines.first { it.tone == PlotTone.SECONDARY }.points.size)
        assertTrue(hidden.lines.none { it.tone == PlotTone.SECONDARY })
        assertTrue(shown.anchors.any { rows -> rows.rows.any { it.label == "RPE" } })
    }

    @Test
    @DisplayName("a session's tooltip names the set, the estimate, and whether it was off plan")
    fun progressionTooltipRows() {
        val card = progressionCardModel(
            detail(
                sessions = listOf(
                    session("2026-07-01", 60.0, reps = 5, e1rm = 67.5, rpe = 8.0),
                    session("2026-07-08", 62.5, reps = 3, e1rm = 68.0, offPlan = true),
                ),
            ),
            true,
        )
        val anchors = requireNotNull(card.plot).anchors

        assertEquals(
            listOf("top set" to "60×5", "e1RM" to "67.5", "RPE" to "8"),
            anchors[0].rows.map { it.label to it.value },
        )
        assertEquals("off-plan", anchors[1].rows.last().value)
    }

    // ---- volume ------------------------------------------------------------

    @Test
    @DisplayName("the volume legend names the top slugs, and 'other' only when it holds something")
    fun volumeLegend() {
        val withOther = volumeCardModel(
            listOf(
                volumeWeek(
                    "2026-07-06",
                    byExercise = listOf(
                        tonnage("fixture-press", "Fixture Press", 4000.0),
                        tonnage("fixture-squat", "Fixture Squat", 3000.0),
                        tonnage("fixture-row", "Fixture Row", 2000.0),
                        tonnage("fixture-curl", "Fixture Curl", 500.0),
                    ),
                ),
            ),
        )
        val withoutOther = volumeCardModel(
            listOf(
                volumeWeek(
                    "2026-07-06",
                    byExercise = listOf(tonnage("fixture-press", "Fixture Press", 4000.0)),
                ),
            ),
        )

        assertEquals(
            listOf("Fixture Press", "Fixture Squat", "Fixture Row", "other"),
            withOther.legend.map { it.label },
        )
        assertEquals(listOf("Fixture Press"), withoutOther.legend.map { it.label })
    }

    @Test
    @DisplayName("a volume bar's tooltip lists its stacks and the week's own total")
    fun volumeTooltip() {
        val card = volumeCardModel(
            listOf(
                volumeWeek(
                    "2026-07-06",
                    byExercise = listOf(
                        tonnage("fixture-press", "Fixture Press", 4000.0),
                        tonnage("fixture-curl", "Fixture Curl", 500.0),
                    ),
                ),
            ),
        )
        val rows = requireNotNull(card.plot).anchors.single().rows

        assertEquals(listOf("Fixture Press", "Fixture Curl", "total"), rows.map { it.label })
        assertEquals("4.5t", rows.last().value)
    }

    @Test
    @DisplayName("no weeks, no volume chart")
    fun volumeEmpty() {
        assertNull(volumeCardModel(emptyList()).plot)
    }

    // ---- PR board ----------------------------------------------------------

    @Test
    @DisplayName("a record row spells out the set that set it, assistance included")
    fun prBoardDetail() {
        val rows = prBoardRows(
            listOf(
                exerciseSummary("fixture-press", "Fixture Press", assistance = null),
                exerciseSummary("fixture-pulldown", "Fixture Pulldown", assistance = 20.0, plateau = true),
            ),
        )

        assertEquals("76.5 kg", rows[0].best)
        assertEquals("70×3 · 2026-07-30", rows[0].detail)
        assertEquals("70×3 (assist 20) · 2026-07-30", rows[1].detail)
        assertTrue(rows[1].plateau)
    }

    @Test
    @DisplayName("an exercise with no all-time block renders its name alone rather than crashing")
    fun prBoardNullAllTime() {
        val rows = prBoardRows(listOf(exerciseSummary("fixture-press", "Fixture Press", allTime = false)))

        assertEquals("Fixture Press", rows.single().name)
        assertNull(rows.single().best)
        assertNull(rows.single().detail)
    }

    // ---- cardio ------------------------------------------------------------

    private fun cardio(
        weeks: List<CardioWeek> = emptyList(),
        sessions: List<SteadySession> = emptyList(),
    ) = CardioDto(weeks = weeks, steadySessions = sessions)

    private fun cardioWeek(
        weekStart: String,
        planned: Double,
        extra: Double,
        intervals: Int = 0,
        partial: Boolean = false,
    ) = CardioWeek(
        weekStart = weekStart,
        partial = partial,
        zone2PlannedMin = planned,
        zone2ExtraMin = extra,
        intervalSessions = intervals,
    )

    @Test
    @DisplayName("the interval legend line appears only when intervals actually happened")
    fun zone2IntervalLegend() {
        val without = zone2CardModel(cardio(weeks = listOf(cardioWeek("2026-07-06", 120.0, 0.0))))
        val withOne = zone2CardModel(cardio(weeks = listOf(cardioWeek("2026-07-06", 120.0, 0.0, intervals = 1))))
        val withTwo = zone2CardModel(cardio(weeks = listOf(cardioWeek("2026-07-06", 120.0, 0.0, intervals = 2))))

        assertEquals(listOf("planned", "extra"), without.legend.map { it.label })
        assertEquals("1 interval session in range", withOne.legend.last().label)
        assertEquals("2 interval sessions in range", withTwo.legend.last().label)
    }

    @Test
    @DisplayName("a Zone 2 bar's tooltip splits planned from extra and adds the total")
    fun zone2Tooltip() {
        val card = zone2CardModel(
            cardio(weeks = listOf(cardioWeek("2026-07-06", 120.0, 30.5, intervals = 2))),
        )
        val rows = requireNotNull(card.plot).anchors.single().rows

        assertEquals(
            listOf(
                "planned" to "120 min",
                "extra" to "30.5 min",
                "total" to "150.5 min",
                "intervals" to "2",
            ),
            rows.map { it.label to it.value },
        )
    }

    @Test
    @DisplayName("no steady sessions, no aerobic chart")
    fun aerobicEmpty() {
        assertNull(aerobicProxyModel(emptyList()))
    }

    @Test
    @DisplayName("the aerobic axis pads by a FIXED four beats, not by a fraction of the spread")
    fun aerobicFixedPad() {
        val model = requireNotNull(
            aerobicProxyModel(
                listOf(
                    SteadySession("2026-07-01", avgHr = 100.0, durationMin = 30.0, offPlan = false),
                    SteadySession("2026-07-08", avgHr = 108.0, durationMin = 30.0, offPlan = false),
                ),
            ),
        )

        // Domain [96, 112] over a plot from y=168 to y=10. A proportional 10%
        // pad would have put the first dot at ~154.8 instead.
        assertEquals(128.5, model.dots.first().y, 0.001)
        assertEquals(49.5, model.dots.last().y, 0.001)
    }

    @Test
    @DisplayName("dot size runs from the shortest session to the longest, off-plan ones outlined")
    fun aerobicDotSizes() {
        val model = requireNotNull(
            aerobicProxyModel(
                listOf(
                    SteadySession("2026-07-01", avgHr = 130.0, durationMin = 20.0, offPlan = false),
                    SteadySession("2026-07-08", avgHr = 132.0, durationMin = 80.0, offPlan = true),
                ),
            ),
        )

        assertEquals(2.5.dp, model.dots.first().radius)
        assertEquals(7.dp, model.dots.last().radius)
        assertEquals(listOf(false, true), model.dots.map { it.muted })
    }

    // ---- value vs target ---------------------------------------------------

    @Test
    @DisplayName("a note-era string drops out instead of poisoning the scales around it (F1)")
    fun valueTargetCoercesBeforeFiltering() {
        val card = valueTargetCardModel(
            trackerDetail(
                values = listOf(
                    text("2026-07-01", "felt tired"),
                    number("2026-07-02", 5.0),
                    number("2026-07-03", 7.0),
                ),
            ),
        )

        val plot = requireNotNull(card.plot)
        assertEquals(2, plot.dots.size)
        assertEquals(listOf("2026-07-02", "2026-07-03"), plot.anchors.map { it.key })
    }

    @Test
    @DisplayName("nothing numeric in range says so rather than drawing an empty axis")
    fun valueTargetNoValues() {
        val card = valueTargetCardModel(
            trackerDetail(values = listOf(text("2026-07-01", "felt tired"))),
        )

        assertEquals(NO_VALUES_TEXT, card.emptyText)
        assertNull(card.plot)
        assertNull(card.constant)
    }

    @Test
    @DisplayName("a fixed-dose series collapses to the number and a count")
    fun valueTargetConstant() {
        val card = valueTargetCardModel(
            trackerDetail(
                summary = tracker(unit = "mg"),
                values = listOf(number("2026-07-01", 500.0), number("2026-07-02", 500.0)),
            ),
        )

        assertNull(card.plot)
        assertEquals("500", card.constant?.value)
        assertEquals("mg", card.constant?.unit)
        assertEquals("same value for all 2 entries in range", card.constant?.note)
    }

    @Test
    @DisplayName("the x domain runs one day past the last dot so a same-day target keeps width")
    fun valueTargetDomainExtendsOneDay() {
        val card = valueTargetCardModel(
            trackerDetail(
                values = listOf(number("2026-07-01", 5.0), number("2026-07-03", 8.0)),
                segments = listOf(segment("2026-07-01", "2026-07-03", 4.0, 9.0)),
            ),
        )
        val plot = requireNotNull(card.plot)

        // Days 0 and 2 over a domain of [0, 3]: the last dot sits short of the
        // right edge, and the band — inclusive to day 2, exclusive at 3 —
        // reaches it.
        assertEquals(40.0, plot.dots.first().x, 0.001)
        assertEquals(40.0 + 2 * (310.0 / 3), plot.dots.last().x, 0.001)
        val band = plot.rects.single()
        assertEquals(40.0, band.x, 0.01)
        assertEquals(310.0, band.w, 0.01)
    }

    @Test
    @DisplayName("the tooltip names the target in force that day, one-sided ones included")
    fun valueTargetTooltipTarget() {
        val card = valueTargetCardModel(
            trackerDetail(
                summary = tracker(unit = "ml"),
                values = listOf(
                    number("2026-07-01", 1900.0),
                    number("2026-07-05", 2200.0),
                    number("2026-07-09", 2500.0),
                ),
                segments = listOf(
                    segment("2026-07-01", "2026-07-04", 1800.0, 2400.0),
                    segment("2026-07-05", "2026-07-08", 2000.0, null),
                ),
            ),
        )
        val anchors = requireNotNull(card.plot).anchors

        assertEquals("1900 ml", anchors[0].rows.first().value)
        assertEquals("1800–2400", anchors[0].rows.last().value)
        assertEquals("≥ 2000", anchors[1].rows.last().value)
        // The third day is past every segment, so there is no target row at all.
        assertTrue(anchors[2].rows.none { it.label == "target" })
    }

    @Test
    @DisplayName("the subtitle names the unit, the mean, and a target band only when there is one")
    fun valueTargetSubtitle() {
        val withBand = valueTargetCardModel(
            trackerDetail(
                summary = tracker(unit = "ml"),
                values = listOf(number("2026-07-01", 1.0), number("2026-07-02", 2.0)),
                segments = listOf(segment("2026-07-01", "2026-07-02", 1.0, 3.0)),
            ),
        )
        val withoutUnit = valueTargetCardModel(
            trackerDetail(
                summary = tracker(unit = null),
                values = listOf(number("2026-07-01", 1.0), number("2026-07-02", 2.0)),
            ),
        )

        assertEquals("ml · 7d mean · target band", withBand.subtitle)
        assertEquals("7d mean", withoutUnit.subtitle)
    }

    // ---- usage -------------------------------------------------------------

    @Test
    @DisplayName("usage counts get integer axis labels and one anchor per week")
    fun usageCard() {
        val plot = requireNotNull(
            usageCardModel(
                listOf(
                    UsageWeek("2026-07-06", partial = false, count = 3),
                    UsageWeek("2026-07-13", partial = true, count = 1),
                ),
            ),
        )

        assertTrue(
            plot.labels.filter { it.align == LabelAlign.END }.all { !it.text.contains('.') },
            plot.labels.toString(),
        )
        assertEquals(listOf("2026-07-06", "2026-07-13"), plot.anchors.map { it.key })
        assertEquals("3", plot.anchors.first().rows.single().value)
        assertEquals(listOf(false, true), plot.rects.map { it.partial })
    }

    @Test
    @DisplayName("usage bars are plain, not stack members: one series earns no plate")
    fun usageBarsCarryNoStackIdentity() {
        // Colour identifies a series, and this card has exactly one — plus no
        // legend to decode a plate with. `BAR` is the tone that says "a bar with
        // no stack identity", and it resolves to ink.
        val plot = requireNotNull(
            usageCardModel(listOf(UsageWeek("2030-01-06", partial = false, count = 3))),
        )
        assertEquals(listOf(PlotTone.BAR), plot.rects.map { it.tone })
    }

    // ---- adherence ---------------------------------------------------------

    @Test
    @DisplayName("the ribbon title takes the server's own metric name, with a fallback")
    fun adherenceTitle() {
        val avoidance = adherenceCardModel(
            trackerDetail(adherence = listOf(adherenceWeek("2026-07-06", metricKind = "avoidance"))),
        )
        val empty = adherenceCardModel(trackerDetail(adherence = emptyList()))

        assertEquals("Weekly avoidance", avoidance.title)
        assertEquals("Weekly adherence", empty.title)
    }

    @Test
    @DisplayName("a week with no rate shows its counts and no percentage")
    fun adherenceNullRate() {
        val card = adherenceCardModel(
            trackerDetail(
                adherence = listOf(
                    adherenceWeek("2026-07-06", scheduled = 7, met = 4, partialDays = 1, missed = 2, rate = 0.5),
                    adherenceWeek(
                        "2026-07-13",
                        scheduled = 0,
                        met = 0,
                        partialDays = 0,
                        missed = 0,
                        rate = null,
                        paused = true,
                    ),
                ),
            ),
        )

        assertEquals(
            listOf("met" to "4 of 7", "partial" to "1", "missed" to "2", "rate" to "50%"),
            card.plot.anchors[0].rows.map { it.label to it.value },
        )
        assertTrue(card.plot.anchors[1].rows.none { it.label == "rate" })
        assertEquals(2, card.currentStreak)
        assertEquals(9, card.bestStreak)
    }

    // ---- HRV ---------------------------------------------------------------

    @Test
    @DisplayName("a dot is warned about only below Garmin's own floor")
    fun hrvWarnDots() {
        val model = requireNotNull(
            hrvCardModel(
                listOf(
                    recoveryDay("2026-07-01", hrv = 34.0, band = band(28.0, 42.0, floor = 26.0)),
                    recoveryDay("2026-07-02", hrv = 25.0, band = band(28.0, 42.0, floor = 26.0)),
                    // No floor published: nothing to be below, so nothing to warn about.
                    recoveryDay("2026-07-03", hrv = 20.0, band = band(28.0, 42.0, floor = null)),
                ),
            ),
        )

        assertEquals(
            listOf(PlotTone.VALUE, PlotTone.WARN, PlotTone.VALUE),
            model.dots.map { it.tone },
        )
    }

    @Test
    @DisplayName("the baseline band merges across days the watch was off")
    fun hrvBandMergesAcrossGaps() {
        val model = requireNotNull(
            hrvCardModel(
                listOf(
                    recoveryDay("2026-07-01", hrv = 34.0, band = band(28.0, 42.0)),
                    recoveryDay("2026-07-02", hrv = null, band = null),
                    recoveryDay("2026-07-03", hrv = 33.0, band = band(28.0, 42.0)),
                    recoveryDay("2026-07-04", hrv = 35.0, band = band(28.0, 42.0)),
                ),
            ),
        )

        // The null day breaks the run; the two days after it merge into one.
        assertEquals(2, model.rects.size)
    }

    @Test
    @DisplayName("HRV extends its x domain by a day so the last band has width")
    fun hrvDomainExtended() {
        val model = requireNotNull(
            hrvCardModel(
                listOf(
                    recoveryDay("2026-07-01", hrv = 30.0, band = band(28.0, 42.0)),
                    recoveryDay("2026-07-03", hrv = 34.0, band = band(28.0, 42.0)),
                ),
            ),
        )

        // Domain [0, 3] across x=40..350: the last dot lands short of the edge.
        assertEquals(40.0 + 2 * (310.0 / 3), model.dots.last().x, 0.001)
    }

    @Test
    @DisplayName("HRV's tooltip carries the reading and the baseline it is measured against")
    fun hrvTooltip() {
        val model = requireNotNull(
            hrvCardModel(
                listOf(
                    recoveryDay("2026-07-01", hrv = 34.0, band = band(28.0, 42.0)),
                    recoveryDay("2026-07-02", hrv = 31.0, band = null),
                ),
            ),
        )

        assertEquals(
            listOf("hrv" to "34 ms", "7d" to "34.0", "baseline" to "28–42"),
            model.anchors[0].rows.map { it.label to it.value },
        )
        // No band published that night, so that row is simply absent.
        assertEquals(listOf("hrv", "7d"), model.anchors[1].rows.map { it.label })
    }

    @Test
    @DisplayName("a night the watch was off still anchors whatever is drawn above it")
    fun hrvAnchorsCoverReadingLessNights() {
        val model = requireNotNull(
            hrvCardModel(
                listOf(
                    recoveryDay("2026-07-01", hrv = 34.0, band = band(28.0, 42.0)),
                    recoveryDay("2026-07-02", hrv = null, band = band(28.0, 42.0)),
                    recoveryDay("2026-07-03", hrv = 33.0, band = band(28.0, 42.0)),
                ),
            ),
        )

        // The mean line and the baseline band both run through the 2nd. Without
        // an anchor there, scrubbing the visible mean snaps to a neighbour and
        // reads out the wrong night.
        assertEquals(listOf("2026-07-01", "2026-07-02", "2026-07-03"), model.anchors.map { it.key })
        assertEquals(listOf("7d", "baseline"), model.anchors[1].rows.map { it.label })
    }

    @Test
    @DisplayName("nights before the first reading are outside the plot and get no anchor")
    fun hrvAnchorsStopAtTheDomain() {
        val model = requireNotNull(
            hrvCardModel(
                listOf(
                    recoveryDay("2026-07-01", hrv = null, band = band(28.0, 42.0)),
                    recoveryDay("2026-07-02", hrv = 30.0, band = band(28.0, 42.0)),
                    recoveryDay("2026-07-03", hrv = 31.0, band = band(28.0, 42.0)),
                ),
            ),
        )

        assertEquals(listOf("2026-07-02", "2026-07-03"), model.anchors.map { it.key })
    }

    @Test
    @DisplayName("no HRV at all, no HRV chart")
    fun hrvEmpty() {
        assertNull(hrvCardModel(listOf(recoveryDay("2026-07-01", rhr = 50.0))))
        assertNull(hrvCardModel(emptyList()))
    }

    // ---- resting HR --------------------------------------------------------

    @Test
    @DisplayName("resting HR does NOT extend its domain — there is no band needing the room")
    fun rhrDomainNotExtended() {
        val model = requireNotNull(
            rhrCardModel(
                listOf(
                    recoveryDay("2026-07-01", rhr = 48.0),
                    recoveryDay("2026-07-05", rhr = 52.0),
                ),
            ),
        )

        assertEquals(40.0, model.dots.first().x, 0.001)
        assertEquals(350.0, model.dots.last().x, 0.001)
    }

    @Test
    @DisplayName("resting HR draws its dots under both means")
    fun rhrLayers() {
        val model = requireNotNull(
            rhrCardModel((1..10).map { recoveryDay("2026-07-%02d".format(it), rhr = 48.0 + it) }),
        )

        assertEquals(listOf(PlotTone.PRIMARY, PlotTone.ALT), model.lines.map { it.tone })
        assertEquals(10, model.dots.size)
        // The daily scatter goes underneath: the means are what this card is for.
        assertTrue(model.dotsBelowLines)
        assertEquals(listOf("rhr", "7d", "28d"), model.anchors.first().rows.map { it.label })
    }

    @Test
    @DisplayName("a date carrying only a rolling mean still gets its own anchor")
    fun rhrAnchorsCoverMeanOnlyDates() {
        val model = requireNotNull(
            rhrCardModel(
                listOf(
                    recoveryDay("2026-07-01", rhr = 48.0),
                    recoveryDay("2026-07-02", rhr = null),
                    recoveryDay("2026-07-03", rhr = 50.0),
                ),
            ),
        )

        assertEquals(listOf("2026-07-01", "2026-07-02", "2026-07-03"), model.anchors.map { it.key })
        assertEquals(listOf("7d", "28d"), model.anchors[1].rows.map { it.label })
    }

    // ---- sleep -------------------------------------------------------------

    @Test
    @DisplayName("the sleep axis never drops below nine hours, so a short night looks short")
    fun sleepAxisFloor() {
        val model = requireNotNull(
            sleepCardModel(
                listOf(
                    recoveryDay("2026-07-01", sleepHours = 5.0),
                    recoveryDay("2026-07-02", sleepHours = 6.0),
                ),
            ),
        )

        // The plot runs 148 units tall over a domain of [0, 9 * 1.05], so a
        // six-hour night fills 94 of them. An axis that had refitted itself to
        // the six-hour maximum would have drawn the same night at 141 — the
        // worst week of the year looking like the best.
        val tallest = model.rects.maxByOrNull { it.h }!!
        assertEquals(6.0 / (9.0 * 1.05) * 148.0, tallest.h, 0.05)
    }

    @Test
    @DisplayName("the sleep score rides a fixed 0-100 axis with its own two labels")
    fun sleepScoreAxis() {
        val model = requireNotNull(
            sleepCardModel(
                listOf(
                    recoveryDay("2026-07-01", sleepHours = 7.0, sleepScore = 100.0),
                    recoveryDay("2026-07-02", sleepHours = 8.0, sleepScore = 0.0),
                ),
            ),
        )

        val scoreDots = model.dots.filter { it.tone == PlotTone.SECONDARY }
        assertEquals(2, scoreDots.size)
        assertEquals(10.0, scoreDots.first().y, 0.001)
        assertEquals(158.0, scoreDots.last().y, 0.001)
        val rightLabels = model.labels.filter { it.align == LabelAlign.START }
        assertEquals(listOf("100", "0"), rightLabels.map { it.text })
    }

    @Test
    @DisplayName("a score-only night inside the plotted range keeps its dot and its anchor")
    fun sleepScoreOnlyNightInsideTheDomain() {
        val model = requireNotNull(
            sleepCardModel(
                listOf(
                    recoveryDay("2026-07-01", sleepHours = 7.0, sleepScore = 80.0),
                    recoveryDay("2026-07-02", sleepHours = null, sleepScore = 60.0),
                    recoveryDay("2026-07-03", sleepHours = 6.5, sleepScore = 70.0),
                ),
            ),
        )

        assertEquals(listOf("2026-07-01", "2026-07-02", "2026-07-03"), model.anchors.map { it.key })
        assertEquals(listOf("score"), model.anchors[1].rows.map { it.label })
        assertEquals(2, model.rects.size, "only the nights with hours draw a bar")
        assertEquals(3, model.dots.count { it.tone == PlotTone.SECONDARY })
    }

    @Test
    @DisplayName("a score outside the plotted range is dropped from the dots AND the anchors")
    fun sleepScoreOutsideTheDomainIsDropped() {
        val model = requireNotNull(
            sleepCardModel(
                listOf(
                    // A score logged before any night with hours: the x domain
                    // starts at the first hours night, so this maps left of the
                    // axis — drawn there it would be off the card, and anchored
                    // there it would capture every scrub near the left edge.
                    recoveryDay("2026-07-01", sleepHours = null, sleepScore = 55.0),
                    recoveryDay("2026-07-02", sleepHours = 7.0, sleepScore = 80.0),
                    recoveryDay("2026-07-03", sleepHours = 6.5, sleepScore = 70.0),
                ),
            ),
        )

        assertEquals(listOf("2026-07-02", "2026-07-03"), model.anchors.map { it.key })
        assertEquals(2, model.dots.count { it.tone == PlotTone.SECONDARY })
        assertTrue(model.anchors.all { it.x >= 0 })
    }

    @Test
    @DisplayName("the eight-hour guide is drawn, dashed, across the plot")
    fun sleepGuide() {
        val model = requireNotNull(sleepCardModel(listOf(recoveryDay("2026-07-01", sleepHours = 7.0))))

        val guide = model.guides.single()
        assertTrue(guide.dashed)
        assertEquals(40.0, guide.x0)
        assertEquals(LOGICAL_WIDTH - 34.0, guide.x1)
    }

    // ---- body + DEXA -------------------------------------------------------

    @Test
    @DisplayName("scans are filtered to the weight series' own span, lexically")
    fun bodyScanFilter() {
        val card = requireNotNull(
            bodyCardModel(
                weightSeries("2026-07-01" to 80.0, "2026-07-31" to 79.0),
                listOf(
                    scan("2026-06-30", total = 81.0), // before the span
                    scan("2026-07-15", total = 79.5), // inside
                    scan("2026-08-01", total = 78.5), // after
                    scan("2026-07-20", total = null), // inside but nothing to plot
                ),
            ),
        )

        assertEquals(1, card.plot.dots.count { it.tone == PlotTone.SCAN })
        assertEquals(listOf("7d mean", "DEXA total"), card.legend.map { it.label })
    }

    @Test
    @DisplayName("no scans in the window says where the scans went")
    fun bodyNoScansNote() {
        val card = requireNotNull(
            bodyCardModel(weightSeries("2026-07-01" to 80.0, "2026-07-05" to 79.6), emptyList()),
        )

        assertEquals(NO_SCANS_IN_RANGE_TEXT, card.legend.last().label)
    }

    @Test
    @DisplayName("a weight reading and a scan on the same day share one anchor")
    fun bodySameDayAnchor() {
        val card = requireNotNull(
            bodyCardModel(
                weightSeries("2026-07-01" to 80.0, "2026-07-15" to 79.5, "2026-07-31" to 79.0),
                listOf(scan("2026-07-15", total = 79.4)),
            ),
        )

        val shared = card.plot.anchors.first { it.key == "2026-07-15" }
        assertEquals(listOf("kg", "7d", "DEXA"), shared.rows.map { it.label })
        assertEquals("79.4 kg", shared.rows.last().value)
        assertEquals(3, card.plot.anchors.size, "the scan must not add a fourth anchor")
    }

    @Test
    @DisplayName("no weight readings, no body card")
    fun bodyEmpty() {
        assertNull(bodyCardModel(emptyList(), listOf(scan("2026-07-15", total = 79.4))))
    }

    // ---- composition -------------------------------------------------------

    @Test
    @DisplayName("the five strips keep their order, and a metric nobody measured is left out")
    fun compositionStrips() {
        val card = requireNotNull(
            compositionCardModel(
                listOf(
                    scan("2026-01-14", lean = 58.2, fat = 20.4, bodyFat = 25.5, vat = 0.5),
                    scan("2026-07-11", lean = 59.6, fat = 18.5, bodyFat = 23.4, vat = 0.44),
                ),
            ),
        )

        // A/G ratio was never recorded, so its strip is absent rather than blank.
        assertEquals(listOf("Lean mass", "Fat mass", "Body fat", "VAT"), card.metrics.map { it.label })
        assertEquals("59.6kg", card.metrics.first().latest)
        assertEquals("23.4%", card.metrics[2].latest)
    }

    @Test
    @DisplayName("the bone table lists only the scans that measured bone")
    fun compositionBoneRows() {
        val card = requireNotNull(
            compositionCardModel(
                listOf(
                    scan("2026-01-14", lean = 58.0, bmd = 1.24, tScore = 0.7),
                    scan("2026-04-18", lean = 59.0, bmd = 1.26, tScore = null),
                    scan("2026-07-11", lean = 59.6, bmd = null),
                ),
            ),
        )

        assertEquals(listOf("2026-01-14", "2026-04-18"), card.bone.map { it.date })
        assertEquals("1.24 g/cm²", card.bone.first().bmd)
        assertEquals("t-score 0.7", card.bone.first().tScore)
        // A missing t-score reads as absent rather than as the word "null".
        assertEquals("t-score —", card.bone.last().tScore)
    }

    @Test
    @DisplayName("composition is range-immune: every scan, however old")
    fun compositionKeepsEveryScan() {
        val card = requireNotNull(
            compositionCardModel(
                listOf(
                    scan("2023-02-01", lean = 55.0),
                    scan("2024-09-14", lean = 57.0),
                    scan("2026-07-11", lean = 59.6),
                ),
            ),
        )

        assertEquals(3, card.metrics.single().plot.dots.size)
        assertEquals(3, card.metrics.single().plot.anchors.size)
        assertTrue(card.axis.labels.isNotEmpty())
    }

    @Test
    @DisplayName("no scans, no composition card")
    fun compositionEmpty() {
        assertNull(compositionCardModel(emptyList()))
    }

    // ---- labs --------------------------------------------------------------

    private val panels = listOf(
        labPanel(
            "Fixture Panel One",
            listOf(
                labTest(
                    "Fixture Test Alpha",
                    "ng/mL",
                    listOf(
                        labObs("2026-01-14", value = 42.0, refLow = 30.0, refHigh = 400.0),
                        labObs("2026-07-11", value = 28.5, flag = "L", refLow = 30.0, refHigh = 400.0),
                    ),
                ),
                labTest(
                    "Fixture Test Gamma",
                    "mg/dL",
                    listOf(
                        labObs("2025-11-02", value = 5.0, prefix = "<", refHigh = 10.0),
                        labObs("2026-07-11", value = 12.0, flag = "H", refHigh = 10.0),
                    ),
                ),
                labTest("Fixture Test Beta", null, listOf(labObs("2026-07-11", text = "Not Detected"))),
            ),
        ),
        labPanel(
            "Fixture Panel Two",
            listOf(labTest("Fixture Test Delta", "%", listOf(labObs("2026-04-18", value = 5.2)))),
        ),
    )

    @Test
    @DisplayName("a remembered panel is honoured; an unknown one falls back to the first")
    fun labPanelSelection() {
        assertEquals("Fixture Panel Two", labsSectionModel(panels, "Fixture Panel Two")?.selectedPanel)
        assertEquals("Fixture Panel One", labsSectionModel(panels, "Fixture Panel Gone")?.selectedPanel)
        assertEquals("Fixture Panel One", labsSectionModel(panels, null)?.selectedPanel)
        assertNull(labsSectionModel(emptyList(), null))
    }

    @Test
    @DisplayName("every strip in a panel measures from the same origin, so they line up")
    fun labStripsShareAnOrigin() {
        val section = requireNotNull(labsSectionModel(panels, "Fixture Panel One"))

        // 2025-11-02 is the earliest date in the PANEL, not in each test, so
        // Alpha's first point sits well right of the axis rather than on it.
        val alpha = section.charts.first { it.name == "Fixture Test Alpha" }
        val gamma = section.charts.first { it.name == "Fixture Test Gamma" }
        assertTrue(alpha.plot.dots.first().x > gamma.plot.dots.first().x)
        assertEquals(8.0, gamma.plot.dots.first().x, 0.001)
        assertEquals(alpha.plot.dots.last().x, gamma.plot.dots.last().x, 0.001)
    }

    @Test
    @DisplayName("a flagged observation is toned by the LAB's flag, never by a recomputed range")
    fun labFlagTones() {
        val section = requireNotNull(labsSectionModel(panels, "Fixture Panel One"))
        val alpha = section.charts.first { it.name == "Fixture Test Alpha" }

        assertEquals(listOf(PlotTone.VALUE, PlotTone.WARN), alpha.plot.dots.map { it.tone })
    }

    @Test
    @DisplayName("the reference band comes from the latest observation, one-sided included")
    fun labReferenceBand() {
        val section = requireNotNull(labsSectionModel(panels, "Fixture Panel One"))
        val gamma = section.charts.first { it.name == "Fixture Test Gamma" }

        // Only a high bound, so the band opens downward and closes on the
        // plot's own floor rather than on a bound nobody published.
        val rect = gamma.plot.rects.single()
        assertEquals(PlotTone.BAND, rect.tone)
        assertEquals(MINI_LAB_BOTTOM, rect.y + rect.h, 0.001)
        assertTrue(rect.y > MINI_LAB_TOP, "the band's top follows the reference high")
    }

    @Test
    @DisplayName("a charted value keeps the report's own prefix in what it displays")
    fun labValuePrefix() {
        val section = requireNotNull(labsSectionModel(panels, "Fixture Panel One"))
        val gamma = section.charts.first { it.name == "Fixture Test Gamma" }

        assertEquals("12 mg/dL", gamma.latest)
        assertEquals("<5 mg/dL", gamma.plot.anchors.first().rows.first().value)
        assertEquals("≤ 10", gamma.plot.anchors.first().rows[1].value)
        assertEquals("H", gamma.plot.anchors.last().rows.last().value)
    }

    @Test
    @DisplayName("a text-only test becomes a row rather than a chart")
    fun labTabularRow() {
        val section = requireNotNull(labsSectionModel(panels, "Fixture Panel One"))

        assertEquals(listOf("Fixture Test Beta"), section.rows.map { it.name })
        assertEquals("Not Detected", section.rows.single().value)
        assertEquals("2026-07-11", section.rows.single().subLabel)
        assertFalse(section.rows.single().flagged)
    }

    @Test
    @DisplayName("a tabular row shows the reference text the report printed")
    fun labTabularRefText() {
        val section = requireNotNull(
            labsSectionModel(
                listOf(
                    labPanel(
                        "Fixture Panel Three",
                        listOf(
                            labTest(
                                "Fixture Test Epsilon",
                                "%",
                                listOf(labObs("2026-04-18", value = 5.9, flag = "H", refText = "4.0-5.6")),
                            ),
                        ),
                    ),
                ),
                null,
            ),
        )

        assertEquals("2026-04-18 · ref 4.0-5.6", section.rows.single().subLabel)
        assertEquals("5.9 %", section.rows.single().value)
        assertTrue(section.rows.single().flagged)
    }

    // ---- overview tiles ----------------------------------------------------

    @Test
    @DisplayName("a stat tile headlines the last complete week and offers the in-progress one separately")
    fun statTiles() {
        val tiles = overviewStatTiles(overviewDto(lastWeek = 150.0, avg = 120.0, soFar = 42.0))

        assertEquals("Zone 2 last week", tiles[0].label)
        assertEquals("150", tiles[0].headline)
        assertEquals("4wk avg 120 · +25%", tiles[0].avgLine)
        assertEquals("this week so far: 42 min", tiles[0].soFarLine)
        assertEquals("cardio", tiles[0].target)
        assertEquals("strength", tiles[1].target)
    }

    @Test
    @DisplayName("a missing week headlines zero, and a missing average drops the whole line")
    fun statTilesNulls() {
        val tiles = overviewStatTiles(overviewDto(lastWeek = null, avg = null, soFar = 0.0))

        assertEquals("0", tiles[0].headline)
        assertNull(tiles[0].avgLine)
        assertEquals("this week so far: 0 min", tiles[0].soFarLine)
    }

    @Test
    @DisplayName("the sparkline sums planned and extra minutes per week")
    fun statTileSparkline() {
        val tiles = overviewStatTiles(
            overviewDto(lastWeek = 150.0, avg = 120.0, soFar = 42.0, spark = listOf(0.0 to 0.0, 60.0 to 36.0)),
        )

        // Two weeks: 0 and 96, mapped across a 96×26 box.
        assertEquals("0,26 96,0", tiles[0].sparkline)
    }

    @Test
    @DisplayName("the PR tile hides when nothing was set, and pluralises when something was")
    fun prTile() {
        assertNull(prTileModel(prSummary(count = 0)))
        assertEquals("🏆 1 PR in 30d", prTileModel(prSummary(count = 1))?.badge)
        assertEquals("🏆 3 PRs in 30d", prTileModel(prSummary(count = 3))?.badge)
        assertEquals(
            "latest: Fixture Press e1RM 72.5 kg (2026-07-30)",
            prTileModel(prSummary(count = 1, withLatest = true))?.latest,
        )
        assertNull(prTileModel(prSummary(count = 1))?.latest)
    }

    @Test
    @DisplayName("a focus row states its rate as a whole percentage of its own metric")
    fun focusRows() {
        val rows = focusRowModels(overviewDto(lastWeek = 1.0, avg = 1.0, soFar = 1.0))

        assertEquals("Fixture Tracker Alpha", rows.single().name)
        assertEquals("42% adherence", rows.single().rate)
        assertTrue(rows.single().dropping)
        assertEquals(listOf("met", "missed"), rows.single().ribbon.map { it.status })
    }

    private companion object {
        const val MINI_LAB_TOP = 10.0
        const val MINI_LAB_BOTTOM = 64.0 - 14.0
    }
}

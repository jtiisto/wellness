package dev.jtiisto.wellness.core.data.journal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `test/js/journal-summary.test.js`, transcribed: the collapsed-category
 * on-track rollup and its badge formatter.
 *
 * The distinction the whole file turns on: an untargeted *neutral* tracker is
 * an observation, not a goal — excluded from the fraction, counted separately.
 */
class JournalSummaryLogicTest {

    private val mon = "2026-07-06" // a Monday (local weekday 1)

    // ---- categorySummary --------------------------------------------------

    @Test
    @DisplayName("categorySummary: not-expected trackers are excluded (off-schedule ≠ miss)")
    fun excludesOffSchedule() {
        val weekendOnly = tracker(
            id = "w",
            polarity = "positive",
            scheduleHistory = listOf(schedule(SCHEDULE_GENESIS_DATE, 0, 6)),
        )
        val daily = tracker(id = "d", polarity = "positive")
        val summary = categorySummary(listOf(weekendOnly, daily), mon, mapOf("d" to entry(completed = true)))
        assertEquals(1, summary.actionable, "weekendOnly is not expected on Monday")
        assertEquals(1, summary.onTrack)
    }

    @Test
    @DisplayName("categorySummary: mixed polarity — negative-empty counts as on track")
    fun mixedPolarity() {
        val trackers = listOf(
            tracker(id = "p", polarity = "positive"),
            tracker(id = "q", polarity = "positive"),
            tracker(id = "v", polarity = "negative"),
        )
        val summary = categorySummary(trackers, mon, mapOf("p" to entry(completed = true)))
        assertEquals(3, summary.actionable)
        assertEquals(2, summary.onTrack, "p (checked) + v (avoided)")
        assertEquals(1, summary.notYet)
        assertEquals(0, summary.observed)
    }

    @Test
    @DisplayName("""categorySummary: a logged negative tracker is not "on track"""")
    fun loggedNegative() {
        val summary = categorySummary(
            listOf(tracker(id = "v", polarity = "negative")),
            mon,
            mapOf("v" to entry(value = num(1))),
        )
        assertEquals(1, summary.actionable)
        assertEquals(0, summary.onTrack, "logged = not avoided")
    }

    @Test
    @DisplayName("categorySummary: targeted tracker uses value-vs-target; partial is bucketed")
    fun targetedBucketing() {
        val t = tracker(
            id = "t",
            polarity = "positive",
            targetHistory = listOf(targetSegment(SCHEDULE_GENESIS_DATE, target(min = 150))),
        )
        val met = categorySummary(listOf(t), mon, mapOf("t" to entry(value = num(160))))
        assertEquals(listOf(1, 0, 0), listOf(met.onTrack, met.partial, met.notYet))
        val partial = categorySummary(listOf(t), mon, mapOf("t" to entry(value = num(100))))
        assertEquals(listOf(0, 1, 0), listOf(partial.onTrack, partial.partial, partial.notYet))
    }

    @Test
    @DisplayName("categorySummary: untargeted neutral observations are excluded from the fraction")
    fun observationsExcluded() {
        val trackers = listOf(
            tracker(id = "p1", polarity = "positive"),
            tracker(id = "p2", polarity = "positive"),
            tracker(id = "headache", polarity = "neutral"),
        )
        val summary = categorySummary(
            trackers,
            mon,
            mapOf("p1" to entry(completed = true), "headache" to entry(value = num(1))),
        )
        assertEquals(2, summary.actionable, "the denominator is 2, not 3")
        assertEquals(1, summary.onTrack)
        assertEquals(1, summary.observed)
        assertEquals(CategoryBadge("1 of 2 on track", SummaryTone.NEUTRAL), formatCategorySummary(summary))

        // Whether the observation is logged does not change the fraction.
        val withoutObservation = categorySummary(trackers, mon, mapOf("p1" to entry(completed = true)))
        assertEquals(2, withoutObservation.actionable)
        assertEquals(1, withoutObservation.onTrack)
        assertEquals(0, withoutObservation.observed)
        assertEquals(
            CategoryBadge("1 of 2 on track", SummaryTone.NEUTRAL),
            formatCategorySummary(withoutObservation),
        )
    }

    @Test
    @DisplayName("""categorySummary: a pure-observation category reads "K logged" (no denominator)""")
    fun pureObservationCategory() {
        val trackers = listOf(
            tracker(id = "headache", polarity = "neutral"),
            tracker(id = "mood-note", polarity = "neutral"),
        )
        val summary = categorySummary(trackers, mon, mapOf("headache" to entry(value = num(1))))
        assertEquals(0, summary.actionable)
        assertEquals(1, summary.observed)
        assertEquals(CategoryBadge("1 logged", SummaryTone.NEUTRAL), formatCategorySummary(summary))
    }

    @Test
    @DisplayName("categorySummary: a targeted neutral is actionable (on track, not logged)")
    fun targetedNeutralIsActionable() {
        val t = tracker(
            id = "weight",
            polarity = "neutral",
            targetHistory = listOf(targetSegment(SCHEDULE_GENESIS_DATE, target(150, 170))),
        )
        val summary = categorySummary(listOf(t), mon, mapOf("weight" to entry(value = num(160))))
        assertEquals(1, summary.actionable)
        assertEquals(1, summary.onTrack)
        assertEquals(0, summary.observed)
        assertEquals(CategoryBadge("All on track", SummaryTone.MET), formatCategorySummary(summary))
    }

    // ---- formatCategorySummary --------------------------------------------

    @Test
    @DisplayName("formatCategorySummary: null when nothing actionable and nothing observed")
    fun badgeSuppressed() {
        assertNull(formatCategorySummary(null))
        assertNull(formatCategorySummary(CategorySummary(actionable = 0, onTrack = 0, observed = 0)))
    }

    @Test
    @DisplayName("formatCategorySummary: on-track fraction (partial → neutral, all met → met)")
    fun badgeFraction() {
        assertEquals(
            CategoryBadge("3 of 4 on track", SummaryTone.NEUTRAL),
            formatCategorySummary(CategorySummary(actionable = 4, onTrack = 3, observed = 2)),
        )
        assertEquals(
            CategoryBadge("All on track", SummaryTone.MET),
            formatCategorySummary(CategorySummary(actionable = 3, onTrack = 3, observed = 0)),
        )
    }

    @Test
    @DisplayName("formatCategorySummary: observation activity is dropped when actionable > 0")
    fun badgeDropsObservations() {
        assertEquals(
            CategoryBadge("All on track", SummaryTone.MET),
            formatCategorySummary(CategorySummary(actionable = 2, onTrack = 2, observed = 5)),
        )
    }

    @Test
    @DisplayName("""formatCategorySummary: pure observations → "K logged", no "All", neutral tone""")
    fun badgePureObservations() {
        assertEquals(
            CategoryBadge("3 logged", SummaryTone.NEUTRAL),
            formatCategorySummary(CategorySummary(actionable = 0, onTrack = 0, observed = 3)),
        )
        assertEquals(
            CategoryBadge("1 logged", SummaryTone.NEUTRAL),
            formatCategorySummary(CategorySummary(actionable = 0, onTrack = 0, observed = 1)),
        )
    }
}

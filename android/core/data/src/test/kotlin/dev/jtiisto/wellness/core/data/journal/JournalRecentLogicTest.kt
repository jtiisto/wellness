package dev.jtiisto.wellness.core.data.journal

import dev.jtiisto.wellness.core.data.network.DateString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `test/js/journal-recent.test.js`, transcribed: the 7-day dot row — the
 * single-day [dayStatus] predicate repeated, with off-schedule and
 * before-the-window days muted rather than judged.
 */
class JournalRecentLogicTest {

    // A Monday. The 7-day window ending here is (oldest→newest):
    // 06-30 Tue, 07-01 Wed, 07-02 Thu, 07-03 Fri, 07-04 Sat, 07-05 Sun, 07-06 Mon.
    private val end = "2026-07-06"

    private fun byDate(dots: List<DayDot>): Map<DateString, DotState> =
        dots.associate { it.date to it.state }

    @Test
    @DisplayName("recentDayStates: returns n days oldest→newest ending endDate")
    fun windowShape() {
        val dots = recentDayStates(tracker(id = "t"), end, emptyMap(), 7)
        assertEquals(7, dots.size)
        assertEquals(
            listOf(
                "2026-06-30", "2026-07-01", "2026-07-02", "2026-07-03",
                "2026-07-04", "2026-07-05", "2026-07-06",
            ),
            dots.map { it.date },
        )
    }

    @Test
    @DisplayName("""recentDayStates: off-schedule days are "off" (weekends for a Mon–Fri tracker)""")
    fun offScheduleDays() {
        val t = tracker(
            id = "t",
            polarity = "positive",
            scheduleHistory = listOf(schedule(SCHEDULE_GENESIS_DATE, 1, 2, 3, 4, 5)),
        )
        val states = byDate(recentDayStates(t, end, emptyMap(), 7))
        assertEquals(DotState.OFF, states["2026-07-04"], "Saturday")
        assertEquals(DotState.OFF, states["2026-07-05"], "Sunday")
        assertEquals(DotState.MISSED, states["2026-07-06"], "Monday, expected, no entry, positive")
        assertEquals(DotState.MISSED, states["2026-07-01"], "Wednesday, expected, no entry")
    }

    @Test
    @DisplayName("""recentDayStates: negative tracker with no entries is "met" (avoided) all week""")
    fun negativeAvoided() {
        val dots = recentDayStates(tracker(id = "v", polarity = "negative"), end, emptyMap(), 7)
        assertTrue(dots.all { it.state == DotState.MET }, "$dots")
    }

    @Test
    @DisplayName("""recentDayStates: positive tracker with no entries anywhere is all "missed"""")
    fun positiveAllMissed() {
        val dots = recentDayStates(tracker(id = "t", polarity = "positive"), end, emptyMap(), 7)
        assertTrue(dots.all { it.state == DotState.MISSED }, "$dots")
    }

    @Test
    @DisplayName("recentDayStates: targeted tracker mixes met/partial/missed by value")
    fun targetedMix() {
        val t = tracker(
            id = "t",
            polarity = "positive",
            targetHistory = listOf(targetSegment(SCHEDULE_GENESIS_DATE, target(min = 150))),
        )
        val logs = mapOf(
            "2026-07-05" to mapOf("t" to entry(value = num(160))),
            "2026-07-06" to mapOf("t" to entry(value = num(100))),
        )
        val states = byDate(recentDayStates(t, end, logs, 7))
        assertEquals(DotState.MET, states["2026-07-05"])
        assertEquals(DotState.PARTIAL, states["2026-07-06"])
        assertEquals(DotState.MISSED, states["2026-07-04"], "no entry, positive")
    }

    @Test
    @DisplayName("recentDayStates: honors a custom window length and tolerates missing logs")
    fun customWindow() {
        val dots = recentDayStates(tracker(id = "t", polarity = "negative"), end, null, 3)
        assertEquals(3, dots.size)
        assertEquals(listOf("2026-07-04", "2026-07-05", "2026-07-06"), dots.map { it.date })
        assertTrue(dots.all { it.state == DotState.MET }, "negative no-entry = met")
    }

    @Test
    @DisplayName("recentDayStates: untargeted-neutral tracker uses noted/quiet, not met/missed")
    fun observationsUseNotedQuiet() {
        val t = tracker(id = "h", polarity = "neutral")
        val logs = mapOf(
            "2026-07-06" to mapOf("h" to entry(completed = true)),
            "2026-07-02" to mapOf("h" to entry(value = num(1))),
        )
        val states = byDate(recentDayStates(t, end, logs, 7))
        assertEquals(DotState.NOTED, states["2026-07-06"])
        assertEquals(DotState.NOTED, states["2026-07-02"])
        assertEquals(DotState.QUIET, states["2026-07-01"], "expected, no entry")
        assertFalse(states.values.any { it == DotState.MET || it == DotState.MISSED }, "$states")
    }

    @Test
    @DisplayName("""recentDayStates: a neutral observation still marks off-schedule days "off"""")
    fun observationsStillMuteOffSchedule() {
        val t = tracker(
            id = "h",
            polarity = "neutral",
            scheduleHistory = listOf(schedule(SCHEDULE_GENESIS_DATE, 1, 2, 3, 4, 5)),
        )
        val states = byDate(recentDayStates(t, end, emptyMap(), 7))
        assertEquals(DotState.OFF, states["2026-07-04"], "Saturday")
        assertEquals(DotState.OFF, states["2026-07-05"], "Sunday")
        assertEquals(DotState.QUIET, states["2026-07-06"], "Monday, expected neutral, no entry")
    }

    @Test
    @DisplayName("""recentDayStates: days before earliestKnownDate render "off", not missed/quiet""")
    fun beforeWindowIsMuted() {
        // Browsing back, the window reaches days the store has pruned — absence
        // there is "unknown", not a fabricated miss.
        val t = tracker(id = "t", polarity = "positive")
        val states = byDate(recentDayStates(t, end, emptyMap(), 7, "2026-07-03"))
        assertEquals(DotState.OFF, states["2026-06-30"])
        assertEquals(DotState.OFF, states["2026-07-01"])
        assertEquals(DotState.OFF, states["2026-07-02"])
        assertEquals(DotState.MISSED, states["2026-07-03"], "inside the window: judged")
        assertEquals(DotState.MISSED, states["2026-07-06"])
    }

    @Test
    @DisplayName("recentDayStates: no earliestKnownDate keeps the old judge-everything behavior")
    fun noWindowJudgesEverything() {
        val dots = recentDayStates(tracker(id = "t", polarity = "positive"), end, emptyMap(), 7)
        assertTrue(dots.all { it.state == DotState.MISSED })
    }
}

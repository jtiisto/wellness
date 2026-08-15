package dev.jtiisto.wellness.core.data.journal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `test/js/journal-schedule.test.js`, transcribed: the effective-dated schedule
 * derivation, the legacy `frequency`/`weeklyDay` fallback, and the pure
 * apply-from-today write helper.
 *
 * The JS file pins `TZ=America/Los_Angeles` so that a regression to UTC date
 * parsing would shift weekdays and fail. [getDayOfWeek] parses a `LocalDate`,
 * which has no zone to get wrong, so the same guard is expressed as fixed-date
 * weekday assertions instead.
 */
class JournalScheduleLogicTest {

    // Reference dates: 2026-07-03 = Friday (5), 07-04 = Saturday (6),
    // 07-05 = Sunday (0), 07-06 = Monday (1).
    private val fri = "2026-07-03"
    private val sat = "2026-07-04"
    private val sun = "2026-07-05"
    private val mon = "2026-07-06"

    private fun sorted(days: Set<Int>) = days.sorted()

    // ---- constants -------------------------------------------------------

    @Test
    @DisplayName("ALL_DAYS is the full Sun..Sat week")
    fun allDays() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), ALL_DAYS)
    }

    @Test
    @DisplayName("SCHEDULE_GENESIS_DATE sorts below any real YYYY-MM-DD")
    fun genesisSortsFirst() {
        assertTrue(SCHEDULE_GENESIS_DATE < "1970-01-01")
        assertTrue(SCHEDULE_GENESIS_DATE < "2026-07-03")
    }

    @Test
    @DisplayName("POLARITY_VALUES lists the three polarities")
    fun polarityValues() {
        assertEquals(listOf("positive", "negative", "neutral"), POLARITY_VALUES)
    }

    // ---- legacy + default derivation --------------------------------------

    @Test
    @DisplayName("legacy daily → expected every day")
    fun legacyDaily() {
        val t = legacyTracker(frequency = "daily")
        assertEquals(ALL_DAYS, sorted(getScheduleDaysForDate(t, mon)))
        assertTrue(isExpectedOn(t, sat))
        assertTrue(isExpectedOn(t, mon))
    }

    @Test
    @DisplayName("legacy weekly → expected only on weeklyDay")
    fun legacyWeekly() {
        val t = legacyTracker(frequency = "weekly", weeklyDay = 1)
        assertEquals(listOf(1), sorted(getScheduleDaysForDate(t, mon)))
        assertTrue(isExpectedOn(t, mon))
        assertFalse(isExpectedOn(t, sat))
    }

    @Test
    @DisplayName("absent frequency/schedule → daily default")
    fun absentScheduleIsDaily() {
        val t = tracker()
        assertEquals(ALL_DAYS, sorted(getScheduleDaysForDate(t, sat)))
        assertTrue(isExpectedOn(t, sat))
    }

    @Test
    @DisplayName("empty scheduleHistory array → falls through to daily default")
    fun emptyHistoryIsDaily() {
        val t = tracker(scheduleHistory = emptyList())
        assertEquals(ALL_DAYS, sorted(getScheduleDaysForDate(t, sat)))
        assertTrue(isExpectedOn(t, sat))
    }

    @Test
    @DisplayName("shouldShowTracker with no dayLog equals isExpectedOn (pure expectation)")
    fun shouldShowWithoutLogIsExpectation() {
        val trackers = listOf(
            legacyTracker(frequency = "daily"),
            legacyTracker(frequency = "weekly", weeklyDay = 1),
            tracker(scheduleHistory = listOf(schedule(SCHEDULE_GENESIS_DATE, 1, 2, 3, 4, 5))),
        )
        for (t in trackers) {
            for (date in listOf(fri, sat, sun, mon)) {
                assertEquals(isExpectedOn(t, date), shouldShowTracker(t, date), "diverged on $date")
            }
        }
    }

    // ---- scheduleHistory segment selection --------------------------------

    @Test
    @DisplayName("segment selection: date at/after a segment picks the latest applicable")
    fun segmentSelectionLatestApplicable() {
        val t = tracker(
            scheduleHistory = listOf(
                schedule(SCHEDULE_GENESIS_DATE, 1, 2, 3, 4, 5),
                schedule(fri, 1, 2, 3, 4, 5, 6),
            ),
        )
        // On the effectiveFrom boundary (<=) the new segment applies.
        assertEquals(listOf(1, 2, 3, 4, 5, 6), sorted(getScheduleDaysForDate(t, fri)))
        assertEquals(listOf(1, 2, 3, 4, 5, 6), sorted(getScheduleDaysForDate(t, sat)))
    }

    @Test
    @DisplayName("segment selection: date before a change uses the prior (genesis) segment")
    fun segmentSelectionBeforeChange() {
        val t = tracker(
            scheduleHistory = listOf(
                schedule(SCHEDULE_GENESIS_DATE, 1, 2, 3, 4, 5),
                schedule(fri, 1, 2, 3, 4, 5, 6),
            ),
        )
        assertEquals(listOf(1, 2, 3, 4, 5), sorted(getScheduleDaysForDate(t, "2020-01-01")))
    }

    @Test
    @DisplayName("segment selection: date before all segments falls back to earliest")
    fun segmentSelectionPreHistory() {
        val t = tracker(
            scheduleHistory = listOf(
                schedule("2026-06-01", 1),
                schedule("2026-07-01", 2),
            ),
        )
        assertEquals(listOf(1), sorted(getScheduleDaysForDate(t, "2026-05-01")))
    }

    @Test
    @DisplayName("segment selection is order-independent (unsorted history)")
    fun segmentSelectionOrderIndependent() {
        val t = tracker(
            scheduleHistory = listOf(
                schedule(fri, 1, 2, 3, 4, 5, 6),
                schedule(SCHEDULE_GENESIS_DATE, 1, 2, 3, 4, 5),
            ),
        )
        assertEquals(listOf(1, 2, 3, 4, 5), sorted(getScheduleDaysForDate(t, "2020-01-01")))
        assertEquals(listOf(1, 2, 3, 4, 5, 6), sorted(getScheduleDaysForDate(t, sat)))
    }

    @Test
    @DisplayName("genesis sentinel covers all past dates; latest schedule drives isExpectedOn")
    fun genesisCoversPast() {
        val t = tracker(
            scheduleHistory = listOf(
                schedule(SCHEDULE_GENESIS_DATE, 1, 2, 3, 4, 5),
                schedule(fri, ALL_DAYS),
            ),
        )
        assertTrue(isExpectedOn(t, sat))
    }

    @Test
    @DisplayName("scheduleHistory days are normalized on read (dedup/sort/range-filter)")
    fun historyDaysNormalizedOnRead() {
        val t = tracker(scheduleHistory = listOf(schedule(SCHEDULE_GENESIS_DATE, 5, 1, 1, 3, 9, -1)))
        assertEquals(listOf(1, 3, 5), sorted(getScheduleDaysForDate(t, mon)))
    }

    // ---- write helper (apply-from-today rules) ----------------------------

    @Test
    @DisplayName("write helper: no-op when new days equal current (legacy daily)")
    fun writeNoOpLegacyDaily() {
        val t = legacyTracker(frequency = "daily")
        val result = computeScheduleHistoryUpdate(t, ALL_DAYS, fri)
        assertFalse(result.changed)
        assertNull(result.history)
    }

    @Test
    @DisplayName("write helper: no-op when new days equal current, ignoring order (existing history)")
    fun writeNoOpIgnoresOrder() {
        val history = listOf(
            schedule(SCHEDULE_GENESIS_DATE, ALL_DAYS),
            schedule("2026-06-01", 1, 2, 3, 4, 5),
        )
        val t = tracker(scheduleHistory = history)
        val result = computeScheduleHistoryUpdate(t, listOf(5, 4, 3, 2, 1), mon)
        assertFalse(result.changed)
        assertSame(history, result.history, "an unchanged history must come back by the same reference")
    }

    @Test
    @DisplayName("write helper: first edit of a legacy daily tracker splits genesis + today")
    fun writeFirstEditLegacyDaily() {
        val t = legacyTracker(frequency = "daily")
        val result = computeScheduleHistoryUpdate(t, listOf(1, 2, 3, 4, 5), fri)
        assertTrue(result.changed)
        assertEquals(2, result.history?.size)
        assertEquals(schedule(SCHEDULE_GENESIS_DATE, ALL_DAYS), result.history!![0])
        assertEquals(schedule(fri, 1, 2, 3, 4, 5), result.history[1])
    }

    @Test
    @DisplayName("write helper: first edit of a legacy weekly tracker carries the weekly day into genesis")
    fun writeFirstEditLegacyWeekly() {
        val t = legacyTracker(frequency = "weekly", weeklyDay = 1)
        val result = computeScheduleHistoryUpdate(t, listOf(1, 2, 3, 4, 5), fri)
        assertTrue(result.changed)
        assertEquals(schedule(SCHEDULE_GENESIS_DATE, 1), result.history!![0])
        assertEquals(schedule(fri, 1, 2, 3, 4, 5), result.history[1])
    }

    @Test
    @DisplayName("write helper: a later change appends a new segment")
    fun writeLaterChangeAppends() {
        val t = tracker(
            scheduleHistory = listOf(
                schedule(SCHEDULE_GENESIS_DATE, 1, 2, 3, 4, 5),
                schedule("2026-06-01", 1, 2, 3, 4, 5, 6),
            ),
        )
        val result = computeScheduleHistoryUpdate(t, listOf(1, 2, 3, 4, 5), fri)
        assertTrue(result.changed)
        assertEquals(3, result.history?.size)
        assertEquals(schedule(fri, 1, 2, 3, 4, 5), result.history!![2])
    }

    @Test
    @DisplayName("write helper: same-day re-edit replaces the latest segment in place")
    fun writeSameDayReplaces() {
        val t = tracker(
            scheduleHistory = listOf(
                schedule(SCHEDULE_GENESIS_DATE, 1, 2, 3, 4, 5),
                schedule(fri, 1, 2, 3, 4, 5, 6),
            ),
        )
        val result = computeScheduleHistoryUpdate(t, ALL_DAYS, fri)
        assertTrue(result.changed)
        assertEquals(2, result.history?.size, "replaced, not appended")
        val froms = result.history!!.map { it.effectiveFrom }
        assertEquals(froms.size, froms.toSet().size, "no duplicate effectiveFrom")
        assertEquals(schedule(fri, ALL_DAYS), result.history[1])
    }

    @Test
    @DisplayName("write helper: input days are normalized (sorted, deduped, range-filtered)")
    fun writeNormalizesInput() {
        val t = legacyTracker(frequency = "weekly", weeklyDay = 1)
        // The JS case also passes the string '2'; a typed List<Int> cannot carry one.
        val result = computeScheduleHistoryUpdate(t, listOf(5, 3, 3, 1, 9, -1, 2), fri)
        assertTrue(result.changed)
        assertEquals(listOf(1, 2, 3, 5), result.history!![1].days)
    }

    // ---- local-date weekday pin -------------------------------------------

    @Test
    @DisplayName("local-date weekday: Mon–Fri hidden on Saturday")
    fun localDateWeekday() {
        assertEquals(6, getDayOfWeek(sat), "sanity: SAT resolves to Saturday")
        assertEquals(5, getDayOfWeek(fri), "sanity: FRI resolves to Friday")

        val viaHistory = tracker(scheduleHistory = listOf(schedule(SCHEDULE_GENESIS_DATE, 1, 2, 3, 4, 5)))
        assertFalse(isExpectedOn(viaHistory, sat))
        assertTrue(isExpectedOn(viaHistory, fri))
        assertFalse(shouldShowTracker(viaHistory, sat))

        val viaLegacy = legacyTracker(frequency = "weekly", weeklyDay = 1)
        assertTrue(isExpectedOn(viaLegacy, mon))
        assertFalse(isExpectedOn(viaLegacy, sat))
    }

    // ---- shouldShowTracker: entry-exists visibility override --------------

    @Test
    @DisplayName("shouldShowTracker: on-schedule tracker is always visible")
    fun shouldShowOnSchedule() {
        val t = tracker(id = "x", scheduleHistory = listOf(schedule(SCHEDULE_GENESIS_DATE, 1, 2, 3, 4, 5)))
        assertTrue(shouldShowTracker(t, fri))
        assertTrue(shouldShowTracker(t, fri, emptyMap()))
    }

    @Test
    @DisplayName("shouldShowTracker: off-schedule tracker with an entry that day is visible")
    fun shouldShowOffScheduleWithEntry() {
        val t = tracker(id = "x", scheduleHistory = listOf(schedule(SCHEDULE_GENESIS_DATE, 1, 2, 3, 4, 5)))
        assertTrue(shouldShowTracker(t, sat, mapOf("x" to entry(completed = true))))
        // Even completed=false counts — unchecking must not hide the row mid-edit.
        assertTrue(shouldShowTracker(t, sat, mapOf("x" to entry(completed = false))))
    }

    @Test
    @DisplayName("shouldShowTracker: off-schedule tracker with no entry is hidden")
    fun shouldShowOffScheduleWithoutEntry() {
        val t = tracker(id = "x", scheduleHistory = listOf(schedule(SCHEDULE_GENESIS_DATE, 1, 2, 3, 4, 5)))
        assertFalse(shouldShowTracker(t, sat, emptyMap()))
        assertFalse(shouldShowTracker(t, sat, mapOf("other" to entry(completed = true))))
    }

    @Test
    @DisplayName("shouldShowTracker: legacy weekly tracker with an off-day entry becomes visible")
    fun shouldShowLegacyWeeklyOffDayEntry() {
        val t = legacyTracker(id = "w", frequency = "weekly", weeklyDay = 1)
        assertFalse(shouldShowTracker(t, sat))
        assertTrue(shouldShowTracker(t, sat, mapOf("w" to entry(value = num(3)))))
    }

    @Test
    @DisplayName("shouldShowTracker: omitted dayLog reduces to pure expectation")
    fun shouldShowOmittedLog() {
        val t = tracker(id = "x", scheduleHistory = listOf(schedule(SCHEDULE_GENESIS_DATE, 1, 2, 3, 4, 5)))
        assertTrue(shouldShowTracker(t, fri))
        assertFalse(shouldShowTracker(t, sat))
    }

    // ---- normalizeTrackerSchedule (legacy → canonical) --------------------

    @Test
    @DisplayName("normalizeTrackerSchedule: legacy weekly → single genesis segment; strips legacy")
    fun normalizeLegacyWeekly() {
        val t = legacyTracker(id = "t", name = "X", frequency = "weekly", weeklyDay = 1)
        val normalized = normalizeTrackerSchedule(t)
        assertNotSame(t, normalized)
        assertEquals(listOf(schedule(SCHEDULE_GENESIS_DATE, 1)), normalized.scheduleHistory)
        assertFalse(normalized.extras.containsKey("frequency"))
        assertFalse(normalized.extras.containsKey("weeklyDay"))
        assertEquals("X", normalized.name)
    }

    @Test
    @DisplayName("normalizeTrackerSchedule: legacy daily → strips frequency, no scheduleHistory")
    fun normalizeLegacyDaily() {
        val t = legacyTracker(id = "t", frequency = "daily")
        val normalized = normalizeTrackerSchedule(t)
        assertNotSame(t, normalized)
        assertFalse(normalized.extras.containsKey("frequency"))
        assertNull(normalized.scheduleHistory)
    }

    @Test
    @DisplayName("normalizeTrackerSchedule: no legacy fields → same reference (unchanged)")
    fun normalizeNoLegacy() {
        val t = tracker(id = "t", scheduleHistory = listOf(schedule(SCHEDULE_GENESIS_DATE, 1)))
        assertSame(t, normalizeTrackerSchedule(t))
    }

    @Test
    @DisplayName("normalizeTrackerSchedule: existing scheduleHistory preserved, legacy stripped")
    fun normalizePreservesHistory() {
        val history = listOf(schedule(SCHEDULE_GENESIS_DATE, 2, 4))
        val t = legacyTracker(id = "t", frequency = "weekly", weeklyDay = 1, scheduleHistory = history)
        val normalized = normalizeTrackerSchedule(t)
        assertEquals(history, normalized.scheduleHistory, "canonical wins, weeklyDay ignored")
        assertFalse(normalized.extras.containsKey("frequency"))
        assertFalse(normalized.extras.containsKey("weeklyDay"))
    }

    @Test
    @DisplayName("normalizeTrackerSchedule: is idempotent")
    fun normalizeIsIdempotent() {
        val t = legacyTracker(id = "t", frequency = "weekly", weeklyDay = 3)
        val once = normalizeTrackerSchedule(t)
        assertSame(once, normalizeTrackerSchedule(once), "the second pass must be a no-op")
    }

    @Test
    @DisplayName("normalizeTrackerSchedule: invalid weeklyDay → daily (no scheduleHistory)")
    fun normalizeInvalidWeeklyDay() {
        val t = legacyTracker(id = "t", frequency = "weekly", weeklyDay = 9)
        val normalized = normalizeTrackerSchedule(t)
        assertNull(normalized.scheduleHistory)
        assertFalse(normalized.extras.containsKey("frequency"))
    }

    // Representation change, NOT a schedule change: derived visibility must be
    // provably identical before and after normalization, for every date.
    private val fullWeek = listOf(
        "2026-07-03", "2026-07-04", "2026-07-05", "2026-07-06",
        "2026-07-07", "2026-07-08", "2026-07-09",
    )

    @Test
    @DisplayName("normalizeTrackerSchedule: visibility identical pre/post for legacy weekly")
    fun normalizeKeepsWeeklyVisibility() {
        val legacy = legacyTracker(id = "t", frequency = "weekly", weeklyDay = 1)
        val normalized = normalizeTrackerSchedule(legacy)
        for (date in fullWeek) {
            assertEquals(
                shouldShowTracker(legacy, date),
                shouldShowTracker(normalized, date),
                "visibility diverged on $date",
            )
        }
    }

    @Test
    @DisplayName("normalizeTrackerSchedule: visibility identical pre/post for legacy daily")
    fun normalizeKeepsDailyVisibility() {
        val legacy = legacyTracker(id = "t", frequency = "daily")
        val normalized = normalizeTrackerSchedule(legacy)
        for (date in fullWeek) {
            assertEquals(
                shouldShowTracker(legacy, date),
                shouldShowTracker(normalized, date),
                "visibility diverged on $date",
            )
        }
    }

    @Test
    @DisplayName("write helper: an edit supersedes FUTURE-dated segments (clock-skew artifacts)")
    fun writeSupersedesFutureSegments() {
        val t = tracker(
            scheduleHistory = listOf(
                schedule(SCHEDULE_GENESIS_DATE, ALL_DAYS),
                schedule("2026-07-10", 3),
            ),
        )
        val result = computeScheduleHistoryUpdate(t, listOf(1, 2, 3, 4, 5), mon)
        assertTrue(result.changed)
        assertTrue(result.history!!.all { it.effectiveFrom <= mon })
        assertEquals(schedule(mon, 1, 2, 3, 4, 5), result.history.last())
    }

    @Test
    @DisplayName("write helper: a value-EQUAL edit still clears pending future segments")
    fun writeEqualEditClearsFutures() {
        val t = tracker(
            scheduleHistory = listOf(
                schedule(SCHEDULE_GENESIS_DATE, ALL_DAYS),
                schedule("2026-07-10", 3),
            ),
        )
        val result = computeScheduleHistoryUpdate(t, ALL_DAYS, mon)
        assertTrue(result.changed)
        assertTrue(result.history!!.all { it.effectiveFrom <= mon })
    }

    // ---- lastActiveScheduleDays (unpause restore-days) --------------------

    @Test
    @DisplayName("lastActiveScheduleDays: most recent non-empty segment wins, skipping the pause segment")
    fun lastActiveSkipsPause() {
        val t = tracker(
            scheduleHistory = listOf(
                schedule(SCHEDULE_GENESIS_DATE, ALL_DAYS),
                schedule("2026-06-01", 1, 2, 3, 4, 5),
                schedule("2026-07-01", emptyList()),
            ),
        )
        assertEquals(listOf(1, 2, 3, 4, 5), lastActiveScheduleDays(t))
    }

    @Test
    @DisplayName("lastActiveScheduleDays: is order-independent (unsorted history)")
    fun lastActiveOrderIndependent() {
        val t = tracker(
            scheduleHistory = listOf(
                schedule("2026-07-01", emptyList()),
                schedule("2026-06-01", 2, 4),
                schedule(SCHEDULE_GENESIS_DATE, ALL_DAYS),
            ),
        )
        assertEquals(listOf(2, 4), lastActiveScheduleDays(t))
    }

    @Test
    @DisplayName("lastActiveScheduleDays: normalizes the chosen segment (sort/dedupe/range)")
    fun lastActiveNormalizes() {
        val t = tracker(scheduleHistory = listOf(schedule(SCHEDULE_GENESIS_DATE, 5, 1, 1, 9, 3)))
        assertEquals(listOf(1, 3, 5), lastActiveScheduleDays(t))
    }

    @Test
    @DisplayName("lastActiveScheduleDays: a born-paused tracker (only empty segments) falls back to Daily")
    fun lastActiveBornPaused() {
        val t = tracker(scheduleHistory = listOf(schedule(SCHEDULE_GENESIS_DATE, emptyList())))
        assertEquals(ALL_DAYS, lastActiveScheduleDays(t))
    }

    @Test
    @DisplayName("lastActiveScheduleDays: legacy weekly falls back to [weeklyDay]")
    fun lastActiveLegacyWeekly() {
        assertEquals(listOf(3), lastActiveScheduleDays(legacyTracker(frequency = "weekly", weeklyDay = 3)))
    }

    @Test
    @DisplayName("lastActiveScheduleDays: legacy weekly with an invalid day falls back to Daily")
    fun lastActiveLegacyWeeklyInvalid() {
        assertEquals(ALL_DAYS, lastActiveScheduleDays(legacyTracker(frequency = "weekly", weeklyDay = 9)))
    }

    @Test
    @DisplayName("lastActiveScheduleDays: no schedule at all falls back to Daily")
    fun lastActiveNoSchedule() {
        assertEquals(ALL_DAYS, lastActiveScheduleDays(tracker()))
        assertEquals(ALL_DAYS, lastActiveScheduleDays(tracker(scheduleHistory = emptyList())))
    }
}

package dev.jtiisto.wellness.core.data.journal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `test/js/journal-config-mapping.test.js`, transcribed: the config form →
 * tracker-patch mapping, with the no-op / genesis / append / replace rules and
 * the empty→Daily coercion covered without rendering anything.
 *
 * "The key is absent" in the JS assertions is a null field here; the two
 * tri-state fields ([TargetField], [PolarityField]) spell out the difference
 * between "leave it" and "clear it" that JS expressed as `undefined`.
 */
class TrackerSaveFieldsTest {

    private val today = "2026-07-03"

    private fun form(
        days: List<Int> = ALL_DAYS,
        polarity: String = "",
        target: TargetField = TargetField.Unchanged,
        paused: Boolean = false,
    ) = FormSelections(days = days, polarity = polarity, target = target, paused = paused)

    // ---- schedule (new tracker) -------------------------------------------

    @Test
    @DisplayName("new tracker at Daily writes no scheduleHistory")
    fun newDailyWritesNothing() {
        assertNull(buildTrackerSaveFields(null, form(days = ALL_DAYS), today).scheduleHistory)
    }

    @Test
    @DisplayName("new tracker with empty selection coerces to Daily (no scheduleHistory)")
    fun newEmptyCoercesToDaily() {
        assertNull(buildTrackerSaveFields(null, form(days = emptyList()), today).scheduleHistory)
    }

    @Test
    @DisplayName("new narrower tracker gets a single genesis segment (no phantom past split)")
    fun newNarrowerGetsGenesis() {
        val fields = buildTrackerSaveFields(null, form(days = listOf(1, 2, 3, 4, 5)), today)
        assertEquals(listOf(schedule(SCHEDULE_GENESIS_DATE, 1, 2, 3, 4, 5)), fields.scheduleHistory)
    }

    @Test
    @DisplayName("new tracker normalizes chosen days (sort/dedupe/range)")
    fun newNormalizesDays() {
        val fields = buildTrackerSaveFields(null, form(days = listOf(5, 1, 1, 9, 3)), today)
        assertEquals(listOf(1, 3, 5), fields.scheduleHistory!![0].days)
    }

    // ---- schedule (editing) -----------------------------------------------

    @Test
    @DisplayName("editing to the same day-set writes no scheduleHistory (no-op)")
    fun editSameDaysIsNoOp() {
        val t = tracker(scheduleHistory = listOf(schedule(SCHEDULE_GENESIS_DATE, 1, 2, 3, 4, 5)))
        assertNull(buildTrackerSaveFields(t, form(days = listOf(1, 2, 3, 4, 5)), today).scheduleHistory)
    }

    @Test
    @DisplayName("editing a legacy daily tracker splits genesis + today")
    fun editLegacyDailySplits() {
        val t = legacyTracker(frequency = "daily")
        val fields = buildTrackerSaveFields(t, form(days = listOf(1, 2, 3, 4, 5)), today)
        assertEquals(
            listOf(
                schedule(SCHEDULE_GENESIS_DATE, ALL_DAYS),
                schedule(today, 1, 2, 3, 4, 5),
            ),
            fields.scheduleHistory,
        )
    }

    @Test
    @DisplayName("editing an existing scheduled tracker on a later day appends a segment")
    fun editLaterDayAppends() {
        val t = tracker(
            scheduleHistory = listOf(
                schedule(SCHEDULE_GENESIS_DATE, 1, 2, 3, 4, 5),
                schedule("2026-06-01", 1, 2, 3, 4, 5, 6),
            ),
        )
        val fields = buildTrackerSaveFields(t, form(days = listOf(1, 2, 3, 4, 5)), today)
        assertEquals(3, fields.scheduleHistory?.size)
        assertEquals(schedule(today, 1, 2, 3, 4, 5), fields.scheduleHistory!![2])
    }

    // ---- polarity ----------------------------------------------------------

    @Test
    @DisplayName("new tracker with a valid polarity writes it")
    fun newValidPolarity() {
        val fields = buildTrackerSaveFields(null, form(polarity = "negative"), today)
        assertEquals(PolarityField.Set("negative"), fields.polarity)
    }

    @Test
    @DisplayName("new tracker with unspecified polarity omits the key")
    fun newUnspecifiedPolarity() {
        assertEquals(PolarityField.Unchanged, buildTrackerSaveFields(null, form(), today).polarity)
    }

    @Test
    @DisplayName("editing to unspecified clears an existing polarity")
    fun editClearsPolarity() {
        val fields = buildTrackerSaveFields(tracker(polarity = "positive"), form(), today)
        assertEquals(PolarityField.Set(null), fields.polarity, "an explicit clear, not 'leave it'")
    }

    @Test
    @DisplayName("editing an unspecified tracker to a value writes it")
    fun editSetsPolarity() {
        val fields = buildTrackerSaveFields(tracker(), form(polarity = "neutral"), today)
        assertEquals(PolarityField.Set("neutral"), fields.polarity)
    }

    // ---- formatScheduleSummary --------------------------------------------

    @Test
    @DisplayName("formatScheduleSummary: Paused / Daily / Mon–Fri / slash list")
    fun scheduleSummary() {
        assertEquals("Daily", formatScheduleSummary(ALL_DAYS))
        // An empty day-set is a paused tracker.
        assertEquals("Paused", formatScheduleSummary(emptyList()))
        assertEquals("Paused", formatScheduleSummary(emptySet()))
        assertEquals("Mon–Fri", formatScheduleSummary(listOf(1, 2, 3, 4, 5)))
        assertEquals("Mon/Wed/Fri", formatScheduleSummary(listOf(1, 3, 5)))
        assertEquals("Sun/Sat", formatScheduleSummary(listOf(0, 6)))
        assertEquals("Mon/Sat", formatScheduleSummary(setOf(6, 1)))
    }

    // ---- target (quantifiable) --------------------------------------------

    @Test
    @DisplayName("buildTrackerSaveFields: new tracker with a target → single genesis segment")
    fun newTargetGenesis() {
        val fields = buildTrackerSaveFields(null, form(target = TargetField.Set(target(150, 170))), today)
        assertEquals(listOf(targetSegment(SCHEDULE_GENESIS_DATE, target(150, 170))), fields.targetHistory)
    }

    @Test
    @DisplayName("buildTrackerSaveFields: new tracker with null target writes no targetHistory")
    fun newNullTargetWritesNothing() {
        assertNull(buildTrackerSaveFields(null, form(target = TargetField.Set(null)), today).targetHistory)
    }

    @Test
    @DisplayName("buildTrackerSaveFields: target Unchanged leaves targetHistory untouched")
    fun unchangedTargetWritesNothing() {
        val t = tracker(targetHistory = listOf(targetSegment(SCHEDULE_GENESIS_DATE, target(min = 10))))
        assertNull(buildTrackerSaveFields(t, form(), today).targetHistory)
    }

    @Test
    @DisplayName("buildTrackerSaveFields: editing to a new target updates via the writer")
    fun editTargetUpdates() {
        val t = tracker(targetHistory = listOf(targetSegment(SCHEDULE_GENESIS_DATE, target(min = 120))))
        val fields = buildTrackerSaveFields(t, form(target = TargetField.Set(target(min = 150))), today)
        assertNotNull(fields.targetHistory)
        assertEquals(targetSegment(today, target(min = 150)), fields.targetHistory!!.last())
    }

    @Test
    @DisplayName("buildTrackerSaveFields: editing to the same target is a no-op")
    fun editSameTargetIsNoOp() {
        val t = tracker(targetHistory = listOf(targetSegment(SCHEDULE_GENESIS_DATE, target(min = 120))))
        assertNull(buildTrackerSaveFields(t, form(target = TargetField.Set(target(min = 120))), today).targetHistory)
    }

    @Test
    @DisplayName("buildTrackerSaveFields: clearing a target records a null-target segment")
    fun clearTargetRecordsSegment() {
        val t = tracker(targetHistory = listOf(targetSegment(SCHEDULE_GENESIS_DATE, target(min = 120))))
        val fields = buildTrackerSaveFields(t, form(target = TargetField.Set(null)), today)
        assertNotNull(fields.targetHistory)
        assertEquals(targetSegment(today, null), fields.targetHistory!!.last())
    }

    // ---- pause (empty-days schedule) --------------------------------------

    @Test
    @DisplayName("pause: new tracker created paused gets a single genesis [] segment")
    fun newPausedGenesis() {
        // `days` is ignored when paused — the empty schedule is what pauses it.
        val fields = buildTrackerSaveFields(null, form(days = ALL_DAYS, paused = true), today)
        assertEquals(listOf(schedule(SCHEDULE_GENESIS_DATE, emptyList())), fields.scheduleHistory)
    }

    @Test
    @DisplayName("pause: editing a scheduled tracker appends a today [] segment")
    fun pauseAppendsEmptySegment() {
        val t = tracker(scheduleHistory = listOf(schedule(SCHEDULE_GENESIS_DATE, 1, 2, 3, 4, 5)))
        val fields = buildTrackerSaveFields(t, form(days = listOf(1, 2, 3, 4, 5), paused = true), today)
        assertEquals(2, fields.scheduleHistory?.size)
        assertEquals(schedule(today, emptyList()), fields.scheduleHistory!!.last())
    }

    @Test
    @DisplayName("unpause: editing a paused tracker appends the picked days")
    fun unpauseAppendsPickedDays() {
        val t = tracker(
            scheduleHistory = listOf(
                schedule(SCHEDULE_GENESIS_DATE, ALL_DAYS),
                schedule("2026-06-01", emptyList()),
            ),
        )
        val fields = buildTrackerSaveFields(t, form(days = listOf(1, 2, 3, 4, 5), paused = false), today)
        assertEquals(schedule(today, 1, 2, 3, 4, 5), fields.scheduleHistory!!.last())
    }

    @Test
    @DisplayName("same-day pause then unpause replaces the today segment (no duplicate effectiveFrom)")
    fun sameDayPauseUnpauseReplaces() {
        val t = tracker(
            scheduleHistory = listOf(
                schedule(SCHEDULE_GENESIS_DATE, 1, 2, 3, 4, 5),
                schedule(today, emptyList()),
            ),
        )
        val fields = buildTrackerSaveFields(t, form(days = listOf(1, 2, 3, 4, 5), paused = false), today)
        assertEquals(2, fields.scheduleHistory?.size)
        val froms = fields.scheduleHistory!!.map { it.effectiveFrom }
        assertEquals(froms.size, froms.toSet().size)
        assertEquals(schedule(today, 1, 2, 3, 4, 5), fields.scheduleHistory[1])
    }

    @Test
    @DisplayName("regression: a NON-paused empty selection still coerces to Daily (no schedule written)")
    fun nonPausedEmptyStillCoerces() {
        // The footgun-guard is intact — only paused = true bypasses it.
        assertNull(
            buildTrackerSaveFields(null, form(days = emptyList(), paused = false), today).scheduleHistory,
        )
        // Editing to empty (unpaused) is a no-op against a Daily tracker.
        assertNull(
            buildTrackerSaveFields(
                legacyTracker(frequency = "daily"),
                form(days = emptyList(), paused = false),
                today,
            ).scheduleHistory,
        )
    }

    @Test
    @DisplayName("pause supersedes pending FUTURE segments (clock-skew) — all effectiveFrom <= today")
    fun pauseSupersedesFutures() {
        val t = tracker(
            scheduleHistory = listOf(
                schedule(SCHEDULE_GENESIS_DATE, ALL_DAYS),
                schedule("2026-07-10", 3),
            ),
        )
        val fields = buildTrackerSaveFields(t, form(days = ALL_DAYS, paused = true), today)
        assertTrue(fields.scheduleHistory!!.all { it.effectiveFrom <= today })
        assertEquals(schedule(today, emptyList()), fields.scheduleHistory.last())
    }
}

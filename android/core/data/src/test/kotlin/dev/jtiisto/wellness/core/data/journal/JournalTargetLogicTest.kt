package dev.jtiisto.wellness.core.data.journal

import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `test/js/journal-targets.test.js`, transcribed: parse/format, the
 * effective-dated `targetForDate` selection, the apply-from-today target
 * writer, and the day judgment.
 *
 * The [targetStatus] table is deliberately parallel to
 * `test_journal_adherence.py::TestTargetAdherence`, so this port and the Python
 * source of truth can never drift apart unnoticed.
 */
class JournalTargetLogicTest {

    private val today = "2026-07-06"

    // ---- parseTarget ------------------------------------------------------

    @Test
    @DisplayName("parseTarget: empty / whitespace / null → no target, no error")
    fun parseEmpty() {
        assertEquals(ParsedTarget(), parseTarget("", "positive"))
        assertEquals(ParsedTarget(), parseTarget("   ", "positive"))
        assertEquals(ParsedTarget(), parseTarget(null, "positive"))
    }

    @Test
    @DisplayName("parseTarget: bare number is polarity-defaulted")
    fun parseBareNumber() {
        assertEquals(target(min = 10), parseTarget("10", "positive").target)
        assertEquals(target(min = 10), parseTarget("10", null).target)
        assertEquals(target(min = 10), parseTarget("10", "neutral").target)
        assertEquals(target(max = 10), parseTarget("10", "negative").target)
    }

    @Test
    @DisplayName("""parseTarget: range "A-B"""")
    fun parseRange() {
        assertEquals(target(150, 170), parseTarget("150-170", "positive").target)
        assertEquals(target(150, 170), parseTarget("150 - 170", "positive").target)
        assertEquals(target(10, 10), parseTarget("10-10", "positive").target)
    }

    @Test
    @DisplayName("parseTarget: decimals + surrounding whitespace")
    fun parseDecimals() {
        assertEquals(target(min = 1.5), parseTarget("1.5", "positive").target)
        assertEquals(target(min = 10), parseTarget("  10  ", "positive").target)
    }

    @Test
    @DisplayName("parseTarget: invalid → error, null target")
    fun parseInvalid() {
        assertNull(parseTarget("170-150", "positive").target)
        assertNotNull(parseTarget("170-150", "positive").error)
        assertNotNull(parseTarget("abc", "positive").error)
        assertNotNull(parseTarget("-5", "positive").error, "a leading minus is rejected")
        assertNotNull(parseTarget("10x", "positive").error)
    }

    // ---- formatTarget -----------------------------------------------------

    @Test
    @DisplayName("formatTarget: bounds + unit")
    fun formatBounds() {
        assertEquals("", formatTarget(null))
        assertEquals("", formatTarget(target()))
        assertEquals("≥ 10", formatTarget(target(min = 10)))
        assertEquals("≥ 10 g", formatTarget(target(min = 10), "g"))
        assertEquals("≤ 2", formatTarget(target(max = 2)))
        assertEquals("150–170 g", formatTarget(target(150, 170), "g"))
        assertEquals("8 g", formatTarget(target(8, 8), "g"))
    }

    // ---- targetForDate ----------------------------------------------------

    @Test
    @DisplayName("targetForDate: absent history → null")
    fun targetForDateAbsent() {
        assertNull(targetForDate(tracker(id = "t"), today))
        assertNull(targetForDate(tracker(id = "t", targetHistory = emptyList()), today))
    }

    @Test
    @DisplayName("targetForDate: picks the segment in effect (past vs latest)")
    fun targetForDateSelection() {
        val t = tracker(
            targetHistory = listOf(
                targetSegment(SCHEDULE_GENESIS_DATE, target(min = 120)),
                targetSegment("2026-07-01", target(min = 150)),
            ),
        )
        assertEquals(target(min = 120), targetForDate(t, "2020-01-01"))
        assertEquals(target(min = 150), targetForDate(t, "2026-07-01"))
        assertEquals(target(min = 150), targetForDate(t, today))
    }

    @Test
    @DisplayName("targetForDate: a null-target segment reads as no target")
    fun targetForDateCleared() {
        val t = tracker(
            targetHistory = listOf(
                targetSegment(SCHEDULE_GENESIS_DATE, target(min = 120)),
                targetSegment("2026-07-01", null),
            ),
        )
        assertEquals(target(min = 120), targetForDate(t, "2020-01-01"))
        assertNull(targetForDate(t, today))
    }

    // ---- computeTargetHistoryUpdate ---------------------------------------

    @Test
    @DisplayName("computeTargetHistoryUpdate: no-op when unchanged (same ref)")
    fun targetUpdateNoOp() {
        val history = listOf(targetSegment(SCHEDULE_GENESIS_DATE, target(min = 10)))
        val result = computeTargetHistoryUpdate(tracker(targetHistory = history), target(min = 10), today)
        assertFalse(result.changed)
        assertSame(history, result.history)
    }

    @Test
    @DisplayName("computeTargetHistoryUpdate: first edit splits genesis (no prior target)")
    fun targetUpdateFirstEdit() {
        val result = computeTargetHistoryUpdate(tracker(id = "t"), target(150, 170), today)
        assertTrue(result.changed)
        assertEquals(
            listOf(
                targetSegment(SCHEDULE_GENESIS_DATE, null),
                targetSegment(today, target(150, 170)),
            ),
            result.history,
        )
    }

    @Test
    @DisplayName("computeTargetHistoryUpdate: later change appends a segment")
    fun targetUpdateAppends() {
        val t = tracker(
            targetHistory = listOf(
                targetSegment(SCHEDULE_GENESIS_DATE, null),
                targetSegment("2026-06-01", target(min = 120)),
            ),
        )
        val result = computeTargetHistoryUpdate(t, target(min = 150), today)
        assertTrue(result.changed)
        assertEquals(3, result.history?.size)
        assertEquals(targetSegment(today, target(min = 150)), result.history!![2])
    }

    @Test
    @DisplayName("computeTargetHistoryUpdate: same-day re-edit replaces the latest segment")
    fun targetUpdateSameDayReplaces() {
        val t = tracker(
            targetHistory = listOf(
                targetSegment(SCHEDULE_GENESIS_DATE, null),
                targetSegment(today, target(min = 120)),
            ),
        )
        val result = computeTargetHistoryUpdate(t, target(min = 150), today)
        assertTrue(result.changed)
        assertEquals(2, result.history?.size, "replaced, not appended")
        assertEquals(targetSegment(today, target(min = 150)), result.history!![1])
    }

    @Test
    @DisplayName("computeTargetHistoryUpdate: clearing a target records a null-target segment")
    fun targetUpdateClears() {
        val t = tracker(targetHistory = listOf(targetSegment(SCHEDULE_GENESIS_DATE, target(min = 10))))
        val result = computeTargetHistoryUpdate(t, null, today)
        assertTrue(result.changed)
        assertEquals(targetSegment(today, null), result.history!!.last())
    }

    // ---- formatTargetInput ------------------------------------------------

    @Test
    @DisplayName("formatTargetInput: renders re-parseable raw strings")
    fun formatInput() {
        assertEquals("", formatTargetInput(null))
        assertEquals("", formatTargetInput(target()))
        assertEquals("10", formatTargetInput(target(min = 10)))
        assertEquals("2", formatTargetInput(target(max = 2)))
        assertEquals("150-170", formatTargetInput(target(150, 170)))
        assertEquals("8-8", formatTargetInput(target(8, 8)), "exact targets use the range form")
    }

    @Test
    @DisplayName("formatTargetInput ↔ parseTarget round-trips (unchanged polarity)")
    fun formatInputRoundTrips() {
        val cases = listOf(
            target(min = 10) to "positive",
            target(max = 10) to "negative",
            target(150, 170) to "positive",
            target(8, 8) to "positive",
        )
        for ((value, polarity) in cases) {
            assertEquals(value, parseTarget(formatTargetInput(value), polarity).target, "round-trip failed for $value")
        }
    }

    // ---- targetStatus -----------------------------------------------------

    @Test
    @DisplayName("targetStatus: no-entry gate applied FIRST for every target kind")
    fun statusNoEntryGate() {
        assertEquals(TargetState.MET, targetStatus(target(max = 2), null, false, "negative"))
        assertEquals(TargetState.MISSED, targetStatus(target(min = 10), null, false, "positive"))
        assertEquals(TargetState.MISSED, targetStatus(target(150, 170), null, false, "neutral"))
        // The gate runs before the bound check that would read "no value ≤ max" as met.
        assertEquals(TargetState.MISSED, targetStatus(target(max = 2), null, false, "positive"))
        assertEquals(TargetState.MISSED, targetStatus(target(max = 2), null, false, null))
    }

    @Test
    @DisplayName("targetStatus: entry present with null value → missed")
    fun statusNullValue() {
        assertEquals(TargetState.MISSED, targetStatus(target(min = 10), null, true, "positive"))
        assertEquals(TargetState.MISSED, targetStatus(target(max = 2), null, true, "negative"))
        assertEquals(TargetState.MISSED, targetStatus(target(1, 5), null, true, "neutral"))
    }

    @Test
    @DisplayName("targetStatus: at-least (min) met / partial / missed")
    fun statusAtLeast() {
        assertEquals(TargetState.MET, targetStatus(target(min = 150), num(160), true, "positive"))
        assertEquals(TargetState.MET, targetStatus(target(min = 150), num(150), true, "positive"))
        assertEquals(TargetState.PARTIAL, targetStatus(target(min = 150), num(100), true, "positive"))
        assertEquals(
            TargetState.MISSED,
            targetStatus(target(min = 150), num(0), true, "positive"),
            "zero is missed, not partial",
        )
    }

    @Test
    @DisplayName("targetStatus: at-most (max) met / over — no partial state")
    fun statusAtMost() {
        assertEquals(TargetState.MET, targetStatus(target(max = 2), num(1), true, "negative"))
        assertEquals(TargetState.MET, targetStatus(target(max = 2), num(2), true, "negative"))
        assertEquals(TargetState.MISSED, targetStatus(target(max = 2), num(3), true, "negative"))
        assertEquals(TargetState.MET, targetStatus(target(max = 2), num(0), true, "negative"))
    }

    @Test
    @DisplayName("targetStatus: range (min,max) met / partial(below) / over(above)")
    fun statusRange() {
        assertEquals(TargetState.MET, targetStatus(target(150, 170), num(160), true, "neutral"))
        assertEquals(TargetState.MET, targetStatus(target(150, 170), num(150), true, "neutral"))
        assertEquals(TargetState.MET, targetStatus(target(150, 170), num(170), true, "neutral"))
        assertEquals(TargetState.PARTIAL, targetStatus(target(150, 170), num(100), true, "neutral"))
        assertEquals(TargetState.MISSED, targetStatus(target(150, 170), num(200), true, "neutral"))
    }

    // ---- dayStatus --------------------------------------------------------

    @Test
    @DisplayName("dayStatus: untargeted positive/neutral → met iff completed (checkbox parity)")
    fun dayStatusUntargetedPositive() {
        val positive = tracker(id = "t", polarity = "positive")
        assertEquals(TargetState.MET, dayStatus(positive, today, entry(completed = true)).state)
        assertEquals(TargetState.MISSED, dayStatus(positive, today, entry(completed = false)).state)
        assertEquals(TargetState.MISSED, dayStatus(positive, today, null).state)
        // A value with no checkbox is NOT met — no logged-counts special case.
        assertEquals(
            TargetState.MISSED,
            dayStatus(positive, today, entry(value = num(42), completed = false)).state,
        )
        val unspecified = tracker(id = "t")
        assertEquals(TargetState.MET, dayStatus(unspecified, today, entry(completed = true)).state)
        assertEquals(TargetState.MISSED, dayStatus(unspecified, today, null).state)
    }

    @Test
    @DisplayName("dayStatus: untargeted negative → met iff no entry (avoided)")
    fun dayStatusUntargetedNegative() {
        val negative = tracker(id = "t", polarity = "negative")
        assertEquals(TargetState.MET, dayStatus(negative, today, null).state)
        assertEquals(TargetState.MISSED, dayStatus(negative, today, entry(completed = true)).state)
        assertEquals(TargetState.MISSED, dayStatus(negative, today, entry(value = num(1))).state)
    }

    // ---- the emptiness rule (judgment presence) ---------------------------

    @Test
    @DisplayName("countsAsLogged: an entry counts iff it asserts something")
    fun countsAsLoggedMatrix() {
        assertFalse((null as EntryDto?).countsAsLogged())
        // The row an uncheck leaves behind: nothing said, so nothing counted.
        assertFalse(entry(completed = false).countsAsLogged())
        assertFalse(entry().countsAsLogged())
        assertFalse(entry(value = JsonNull, completed = false).countsAsLogged())
        // Either half is an assertion on its own.
        assertTrue(entry(completed = true).countsAsLogged())
        assertTrue(entry(value = num(0)).countsAsLogged(), "zero is a value")
        assertTrue(entry(value = text("")).countsAsLogged(), "so is empty text")
        assertTrue(entry(value = num(3), completed = false).countsAsLogged())
    }

    @Test
    @DisplayName("dayStatus: an emptied row judges as no row, so an uncheck can be retracted")
    fun dayStatusEmptiedRow() {
        val negative = tracker(id = "t", polarity = "negative")
        assertEquals(TargetState.MET, dayStatus(negative, today, entry(completed = false)).state)
        assertFalse(dayStatus(negative, today, entry(completed = false)).hasEntry)
        // A written value keeps the row judged even once the box is cleared —
        // the value is the assertion; blanking the field is its retraction.
        val valued = entry(value = num(1), completed = false)
        assertEquals(TargetState.MISSED, dayStatus(negative, today, valued).state)
        assertTrue(dayStatus(negative, today, valued).hasEntry)
    }

    @Test
    @DisplayName("dayStatus: targeted delegates to targetStatus with the in-effect target")
    fun dayStatusTargeted() {
        val t = tracker(
            id = "t",
            polarity = "positive",
            targetHistory = listOf(targetSegment(SCHEDULE_GENESIS_DATE, target(min = 150))),
        )
        val result = dayStatus(t, today, entry(value = num(160)))
        assertEquals(TargetState.MET, result.state)
        assertTrue(result.hasTarget)
        assertEquals(target(min = 150), result.target)
        assertEquals(TargetState.PARTIAL, dayStatus(t, today, entry(value = num(100))).state)
        assertEquals(TargetState.MISSED, dayStatus(t, today, null).state)
    }

    @Test
    @DisplayName("dayStatus: uses the target in effect on the date (effective-dated)")
    fun dayStatusEffectiveDated() {
        val t = tracker(
            id = "t",
            polarity = "positive",
            targetHistory = listOf(
                targetSegment(SCHEDULE_GENESIS_DATE, target(min = 100)),
                targetSegment("2026-07-06", target(min = 200)),
            ),
        )
        assertEquals(TargetState.MET, dayStatus(t, "2026-07-05", entry(value = num(150))).state)
        assertEquals(TargetState.PARTIAL, dayStatus(t, "2026-07-06", entry(value = num(150))).state)
    }

    @Test
    @DisplayName("dayStatus: a null-target segment reads as untargeted (checkbox parity)")
    fun dayStatusClearedTarget() {
        val t = tracker(
            id = "t",
            polarity = "positive",
            targetHistory = listOf(
                targetSegment(SCHEDULE_GENESIS_DATE, target(min = 100)),
                targetSegment("2026-07-01", null),
            ),
        )
        val result = dayStatus(t, today, entry(value = num(999), completed = false))
        assertFalse(result.hasTarget)
        assertEquals(TargetState.MISSED, result.state)
        assertEquals(TargetState.MET, dayStatus(t, today, entry(completed = true)).state)
    }

    // ---- formatTargetProgress ---------------------------------------------

    private fun progress(t: TrackerDto, e: EntryDto?, unit: String?) =
        formatTargetProgress(dayStatus(t, today, e), unit)

    @Test
    @DisplayName("formatTargetProgress: null when there is no target in effect")
    fun progressNoTarget() {
        assertNull(formatTargetProgress(dayStatus(tracker(id = "t"), today, entry(value = num(5))), "g"))
    }

    @Test
    @DisplayName("""formatTargetProgress: at-least shows "value / ≥ min" + fill ratio""")
    fun progressAtLeast() {
        val t = tracker(
            id = "t",
            polarity = "positive",
            targetHistory = listOf(targetSegment(SCHEDULE_GENESIS_DATE, target(min = 150))),
        )
        assertEquals(
            TargetProgress("160 / ≥ 150 g", ProgressTone.MET, 100.0),
            progress(t, entry(value = num(160)), "g"),
        )
        assertEquals(
            TargetProgress("75 / ≥ 150 g", ProgressTone.PARTIAL, 50.0),
            progress(t, entry(value = num(75)), "g"),
        )
    }

    @Test
    @DisplayName("formatTargetProgress: at-least with no entry shows the goal + empty fill")
    fun progressAtLeastNoEntry() {
        val t = tracker(
            id = "t",
            polarity = "positive",
            targetHistory = listOf(targetSegment(SCHEDULE_GENESIS_DATE, target(min = 150))),
        )
        assertEquals(TargetProgress("≥ 150 g", ProgressTone.NEUTRAL, 0.0), progress(t, null, "g"))
    }

    @Test
    @DisplayName("formatTargetProgress: at-most shows headroom / over (calm), no fill")
    fun progressAtMost() {
        val t = tracker(
            id = "t",
            polarity = "negative",
            targetHistory = listOf(targetSegment(SCHEDULE_GENESIS_DATE, target(max = 2))),
        )
        assertEquals(
            TargetProgress("1 of ≤ 2 · 1 left", ProgressTone.MET, null),
            progress(t, entry(value = num(1)), ""),
        )
        assertEquals(
            TargetProgress("3 of ≤ 2 · over by 1", ProgressTone.OVER, null),
            progress(t, entry(value = num(3)), ""),
        )
        // No entry on a negative tracker → avoided (met), never failure.
        assertEquals(TargetProgress("≤ 2 · avoided", ProgressTone.MET, null), progress(t, null, ""))
    }

    @Test
    @DisplayName("formatTargetProgress: range membership (in / below / above)")
    fun progressRange() {
        val t = tracker(
            id = "t",
            polarity = "neutral",
            targetHistory = listOf(targetSegment(SCHEDULE_GENESIS_DATE, target(150, 170))),
        )
        assertEquals(
            TargetProgress("160 in 150–170 g", ProgressTone.MET, null),
            progress(t, entry(value = num(160)), "g"),
        )
        assertEquals(ProgressTone.PARTIAL, progress(t, entry(value = num(100)), "g")?.tone)
        assertEquals(ProgressTone.OVER, progress(t, entry(value = num(200)), "g")?.tone)
    }

    @Test
    @DisplayName("targetStatus: non-numeric strings are missed for every target kind (twin of _coerce_numeric)")
    fun statusNonNumericStrings() {
        // NaN comparisons are all false — without coercion a string inside a
        // RANGE target silently read as 'met'.
        assertEquals(TargetState.MISSED, targetStatus(target(1, 5), text("three"), true, "positive"))
        assertEquals(TargetState.MISSED, targetStatus(target(max = 2), text("skipped it"), true, "negative"))
        assertEquals(TargetState.MISSED, targetStatus(target(min = 150), text("lots"), true, "positive"))
    }

    @Test
    @DisplayName("targetStatus: numeric strings still count")
    fun statusNumericStrings() {
        assertEquals(TargetState.MET, targetStatus(target(min = 150), text("160"), true, "positive"))
        assertEquals(TargetState.MET, targetStatus(target(1, 5), text("3"), true, "positive"))
    }

    @Test
    @DisplayName("formatTargetProgress: at-most with a value-less entry is neutral, matching the missed day dot")
    fun progressAtMostValueless() {
        val t = tracker(
            id = "t",
            polarity = "negative",
            targetHistory = listOf(targetSegment(SCHEDULE_GENESIS_DATE, target(max = 2))),
        )
        val result = progress(t, entry(completed = true), "drinks")
        assertEquals(ProgressTone.NEUTRAL, result?.tone, "'met' here would contradict dayStatus")
        assertTrue(result!!.text.startsWith("—"))
    }

    @Test
    @DisplayName("formatTargetProgress: non-numeric string value never reads as met/over math")
    fun progressNonNumericString() {
        val t = tracker(
            id = "t",
            polarity = "negative",
            targetHistory = listOf(targetSegment(SCHEDULE_GENESIS_DATE, target(max = 2))),
        )
        val result = progress(t, entry(value = text("a couple")), "drinks")
        assertEquals(ProgressTone.NEUTRAL, result?.tone)
        assertTrue(result!!.text.startsWith("a couple"), "the raw value is still displayed")
    }
}

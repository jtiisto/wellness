package dev.jtiisto.wellness.core.data.coach

import dev.jtiisto.wellness.core.data.network.DateString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * The ghost-value lookup, transcribed case for case from
 * `test/js/last-performance.test.js` (17 cases).
 *
 * The fixtures keep the JS names and dates so a failure can be read straight
 * against the original.
 */
class LastPerformanceTest {

    @Test
    @DisplayName("setHasData: true only when a real metric is present")
    fun setHasDataOnlyForRealMetrics() {
        assertFalse(setHasData(null))
        assertFalse(setHasData(buildJsonObject { }))
        assertFalse(setHasData(loggedSet(setNum = 1)))
        assertFalse(setHasData(loggedSet(completed = true)))
        // 0 is real data: a zero-weight set is a bodyweight set.
        assertTrue(setHasData(loggedSet(weight = 0.0)))
        assertTrue(setHasData(loggedSet(reps = 5)))
        assertTrue(setHasData(loggedSet(rpe = 8.0)))
        assertTrue(setHasData(loggedSet(durationSec = 40)))
    }

    @Test
    @DisplayName("no history -> null")
    fun noHistory() {
        assertNull(findLastPerformance("squat", "2026-06-07", emptyMap(), emptyMap()))
    }

    @Test
    @DisplayName("missing/empty slug -> null")
    fun missingSlug() {
        val plans = mapOf("2026-06-01" to planWith("ex_1", "squat"))
        val logs = mapOf("2026-06-01" to logWith("ex_1", listOf(loggedSet(setNum = 1, reps = 5))))
        assertNull(findLastPerformance("", "2026-06-07", plans, logs))
        assertNull(findLastPerformance(null, "2026-06-07", plans, logs))
    }

    @Test
    @DisplayName("one prior in a different workout (different exercise_key) -> that session")
    fun priorInAnotherWorkout() {
        val plans = mapOf("2026-06-01" to planWith("ex_3", "squat"))
        val logs = mapOf(
            "2026-06-01" to logWith("ex_3", listOf(loggedSet(setNum = 1, weight = 60.0, reps = 8, rpe = 8.0))),
        )

        val result = findLastPerformance("squat", "2026-06-07", plans, logs)

        assertEquals("2026-06-01", result?.date)
        assertEquals(listOf(loggedSet(setNum = 1, weight = 60.0, reps = 8, rpe = 8.0)), result?.sets)
    }

    @Test
    @DisplayName("multiple priors -> most recent chosen")
    fun mostRecentPrior() {
        val plans = mapOf(
            "2026-05-20" to planWith("ex_1", "squat"),
            "2026-06-03" to planWith("ex_9", "squat"),
        )
        val logs = mapOf(
            "2026-05-20" to logWith("ex_1", listOf(loggedSet(setNum = 1, weight = 50.0, reps = 10))),
            "2026-06-03" to logWith("ex_9", listOf(loggedSet(setNum = 1, weight = 65.0, reps = 6))),
        )

        val result = findLastPerformance("squat", "2026-06-07", plans, logs)

        assertEquals("2026-06-03", result?.date)
        assertEquals(65.0, result?.sets?.first()?.number("weight"))
    }

    @Test
    @DisplayName("planned-but-not-logged date is skipped -> next logged one")
    fun plannedButNotLoggedSkipped() {
        val plans = mapOf(
            "2026-05-28" to planWith("ex_1", "squat"),
            "2026-06-04" to planWith("ex_2", "squat"),
        )
        val logs = mapOf(
            // 06-04 was planned but only holds placeholders, so it was not performed.
            "2026-06-04" to logWith("ex_2", listOf(loggedSet(setNum = 1), loggedSet(setNum = 2, completed = true))),
            "2026-05-28" to logWith("ex_1", listOf(loggedSet(setNum = 1, weight = 40.0, reps = 12))),
        )

        assertEquals("2026-05-28", findLastPerformance("squat", "2026-06-07", plans, logs)?.date)
    }

    @Test
    @DisplayName("refDate excludes same-day and future sessions")
    fun strictlyBeforeRefDate() {
        val plans = mapOf(
            "2026-06-07" to planWith("ex_1", "squat"),
            "2026-06-10" to planWith("ex_2", "squat"),
            "2026-06-02" to planWith("ex_3", "squat"),
        )
        val logs = mapOf(
            "2026-06-07" to logWith("ex_1", listOf(loggedSet(setNum = 1, weight = 99.0, reps = 1))),
            "2026-06-10" to logWith("ex_2", listOf(loggedSet(setNum = 1, weight = 88.0, reps = 1))),
            "2026-06-02" to logWith("ex_3", listOf(loggedSet(setNum = 1, weight = 70.0, reps = 5))),
        )

        assertEquals("2026-06-02", findLastPerformance("squat", "2026-06-07", plans, logs)?.date)
    }

    @Test
    @DisplayName("only sets with data are returned (set_num preserved)")
    fun onlySetsWithData() {
        val plans = mapOf("2026-06-01" to planWith("ex_1", "squat"))
        val logs = mapOf(
            "2026-06-01" to logWith(
                "ex_1",
                listOf(
                    loggedSet(setNum = 1),
                    loggedSet(setNum = 2, weight = 60.0, reps = 8, rpe = 8.0),
                    loggedSet(setNum = 3, weight = 60.0, reps = 7),
                ),
            ),
        )

        val result = findLastPerformance("squat", "2026-06-07", plans, logs)

        assertEquals(listOf(2.0, 3.0), result?.sets?.map { it.number("set_num") })
    }

    @Test
    @DisplayName("slug present in plan but no log at all -> skipped")
    fun planWithoutLog() {
        val plans = mapOf("2026-06-01" to planWith("ex_1", "squat"))

        assertNull(findLastPerformance("squat", "2026-06-07", plans, emptyMap()))
    }

    // ---- exposure-aware lookup ---------------------------------------------

    @Test
    @DisplayName("no exposure requested -> most recent match regardless of exposures")
    fun noExposureRequested() {
        val result = findLastPerformance("squat", "2026-06-09", twoExposurePlans(), twoExposureLogs())

        assertEquals("2026-06-05", result?.date)
        assertEquals(61.0, result?.sets?.first()?.number("weight"))
    }

    @Test
    @DisplayName("same-exposure match wins over a NEWER other-exposure one")
    fun sameExposureBeatsNewer() {
        val result = findLastPerformance("squat", "2026-06-09", twoExposurePlans(), twoExposureLogs(), "HEAVY")

        assertEquals("2026-06-02", result?.date)
        assertEquals(92.5, result?.sets?.first()?.number("weight"))
    }

    @Test
    @DisplayName("older same-exposure sessions -> the most recent of that chain")
    fun mostRecentOfTheExposureChain() {
        val plans = mapOf(
            "2026-05-19" to planWith("ex_1", "squat", "HEAVY"),
            "2026-05-26" to planWith("ex_2", "squat", "HEAVY"),
            "2026-06-05" to planWith("ex_3", "squat", "LIGHT"),
        )
        val logs = mapOf(
            "2026-05-19" to logWith("ex_1", listOf(loggedSet(setNum = 1, weight = 85.0, reps = 5))),
            "2026-05-26" to logWith("ex_2", listOf(loggedSet(setNum = 1, weight = 87.5, reps = 5))),
            "2026-06-05" to logWith("ex_3", listOf(loggedSet(setNum = 1, weight = 61.0, reps = 10))),
        )

        assertEquals("2026-05-26", findLastPerformance("squat", "2026-06-09", plans, logs, "HEAVY")?.date)
    }

    @Test
    @DisplayName("no same-exposure history -> falls back to the newest any-exposure match")
    fun fallsBackToNewestAnyExposure() {
        // First cycle of a new exposure key: only the LIGHT chain exists.
        val plans = mapOf(
            "2026-05-29" to planWith("ex_1", "squat", "LIGHT"),
            "2026-06-05" to planWith("ex_2", "squat", "LIGHT"),
        )
        val logs = mapOf(
            "2026-05-29" to logWith("ex_1", listOf(loggedSet(setNum = 1, weight = 57.5, reps = 12))),
            "2026-06-05" to logWith("ex_2", listOf(loggedSet(setNum = 1, weight = 61.0, reps = 10))),
        )

        val result = findLastPerformance("squat", "2026-06-09", plans, logs, "HEAVY")

        assertEquals("2026-06-05", result?.date)
        assertEquals(61.0, result?.sets?.first()?.number("weight"))
    }

    @Test
    @DisplayName("a plan exercise with NO exposure never matches a requested one (but is fallback-eligible)")
    fun exposurelessPlanIsFallbackOnly() {
        // Pre-epoch history carries no exposure at all.
        val plans = mutableMapOf<DateString, PlanDto>("2026-06-05" to planWith("ex_1", "squat"))
        val logs = mutableMapOf<DateString, JsonObject>(
            "2026-06-05" to logWith("ex_1", listOf(loggedSet(setNum = 1, weight = 80.0, reps = 6))),
        )

        assertEquals("2026-06-05", findLastPerformance("squat", "2026-06-09", plans, logs, "HEAVY")?.date)

        // With a real HEAVY session present, the fallback loses to it even when older.
        plans["2026-05-22"] = planWith("ex_0", "squat", "HEAVY")
        logs["2026-05-22"] = logWith("ex_0", listOf(loggedSet(setNum = 1, weight = 90.0, reps = 5)))

        assertEquals("2026-05-22", findLastPerformance("squat", "2026-06-09", plans, logs, "HEAVY")?.date)
    }

    @Test
    @DisplayName("an unlogged same-exposure session is skipped, not counted as the match")
    fun unloggedSameExposureSkipped() {
        val plans = mapOf(
            "2026-05-22" to planWith("ex_1", "squat", "HEAVY"),
            "2026-06-01" to planWith("ex_2", "squat", "HEAVY"),
            "2026-06-05" to planWith("ex_3", "squat", "LIGHT"),
        )
        val logs = mapOf(
            "2026-05-22" to logWith("ex_1", listOf(loggedSet(setNum = 1, weight = 90.0, reps = 5))),
            "2026-06-01" to logWith("ex_2", listOf(loggedSet(setNum = 1), loggedSet(setNum = 2))),
            "2026-06-05" to logWith("ex_3", listOf(loggedSet(setNum = 1, weight = 61.0, reps = 10))),
        )

        assertEquals("2026-05-22", findLastPerformance("squat", "2026-06-09", plans, logs, "HEAVY")?.date)
    }

    @Test
    @DisplayName("exposure requested but no history at all -> null")
    fun exposureWithNoHistory() {
        assertNull(findLastPerformance("squat", "2026-06-09", emptyMap(), emptyMap(), "HEAVY"))
    }

    @Test
    @DisplayName("formatShortDate: YYYY-MM-DD -> \"Mon D\" without TZ shift")
    fun shortDate() {
        // Locale-pinned: the month name is localized here where the PWA indexes
        // a hardcoded English array (deviation 3).
        assertEquals("Jun 1", formatShortDate("2026-06-01", Locale.ENGLISH))
        assertEquals("Jan 31", formatShortDate("2026-01-31", Locale.ENGLISH))
        assertEquals("Dec 9", formatShortDate("2026-12-09", Locale.ENGLISH))
        assertEquals("", formatShortDate("", Locale.ENGLISH))
        assertEquals("", formatShortDate(null, Locale.ENGLISH))
        // Unparseable input is echoed rather than rendered as a wrong date.
        assertEquals("not-a-date", formatShortDate("not-a-date", Locale.ENGLISH))
    }

    private fun twoExposurePlans() = mapOf(
        "2026-06-02" to planWith("ex_1", "squat", "HEAVY"),
        "2026-06-05" to planWith("ex_2", "squat", "LIGHT"),
    )

    private fun twoExposureLogs() = mapOf(
        "2026-06-02" to logWith("ex_1", listOf(loggedSet(setNum = 1, weight = 92.5, reps = 4))),
        "2026-06-05" to logWith("ex_2", listOf(loggedSet(setNum = 1, weight = 61.0, reps = 10))),
    )
}

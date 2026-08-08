package dev.jtiisto.wellness.core.data.coach

import dev.jtiisto.wellness.core.data.network.DateString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Locale

/**
 * The coach helpers the PWA never had a unit test for: the calendar's status
 * matrix and date caption, the set-grid column shapes, name parsing, the hook
 * status mapping, and the merged progress guard.
 *
 * These are new pins rather than transcriptions. Each one states the rule it is
 * fixing in place, because there is no JS test to read it off.
 */
class CoachUiHelpersTest {

    private val today: DateString = "2026-08-08"
    private val squatPlan = planWith("ex_1", "squat")

    // ---- getWorkoutStatus -----------------------------------------------------

    @Test
    @DisplayName("a planned day with logged content is completed, whatever day it is")
    fun plannedAndLogged() {
        val plans = mapOf(today to squatPlan, "2026-08-01" to squatPlan)
        val logs = mapOf(
            today to logWith("ex_1", listOf(loggedSet(setNum = 1, weight = 60.0))),
            "2026-08-01" to logWith("ex_1", listOf(loggedSet(setNum = 1, reps = 5))),
        )

        assertEquals(WorkoutStatus.COMPLETED, getWorkoutStatus(today, plans, logs, today))
        assertEquals(WorkoutStatus.COMPLETED, getWorkoutStatus("2026-08-01", plans, logs, today))
    }

    @Test
    @DisplayName("a planned day whose log holds nothing real is missed, today included")
    fun plannedAndEmptyLog() {
        val plans = mapOf(today to squatPlan, "2026-08-01" to squatPlan)
        // A day that exists only because feedback was typed into it has no
        // exercise content, so it counts as not done.
        val emptyDay = buildJsonObject {
            put("session_feedback", buildJsonObject { put("general_notes", "felt off") })
            put("_lastModifiedAt", "t1")
        }
        val logs = mapOf(today to emptyDay, "2026-08-01" to emptyDay)

        assertEquals(WorkoutStatus.MISSED, getWorkoutStatus(today, plans, logs, today))
        assertEquals(WorkoutStatus.MISSED, getWorkoutStatus("2026-08-01", plans, logs, today))
    }

    @Test
    @DisplayName("with no log at all, today is still scheduled but a past day is missed")
    fun todayVersusPastWithNoLog() {
        val plans = mapOf(today to squatPlan, "2026-08-01" to squatPlan)

        assertEquals(WorkoutStatus.SCHEDULED, getWorkoutStatus(today, plans, emptyMap(), today))
        assertEquals(WorkoutStatus.MISSED, getWorkoutStatus("2026-08-01", plans, emptyMap(), today))
    }

    @Test
    @DisplayName("a future planned day is scheduled, logged or not")
    fun futurePlanned() {
        val plans = mapOf("2026-08-20" to squatPlan)
        val logs = mapOf("2026-08-20" to logWith("ex_1", listOf(loggedSet(setNum = 1, reps = 5))))

        assertEquals(WorkoutStatus.SCHEDULED, getWorkoutStatus("2026-08-20", plans, emptyMap(), today))
        assertEquals(WorkoutStatus.SCHEDULED, getWorkoutStatus("2026-08-20", plans, logs, today))
    }

    @Test
    @DisplayName("a quiet rest day stays blank")
    fun quietRestDay() {
        assertNull(getWorkoutStatus("2026-08-02", emptyMap(), emptyMap(), today))
        assertNull(
            getWorkoutStatus(
                "2026-08-02",
                emptyMap(),
                mapOf("2026-08-02" to buildJsonObject { put("session_feedback", buildJsonObject { }) }),
                today,
            ),
        )
    }

    @Test
    @DisplayName("an ad-hoc session on a rest day earns the completed dot")
    fun extraSessionEarnsCompleted() {
        val logs = mapOf(
            "2026-08-02" to buildJsonObject {
                put(EXTRA_SESSION_KEY, buildJsonObject { put("duration_min", 45) })
            },
        )

        assertEquals(WorkoutStatus.COMPLETED, getWorkoutStatus("2026-08-02", emptyMap(), logs, today))
    }

    // ---- hasAnyProgress ---------------------------------------------------------

    @Test
    @DisplayName("hasAnyProgress: null-safe, and blind to feedback and sync bookkeeping")
    fun hasAnyProgressGuards() {
        assertFalse(hasAnyProgress(null))
        assertFalse(hasAnyProgress(buildJsonObject { }))
        assertFalse(
            hasAnyProgress(
                buildJsonObject {
                    put("session_feedback", buildJsonObject { put("general_notes", "sore") })
                    put("_lastModifiedAt", "t1")
                    put("_lastModifiedBy", "client")
                },
            ),
        )
    }

    @Test
    @DisplayName("hasAnyProgress: sets, checked items or a duration all count")
    fun hasAnyProgressContent() {
        assertTrue(hasAnyProgress(buildJsonObject { put("ex_1", entryWithSets(loggedSet(setNum = 1))) }))
        assertTrue(
            hasAnyProgress(
                buildJsonObject {
                    put(
                        "ex_1",
                        buildJsonObject { put("completed_items", JsonArray(listOf(jsonText("a")))) },
                    )
                },
            ),
        )
        assertTrue(hasAnyProgress(buildJsonObject { put("ex_1", buildJsonObject { put("duration_min", 30) }) }))
    }

    @Test
    @DisplayName("hasAnyProgress: an empty entry, an explicit null duration and a tombstone are all nothing")
    fun hasAnyProgressEmptyShapes() {
        assertFalse(hasAnyProgress(buildJsonObject { put("ex_1", buildJsonObject { }) }))
        assertFalse(hasAnyProgress(buildJsonObject { put("ex_1", entryWithSets()) }))
        assertFalse(
            hasAnyProgress(buildJsonObject { put("ex_1", buildJsonObject { put("duration_min", JsonNull) }) }),
        )
        assertFalse(
            hasAnyProgress(
                buildJsonObject {
                    put(EXTRA_SESSION_KEY, buildJsonObject { put("_deleted", true) })
                },
            ),
        )
    }

    // ---- date helpers -------------------------------------------------------------

    @Test
    @DisplayName("formatSelectedDate: the three relative words, then a locale short date")
    fun selectedDateCaption() {
        val now = LocalDate.parse("2026-08-08")

        assertEquals("Today", formatSelectedDate("2026-08-08", now, Locale.ENGLISH))
        assertEquals("Yesterday", formatSelectedDate("2026-08-07", now, Locale.ENGLISH))
        assertEquals("Tomorrow", formatSelectedDate("2026-08-09", now, Locale.ENGLISH))
        assertEquals("Mon, Jun 1", formatSelectedDate("2026-06-01", now, Locale.ENGLISH))
    }

    @Test
    @DisplayName("formatDateShort: localized weekday plus the day number")
    fun dateShort() {
        assertEquals(DateShort("Mon", 1), formatDateShort("2026-06-01", Locale.ENGLISH))
        assertEquals(DateShort("Sat", 8), formatDateShort("2026-08-08", Locale.ENGLISH))
    }

    @Test
    @DisplayName("getDateRange: seven days centred on the given one, oldest first")
    fun dateRange() {
        assertEquals(
            listOf(
                "2026-08-05", "2026-08-06", "2026-08-07", "2026-08-08",
                "2026-08-09", "2026-08-10", "2026-08-11",
            ),
            getDateRange("2026-08-08"),
        )
        assertEquals(listOf("2026-08-07", "2026-08-08", "2026-08-09"), getDateRange("2026-08-08", daysAround = 1))
        // Month and leap-year boundaries come free from LocalDate.
        assertEquals(listOf("2026-07-31", "2026-08-01", "2026-08-02"), getDateRange("2026-08-01", daysAround = 1))
        assertEquals(listOf("2024-02-28", "2024-02-29", "2024-03-01"), getDateRange("2024-02-29", daysAround = 1))
    }

    @Test
    @DisplayName("isToday / isPast / isFuture compare lexically against the given today")
    fun relativeDayTests() {
        assertTrue(isToday("2026-08-08", today))
        assertFalse(isToday("2026-08-07", today))
        assertTrue(isPast("2026-08-07", today))
        assertFalse(isPast("2026-08-08", today))
        assertTrue(isFuture("2026-08-09", today))
        assertFalse(isFuture("2026-08-08", today))
    }

    // ---- set grid columns -------------------------------------------------------------

    @Test
    @DisplayName("buildColumns: the four grid shapes")
    fun columnShapes() {
        assertEquals(
            listOf(SetColumn("weight", "Weight", "lbs"), SetColumn("reps", "Reps"), SetColumn("rpe", "RPE")),
            buildColumns(showWeight = true, showTime = false),
        )
        assertEquals(
            listOf(SetColumn("reps", "Reps"), SetColumn("rpe", "RPE")),
            buildColumns(showWeight = false, showTime = false),
        )
        assertEquals(
            listOf(SetColumn("weight", "Weight", "lbs"), SetColumn("duration_sec", "Time", "sec")),
            buildColumns(showWeight = true, showTime = true),
        )
        assertEquals(
            listOf(SetColumn("duration_sec", "Time", "sec")),
            buildColumns(showWeight = false, showTime = true),
        )
    }

    // ---- names ---------------------------------------------------------------------------

    @Test
    @DisplayName("parseName lifts bracketed qualifiers out of the title")
    fun names() {
        assertEquals(ParsedName("Goblet Squat", listOf("KB")), parseName("Goblet Squat (KB)"))
        assertEquals(ParsedName("Row", listOf("DB", "each side")), parseName("Row (DB) [each side]"))
        assertEquals(ParsedName("Front Squat", emptyList()), parseName("Front Squat"))
        assertEquals(ParsedName("", listOf("only")), parseName("(only)"))
        // Non-greedy, so two groups stay two pills rather than swallowing the middle.
        assertEquals(ParsedName("A B", listOf("x", "y")), parseName("A (x) B (y)"))
    }

    // ---- hook status ------------------------------------------------------------------------

    @Test
    @DisplayName("statusToState: absent is default, a null exit code is pending, 0 is fired")
    fun hookStates() {
        assertEquals(HookButtonState.DEFAULT, statusToState(null))
        assertEquals(HookButtonState.PENDING, statusToState(HookResultDto(firedAt = "t1")))
        assertEquals(HookButtonState.FIRED, statusToState(HookResultDto(firedAt = "t1", exitCode = 0)))
        assertEquals(HookButtonState.FAILED, statusToState(HookResultDto(firedAt = "t1", exitCode = 1)))
        // -1 (spawn failure) and -2 (timeout) are not told apart from any other
        // non-zero exit: the UI offers the same retry for all of them.
        assertEquals(HookButtonState.FAILED, statusToState(HookResultDto(firedAt = "t1", exitCode = -1)))
        assertEquals(HookButtonState.FAILED, statusToState(HookResultDto(firedAt = "t1", exitCode = -2)))
    }
}

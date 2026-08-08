package dev.jtiisto.wellness.feature.coach

import dev.jtiisto.wellness.core.data.coach.EXTRA_SESSION_KEY
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/**
 * The ad-hoc Zone 2 session: its draft rules, and how a stored one renders.
 */
class ExtraSessionLogicTest {

    private val today: LocalDate = LocalDate.parse("2026-08-08")

    // ---- the draft --------------------------------------------------------

    @Test
    @DisplayName("Save stays disabled until a duration is entered")
    fun saveNeedsADuration() {
        assertFalse(draftCanSave(ExtraSessionDraft()))
        // A duration-less cardio entry carries no uploadable content, so its
        // dirty date would be dropped as unsatisfiable — hence the requirement.
        assertFalse(draftCanSave(ExtraSessionDraft(avgHr = "128", maxHr = "142")))
        assertFalse(draftCanSave(ExtraSessionDraft(durationMin = "   ")))
        assertFalse(draftCanSave(ExtraSessionDraft(durationMin = "abc")))
        assertTrue(draftCanSave(ExtraSessionDraft(durationMin = "45")))
        assertTrue(draftCanSave(ExtraSessionDraft(durationMin = "0")))
    }

    @Test
    @DisplayName("Save copies only the fields that carry a value, in one write")
    fun payloadSkipsEmptyFields() {
        val payload = draftPayload(ExtraSessionDraft(durationMin = "45", maxHr = "142"))

        assertEquals(setOf("duration_min", "max_hr"), payload.keys)
        assertEquals("45", payload["duration_min"]?.jsonPrimitive?.content)
        assertEquals("142", payload["max_hr"]?.jsonPrimitive?.content)
    }

    @Test
    @DisplayName("the payload writes integral values without a decimal point")
    fun payloadNumbers() {
        val payload = draftPayload(ExtraSessionDraft(durationMin = "45", avgHr = "128.5", maxHr = "junk"))

        assertEquals("45", payload["duration_min"]?.jsonPrimitive?.content)
        assertEquals("128.5", payload["avg_hr"]?.jsonPrimitive?.content)
        assertNull(payload["max_hr"])
    }

    // ---- how a stored session renders ----------------------------------------

    @Test
    @DisplayName("today's rest day offers the add button and no card")
    fun idleOnTodaysRestDay() {
        val rest = restDayState(log = null, date = today.toString())

        assertTrue(rest.showEmptyState)
        assertEquals(ExtraSessionState.Idle, rest.extra)
    }

    @Test
    @DisplayName("a past rest day with nothing logged shows the empty state alone")
    fun nothingOnAPastRestDay() {
        val rest = restDayState(log = null, date = "2026-08-01")

        assertTrue(rest.showEmptyState)
        assertNull(rest.extra)
    }

    @Test
    @DisplayName("a saved session replaces the empty state and stays visible on a past day")
    fun savedSession() {
        val log = buildJsonObject {
            put(
                EXTRA_SESSION_KEY,
                buildJsonObject {
                    put("duration_min", 45)
                    put("avg_hr", 128)
                },
            )
        }

        val onToday = restDayState(log, today.toString())
        assertFalse(onToday.showEmptyState)
        val saved = onToday.extra as ExtraSessionState.Saved
        assertEquals("45", saved.durationText)
        assertEquals("128", saved.avgHrText)
        assertEquals("", saved.maxHrText)
        assertTrue(saved.editable)

        // The same entry read on another day is visible but read-only.
        val onAPastDay = restDayState(log, "2026-08-01")
        assertFalse(onAPastDay.showEmptyState)
        assertFalse((onAPastDay.extra as ExtraSessionState.Saved).editable)
    }

    @Test
    @DisplayName("a tombstoned session renders as absent, delete still in flight or not")
    fun tombstoneRendersAsAbsent() {
        val log = buildJsonObject {
            put(
                EXTRA_SESSION_KEY,
                buildJsonObject {
                    put("duration_min", 45)
                    put("_deleted", true)
                },
            )
        }

        val rest = restDayState(log, today.toString())

        assertTrue(rest.showEmptyState)
        assertEquals(ExtraSessionState.Idle, rest.extra)
    }

    private fun restDayState(log: kotlinx.serialization.json.JsonObject?, date: String): WorkoutDayState.Rest =
        buildCoachUiState(
            selectedDate = date,
            viewMonth = YearMonth.of(2026, 8),
            plans = emptyMap(),
            logs = log?.let { mapOf(date to it) } ?: emptyMap(),
            earliestDate = null,
            today = today,
            hooks = WorkoutHooksState(),
            expandedExercises = emptySet(),
            locale = Locale.ENGLISH,
        ).day as WorkoutDayState.Rest
}

package dev.jtiisto.wellness.core.data.coach

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Progress pill and completion styling.
 *
 * Transcribed from the pure-function cases in
 * `test/e2e_browser/test_coach_interval.py` (3 getExerciseProgress, 5
 * isExerciseCompleted), plus explicit pins for the two places where the two
 * functions deliberately disagree. Those divergences are ported as they are:
 * "fixing" one of them would change what the PWA shows for the same log.
 */
class CoachProgressTest {

    // ---- getExerciseProgress (3 transcribed) --------------------------------

    @Test
    @DisplayName("interval, nothing logged -> no pill")
    fun intervalUnlogged() {
        assertNull(getExerciseProgress(exercise(type = TYPE_INTERVAL, rounds = 4), buildJsonObject { }))
    }

    @Test
    @DisplayName("interval, a duration logged -> a completed tick")
    fun intervalLogged() {
        val progress = getExerciseProgress(
            exercise(type = TYPE_INTERVAL, rounds = 4),
            buildJsonObject { put("duration_min", 12) },
        )

        assertEquals(ExerciseProgress("✓", complete = true), progress)
    }

    @Test
    @DisplayName("interval, an empty-string duration does not register as logged")
    fun intervalEmptyStringDuration() {
        assertNull(
            getExerciseProgress(
                exercise(type = TYPE_INTERVAL, rounds = 4),
                buildJsonObject { put("duration_min", "") },
            ),
        )
    }

    // ---- isExerciseCompleted (5 transcribed) ----------------------------------

    @Test
    @DisplayName("interval, nothing logged -> not completed")
    fun completedIntervalUnlogged() {
        assertFalse(isExerciseCompleted(exercise(type = TYPE_INTERVAL), null))
        assertFalse(isExerciseCompleted(exercise(type = TYPE_INTERVAL), buildJsonObject { }))
    }

    @Test
    @DisplayName("interval, a duration logged -> completed")
    fun completedIntervalLogged() {
        assertTrue(
            isExerciseCompleted(exercise(type = TYPE_INTERVAL), buildJsonObject { put("duration_min", 12) }),
        )
    }

    @Test
    @DisplayName("circuit completion derives from the number of set rows")
    fun completedCircuitFromSets() {
        val circuit = exercise(type = TYPE_CIRCUIT, targetSets = 3)

        assertFalse(isExerciseCompleted(circuit, entryWithSets(empty(), empty())))
        assertTrue(isExerciseCompleted(circuit, entryWithSets(empty(), empty(), empty())))
    }

    @Test
    @DisplayName("weighted_time completion derives from the number of set rows")
    fun completedWeightedTimeFromSets() {
        val hold = exercise(type = TYPE_WEIGHTED_TIME, targetSets = 1)

        assertFalse(isExerciseCompleted(hold, buildJsonObject { }))
        assertTrue(isExerciseCompleted(hold, entryWithSets(loggedSet(durationSec = 40))))
    }

    @Test
    @DisplayName("an unknown type is completed by any content at all")
    fun completedUnknownType() {
        val mystery = exercise(type = "mystery")

        assertFalse(isExerciseCompleted(mystery, buildJsonObject { }))
        assertTrue(isExerciseCompleted(mystery, entryWithSets(empty())))
        assertTrue(isExerciseCompleted(mystery, buildJsonObject { put("duration_min", 5) }))
        assertTrue(
            isExerciseCompleted(
                mystery,
                buildJsonObject { put("completed_items", JsonArray(listOf(jsonText("a")))) },
            ),
        )
    }

    // ---- the tally marks the pill is formatted from ---------------------------------

    @Test
    @DisplayName("set tallies count ticks against the target, exactly as the pill reads")
    fun setTally() {
        val strength = exercise(type = TYPE_STRENGTH, targetSets = 3)
        val log = entryWithSets(
            loggedSet(setNum = 1, completed = true),
            loggedSet(setNum = 2, weight = 60.0),
            loggedSet(setNum = 3, completed = true),
        )

        assertEquals(TallyMarks(filled = 2, total = 3), exerciseTally(strength, log))
        assertEquals(ExerciseProgress("2/3", complete = false), getExerciseProgress(strength, log))
        assertEquals(TallyMarks(filled = 0, total = 3), exerciseTally(strength, null))
    }

    @Test
    @DisplayName("checklist tallies count checked items against the item list")
    fun checklistTally() {
        val checklist = exercise(type = TYPE_CHECKLIST, items = listOf("a", "b"))

        assertEquals(
            TallyMarks(filled = 1, total = 2),
            exerciseTally(checklist, buildJsonObject { put("completed_items", JsonArray(listOf(jsonText("a")))) }),
        )
        assertEquals(TallyMarks(filled = 0, total = 2), exerciseTally(checklist, buildJsonObject { }))
    }

    @Test
    @DisplayName("cardio draws one mark: the tally exists where the pill does not")
    fun cardioTally() {
        val cardio = exercise(type = TYPE_DURATION, targetDurationMin = 30)

        assertEquals(
            TallyMarks(filled = 1, total = 1),
            exerciseTally(cardio, buildJsonObject { put("duration_min", 32) }),
        )
        assertEquals(TallyMarks(filled = 0, total = 1), exerciseTally(cardio, buildJsonObject { }))
        assertNull(getExerciseProgress(cardio, buildJsonObject { }))
        // The empty-string rule is the pill's, and the tally inherits it.
        assertEquals(
            TallyMarks(filled = 0, total = 1),
            exerciseTally(cardio, buildJsonObject { put("duration_min", "") }),
        )
    }

    @Test
    @DisplayName("nothing countable, nothing drawn — the same silences the pill keeps")
    fun tallyIsNullWhereThePillIs() {
        assertNull(exerciseTally(exercise(type = TYPE_STRENGTH), entryWithSets(empty())))
        assertNull(exerciseTally(exercise(type = TYPE_STRENGTH, targetSets = 0), entryWithSets(empty())))
        assertNull(exerciseTally(exercise(type = TYPE_CHECKLIST, items = emptyList()), buildJsonObject { }))
        assertNull(exerciseTally(exercise(type = "mystery"), buildJsonObject { }))
    }

    // ---- the two named divergences ----------------------------------------------

    @Test
    @DisplayName("divergence: completion counts set ROWS while the pill counts TICKED sets")
    fun setsVersusTicks() {
        val strength = exercise(type = TYPE_STRENGTH, targetSets = 3)
        // Three rows logged, none ticked. The accordion styles as completed while
        // the pill still reads 0/3 — the PWA behaves exactly this way, and both
        // readings are defensible ("I logged all three" vs "I ticked none").
        val log = entryWithSets(
            loggedSet(setNum = 1, weight = 60.0),
            loggedSet(setNum = 2, weight = 60.0),
            loggedSet(setNum = 3, weight = 60.0),
        )

        assertTrue(isExerciseCompleted(strength, log))
        assertEquals(ExerciseProgress("0/3", complete = false), getExerciseProgress(strength, log))
    }

    @Test
    @DisplayName("divergence: an empty-string duration completes the exercise but shows no pill")
    fun emptyStringDurationDivergence() {
        val cardio = exercise(type = TYPE_DURATION, targetDurationMin = 30)
        val log = buildJsonObject { put("duration_min", "") }

        assertNull(getExerciseProgress(cardio, log))
        assertTrue(isExerciseCompleted(cardio, log))
    }

    // ---- the rest of the matrix ---------------------------------------------------

    @Test
    @DisplayName("set progress counts only sets ticked with a real boolean true")
    fun tickedSetsOnly() {
        val strength = exercise(type = TYPE_STRENGTH, targetSets = 3)
        val log = entryWithSets(
            loggedSet(setNum = 1, completed = true),
            loggedSet(setNum = 2, completed = false),
            buildJsonObject {
                put("set_num", 3)
                // A stringified "true" is not a tick — the JS compares with ===.
                put("completed", "true")
            },
        )

        assertEquals(ExerciseProgress("1/3", complete = false), getExerciseProgress(strength, log))
    }

    @Test
    @DisplayName("no target sets means no pill at all")
    fun noTargetNoPill() {
        assertNull(getExerciseProgress(exercise(type = TYPE_STRENGTH), entryWithSets(empty())))
        assertNull(getExerciseProgress(exercise(type = TYPE_STRENGTH, targetSets = 0), entryWithSets(empty())))
        assertNull(getExerciseProgress(exercise(type = TYPE_CHECKLIST, items = emptyList()), buildJsonObject { }))
        assertNull(getExerciseProgress(exercise(type = "mystery"), buildJsonObject { }))
    }

    @Test
    @DisplayName("checklist progress counts checked items and completes on equality")
    fun checklistProgress() {
        val checklist = exercise(type = TYPE_CHECKLIST, items = listOf("a", "b"))
        val oneDone = buildJsonObject { put("completed_items", JsonArray(listOf(jsonText("a")))) }
        val bothDone = buildJsonObject {
            put("completed_items", JsonArray(listOf(jsonText("a"), jsonText("b"))))
        }

        assertEquals(ExerciseProgress("1/2", complete = false), getExerciseProgress(checklist, oneDone))
        assertEquals(ExerciseProgress("2/2", complete = true), getExerciseProgress(checklist, bothDone))
        assertFalse(isExerciseCompleted(checklist, oneDone))
        assertTrue(isExerciseCompleted(checklist, bothDone))
    }

    @Test
    @DisplayName("a checklist with no items is completed by an empty log — strict equality, 0 == 0")
    fun emptyChecklistIsComplete() {
        assertTrue(isExerciseCompleted(exercise(type = TYPE_CHECKLIST), buildJsonObject { }))
    }

    @Test
    @DisplayName("a set-based exercise with no target still needs one logged row")
    fun defaultTargetSets() {
        // `target_sets || 1`: absent AND zero both fall through to one.
        assertFalse(isExerciseCompleted(exercise(type = TYPE_STRENGTH), buildJsonObject { }))
        assertTrue(isExerciseCompleted(exercise(type = TYPE_STRENGTH), entryWithSets(empty())))
        assertTrue(isExerciseCompleted(exercise(type = TYPE_STRENGTH, targetSets = 0), entryWithSets(empty())))
    }

    private fun empty() = buildJsonObject { }
}

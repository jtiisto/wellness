package dev.jtiisto.wellness.ui.coach

import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.coach.EXTRA_SESSION_KEY
import dev.jtiisto.wellness.core.data.coach.PlanDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Where the Phase 4 debug tab's scratch write goes, and what it renders. */
class CoachDebugLogicTest {

    private fun plan(text: String): PlanDto = WellnessJson.decodeFromString(PlanDto.serializer(), text)

    @Test
    @DisplayName("the scratch set targets the first planned strength exercise, in block order")
    fun targetsTheFirstStrengthExercise() {
        val write = scratchWriteFor(
            plan(
                """{"session_id":1,"blocks":[
                   {"block_index":0,"block_type":"warmup","exercises":[
                     {"id":"w1","name":"Mobility","type":"checklist"}]},
                   {"block_index":1,"block_type":"strength","exercises":[
                     {"id":"s1","name":"Bench","type":"strength"},
                     {"id":"s2","name":"Row","type":"strength"}]}]}""",
            ),
        )

        assertEquals("s1", write.exerciseKey)
        assertEquals("Bench", write.label)
        assertEquals(
            """{"sets":[{"set_num":1,"weight":100,"reps":5,"completed":true}]}""",
            write.data.toString(),
        )
    }

    @Test
    @DisplayName("a rest day writes an ad-hoc Zone 2 session instead")
    fun restDayFallsBackToTheAdHocKey() {
        val write = scratchWriteFor(null)

        // The key itself is the off-plan signal; the server needs no planned
        // exercise to attach it to.
        assertEquals(EXTRA_SESSION_KEY, write.exerciseKey)
        assertEquals("""{"duration_min":30}""", write.data.toString())
    }

    @Test
    @DisplayName("a planned day with no strength work falls back the same way")
    fun cardioOnlyDayFallsBackToo() {
        val write = scratchWriteFor(
            plan(
                """{"session_id":1,"blocks":[{"block_index":0,"block_type":"cardio","exercises":[
                   {"id":"c1","name":"Zone 2","type":"duration"}]}]}""",
            ),
        )

        assertEquals(EXTRA_SESSION_KEY, write.exerciseKey)
    }

    @Test
    @DisplayName("targets render as one line, free-form server text verbatim")
    fun targetsRenderAsOneLine() {
        assertEquals(
            "4 x 8-10 · @ 70% · RPE 6-7",
            exerciseTargets(
                targetSets = 4,
                targetReps = "8-10",
                targetDurationMin = null,
                targetDurationSec = null,
                targetLoad = "70%",
                targetRpe = "6-7",
            ),
        )
        assertEquals(
            "3 sets · 45 s",
            exerciseTargets(3, null, null, 45, null, null),
        )
        assertEquals(
            "12 min",
            exerciseTargets(null, null, 12, null, null, null),
        )
        // Reps without a set count still say something useful.
        assertEquals("8-10", exerciseTargets(null, "8-10", null, null, null, null))
        assertEquals("", exerciseTargets(null, null, null, null, null, null))
    }
}

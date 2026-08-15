package dev.jtiisto.wellness.core.data.coach

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Target and prescription formatting.
 *
 * Transcribed from `test/js/prescription.test.js` (4 cases) and the pure-function
 * cases embedded in `test/e2e_browser/test_coach_interval.py` (8 formatTarget,
 * 3 formatInterval), plus pins for the types those suites do not reach and for
 * the one declared micro-deviation.
 */
class CoachFormattingTest {

    // ---- buildPrescription (4 transcribed) ---------------------------------

    @Test
    @DisplayName("buildPrescription: empty when no optional fields are set")
    fun prescriptionEmpty() {
        assertEquals(emptyList<RxToken>(), buildPrescription(exercise(targetSets = 3, targetReps = "5")))
    }

    @Test
    @DisplayName("buildPrescription: full order is rpe, load, tempo")
    fun prescriptionOrder() {
        assertEquals(
            listOf(
                RxToken(RxKind.RPE, "6-7"),
                RxToken(RxKind.LOAD, "70%"),
                RxToken(RxKind.TEMPO, "3-1-2-0"),
            ),
            buildPrescription(exercise(targetRpe = "6-7", targetLoad = "70%", tempo = "3-1-2-0")),
        )
    }

    @Test
    @DisplayName("buildPrescription: omits absent tokens, preserves order")
    fun prescriptionOmitsAbsent() {
        assertEquals(
            listOf(RxToken(RxKind.LOAD, "24kg")),
            buildPrescription(exercise(targetLoad = "24kg")),
        )
        assertEquals(
            listOf(RxToken(RxKind.RPE, "8"), RxToken(RxKind.TEMPO, "30X1")),
            buildPrescription(exercise(tempo = "30X1", targetRpe = "8")),
        )
    }

    @Test
    @DisplayName("buildPrescription: a bare numeric RPE renders as plain text")
    fun prescriptionNumericRpe() {
        // The JS coerces with String(); here the DTO already types these three
        // fields as strings — the server stores free-form coaching text in them
        // ("6-7", "70%"), so a bare "8" is just the degenerate case.
        assertEquals(listOf(RxToken(RxKind.RPE, "8")), buildPrescription(exercise(targetRpe = "8")))
    }

    // ---- formatTarget, interval (8 transcribed) -----------------------------

    @Test
    @DisplayName("interval: structured rounds/work/rest win over target_duration_min")
    fun structuredBeatsDuration() {
        assertEquals(
            "4 × 0:30/1:30",
            formatTarget(
                exercise(
                    type = TYPE_INTERVAL,
                    targetDurationMin = 20,
                    rounds = 4,
                    workDurationSec = 30,
                    restDurationSec = 90,
                ),
            ),
        )
    }

    @Test
    @DisplayName("interval: rounds + work + rest")
    fun roundsWorkRest() {
        assertEquals(
            "4 × 0:30/1:30",
            formatTarget(exercise(type = TYPE_INTERVAL, rounds = 4, workDurationSec = 30, restDurationSec = 90)),
        )
    }

    @Test
    @DisplayName("interval: rounds + work only")
    fun roundsWorkOnly() {
        assertEquals("4 × 0:30", formatTarget(exercise(type = TYPE_INTERVAL, rounds = 4, workDurationSec = 30)))
    }

    @Test
    @DisplayName("interval: multi-minute work/rest formats as mm:ss")
    fun multiMinute() {
        assertEquals(
            "4 × 3:00/2:00",
            formatTarget(exercise(type = TYPE_INTERVAL, rounds = 4, workDurationSec = 180, restDurationSec = 120)),
        )
    }

    @Test
    @DisplayName("interval: rounds only")
    fun roundsOnly() {
        assertEquals("4 rounds", formatTarget(exercise(type = TYPE_INTERVAL, rounds = 4)))
    }

    @Test
    @DisplayName("interval: nothing to show")
    fun intervalEmpty() {
        assertEquals("", formatTarget(exercise(type = TYPE_INTERVAL)))
    }

    @Test
    @DisplayName("interval: an exercise with no timing of its own falls back to the block's")
    fun fallsBackToBlock() {
        assertEquals(
            "4 × 0:30/1:30",
            formatTarget(
                exercise(type = TYPE_INTERVAL, targetDurationMin = 20),
                block(rounds = 4, workDurationSec = 30, restDurationSec = 90),
            ),
        )
    }

    @Test
    @DisplayName("interval: the exercise's own timing beats the block's")
    fun exerciseBeatsBlock() {
        assertEquals(
            "8 × 1:00/1:00",
            formatTarget(
                exercise(type = TYPE_INTERVAL, rounds = 8, workDurationSec = 60, restDurationSec = 60),
                block(rounds = 4, workDurationSec = 30, restDurationSec = 90),
            ),
        )
    }

    // ---- formatInterval (3 transcribed) --------------------------------------

    @Test
    @DisplayName("formatInterval: rounds, work and rest")
    fun formatIntervalFull() {
        assertEquals("4 × 0:40/0:20", formatInterval(rounds = 4, workSec = 40, restSec = 20))
    }

    @Test
    @DisplayName("formatInterval: work and rest without rounds")
    fun formatIntervalNoRounds() {
        assertEquals("0:40/0:20", formatInterval(workSec = 40, restSec = 20))
    }

    @Test
    @DisplayName("formatInterval: nothing set")
    fun formatIntervalEmpty() {
        assertEquals("", formatInterval())
    }

    // ---- new pins -------------------------------------------------------------

    @Test
    @DisplayName("formatInterval: a zero is absent, not a value")
    fun zeroIsAbsent() {
        // JS-truthy guards: `rest_duration_sec: 0` must read as work-only.
        assertEquals("4 × 0:30", formatInterval(rounds = 4, workSec = 30, restSec = 0))
        assertEquals("0:30", formatInterval(rounds = 0, workSec = 30))
        assertEquals("", formatInterval(rounds = 0, workSec = 0, restSec = 0, durationMin = 0))
        assertEquals("20 min", formatInterval(durationMin = 20))
    }

    @Test
    @DisplayName("formatInterval: a block supplies the badge's timing, never its duration")
    fun blockBadge() {
        assertEquals("3 × 0:45/0:15", formatInterval(block(rounds = 3, workDurationSec = 45, restDurationSec = 15)))
        assertEquals("", formatInterval(block()))
    }

    @Test
    @DisplayName("strength and circuit: lowercase x, with either half alone as a fallback")
    fun strengthTargets() {
        assertEquals("3 x 8", formatTarget(exercise(targetSets = 3, targetReps = "8")))
        assertEquals("8-10", formatTarget(exercise(targetReps = "8-10")))
        assertEquals("3", formatTarget(exercise(targetSets = 3)))
        assertEquals("", formatTarget(exercise()))
        assertEquals("4 x 12", formatTarget(exercise(type = TYPE_CIRCUIT, targetSets = 4, targetReps = "12")))
        // Truthiness again: an empty reps string falls through to the set count.
        assertEquals("3", formatTarget(exercise(targetSets = 3, targetReps = "")))
    }

    @Test
    @DisplayName("duration: a missing target renders empty rather than the PWA's \"undefined min\"")
    fun durationMicroDeviation() {
        assertEquals("30 min", formatTarget(exercise(type = TYPE_DURATION, targetDurationMin = 30)))
        // The declared micro-deviation: the PWA's unguarded template literal
        // prints the string "undefined min" here.
        assertEquals("", formatTarget(exercise(type = TYPE_DURATION)))
    }

    @Test
    @DisplayName("checklist counts its items; weighted_time defaults to 60 seconds")
    fun checklistAndWeightedTime() {
        assertEquals("2 items", formatTarget(exercise(type = TYPE_CHECKLIST, items = listOf("a", "b"))))
        assertEquals("0 items", formatTarget(exercise(type = TYPE_CHECKLIST)))
        assertEquals("45s", formatTarget(exercise(type = TYPE_WEIGHTED_TIME, targetDurationSec = 45)))
        assertEquals("60s", formatTarget(exercise(type = TYPE_WEIGHTED_TIME)))
        assertEquals("60s", formatTarget(exercise(type = TYPE_WEIGHTED_TIME, targetDurationSec = 0)))
    }

    @Test
    @DisplayName("an unknown exercise type shows no target at all")
    fun unknownType() {
        assertEquals("", formatTarget(exercise(type = "mystery", targetSets = 3, targetReps = "8")))
    }
}

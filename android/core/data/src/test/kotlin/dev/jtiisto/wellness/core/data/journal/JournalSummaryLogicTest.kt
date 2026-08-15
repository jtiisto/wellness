package dev.jtiisto.wellness.core.data.journal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `test/js/journal-summary.test.js`, transcribed onto its successor: the
 * category band's rollup, which splits the day into habits, avoidances and
 * observations instead of the JS suite's single on-track fraction.
 *
 * The distinction the whole file turns on is unchanged: an untargeted *neutral*
 * tracker is an observation, not a goal — it never joins the fraction. What the
 * split adds is that avoidances leave it too (holding one is not "doing" it),
 * and that an unnoted observation is still counted as asked-about.
 */
class JournalSummaryLogicTest {

    private val mon = "2026-07-06" // a Monday (local weekday 1)

    @Test
    @DisplayName("categoryRollup: not-expected trackers are excluded (off-schedule ≠ miss)")
    fun excludesOffSchedule() {
        val weekendOnly = tracker(
            id = "w",
            polarity = "positive",
            scheduleHistory = listOf(schedule(SCHEDULE_GENESIS_DATE, 0, 6)),
        )
        val daily = tracker(id = "d", polarity = "positive")
        val rollup = categoryRollup(listOf(weekendOnly, daily), mon, mapOf("d" to entry(completed = true)))!!
        assertEquals(1, rollup.habits, "weekendOnly is not expected on Monday")
        assertEquals(1, rollup.habitsMet)
    }

    @Test
    @DisplayName("categoryRollup: null when the day expects nothing of the category")
    fun nothingExpected() {
        val weekendOnly = tracker(
            id = "w",
            polarity = "positive",
            scheduleHistory = listOf(schedule(SCHEDULE_GENESIS_DATE, 0, 6)),
        )
        // Logged anyway, which is what makes the row visible — but the day did
        // not ask for it, so the band stays bare.
        assertNull(categoryRollup(listOf(weekendOnly), mon, mapOf("w" to entry(completed = true))))
        assertNull(categoryRollup(emptyList(), mon))
        assertNull(categoryRollup(null, mon))
    }

    @Test
    @DisplayName("categoryRollup: avoidances leave the habit fraction — held is not done")
    fun avoidancesAreTheirOwnClass() {
        val trackers = listOf(
            tracker(id = "p", polarity = "positive"),
            tracker(id = "q", polarity = "positive"),
            tracker(id = "v", polarity = "negative"),
        )
        val rollup = categoryRollup(trackers, mon, mapOf("p" to entry(completed = true)))!!
        assertEquals(2, rollup.habits, "the negative tracker is not a habit")
        assertEquals(1, rollup.habitsMet)
        assertEquals(1, rollup.habitsNotYet)
        assertEquals(1, rollup.avoidances)
        assertEquals(0, rollup.avoidancesBroken, "no entry = avoided")
        assertEquals(0, rollup.observationsExpected)
    }

    @Test
    @DisplayName("categoryRollup: a logged negative tracker is broken, not merely not-met")
    fun loggedNegative() {
        val rollup = categoryRollup(
            listOf(tracker(id = "v", polarity = "negative")),
            mon,
            mapOf("v" to entry(value = num(1))),
        )!!
        assertEquals(1, rollup.avoidances)
        assertEquals(1, rollup.avoidancesBroken, "logged = not avoided")
        assertEquals(0, rollup.habits)
    }

    @Test
    @DisplayName("categoryRollup: a negative tracker over its ceiling is broken")
    fun negativeOverCeiling() {
        val capped = tracker(
            id = "c",
            polarity = "negative",
            targetHistory = listOf(targetSegment(SCHEDULE_GENESIS_DATE, target(max = 2))),
        )
        val under = categoryRollup(listOf(capped), mon, mapOf("c" to entry(value = num(2))))!!
        assertEquals(0, under.avoidancesBroken, "at the ceiling is still held")
        val over = categoryRollup(listOf(capped), mon, mapOf("c" to entry(value = num(3))))!!
        assertEquals(1, over.avoidances)
        assertEquals(1, over.avoidancesBroken)
    }

    @Test
    @DisplayName("categoryRollup: targeted tracker uses value-vs-target; partial is bucketed")
    fun targetedBucketing() {
        val t = tracker(
            id = "t",
            polarity = "positive",
            targetHistory = listOf(targetSegment(SCHEDULE_GENESIS_DATE, target(min = 150))),
        )
        val met = categoryRollup(listOf(t), mon, mapOf("t" to entry(value = num(160))))!!
        assertEquals(listOf(1, 0, 0), listOf(met.habitsMet, met.habitsPartial, met.habitsNotYet))
        val partial = categoryRollup(listOf(t), mon, mapOf("t" to entry(value = num(100))))!!
        assertEquals(listOf(0, 1, 0), listOf(partial.habitsMet, partial.habitsPartial, partial.habitsNotYet))
        val missed = categoryRollup(listOf(t), mon, mapOf("t" to entry(value = num(0))))!!
        assertEquals(listOf(0, 0, 1), listOf(missed.habitsMet, missed.habitsPartial, missed.habitsNotYet))
    }

    @Test
    @DisplayName("categoryRollup: every habit bucket matches its own dayStatus verdict")
    fun bucketsFollowDayStatus() {
        val trackers = listOf(
            tracker(
                id = "a",
                polarity = "positive",
                targetHistory = listOf(targetSegment(SCHEDULE_GENESIS_DATE, target(min = 10))),
            ),
            tracker(
                id = "b",
                polarity = "positive",
                targetHistory = listOf(targetSegment(SCHEDULE_GENESIS_DATE, target(min = 10))),
            ),
            tracker(id = "c", polarity = "positive"),
        )
        val log = mapOf("a" to entry(value = num(12)), "b" to entry(value = num(4)))
        val rollup = categoryRollup(trackers, mon, log)!!

        val verdicts = trackers.map { dayStatus(it, mon, log[it.id]).state }
        assertEquals(listOf(TargetState.MET, TargetState.PARTIAL, TargetState.MISSED), verdicts)
        assertEquals(verdicts.count { it == TargetState.MET }, rollup.habitsMet)
        assertEquals(verdicts.count { it == TargetState.PARTIAL }, rollup.habitsPartial)
        assertEquals(verdicts.count { it == TargetState.MISSED }, rollup.habitsNotYet)
    }

    @Test
    @DisplayName("categoryRollup: untargeted neutral observations stay out of the fraction")
    fun observationsExcluded() {
        val trackers = listOf(
            tracker(id = "p1", polarity = "positive"),
            tracker(id = "p2", polarity = "positive"),
            tracker(id = "headache", polarity = "neutral"),
        )
        val rollup = categoryRollup(
            trackers,
            mon,
            mapOf("p1" to entry(completed = true), "headache" to entry(value = num(1))),
        )!!
        assertEquals(2, rollup.habits, "the denominator is 2, not 3")
        assertEquals(1, rollup.habitsMet)
        assertEquals(1, rollup.observationsExpected)
        assertEquals(1, rollup.observationsNoted)

        // Whether the observation is logged does not change the fraction — but
        // unlike the retired badge, the rollup still knows it was asked about.
        val unnoted = categoryRollup(trackers, mon, mapOf("p1" to entry(completed = true)))!!
        assertEquals(2, unnoted.habits)
        assertEquals(1, unnoted.habitsMet)
        assertEquals(1, unnoted.observationsExpected)
        assertEquals(0, unnoted.observationsNoted)
    }

    @Test
    @DisplayName("categoryRollup: a pure-observation category has no habits at all")
    fun pureObservationCategory() {
        val trackers = listOf(
            tracker(id = "headache", polarity = "neutral"),
            tracker(id = "mood-note", polarity = "neutral"),
        )
        val rollup = categoryRollup(trackers, mon, mapOf("headache" to entry(value = num(1))))!!
        assertEquals(0, rollup.habits)
        assertEquals(0, rollup.avoidances)
        assertEquals(2, rollup.observationsExpected)
        assertEquals(1, rollup.observationsNoted)
    }

    @Test
    @DisplayName("categoryRollup: a targeted neutral is a habit (a goal, not an observation)")
    fun targetedNeutralIsAHabit() {
        val t = tracker(
            id = "weight",
            polarity = "neutral",
            targetHistory = listOf(targetSegment(SCHEDULE_GENESIS_DATE, target(150, 170))),
        )
        val rollup = categoryRollup(listOf(t), mon, mapOf("weight" to entry(value = num(160))))!!
        assertEquals(1, rollup.habits)
        assertEquals(1, rollup.habitsMet)
        assertEquals(0, rollup.observationsExpected)
    }

    @Test
    @DisplayName("categoryRollup: a targeted tracker with no polarity is a habit")
    fun nullPolarityIsAHabit() {
        val t = tracker(
            id = "water",
            targetHistory = listOf(targetSegment(SCHEDULE_GENESIS_DATE, target(min = 8))),
        )
        val met = categoryRollup(listOf(t), mon, mapOf("water" to entry(value = num(8))))!!
        assertEquals(1, met.habits, "no polarity is not negative — it goes in the ring")
        assertEquals(1, met.habitsMet)
        assertEquals(0, met.avoidances)

        // Untargeted and unpolarised is the observation case, not a habit.
        val bare = categoryRollup(listOf(tracker(id = "note")), mon, mapOf("note" to entry(value = text("ow"))))!!
        assertEquals(0, bare.habits)
        assertEquals(1, bare.observationsExpected)
        assertEquals(1, bare.observationsNoted)
    }

    @Test
    @DisplayName("categoryRollup: a mixed category counts all three classes at once")
    fun mixedCategory() {
        val trackers = listOf(
            tracker(id = "m", polarity = "positive"),
            tracker(
                id = "p",
                polarity = "positive",
                targetHistory = listOf(targetSegment(SCHEDULE_GENESIS_DATE, target(min = 10))),
            ),
            tracker(id = "n", polarity = "positive"),
            tracker(id = "held", polarity = "negative"),
            tracker(id = "slipped", polarity = "negative"),
            tracker(id = "obs1", polarity = "neutral"),
            tracker(id = "obs2", polarity = "neutral"),
        )
        val rollup = categoryRollup(
            trackers,
            mon,
            mapOf(
                "m" to entry(completed = true),
                "p" to entry(value = num(4)),
                "slipped" to entry(value = num(1)),
                "obs1" to entry(value = num(3)),
            ),
        )!!
        assertEquals(
            CategoryRollup(
                habitsMet = 1,
                habitsPartial = 1,
                habitsNotYet = 1,
                avoidances = 2,
                avoidancesBroken = 1,
                observationsExpected = 2,
                observationsNoted = 1,
            ),
            rollup,
        )
        assertEquals(3, rollup.habits)
    }
}

package dev.jtiisto.wellness.core.data.journal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The sentence a screen reader gets in place of the category head's marks.
 *
 * The cluster degrades by subtraction, so most of what can go wrong is a
 * sentence naming a class the category does not have — or an avoidance phrased
 * as a failure. Both are pinned here.
 */
class CategoryRollupVoiceTest {

    // ---- describeCategoryRollup -----------------------------------------------

    @Test
    @DisplayName("describeCategoryRollup: all three classes read as one sentence")
    fun allThreeClasses() {
        assertEquals(
            "2 of 3 done, avoidances held, 1 noted",
            describeCategoryRollup(
                CategoryRollup(
                    habitsMet = 2,
                    habitsNotYet = 1,
                    avoidances = 2,
                    observationsExpected = 3,
                    observationsNoted = 1,
                ),
            ),
        )
    }

    @Test
    @DisplayName("describeCategoryRollup: absent classes are absent from the sentence")
    fun degradesBySubtraction() {
        assertEquals(
            "1 of 2 done",
            describeCategoryRollup(CategoryRollup(habitsMet = 1, habitsPartial = 1)),
        )
        assertEquals(
            "avoidances held",
            describeCategoryRollup(CategoryRollup(avoidances = 3)),
        )
        assertEquals(
            "0 of 2 noted",
            describeCategoryRollup(CategoryRollup(observationsExpected = 2)),
        )
    }

    @Test
    @DisplayName("describeCategoryRollup: observations name their total only when alone")
    fun observationDenominator() {
        assertEquals(
            "2 of 2 noted",
            describeCategoryRollup(CategoryRollup(observationsExpected = 2, observationsNoted = 2)),
        )
        assertEquals(
            "1 of 1 done, 2 noted",
            describeCategoryRollup(
                CategoryRollup(habitsMet = 1, observationsExpected = 4, observationsNoted = 2),
            ),
        )
    }

    @Test
    @DisplayName("describeCategoryRollup: avoidances are held, broken, or counted")
    fun avoidancePhrasing() {
        assertEquals("avoidance held", describeCategoryRollup(CategoryRollup(avoidances = 1)))
        assertEquals(
            "avoidance broken",
            describeCategoryRollup(CategoryRollup(avoidances = 1, avoidancesBroken = 1)),
        )
        assertEquals(
            "avoidances broken",
            describeCategoryRollup(CategoryRollup(avoidances = 2, avoidancesBroken = 2)),
        )
        assertEquals(
            "1 of 3 avoidances broken",
            describeCategoryRollup(CategoryRollup(avoidances = 3, avoidancesBroken = 1)),
        )
    }
}

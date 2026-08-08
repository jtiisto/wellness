package dev.jtiisto.wellness.core.data.coach

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Superset run detection, transcribed from the three `groupExercises` cases in
 * `test/e2e_browser/test_coach_superset.py`, plus the two rules those cases
 * imply but do not state.
 */
class GroupExercisesTest {

    @Test
    @DisplayName("a compound label groups its run and leaves the unlabeled exercise alone")
    fun compoundLabel() {
        val items = groupExercises(
            listOf(
                exercise(id = "a", name = "A", supersetGroup = "Triplet A"),
                exercise(id = "b", name = "B", supersetGroup = "Triplet A"),
                exercise(id = "c", name = "C", supersetGroup = "Triplet A"),
                exercise(id = "d", name = "D"),
            ),
        )

        assertEquals(2, items.size)
        val group = items[0] as ExerciseGroup.Group
        assertEquals("Triplet A", group.label)
        assertEquals(3, group.exercises.size)
        assertEquals("d", (items[1] as ExerciseGroup.Single).exercise.id)
    }

    @Test
    @DisplayName("two consecutive groups with different labels stay separate")
    fun labelChangeBreaksTheRun() {
        val items = groupExercises(
            listOf(
                exercise(id = "a", supersetGroup = "A"),
                exercise(id = "b", supersetGroup = "A"),
                exercise(id = "c", supersetGroup = "B"),
                exercise(id = "d", supersetGroup = "B"),
            ),
        )

        assertEquals(2, items.size)
        assertEquals("A", (items[0] as ExerciseGroup.Group).label)
        assertEquals(2, (items[0] as ExerciseGroup.Group).exercises.size)
        assertEquals("B", (items[1] as ExerciseGroup.Group).label)
        assertEquals(2, (items[1] as ExerciseGroup.Group).exercises.size)
    }

    @Test
    @DisplayName("an unlabeled exercise between two same-label ones breaks the run")
    fun unlabeledBreaksTheRun() {
        val items = groupExercises(
            listOf(
                exercise(id = "a", supersetGroup = "A"),
                exercise(id = "b"),
                exercise(id = "c", supersetGroup = "A"),
            ),
        )

        // Two separate "A" groups. The plan should re-label; merging them on the
        // client would silently invent a superset the coach did not prescribe.
        assertEquals(3, items.size)
        assertEquals("A", (items[0] as ExerciseGroup.Group).label)
        assertEquals("b", (items[1] as ExerciseGroup.Single).exercise.id)
        assertEquals("A", (items[2] as ExerciseGroup.Group).label)
    }

    @Test
    @DisplayName("a lone labeled exercise is still a group, of one")
    fun loneLabelIsAGroup() {
        val items = groupExercises(listOf(exercise(id = "a", supersetGroup = "A"), exercise(id = "b")))

        assertEquals(2, items.size)
        assertEquals(listOf("a"), (items[0] as ExerciseGroup.Group).exercises.map { it.id })
        assertEquals("b", (items[1] as ExerciseGroup.Single).exercise.id)
    }

    @Test
    @DisplayName("an empty block groups to nothing")
    fun emptyBlock() {
        assertEquals(emptyList<ExerciseGroup>(), groupExercises(emptyList()))
    }

    @Test
    @DisplayName("an empty-string label is no label at all")
    fun emptyLabelIsUnlabeled() {
        val items = groupExercises(listOf(exercise(id = "a", supersetGroup = "")))

        assertEquals("a", (items.single() as ExerciseGroup.Single).exercise.id)
    }

    @Test
    @DisplayName("bare labels get the \"Superset\" prefix; compound ones are shown verbatim")
    fun displayLabels() {
        assertEquals("Superset A", supersetDisplayLabel("A"))
        assertEquals("Superset C2", supersetDisplayLabel("C2"))
        assertEquals("Superset b", supersetDisplayLabel("b"))
        assertEquals("Triplet A", supersetDisplayLabel("Triplet A"))
        assertEquals("Pair B", supersetDisplayLabel("Pair B"))
        assertEquals("A1B", supersetDisplayLabel("A1B"))
        assertEquals("2", supersetDisplayLabel("2"))
    }
}

package dev.jtiisto.wellness.core.data.sync

import dev.jtiisto.wellness.core.data.sync.DirtySetLogic.clearApplied
import dev.jtiisto.wellness.core.data.sync.DirtySetLogic.markDirty
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Transcribed from `test/js/dirty-set.test.js`, which pins the core algorithm
 * behind journal trackers, journal entries and coach dates.
 */
class DirtySetLogicTest {

    @Test
    @DisplayName("markDirty: adds a new key and starts its generation at 1")
    fun marksNewKey() {
        val result = markDirty(DirtyState(), "a")
        assertEquals(listOf("a"), result.keys)
        assertEquals(mapOf("a" to 1L), result.generations)
    }

    @Test
    @DisplayName("markDirty: re-marking keeps membership but bumps the generation")
    fun remarkBumpsGeneration() {
        val result = markDirty(DirtyState(listOf("a"), mapOf("a" to 1L)), "a")
        assertEquals(listOf("a"), result.keys)
        assertEquals(mapOf("a" to 2L), result.generations)
    }

    @Test
    @DisplayName("markDirty: does not mutate its inputs")
    fun markDoesNotMutateInputs() {
        val state = DirtyState(listOf("a"), mapOf("a" to 1L))
        markDirty(state, "b")
        assertEquals(listOf("a"), state.keys)
        assertEquals(mapOf("a" to 1L), state.generations)
    }

    @Test
    @DisplayName("clearApplied: clears applied keys with unchanged generations, drops their gens")
    fun clearsUnchangedKeys() {
        val result = clearApplied(
            DirtyState(listOf("a", "b"), mapOf("a" to 1L, "b" to 1L)),
            listOf("a"),
            mapOf("a" to 1L),
        )
        assertEquals(listOf("b"), result.keys)
        assertEquals(mapOf("b" to 1L), result.generations)
    }

    @Test
    @DisplayName("clearApplied: a key re-modified mid-sync (gen advanced) stays dirty")
    fun remodifiedKeyStaysDirty() {
        val result = clearApplied(DirtyState(listOf("a"), mapOf("a" to 2L)), listOf("a"), mapOf("a" to 1L))
        assertEquals(listOf("a"), result.keys)
        assertEquals(mapOf("a" to 2L), result.generations)
    }

    @Test
    @DisplayName("clearApplied: null snapshot skips the re-modification check")
    fun nullSnapshotSkipsCheck() {
        val result = clearApplied(DirtyState(listOf("a"), mapOf("a" to 5L)), listOf("a"), null)
        assertEquals(emptyList<String>(), result.keys)
        assertEquals(emptyMap<String, Long>(), result.generations)
    }

    @Test
    @DisplayName("clearApplied: unapplied keys are untouched")
    fun unappliedKeysUntouched() {
        val result = clearApplied(
            DirtyState(listOf("a", "b"), mapOf("a" to 1L, "b" to 3L)),
            emptyList(),
            mapOf("a" to 1L, "b" to 3L),
        )
        assertEquals(listOf("a", "b"), result.keys)
        assertEquals(mapOf("a" to 1L, "b" to 3L), result.generations)
    }

    // --- Beyond the JS suite ---

    @Test
    @DisplayName("clearApplied: an applied key missing from a non-null snapshot stays dirty")
    fun missingSnapshotEntryStaysDirty() {
        // Mirrors the JS `generations[key] !== undefined` comparison: a key the
        // snapshot never saw cannot be proven unmodified, so it survives.
        val result = clearApplied(
            DirtyState(listOf("a", "b"), mapOf("a" to 1L, "b" to 1L)),
            listOf("a", "b"),
            mapOf("b" to 1L),
        )
        assertEquals(listOf("a"), result.keys)
        assertEquals(mapOf("a" to 1L), result.generations)
    }

    @Test
    @DisplayName("clearApplied: a key absent from both current and snapshot generations clears")
    fun absentOnBothSidesClears() {
        val result = clearApplied(DirtyState(listOf("a"), emptyMap()), listOf("a"), emptyMap())
        assertEquals(emptyList<String>(), result.keys)
        assertEquals(emptyMap<String, Long>(), result.generations)
    }

    @Test
    @DisplayName("generations are Long: a hot key past Int.MAX_VALUE keeps counting")
    fun generationsAreLong() {
        val hot = Int.MAX_VALUE.toLong()
        val marked = markDirty(DirtyState(listOf("a"), mapOf("a" to hot)), "a")
        assertEquals(hot + 1L, marked.generations.getValue("a"))

        // And the snapshot comparison still distinguishes the two.
        val cleared = clearApplied(marked, listOf("a"), mapOf("a" to hot))
        assertEquals(listOf("a"), cleared.keys)
    }
}

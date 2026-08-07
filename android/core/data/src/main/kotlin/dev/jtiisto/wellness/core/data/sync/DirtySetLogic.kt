package dev.jtiisto.wellness.core.data.sync

/**
 * A dirty key list plus its per-key generation counters.
 *
 * Generations are `Long`: JS numbers do not wrap at 32 bits and a hot key
 * (an entry toggled all day) must not overflow into a false "unchanged".
 */
data class DirtyState(
    val keys: List<String> = emptyList(),
    val generations: Map<String, Long> = emptyMap(),
)

/**
 * The one implementation of the dirty-tracking generation machinery, ported
 * from `shared/dirty-set.js`.
 *
 * mark -> snapshot -> clear-if-generation-unchanged guarantees an edit made
 * *during* a sync is never lost: the edit bumps the generation, so the
 * post-upload clear (comparing against the pre-upload snapshot) keeps the key
 * dirty for the next cycle. Pure functions only — no storage, no clock.
 */
object DirtySetLogic {

    /**
     * Mark [key] dirty: ensure membership and bump its generation. The bump is
     * unconditional — that is what detects re-modification during a sync.
     */
    fun markDirty(state: DirtyState, key: String): DirtyState {
        val nextKeys = if (state.keys.contains(key)) state.keys else state.keys + key
        val nextGens = state.generations + (key to ((state.generations[key] ?: 0L) + 1L))
        return DirtyState(nextKeys, nextGens)
    }

    /**
     * Clear the [appliedKeys] whose generation still matches [snapshotGens] (the
     * generations captured before the sync started). A key re-modified mid-sync
     * stays dirty; generations of keys actually cleared are dropped.
     *
     * [snapshotGens] `null` disables the re-modification check entirely.
     *
     * An applied key *absent* from a non-null snapshot compares unequal and so
     * stays dirty — mirroring the JS `generations[key] !== snapshotGens[key]`
     * against `undefined`. A key absent from both sides clears.
     */
    fun clearApplied(
        state: DirtyState,
        appliedKeys: Collection<String>,
        snapshotGens: Map<String, Long>?,
    ): DirtyState {
        val applied = appliedKeys.toSet()
        val nextKeys = state.keys.filter { key ->
            when {
                key !in applied -> true
                snapshotGens != null && state.generations[key] != snapshotGens[key] -> true
                else -> false
            }
        }

        val remaining = nextKeys.toSet()
        val nextGens = state.generations.toMutableMap()
        for (key in appliedKeys) {
            if (key !in remaining) nextGens.remove(key)
        }
        return DirtyState(nextKeys, nextGens)
    }
}

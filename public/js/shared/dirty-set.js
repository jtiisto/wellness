/**
 * DirtySet — the ONE implementation of the dirty-tracking generation machinery.
 *
 * Both stores track unsynced records as (dirty key list + per-key generation
 * counter). The mark → snapshot → clear-if-generation-unchanged algorithm
 * guarantees an edit made *during* a sync is never lost: the edit bumps the
 * generation, so the post-upload clear (which compares against the pre-upload
 * snapshot) keeps the key dirty for the next cycle.
 *
 * It existed as three hand-copies (journal trackers, journal entries, coach
 * dates) that had to evolve in lockstep by hand. Pure functions only — no
 * signals, storage, or Date.
 */

/**
 * Mark `key` dirty: ensure membership and bump its generation (the bump is
 * unconditional — that is what detects re-modification during a sync).
 *
 * @returns {{keys: string[], generations: Object}} new arrays/objects (inputs untouched)
 */
export function markDirty(keys, generations, key) {
    const nextKeys = keys.includes(key) ? [...keys] : [...keys, key];
    const nextGens = { ...generations, [key]: (generations[key] || 0) + 1 };
    return { keys: nextKeys, generations: nextGens };
}

/**
 * Clear the applied keys whose generation still matches the pre-sync snapshot.
 * A key re-modified mid-sync (generation advanced past the snapshot) stays
 * dirty; generations for keys actually cleared are dropped.
 *
 * @param {string[]} keys           current dirty keys
 * @param {Object}   generations    current per-key generations
 * @param {string[]} appliedKeys    keys the sync actually resolved (sent/adopted)
 * @param {Object?}  snapshotGens   generations snapshotted before the sync
 *                                  (null = no re-modification check)
 * @returns {{keys: string[], generations: Object}}
 */
export function clearApplied(keys, generations, appliedKeys, snapshotGens) {
    const appliedSet = new Set(appliedKeys);
    const nextKeys = keys.filter(key => {
        if (!appliedSet.has(key)) return true;   // not applied, keep dirty
        if (snapshotGens && generations[key] !== snapshotGens[key]) {
            return true;                          // re-modified mid-sync, keep dirty
        }
        return false;                             // applied and unchanged, clear
    });

    const nextGens = { ...generations };
    const remaining = new Set(nextKeys);
    for (const key of appliedKeys) {
        if (!remaining.has(key)) delete nextGens[key];
    }
    return { keys: nextKeys, generations: nextGens };
}

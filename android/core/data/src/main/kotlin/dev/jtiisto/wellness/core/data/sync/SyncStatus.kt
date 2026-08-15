package dev.jtiisto.wellness.core.data.sync

/**
 * What a module's sync indicator shows.
 *
 * [RED] is "you have edits the server has not taken yet", not "something
 * broke" — the honest signal when a write is still local. [GRAY] covers both
 * offline and never-synced: in neither case is green a truthful claim.
 */
enum class SyncStatus {
    GREEN,
    RED,
    GRAY,
}

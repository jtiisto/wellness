package dev.jtiisto.wellness.core.sync

enum class SyncStatus {
    CLEAN,
    DIRTY,
    CONFLICTS,
    NEVER_SYNCED,
    OFFLINE,
}

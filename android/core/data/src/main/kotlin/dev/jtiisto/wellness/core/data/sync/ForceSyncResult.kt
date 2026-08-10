package dev.jtiisto.wellness.core.data.sync

/** Why a module's force sync never ran. */
enum class ForceSyncSkipReason { OFFLINE, BUSY }

/**
 * What one module's force cycle achieved, in the terms its own dialog line is
 * written in. The two modules count different things: the journal arbitrates
 * per record, coach per day.
 */
sealed interface ForceSyncCounts {
    data class Journal(val accepted: Int, val conflicts: Int) : ForceSyncCounts

    data class Coach(val uploadedDates: Int) : ForceSyncCounts
}

/**
 * One module's force-sync outcome.
 *
 * Deliberately **not** an extension of [SyncResult]: that type is the
 * scheduler's, and its `success = false` covers both a skip and a failure
 * because the scheduler only needs to know whether to retry. The dialog needs
 * to tell a user "coach was already syncing" from "coach failed", and needs
 * counts on the way through.
 */
sealed interface ForceSyncModuleResult {
    data class Success(val counts: ForceSyncCounts) : ForceSyncModuleResult

    data class Failed(val message: String) : ForceSyncModuleResult

    data class Skipped(val reason: ForceSyncSkipReason) : ForceSyncModuleResult
}

/** Both modules' outcomes, in the order the orchestrator ran them. */
data class ForceSyncReport(
    val coach: ForceSyncModuleResult,
    val journal: ForceSyncModuleResult,
)

/**
 * The dialog copy, as a total function of the report.
 *
 * Every per-module state has a line, including the two skips — the PWA had
 * neither, so a force sync that did nothing because one module was mid-cycle
 * reported only the other module and left the user to guess. Offline is
 * evaluated per module at its own start, so "Coach: 12 days uploaded. Journal:
 * offline" is a reachable and honest outcome for a connection that dropped
 * between the two.
 */
object ForceSyncCopy {

    const val CONFIRM_BODY = "This will reconcile all data with the server. Continue?"
    const val BUSY_BUTTON = "Syncing…"
    const val NOTHING_SYNCED = "No modules synced."

    /** Success styling iff at least one module actually reconciled something. */
    fun isSuccess(report: ForceSyncReport): Boolean =
        report.coach is ForceSyncModuleResult.Success || report.journal is ForceSyncModuleResult.Success

    /**
     * One line per module, joined — unless neither module ran at all, which is
     * the one case worth saying outright rather than as two separate excuses.
     */
    fun message(report: ForceSyncReport): String {
        if (report.coach is ForceSyncModuleResult.Skipped && report.journal is ForceSyncModuleResult.Skipped) {
            return NOTHING_SYNCED
        }
        return listOf(line("Coach", report.coach), line("Journal", report.journal)).joinToString(". ")
    }

    fun line(module: String, result: ForceSyncModuleResult): String = when (result) {
        is ForceSyncModuleResult.Success -> when (val counts = result.counts) {
            is ForceSyncCounts.Journal ->
                "$module: ${counts.accepted} records accepted, ${counts.conflicts} conflicts"
            is ForceSyncCounts.Coach -> "$module: ${counts.uploadedDates} days uploaded"
        }
        is ForceSyncModuleResult.Failed -> "$module: failed"
        is ForceSyncModuleResult.Skipped -> when (result.reason) {
            ForceSyncSkipReason.OFFLINE -> "$module: offline"
            ForceSyncSkipReason.BUSY -> "$module: sync already running"
        }
    }
}

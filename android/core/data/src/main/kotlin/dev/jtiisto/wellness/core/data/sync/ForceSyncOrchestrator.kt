package dev.jtiisto.wellness.core.data.sync

import kotlinx.coroutines.CancellationException

/**
 * Runs both modules' force cycles and hands back one report — the port of
 * `shared/force-sync.js`.
 *
 * Two things it owns that the stores deliberately do not:
 *
 * 1. **Isolation.** Coach runs, then the journal, and a failure in one must not
 *    take the other with it: the user pressed one button for both, and "coach
 *    threw so the journal never tried" is the worst possible outcome for a
 *    button whose job is to recover a stuck client. `CancellationException` is
 *    the exception to the exception — it is rethrown untouched, because
 *    swallowing it would report a cancelled scope as an ordinary module
 *    failure and leave the caller's coroutine looking healthy.
 * 2. **Scheduler follow-up.** The stores know nothing about schedulers. A
 *    module that succeeded has its retry state cleared — the force sync *is*
 *    the evidence that the server is reachable, so an accumulated backoff
 *    should not survive it. A module that still has dirty rows afterwards gets
 *    an immediate re-request rather than waiting out a 30 s poll: those rows
 *    are almost always an edit the user made while the force sync was in
 *    flight, and the generation guard correctly kept them dirty.
 *
 * The follow-up runs only after a cycle that actually executed. A skipped
 * module has either a cycle already running (which owns its own follow-up) or
 * no connection to run one on.
 */
class ForceSyncOrchestrator(
    private val coach: ForceSyncModule,
    private val journal: ForceSyncModule,
    private val isOnline: () -> Boolean,
) {

    suspend fun run(): ForceSyncReport = ForceSyncReport(
        coach = runModule(coach),
        journal = runModule(journal),
    )

    private suspend fun runModule(module: ForceSyncModule): ForceSyncModuleResult {
        val result = try {
            module.forceSync()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            ForceSyncModuleResult.Failed(error::class.simpleName ?: "error")
        }

        if (result is ForceSyncModuleResult.Skipped) return result

        if (result is ForceSyncModuleResult.Success) module.resetRetry()
        if (isOnline() && hasDirtyDataQuietly(module)) module.requestSync()
        return result
    }

    /**
     * A dirty probe that throws must not turn a successful force sync into a
     * failure — the sync already landed. Failing closed only costs the
     * follow-up, which the next poll tick makes anyway.
     */
    private suspend fun hasDirtyDataQuietly(module: ForceSyncModule): Boolean = try {
        module.hasDirtyData()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        false
    }
}

/**
 * One module as the orchestrator needs it: a force cycle, a dirty probe, and
 * the two scheduler hooks.
 *
 * An interface rather than the concrete stores because the two stores have no
 * common supertype and no reason to grow one — and because it lets the
 * isolation and follow-up rules be tested without a Room fake behind them.
 */
interface ForceSyncModule {
    suspend fun forceSync(): ForceSyncModuleResult

    suspend fun hasDirtyData(): Boolean

    fun resetRetry()

    fun requestSync()
}

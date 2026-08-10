package dev.jtiisto.wellness

import dev.jtiisto.wellness.core.data.analysis.AnalysisStore
import dev.jtiisto.wellness.core.data.di.CoachScheduler
import dev.jtiisto.wellness.core.data.di.JournalScheduler
import dev.jtiisto.wellness.core.data.network.ServerBootstrap
import dev.jtiisto.wellness.core.data.network.ServerResolution
import dev.jtiisto.wellness.core.data.sync.ConnectivityMonitor
import dev.jtiisto.wellness.core.data.sync.SyncOrchestrator
import dev.jtiisto.wellness.core.data.sync.SyncScheduler
import org.koin.core.Koin

/**
 * Resolve the server, and start the process-lifetime plumbing **only if it
 * resolved**.
 *
 * The order is a safety property rather than a preference. Every scheduler,
 * every API and the HTTP client itself hang off `ServerConfig`, and
 * `ServerConfig` is produced by [ServerBootstrap] — so a failed resolution
 * simply never constructs any of them. Nothing is running that could write to
 * the wrong server, because nothing capable of writing exists yet. The app
 * shows a recovery screen instead, and its retry calls straight back here.
 *
 * Every step is idempotent, which is what makes that retry safe: the monitor
 * checks its callback, the orchestrator its `started` flag, the analysis store
 * its registration, and registering the same scheduler twice is a no-op.
 */
fun bootWellness(koin: Koin): ServerResolution {
    val resolution = koin.get<ServerBootstrap>().resolveBlocking()
    if (resolution !is ServerResolution.Resolved) return resolution

    // Connectivity first, so the orchestrator's first foreground event already
    // sees the right state, then every module scheduler, then the orchestrator.
    koin.get<ConnectivityMonitor>().start()
    val orchestrator = koin.get<SyncOrchestrator>()
    orchestrator.register(koin.get<SyncScheduler>(JournalScheduler))
    orchestrator.register(koin.get<SyncScheduler>(CoachScheduler))
    orchestrator.start()

    // Analysis owns a poll rather than a scheduler, so it observes the process
    // lifecycle itself. Created here rather than when the tab opens: a
    // foreground event that arrives before the first visit has to be latched,
    // and there is nothing to latch it if the store does not exist.
    koin.get<AnalysisStore>().start()
    return resolution
}

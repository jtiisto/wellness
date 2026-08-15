package dev.jtiisto.wellness.core.data.sync

import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The orchestrator's one rule: **polling runs iff foreground && online**.
 * Uploads are not gated that way — connectivity returning flushes dirty data
 * even while the app is backgrounded.
 *
 * Drives [SyncOrchestratorCore] directly; `SyncOrchestrator` adds only the
 * `ProcessLifecycleOwner` and connectivity subscriptions on top.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncOrchestratorTest {

    @Test
    @DisplayName("polling starts only when the app is both foreground and online")
    fun pollingRequiresForegroundAndOnline() = orchestratorTest { core, scheduler ->
        core.setForeground(true)
        runCurrent()
        verify(exactly = 0) { scheduler.startPolling() }

        core.setOnline(true)
        runCurrent()
        verify(exactly = 1) { scheduler.startPolling() }

        core.setForeground(false)
        runCurrent()
        verify(exactly = 1) { scheduler.stopPolling() }
    }

    @Test
    @DisplayName("coming back to the foreground while online requests a sync")
    fun foregroundWhileOnlineRequestsSync() = orchestratorTest { core, scheduler ->
        core.setOnline(true)
        core.setForeground(true)
        runCurrent()

        verify(exactly = 1) { scheduler.requestSync() }
        verify(exactly = 1) { scheduler.startPolling() }
    }

    @Test
    @DisplayName("coming back to the foreground while offline requests nothing")
    fun foregroundWhileOfflineDoesNothing() = orchestratorTest { core, scheduler ->
        core.setForeground(true)
        runCurrent()

        verify(exactly = 0) { scheduler.requestSync() }
        verify(exactly = 0) { scheduler.startPolling() }
        verify(exactly = 0) { scheduler.stopPolling() }
    }

    @Test
    @DisplayName("going online while backgrounded flushes dirty data but does not start polling")
    fun onlineWhileBackgroundedDoesNotPoll() = orchestratorTest { core, scheduler ->
        core.setOnline(true)
        runCurrent()

        verify(exactly = 1) { scheduler.onOnline() }
        verify(exactly = 0) { scheduler.startPolling() }
    }

    @Test
    @DisplayName("going offline notifies the scheduler and stops polling")
    fun offlineStopsPolling() = orchestratorTest { core, scheduler ->
        core.setForeground(true)
        core.setOnline(true)
        runCurrent()

        core.setOnline(false)
        runCurrent()

        verifyOrder {
            scheduler.startPolling()
            scheduler.onOffline()
            scheduler.stopPolling()
        }
    }

    @Test
    @DisplayName("a scheduler registered after the fact is handed the current state")
    fun lateRegistrationGetsCurrentState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val core = SyncOrchestratorCore(backgroundScope)
        core.setForeground(true)
        core.setOnline(true)
        runCurrent()

        val late = mockk<SyncScheduler>(relaxed = true)
        core.register(late)
        runCurrent()

        verify(exactly = 1) { late.startPolling() }
        verify(exactly = 0) { late.requestSync() }
    }

    @Test
    @DisplayName("repeated identical events are idempotent")
    fun repeatedEventsAreIdempotent() = orchestratorTest { core, scheduler ->
        core.setOnline(true)
        core.setOnline(true)
        core.setForeground(true)
        core.setForeground(true)
        runCurrent()

        verify(exactly = 1) { scheduler.onOnline() }
        verify(exactly = 1) { scheduler.requestSync() }
        verify(exactly = 1) { scheduler.startPolling() }
    }

    @Test
    @DisplayName("flapping connectivity toggles polling once per real transition")
    fun flappingConnectivity() = orchestratorTest { core, scheduler ->
        core.setForeground(true)
        runCurrent()

        repeat(3) {
            core.setOnline(true)
            core.setOnline(false)
        }
        runCurrent()

        verify(exactly = 3) { scheduler.onOnline() }
        verify(exactly = 3) { scheduler.onOffline() }
        verify(exactly = 3) { scheduler.startPolling() }
        verify(exactly = 3) { scheduler.stopPolling() }
    }

    @Test
    @DisplayName("every registered scheduler receives every event")
    fun eventsFanOutToAllSchedulers() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val core = SyncOrchestratorCore(backgroundScope)
        val journal = mockk<SyncScheduler>(relaxed = true)
        val coach = mockk<SyncScheduler>(relaxed = true)
        core.register(journal)
        core.register(coach)

        core.setOnline(true)
        core.setForeground(true)
        runCurrent()

        verify(exactly = 1) { journal.onOnline() }
        verify(exactly = 1) { coach.onOnline() }
        verify(exactly = 1) { journal.startPolling() }
        verify(exactly = 1) { coach.startPolling() }
    }

    @Test
    @DisplayName("registering the same scheduler twice does not double-deliver")
    fun duplicateRegistrationIgnored() = orchestratorTest { core, scheduler ->
        core.register(scheduler)
        core.setOnline(true)
        core.setForeground(true)
        runCurrent()

        verify(exactly = 1) { scheduler.onOnline() }
        verify(exactly = 1) { scheduler.requestSync() }
    }

    /** Reset only after [runTest] tore the scope down — see SyncSchedulerTest. */
    @AfterEach
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun orchestratorTest(
        body: suspend TestScope.(SyncOrchestratorCore, SyncScheduler) -> Unit,
    ) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val scheduler = mockk<SyncScheduler>(relaxed = true)
        val core = SyncOrchestratorCore(backgroundScope)
        core.register(scheduler)
        runCurrent()
        body(core, scheduler)
    }
}

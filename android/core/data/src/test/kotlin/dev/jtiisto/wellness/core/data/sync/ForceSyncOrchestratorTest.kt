package dev.jtiisto.wellness.core.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The two-module orchestration: isolation, ordering, and who owns the scheduler
 * afterwards.
 *
 * The isolation cases are the ones with teeth. One button reconciles both
 * modules, and the failure mode worth preventing is a coach exception quietly
 * meaning the journal never even tried.
 */
class ForceSyncOrchestratorTest {

    private class FakeModule(
        var result: ForceSyncModuleResult = ForceSyncModuleResult.Success(ForceSyncCounts.Coach(0)),
        var throws: Throwable? = null,
        var dirty: Boolean = false,
        var dirtyThrows: Throwable? = null,
    ) : ForceSyncModule {
        val calls = mutableListOf<String>()
        var resetRetryCount = 0
        var requestSyncCount = 0

        override suspend fun forceSync(): ForceSyncModuleResult {
            calls += "forceSync"
            throws?.let { throw it }
            return result
        }

        override suspend fun hasDirtyData(): Boolean {
            dirtyThrows?.let { throw it }
            return dirty
        }

        override fun resetRetry() {
            resetRetryCount++
        }

        override fun requestSync() {
            requestSyncCount++
        }
    }

    private class RecordingModule(
        private val name: String,
        private val order: MutableList<String>,
    ) : ForceSyncModule {
        override suspend fun forceSync(): ForceSyncModuleResult {
            order += name
            return ForceSyncModuleResult.Success(ForceSyncCounts.Coach(0))
        }

        override suspend fun hasDirtyData(): Boolean = false
        override fun resetRetry() = Unit
        override fun requestSync() = Unit
    }

    private fun orchestrator(
        coach: FakeModule,
        journal: FakeModule,
        online: Boolean = true,
    ) = ForceSyncOrchestrator(coach = coach, journal = journal, isOnline = { online })

    @Test
    @DisplayName("coach runs before the journal")
    fun coachRunsFirst() = runTest {
        val order = mutableListOf<String>()
        val tracked = ForceSyncOrchestrator(
            coach = RecordingModule("coach", order),
            journal = RecordingModule("journal", order),
            isOnline = { true },
        )

        tracked.run()

        assertEquals(listOf("coach", "journal"), order)
    }

    @Test
    @DisplayName("a module that throws becomes a failure, and the other one still runs")
    fun oneThrowingModuleIsIsolated() = runTest {
        val coach = FakeModule(throws = IllegalStateException("coach exploded"))
        val journal = FakeModule(result = ForceSyncModuleResult.Success(ForceSyncCounts.Journal(2, 0)))

        val report = orchestrator(coach, journal).run()

        assertTrue(report.coach is ForceSyncModuleResult.Failed)
        assertTrue(report.journal is ForceSyncModuleResult.Success)
        assertEquals(listOf("forceSync"), journal.calls, "the journal must still have been attempted")
    }

    @Test
    @DisplayName("cancellation is rethrown, never converted into a module failure")
    fun cancellationIsRethrown() = runTest {
        // Swallowing this would report a cancelled scope as an ordinary sync
        // failure and leave the caller's coroutine looking healthy.
        val coach = FakeModule(throws = CancellationException("scope died"))
        val journal = FakeModule()

        assertThrows<CancellationException> { orchestrator(coach, journal).run() }
        assertTrue(journal.calls.isEmpty(), "cancellation must not carry on to the next module")
    }

    @Test
    @DisplayName("a success clears the module's accumulated retry backoff")
    fun successResetsRetry() = runTest {
        val coach = FakeModule(result = ForceSyncModuleResult.Success(ForceSyncCounts.Coach(1)))
        val journal = FakeModule(result = ForceSyncModuleResult.Success(ForceSyncCounts.Journal(0, 0)))

        orchestrator(coach, journal).run()

        assertEquals(1, coach.resetRetryCount)
        assertEquals(1, journal.resetRetryCount)
    }

    @Test
    @DisplayName("a failure does not clear retry state — nothing proved the server is back")
    fun failureDoesNotResetRetry() = runTest {
        val coach = FakeModule(result = ForceSyncModuleResult.Failed("nope"))

        orchestrator(coach, FakeModule()).run()

        assertEquals(0, coach.resetRetryCount)
    }

    @Test
    @DisplayName("rows still dirty afterwards get an immediate follow-up rather than the next poll")
    fun dirtyAfterwardsRequestsSync() = runTest {
        // The ordinary cause: an edit made while the force sync was in flight,
        // which the generation guard correctly left dirty.
        val coach = FakeModule(result = ForceSyncModuleResult.Success(ForceSyncCounts.Coach(1)), dirty = true)

        orchestrator(coach, FakeModule()).run()

        assertEquals(1, coach.requestSyncCount)
    }

    @Test
    @DisplayName("a failed module with dirty rows is still re-requested")
    fun failureWithDirtyRowsStillRequests() = runTest {
        val coach = FakeModule(result = ForceSyncModuleResult.Failed("nope"), dirty = true)

        orchestrator(coach, FakeModule()).run()

        assertEquals(1, coach.requestSyncCount)
    }

    @Test
    @DisplayName("nothing dirty means no follow-up")
    fun cleanAfterwardsRequestsNothing() = runTest {
        val coach = FakeModule(dirty = false)

        orchestrator(coach, FakeModule()).run()

        assertEquals(0, coach.requestSyncCount)
    }

    @Test
    @DisplayName("offline suppresses the follow-up: there is nowhere to send it")
    fun offlineSuppressesFollowUp() = runTest {
        val coach = FakeModule(dirty = true)

        orchestrator(coach, FakeModule(), online = false).run()

        assertEquals(0, coach.requestSyncCount)
    }

    @Test
    @DisplayName("a skipped module gets neither hook — it never ran a cycle")
    fun skipsGetNoSchedulerFollowUp() = runTest {
        val coach = FakeModule(
            result = ForceSyncModuleResult.Skipped(ForceSyncSkipReason.BUSY),
            dirty = true,
        )

        val report = orchestrator(coach, FakeModule()).run()

        assertTrue(report.coach is ForceSyncModuleResult.Skipped)
        assertEquals(0, coach.resetRetryCount)
        assertEquals(0, coach.requestSyncCount, "the running cycle owns its own follow-up")
    }

    @Test
    @DisplayName("a dirty probe that throws does not turn a landed sync into a failure")
    fun dirtyProbeFailureIsSwallowed() = runTest {
        val coach = FakeModule(
            result = ForceSyncModuleResult.Success(ForceSyncCounts.Coach(3)),
            dirtyThrows = IllegalStateException("database busy"),
        )

        val report = orchestrator(coach, FakeModule()).run()

        assertTrue(report.coach is ForceSyncModuleResult.Success, "the sync itself succeeded")
        assertEquals(0, coach.requestSyncCount)
    }
}

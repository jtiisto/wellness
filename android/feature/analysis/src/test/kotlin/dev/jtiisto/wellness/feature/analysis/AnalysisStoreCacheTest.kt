package dev.jtiisto.wellness.feature.analysis

import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.analysis.AnalysisEvents
import dev.jtiisto.wellness.core.data.analysis.AnalysisRepository
import dev.jtiisto.wellness.core.data.analysis.AnalysisStore
import dev.jtiisto.wellness.core.data.db.PayloadCacheDao
import dev.jtiisto.wellness.core.data.db.PayloadCacheEntity
import dev.jtiisto.wellness.core.data.network.AnalysisApi
import dev.jtiisto.wellness.core.data.sync.DebugLog
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * What actually reaches the cache, over a **real** repository and a real DAO.
 *
 * The rest of the store suite mocks the repository, which is the right seam for
 * state-machine questions and exactly the wrong one for this: the bug being
 * pinned here lived entirely below that mock. A poll tick whose state mutation
 * the generation check correctly rejected had *already* written `report_{id}`
 * back to disk on its way to being rejected — so a report the user deleted came
 * back on the next launch, and no test that stubbed the repository could see it.
 *
 * Only the wire is faked here. The repository, the cache and the store are the
 * production objects.
 */
class AnalysisStoreCacheTest {

    private class FakeCacheDao : PayloadCacheDao {
        val rows = mutableMapOf<Pair<String, String>, PayloadCacheEntity>()

        override suspend fun upsert(entry: PayloadCacheEntity) {
            rows[entry.module to entry.key] = entry
        }

        override suspend fun find(module: String, key: String): PayloadCacheEntity? =
            rows[module to key]

        override suspend fun delete(module: String, key: String) {
            rows.remove(module to key)
        }

        override suspend fun clearModule(module: String) {
            rows.keys.removeAll { it.first == module }
        }
    }

    private val api = mockk<AnalysisApi>()
    private val dao = FakeCacheDao()
    private val debugLog = mockk<DebugLog>(relaxed = true)

    private fun cachedReport(id: Long) =
        dao.rows[AnalysisRepository.MODULE to AnalysisRepository.reportKey(id)]

    private fun seedCachedReport(id: Long) {
        dao.rows[AnalysisRepository.MODULE to AnalysisRepository.reportKey(id)] =
            PayloadCacheEntity(AnalysisRepository.MODULE, AnalysisRepository.reportKey(id), RUNNING_7, 1L)
    }

    /** The store, wired to production collaborators over a faked wire. */
    private fun TestScope.store(): AnalysisStore {
        val repository = AnalysisRepository(
            api = api,
            cacheDao = dao,
            debugLog = debugLog,
            json = WellnessJson,
            clock = { 1_000L },
        )
        return AnalysisStore(
            repository = repository,
            events = AnalysisEvents(),
            debugLog = debugLog,
            scope = backgroundScope,
            controlContext = StandardTestDispatcher(testScheduler),
            now = { testScheduler.currentTime },
        ).also {
            it.onForeground()
            runCurrent()
            launch { it.initialize() }
            runCurrent()
        }
    }

    @Test
    @DisplayName("a tick that returns after the report was deleted does not put its body back on disk")
    fun rejectedTickCannotRecreateADeletedCacheRow() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { api.queries() } returns "[]"
        coEvery { api.pending() } returns "[$RUNNING_7]"
        coEvery { api.reports() } returns "[]"
        coEvery { api.delete(7L) } returns Unit
        coEvery { api.report(7L) } coAnswers {
            // Already back from Ktor: the store's guard can still refuse the
            // state mutation, but nothing can un-write a cache row.
            withContext(NonCancellable) { gate.await() }
            COMPLETED_7
        }

        val store = store()
        seedCachedReport(7L)
        assertNotNull(cachedReport(7L))

        store.delete(7L)
        runCurrent()
        assertNull(cachedReport(7L), "the delete removes the row")

        gate.complete(Unit)
        runCurrent()

        assertNull(
            cachedReport(7L),
            "the rejected tick must not resurrect it — that row is what comes back on the next launch",
        )
    }

    @Test
    @DisplayName("a tick the store accepts does cache its body — the positive control")
    fun acceptedTerminalTickIsCached() = runTest {
        var tick = 0
        coEvery { api.queries() } returns "[]"
        coEvery { api.pending() } returns "[$RUNNING_7]"
        coEvery { api.reports() } returns "[]"
        coEvery { api.report(7L) } coAnswers { if (tick++ == 0) RUNNING_7 else COMPLETED_7 }

        val store = store()
        assertNull(cachedReport(7L), "a running report is never cached")

        advanceTimeBy(3_001)
        runCurrent()

        assertEquals(COMPLETED_7, cachedReport(7L)?.payloadJson)
        assertEquals(dev.jtiisto.wellness.core.data.analysis.AnalysisView.REPORT, store.state.value.view)
    }

    @Test
    @DisplayName("a report opened and read is cached; one still running is not")
    fun openedTerminalReportIsCachedButRunningIsNot() = runTest {
        coEvery { api.queries() } returns "[]"
        coEvery { api.pending() } returns "[]"
        coEvery { api.reports() } returns "[]"
        coEvery { api.report(41L) } returns COMPLETED_41
        coEvery { api.report(9L) } returns RUNNING_9

        val store = store()

        store.openReport(41L)
        runCurrent()
        assertEquals(COMPLETED_41, cachedReport(41L)?.payloadJson)

        store.openReport(9L)
        runCurrent()
        assertNull(cachedReport(9L), "a report that can still change would come back looking unfinished")
    }

    private companion object {
        const val RUNNING_7 =
            """{"id":7,"query_id":"fixture-a","query_label":"Fixture A","status":"running",""" +
                """"response_markdown":null,"created_at":"2031-03-04T12:00:00Z",""" +
                """"completed_at":null,"error_message":null}"""

        const val COMPLETED_7 =
            """{"id":7,"query_id":"fixture-a","query_label":"Fixture A","status":"completed",""" +
                """"response_markdown":"# body","created_at":"2031-03-04T12:00:00Z",""" +
                """"completed_at":"2031-03-04T12:05:00Z","error_message":null}"""

        const val COMPLETED_41 =
            """{"id":41,"query_id":"fixture-b","query_label":"Fixture B","status":"completed",""" +
                """"response_markdown":"# older","created_at":"2031-03-04T09:15:00Z",""" +
                """"completed_at":"2031-03-04T09:18:00Z","error_message":null}"""

        const val RUNNING_9 =
            """{"id":9,"query_id":"fixture-b","query_label":"Fixture B","status":"running",""" +
                """"response_markdown":null,"created_at":"2031-03-04T13:00:00Z",""" +
                """"completed_at":null,"error_message":null}"""
    }
}

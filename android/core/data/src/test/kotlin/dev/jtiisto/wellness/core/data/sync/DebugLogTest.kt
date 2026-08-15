package dev.jtiisto.wellness.core.data.sync

import app.cash.turbine.test
import dev.jtiisto.wellness.core.data.db.DebugLogDao
import dev.jtiisto.wellness.core.data.db.DebugLogEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The live view's TTL guarantee: an entry that expires while the screen is
 * open disappears within one recheck tick, even when no write ever prunes it.
 * (Room's Flow re-runs its query only on writes — the ticker closes the gap.)
 */
class DebugLogTest {

    private class FakeDao : DebugLogDao() {
        val rows = MutableStateFlow<List<DebugLogEntity>>(emptyList())
        private var nextId = 1L

        override suspend fun insert(entry: DebugLogEntity) {
            rows.value = rows.value + entry.copy(id = nextId++)
        }

        override suspend fun deleteExpired(cutoff: Long) {
            rows.value = rows.value.filter { it.ts > cutoff }
        }

        override suspend fun trimToCap(max: Int) {
            rows.value = rows.value.takeLast(max)
        }

        override fun observeAll(): Flow<List<DebugLogEntity>> =
            rows.map { list -> list.sortedByDescending(DebugLogEntity::id) }

        override suspend fun listSince(cutoff: Long): List<DebugLogEntity> =
            rows.value.filter { it.ts > cutoff }.sortedBy(DebugLogEntity::id)
    }

    @Test
    @DisplayName("an entry that expires mid-collection drops off the live view without any write")
    fun liveViewDropsExpiredEntriesWithoutWrites() = runTest {
        val dao = FakeDao()
        val log = DebugLog(dao, backgroundScope, now = { testScheduler.currentTime })

        log.log("sync", "about to expire")
        // runCurrent, not advanceUntilIdle: as of coroutines 1.10+ the latter
        // no longer runs backgroundScope tasks, and the write is a background
        // launch with no delay.
        runCurrent()

        log.entries().test {
            assertEquals(listOf("about to expire"), awaitItem().map(DebugLogEntity::message))

            // Cross the TTL boundary; the next tick must filter the entry out.
            advanceTimeBy(DebugLogLogic.TTL_MS + DebugLogLogic.TTL_RECHECK_MS)
            assertEquals(emptyList<String>(), awaitItem().map(DebugLogEntity::message))

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    @DisplayName("entries inside the window survive recheck ticks unchanged")
    fun liveEntriesSurviveTicks() = runTest {
        val dao = FakeDao()
        val log = DebugLog(dao, backgroundScope, now = { testScheduler.currentTime })

        log.log("sync", "fresh")
        runCurrent()

        log.entries().test {
            assertEquals(listOf("fresh"), awaitItem().map(DebugLogEntity::message))

            // Several ticks well inside the TTL: distinctUntilChanged suppresses
            // re-emission, so nothing arrives and the entry is still live.
            advanceTimeBy(DebugLogLogic.TTL_RECHECK_MS * 3)
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
    }
}

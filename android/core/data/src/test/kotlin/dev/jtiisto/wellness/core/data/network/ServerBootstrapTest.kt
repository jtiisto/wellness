package dev.jtiisto.wellness.core.data.network

import dev.jtiisto.wellness.core.data.db.ServerProfileEntity
import dev.jtiisto.wellness.core.data.db.ServerProfilesDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Boot resolution — the four-way decision, two arms of which must fail closed.
 *
 * The fail-closed arms are the reason this class exists at all. Room still holds
 * whatever the previous server sent, dirty rows included, so answering a
 * confused address book with "use the built-in one" would upload one server's
 * records to another.
 */
class ServerBootstrapTest {

    private class FakeProfilesDao(
        private val rows: List<ServerProfileEntity> = emptyList(),
        private val failure: Throwable? = null,
    ) : ServerProfilesDao() {
        override fun observeAll(): Flow<List<ServerProfileEntity>> = flowOf(rows)
        override suspend fun listAll(): List<ServerProfileEntity> = rows
        override suspend fun find(id: Long): ServerProfileEntity? = rows.firstOrNull { it.id == id }

        override suspend fun listActive(): List<ServerProfileEntity> {
            failure?.let { throw it }
            return rows.filter { it.isActive }
        }

        override suspend fun insert(row: ServerProfileEntity): Long = 0
        override suspend fun rename(id: Long, nickname: String, url: String) = Unit
        override suspend fun delete(id: Long) = Unit
        override suspend fun activate(id: Long) = Unit
        override suspend fun clearActive() = Unit
    }

    private fun profile(id: Long, nickname: String, url: String, active: Boolean) =
        ServerProfileEntity(id = id, nickname = nickname, url = url, isActive = active)

    private fun bootstrap(dao: ServerProfilesDao) =
        ServerBootstrap(dao = dao, builtInUrl = "https://built-in/wellness")

    @Test
    @DisplayName("exactly one active row resolves to that row's URL and nickname")
    fun oneActiveRow() = runTest {
        val dao = FakeProfilesDao(listOf(profile(1, "Laptop", "https://laptop/wellness", active = true)))

        val resolution = bootstrap(dao).resolve() as ServerResolution.Resolved

        assertEquals("https://laptop/wellness", resolution.config.baseUrl)
        assertEquals("Laptop", resolution.nickname)
    }

    @Test
    @DisplayName("no active row resolves to the built-in server")
    fun noActiveRow() = runTest {
        val dao = FakeProfilesDao(listOf(profile(1, "Laptop", "https://laptop/wellness", active = false)))

        val resolution = bootstrap(dao).resolve() as ServerResolution.Resolved

        assertEquals("https://built-in/wellness", resolution.config.baseUrl)
        assertEquals(ServerBootstrap.BUILT_IN_NICKNAME, resolution.nickname)
    }

    @Test
    @DisplayName("an empty address book is the ordinary first-install case, not a failure")
    fun emptyAddressBook() = runTest {
        val resolution = bootstrap(FakeProfilesDao()).resolve() as ServerResolution.Resolved

        assertEquals("https://built-in/wellness", resolution.config.baseUrl)
    }

    @Test
    @DisplayName("more than one active row fails CLOSED rather than picking a winner")
    fun multipleActiveRowsFailClosed() = runTest {
        val dao = FakeProfilesDao(
            listOf(
                profile(1, "A", "https://a/wellness", active = true),
                profile(2, "B", "https://b/wellness", active = true),
            ),
        )

        val resolution = bootstrap(dao).resolve()

        // Picking one would be a coin toss over which server receives the dirty
        // rows already sitting in Room.
        assertEquals(ServerResolution.Failed(ServerBootstrap.MULTIPLE_ACTIVE), resolution)
    }

    @Test
    @DisplayName("a read failure fails CLOSED rather than falling back to the built-in server")
    fun readFailureFailsClosed() = runTest {
        val dao = FakeProfilesDao(failure = IllegalStateException("database corrupt"))

        assertEquals(
            ServerResolution.Failed(ServerBootstrap.READ_FAILED),
            bootstrap(dao).resolve(),
        )
    }

    @Test
    @DisplayName("resolveBlocking publishes the answer for the UI to observe")
    fun resolveBlockingPublishesState() {
        val boot = bootstrap(FakeProfilesDao(listOf(profile(1, "Laptop", "https://laptop/wellness", true))))

        assertNull(boot.state.value, "nothing is decided until boot runs")
        val resolution = boot.resolveBlocking()

        assertEquals(resolution, boot.state.value)
        assertEquals("https://laptop/wellness", boot.requireConfig().baseUrl)
    }

    @Test
    @DisplayName("asking for the config before resolution, or after a failure, is a crash and not a fallback")
    fun requireConfigRefusesWhenUnresolved() {
        val boot = bootstrap(FakeProfilesDao(failure = IllegalStateException("nope")))

        assertThrows<IllegalStateException> { boot.requireConfig() }
        boot.resolveBlocking()
        // A silent BuildConfig fallback here is exactly the cross-contamination
        // the recovery screen exists to prevent.
        assertThrows<IllegalStateException> { boot.requireConfig() }
    }

    @Test
    @DisplayName("retrying after the address book is repaired resolves normally")
    fun retryAfterRepair() {
        var rows = listOf(
            profile(1, "A", "https://a/wellness", active = true),
            profile(2, "B", "https://b/wellness", active = true),
        )
        val dao = object : ServerProfilesDao() {
            override fun observeAll(): Flow<List<ServerProfileEntity>> = flowOf(rows)
            override suspend fun listAll(): List<ServerProfileEntity> = rows
            override suspend fun find(id: Long): ServerProfileEntity? = rows.firstOrNull { it.id == id }
            override suspend fun listActive(): List<ServerProfileEntity> = rows.filter { it.isActive }
            override suspend fun insert(row: ServerProfileEntity): Long = 0
            override suspend fun rename(id: Long, nickname: String, url: String) = Unit
            override suspend fun delete(id: Long) = Unit
            override suspend fun activate(id: Long) = Unit
            override suspend fun clearActive() = Unit
        }
        val boot = ServerBootstrap(dao, builtInUrl = "https://built-in/wellness")

        assertTrue(boot.resolveBlocking() is ServerResolution.Failed)
        rows = listOf(profile(1, "A", "https://a/wellness", active = true))

        assertEquals("https://a/wellness", (boot.resolveBlocking() as ServerResolution.Resolved).config.baseUrl)
    }
}

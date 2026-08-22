package dev.jtiisto.wellness.core.data.sync

import dev.jtiisto.wellness.core.data.db.DebugLogEntity
import dev.jtiisto.wellness.core.data.db.ServerProfileEntity
import dev.jtiisto.wellness.core.data.db.ServerSwitchDao
import dev.jtiisto.wellness.core.data.export.SharedFileStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The switch's quiescence sequence and its one write.
 *
 * The ordering assertions are the substance. Every step exists because of a
 * specific way a response already in flight could put the previous server's data
 * back after the wipe, and a rearrangement that looks harmless reopens exactly
 * that window.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerSwitcherTest {

    @TempDir
    lateinit var cacheRoot: File

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    /** Records the composed transaction's calls in order. */
    private class FakeSwitchDao : ServerSwitchDao() {
        val calls = mutableListOf<String>()
        var inserted: DebugLogEntity? = null

        override suspend fun clearJournalTrackers() { calls += "journal_trackers" }
        override suspend fun clearJournalEntries() { calls += "journal_entries" }
        override suspend fun clearJournalMeta() { calls += "journal_meta" }
        override suspend fun clearCoachPlans() { calls += "coach_plans" }
        override suspend fun clearCoachLogs() { calls += "coach_logs" }
        override suspend fun clearCoachMeta() { calls += "coach_meta" }
        override suspend fun clearPayloadCache() { calls += "payload_cache" }
        override suspend fun clearTrendsMeta() { calls += "trends_meta" }
        override suspend fun clearHrSessions() { calls += "hr_sessions" }
        override suspend fun clearHrSamples() { calls += "hr_samples" }
        override suspend fun clearSetEvents() { calls += "set_events" }
        override suspend fun clearGuideEvents() { calls += "guide_events" }
        override suspend fun activate(id: Long) { calls += "activate:$id" }
        override suspend fun clearActive() { calls += "clearActive" }
        override suspend fun deleteProfile(id: Long) { calls += "deleteProfile:$id" }

        override suspend fun insertLogLine(entry: DebugLogEntity) {
            calls += "insertLogLine"
            inserted = entry
        }
    }

    private class Harness(scope: TestScope, private val root: File) {
        val order = mutableListOf<String>()
        val gate = ServerSessionGate()
        val dao = FakeSwitchDao()
        val sharedFiles = SharedFileStore(root = root, now = { 1_786_285_805_000L })

        /** How many times the switch ended the process. Never actually exits here. */
        var exits = 0
            private set

        /** What was still staged in `shared/` when the wipe transaction opened. */
        var stagedAtWipe: List<String>? = null
            private set

        /** A real scheduler, so `stop()` is the production one. */
        val scheduler = SyncScheduler(
            scope = scope,
            name = "journal",
            syncFn = { SyncResult(success = true) },
            isSyncing = { false },
            hasDirtyData = { false },
            isOnline = { true },
            isNetworkError = { false },
        )

        var drainGate: CompletableDeferred<Unit>? = null

        val switcher = ServerSwitcher(
            gate = gate,
            schedulers = listOf(scheduler),
            drains = listOf {
                order += "drain"
                drainGate?.await()
            },
            stopAnalysis = { order += "stopAnalysis" },
            backgroundWork = { order += "cancelWork" },
            dao = object : ServerSwitchDao() {
                override suspend fun clearJournalTrackers() = dao.clearJournalTrackers()
                override suspend fun clearJournalEntries() = dao.clearJournalEntries()
                override suspend fun clearJournalMeta() = dao.clearJournalMeta()
                override suspend fun clearCoachPlans() = dao.clearCoachPlans()
                override suspend fun clearCoachLogs() = dao.clearCoachLogs()
                override suspend fun clearCoachMeta() = dao.clearCoachMeta()
                override suspend fun clearPayloadCache() = dao.clearPayloadCache()
                override suspend fun clearTrendsMeta() = dao.clearTrendsMeta()
                override suspend fun clearHrSessions() = dao.clearHrSessions()
                override suspend fun clearHrSamples() = dao.clearHrSamples()
                override suspend fun clearSetEvents() = dao.clearSetEvents()
                override suspend fun clearGuideEvents() = dao.clearGuideEvents()
                override suspend fun activate(id: Long) = dao.activate(id)
                override suspend fun clearActive() = dao.clearActive()
                override suspend fun deleteProfile(id: Long) = dao.deleteProfile(id)
                override suspend fun insertLogLine(entry: DebugLogEntity) = dao.insertLogLine(entry)

                override suspend fun switchTo(targetId: Long?, boundary: DebugLogEntity) {
                    recordWipe()
                    dao.switchTo(targetId, boundary)
                }

                override suspend fun deleteActiveProfile(id: Long, boundary: DebugLogEntity) {
                    recordWipe()
                    dao.deleteActiveProfile(id, boundary)
                }
            },
            sharedFiles = sharedFiles,
            now = { 1_786_285_805_000L },
            exitApp = { exits++ },
        )

        private fun recordWipe() {
            order += "wipe"
            stagedAtWipe = root.list()?.toList().orEmpty()
        }
    }

    private fun profile(id: Long, nickname: String, active: Boolean = false) =
        ServerProfileEntity(id = id, nickname = nickname, url = "https://host-$id/wellness", isActive = active)

    private fun switcherTest(body: suspend TestScope.(Harness) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        body(Harness(this, File(cacheRoot, "shared")))
    }

    // ---- quiescence ------------------------------------------------------

    @Test
    @DisplayName("the gate closes before anything else — every later write is fenced from that instant")
    fun gateClosesFirst() = switcherTest { harness ->
        assertTrue(harness.gate.isOpen)

        harness.switcher.switchTo(profile(2, "B"), fromNickname = "A")

        assertFalse(harness.gate.isOpen)
    }

    @Test
    @DisplayName("the sequence is drain, stop analysis, cancel work, and only then wipe")
    fun quiescenceOrder() = switcherTest { harness ->
        harness.switcher.switchTo(profile(2, "B"), fromNickname = "A")

        // Wiping before the drain would let a cycle mid-flight write the old
        // server's rows straight back into the emptied database.
        assertEquals(listOf("drain", "stopAnalysis", "cancelWork", "wipe"), harness.order)
    }

    @Test
    @DisplayName("the drain waits out an in-flight cycle rather than racing it")
    fun drainBlocksTheWipe() = switcherTest { harness ->
        val held = CompletableDeferred<Unit>()
        harness.drainGate = held
        val job = launch { harness.switcher.switchTo(profile(2, "B"), fromNickname = "A") }

        runCurrent()
        assertEquals(listOf("drain"), harness.order, "the wipe must not have started")

        held.complete(Unit)
        job.join()
        assertTrue(harness.order.contains("wipe"))
    }

    // ---- the wipe --------------------------------------------------------

    @Test
    @DisplayName("exactly twelve tables are cleared, and the two that carry the switch itself are spared")
    fun wipeClearsExactlyElevenTables() = switcherTest { harness ->
        harness.switcher.switchTo(profile(2, "B"), fromNickname = "A")

        val cleared = harness.dao.calls.filter { it !in setOf("insertLogLine") && !it.startsWith("activate") }
        // Spelled out rather than counted: a new table that holds server data is
        // added by editing this list, and a new table that does not belong here
        // has to be argued for in the same edit.
        assertEquals(
            listOf(
                "journal_trackers",
                "journal_entries",
                "journal_meta",
                "coach_plans",
                "coach_logs",
                "coach_meta",
                "payload_cache",
                "trends_meta",
                "hr_sessions",
                "hr_samples",
                "set_events",
                "guide_events",
            ),
            cleared,
        )
        // server_profiles is the list the user just used; debug_log is what they
        // read afterwards to understand what happened.
        assertFalse(harness.dao.calls.any { it == "server_profiles" || it == "debug_log" })
    }

    @Test
    @DisplayName("clearing the journal and coach meta is what makes the full re-pull happen by itself")
    fun watermarksAreCleared() = switcherTest { harness ->
        harness.switcher.switchTo(profile(2, "B"), fromNickname = "A")

        // An empty watermark IS the request for a full pull; there is no
        // separate "resync" step and there must not be one.
        assertTrue(harness.dao.calls.contains("journal_meta"))
        assertTrue(harness.dao.calls.contains("coach_meta"))
    }

    @Test
    @DisplayName("the active flag moves with one CASE update, never a clear followed by a set")
    fun flagUpdateIsAtomic() = switcherTest { harness ->
        harness.switcher.switchTo(profile(7, "B"), fromNickname = "A")

        assertTrue(harness.dao.calls.contains("activate:7"))
        // A clear-then-set pair leaves a window with no active row, and a crash
        // inside it boots against the built-in server holding B's data.
        assertFalse(harness.dao.calls.contains("clearActive"))
    }

    @Test
    @DisplayName("switching to the built-in server clears the flag instead of setting one")
    fun switchingToBuiltIn() = switcherTest { harness ->
        harness.switcher.switchTo(target = null, fromNickname = "A")

        assertTrue(harness.dao.calls.contains("clearActive"))
        assertFalse(harness.dao.calls.any { it.startsWith("activate") })
    }

    @Test
    @DisplayName("the boundary line is written inside the transaction, naming both servers")
    fun boundaryLine() = switcherTest { harness ->
        harness.switcher.switchTo(profile(2, "Laptop"), fromNickname = "Desktop")

        val entry = requireNotNull(harness.dao.inserted)
        assertEquals("server switch: Desktop → Laptop", entry.message)
        assertEquals(ServerSwitcher.TAG, entry.tag)
        // Synchronous, not through DebugLog.log(): that is fire-and-forget on an
        // IO scope and would lose the race with exit(0).
        assertEquals("insertLogLine", harness.dao.calls.last())
    }

    @Test
    @DisplayName("switching to the built-in server names it in the boundary line")
    fun boundaryLineToBuiltIn() = switcherTest { harness ->
        harness.switcher.switchTo(target = null, fromNickname = "Laptop")

        assertEquals("server switch: Laptop → Built-in", requireNotNull(harness.dao.inserted).message)
    }

    // ---- active-profile deletion -----------------------------------------

    @Test
    @DisplayName("deleting the active profile wipes, deletes the row and clears the flag in one transaction")
    fun deleteActiveIsOneTransaction() = switcherTest { harness ->
        harness.switcher.deleteActive(profile(5, "Laptop", active = true))

        assertEquals(listOf("drain", "stopAnalysis", "cancelWork", "wipe"), harness.order)
        // Not a delete followed by a switch: a crash between those two leaves the
        // previous server's data with no profile to explain where it came from.
        assertTrue(harness.dao.calls.contains("deleteProfile:5"))
        assertTrue(harness.dao.calls.contains("clearActive"))
        assertTrue(harness.dao.calls.contains("journal_trackers"))
        assertEquals("server switch: Laptop → Built-in", requireNotNull(harness.dao.inserted).message)
    }

    // ---- shared files ----------------------------------------------------

    @Test
    @DisplayName("staged exports and log dumps are deleted: they describe a server this device no longer uses")
    fun sharedFilesAreDeleted() = switcherTest { harness ->
        val shared = File(cacheRoot, "shared").apply { mkdirs() }
        val export = File(shared, "wellness-export-2026-08-09-100000.json").apply { writeText("old data") }

        harness.switcher.switchTo(profile(2, "B"), fromNickname = "A")

        assertFalse(export.exists())
    }

    @Test
    @DisplayName("the files go BEFORE the wipe commits, so a committed switch can never leave one behind")
    fun sharedFilesGoBeforeTheWipe() = switcherTest { harness ->
        val shared = File(cacheRoot, "shared").apply { mkdirs() }
        File(shared, "stale.json").writeText("x")

        harness.switcher.switchTo(profile(2, "B"), fromNickname = "A")

        // The transaction is the commit point, and the process exits right
        // after it — there is no second chance at this cleanup. An export
        // describing the previous server's data surviving a durable switch is
        // the expensive mistake; losing a regenerable staged file to a wipe
        // that then fails is the cheap one.
        assertEquals(emptyList<String>(), harness.stagedAtWipe)
        assertEquals(emptyList<String>(), shared.list()!!.toList())
    }

    // ---- ownership and cancellation --------------------------------------

    @Test
    @DisplayName("the switch ends the process itself, once, after the transaction")
    fun theSwitchExitsTheProcess() = switcherTest { harness ->
        harness.switcher.switchTo(profile(2, "B"), fromNickname = "A")

        // Not a one-shot event for the UI to collect: a `LaunchedEffect` dies
        // with its screen, and the app would then go on running against config
        // only the next boot can apply.
        assertEquals(1, harness.exits)
        assertEquals("wipe", harness.order.last())
    }

    @Test
    @DisplayName("a caller cancelled mid-quiesce still commits — a closed gate must never be stranded")
    fun cancelledCallerStillCommits() = switcherTest { harness ->
        val held = CompletableDeferred<Unit>()
        harness.drainGate = held
        val job = launch { harness.switcher.switchTo(profile(2, "B"), fromNickname = "A") }
        runCurrent()
        assertFalse(harness.gate.isOpen, "the switch should have reached close()")

        // The tab leaves composition, or its scope is cancelled for any other
        // reason, in the window between close() and the transaction. There is
        // no reopening a gate, so abandoning here would leave every writer in
        // the process permanently refused with nothing on screen to say why.
        job.cancel()
        held.complete(Unit)
        job.join()

        assertTrue(harness.order.contains("wipe"), "the switch was abandoned with the gate closed")
        assertEquals(1, harness.exits)
    }

    @Test
    @DisplayName("a second switch confirmed mid-flight is refused rather than run across the first")
    fun secondSwitchIsRefused() = switcherTest { harness ->
        val held = CompletableDeferred<Unit>()
        harness.drainGate = held
        val first = launch { harness.switcher.switchTo(profile(2, "B"), fromNickname = "A") }
        runCurrent()
        assertTrue(harness.switcher.switching.value, "the flag the UI disables itself on")

        harness.switcher.switchTo(profile(3, "C"), fromNickname = "A")
        held.complete(Unit)
        first.join()

        // One quiesce, one wipe, one exit: the second call returned without
        // touching anything.
        assertEquals(listOf("drain", "stopAnalysis", "cancelWork", "wipe"), harness.order)
        assertEquals(1, harness.exits)
        assertTrue(harness.dao.calls.contains("activate:2"))
        assertFalse(harness.dao.calls.contains("activate:3"))
    }

    @Test
    @DisplayName("switching is false until a switch is claimed, and stays true afterwards")
    fun switchingFlagIsTerminal() = switcherTest { harness ->
        assertFalse(harness.switcher.switching.value)

        harness.switcher.switchTo(profile(2, "B"), fromNickname = "A")

        // Never cleared: the gate is closed by now, so there is nothing this
        // process could usefully re-enable.
        assertTrue(harness.switcher.switching.value)
    }
}

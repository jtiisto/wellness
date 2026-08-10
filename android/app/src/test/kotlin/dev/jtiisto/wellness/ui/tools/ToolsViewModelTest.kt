package dev.jtiisto.wellness.ui.tools

import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.db.CoachLogEntity
import dev.jtiisto.wellness.core.data.db.CoachMetaEntity
import dev.jtiisto.wellness.core.data.db.CoachPlanEntity
import dev.jtiisto.wellness.core.data.db.DebugLogDao
import dev.jtiisto.wellness.core.data.db.DebugLogEntity
import dev.jtiisto.wellness.core.data.db.ExportDao
import dev.jtiisto.wellness.core.data.db.JournalEntryEntity
import dev.jtiisto.wellness.core.data.db.JournalMetaEntity
import dev.jtiisto.wellness.core.data.db.JournalTrackerEntity
import dev.jtiisto.wellness.core.data.db.ServerProfileEntity
import dev.jtiisto.wellness.core.data.db.ServerProfilesDao
import dev.jtiisto.wellness.core.data.db.ServerSwitchDao
import dev.jtiisto.wellness.core.data.export.DataExporter
import dev.jtiisto.wellness.core.data.export.SharedFileStore
import dev.jtiisto.wellness.core.data.network.ServerBootstrap
import dev.jtiisto.wellness.core.data.network.ServerConfig
import dev.jtiisto.wellness.core.data.network.ServerProfileValidation
import dev.jtiisto.wellness.core.data.sync.DebugLog
import dev.jtiisto.wellness.core.data.sync.ForceSyncCopy
import dev.jtiisto.wellness.core.data.sync.ForceSyncCounts
import dev.jtiisto.wellness.core.data.sync.ForceSyncModule
import dev.jtiisto.wellness.core.data.sync.ForceSyncModuleResult
import dev.jtiisto.wellness.core.data.sync.ForceSyncOrchestrator
import dev.jtiisto.wellness.core.data.sync.ForceSyncSkipReason
import dev.jtiisto.wellness.core.data.sync.ServerSessionGate
import dev.jtiisto.wellness.core.data.sync.ServerSwitcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.ZoneId

/**
 * The Tools tab's state machine.
 *
 * Every destructive action is a dialog held in state rather than a `remember`
 * in the composable, which is what makes the confirmation and the work it
 * authorizes inseparable — and what makes all of this assertable without a UI
 * test rig.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ToolsViewModelTest {

    @TempDir
    lateinit var cacheRoot: File

    private val json = WellnessJson

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    // ---- fakes -----------------------------------------------------------

    private class FakeProfilesDao : ServerProfilesDao() {
        val rows = MutableStateFlow<List<ServerProfileEntity>>(emptyList())
        val calls = mutableListOf<String>()
        private var nextId = 1L

        override fun observeAll(): Flow<List<ServerProfileEntity>> = rows
        override suspend fun listAll(): List<ServerProfileEntity> = rows.value
        override suspend fun find(id: Long): ServerProfileEntity? = rows.value.firstOrNull { it.id == id }
        override suspend fun listActive(): List<ServerProfileEntity> = rows.value.filter { it.isActive }

        override suspend fun insert(row: ServerProfileEntity): Long {
            val id = nextId++
            calls += "insert:${row.nickname}:${row.url}"
            rows.value = rows.value + row.copy(id = id)
            return id
        }

        override suspend fun rename(id: Long, nickname: String, url: String) {
            calls += "rename:$id:$nickname:$url"
            rows.value = rows.value.map { if (it.id == id) it.copy(nickname = nickname, url = url) else it }
        }

        override suspend fun delete(id: Long) {
            calls += "delete:$id"
            rows.value = rows.value.filterNot { it.id == id }
        }

        override suspend fun activate(id: Long) {
            calls += "activate:$id"
        }

        override suspend fun clearActive() {
            calls += "clearActive"
        }
    }

    private class EmptyExportDao : ExportDao() {
        override suspend fun trackers(): List<JournalTrackerEntity> = emptyList()
        override suspend fun entries(): List<JournalEntryEntity> = emptyList()
        override suspend fun journalMeta(): List<JournalMetaEntity> = emptyList()
        override suspend fun plans(): List<CoachPlanEntity> = emptyList()
        override suspend fun logs(): List<CoachLogEntity> = emptyList()
        override suspend fun coachMeta(): List<CoachMetaEntity> = emptyList()
    }

    private class FakeDebugLogDao : DebugLogDao() {
        val rows = mutableListOf<DebugLogEntity>()
        private val revision = MutableStateFlow(0L)

        override suspend fun insert(entry: DebugLogEntity) {
            rows += entry
            revision.value += 1
        }

        override suspend fun deleteExpired(cutoff: Long) = Unit
        override suspend fun trimToCap(max: Int) = Unit
        override fun observeAll(): Flow<List<DebugLogEntity>> = MutableStateFlow(rows.toList())
        override suspend fun listSince(cutoff: Long): List<DebugLogEntity> = rows.toList()
    }

    private class NoopSwitchDao : ServerSwitchDao() {
        val calls = mutableListOf<String>()
        override suspend fun clearJournalTrackers() = Unit
        override suspend fun clearJournalEntries() = Unit
        override suspend fun clearJournalMeta() = Unit
        override suspend fun clearCoachPlans() = Unit
        override suspend fun clearCoachLogs() = Unit
        override suspend fun clearCoachMeta() = Unit
        override suspend fun clearPayloadCache() = Unit
        override suspend fun clearTrendsMeta() = Unit
        override suspend fun activate(id: Long) { calls += "activate:$id" }
        override suspend fun clearActive() { calls += "clearActive" }
        override suspend fun deleteProfile(id: Long) { calls += "deleteProfile:$id" }
        override suspend fun insertLogLine(entry: DebugLogEntity) { calls += "log:${entry.message}" }
    }

    private class FixedModule(
        private val result: ForceSyncModuleResult,
        private val held: CompletableDeferred<Unit>? = null,
    ) : ForceSyncModule {
        override suspend fun forceSync(): ForceSyncModuleResult {
            held?.await()
            return result
        }

        override suspend fun hasDirtyData(): Boolean = false
        override fun resetRetry() = Unit
        override fun requestSync() = Unit
    }

    // ---- harness ---------------------------------------------------------

    private inner class World(private val scope: TestScope) {
        val profilesDao = FakeProfilesDao()
        val switchDao = NoopSwitchDao()
        val logDao = FakeDebugLogDao()
        val sharedFiles = SharedFileStore(
            root = File(cacheRoot, "shared"),
            now = { 1_786_285_805_000L },
            zone = ZoneId.of("UTC"),
        )
        val debugLog = DebugLog(logDao, CoroutineScope(UnconfinedTestDispatcher(scope.testScheduler)), json)

        /** Non-null holds the coach module in flight until the test releases it. */
        var held: CompletableDeferred<Unit>? = null
        var coachResult: ForceSyncModuleResult = ForceSyncModuleResult.Success(ForceSyncCounts.Coach(2))
        var journalResult: ForceSyncModuleResult = ForceSyncModuleResult.Success(ForceSyncCounts.Journal(3, 1))
        var statusStamp: String? = "s-1"

        val bootstrap = ServerBootstrap(profilesDao, builtInUrl = "https://built-in/wellness")

        val switcher = ServerSwitcher(
            gate = ServerSessionGate(),
            schedulers = emptyList(),
            drains = emptyList(),
            stopAnalysis = {},
            backgroundWork = {},
            dao = switchDao,
            sharedFiles = sharedFiles,
            now = { 1_786_285_805_000L },
        )

        fun build(): ToolsViewModel {
            val config = ServerConfig("https://built-in/wellness")
            return ToolsViewModel(
                probeSyncStatus = { statusStamp },
                serverConfig = config,
                bootstrap = bootstrap,
                profilesDao = profilesDao,
                forceSyncOrchestrator = ForceSyncOrchestrator(
                    coach = FixedModule(coachResult, held),
                    journal = FixedModule(journalResult),
                    isOnline = { true },
                ),
                exporter = DataExporter(EmptyExportDao(), clientVersion = "1.2.3", json = json) { "now" },
                sharedFiles = sharedFiles,
                switcher = switcher,
                debugLog = debugLog,
                buildStamp = "1.2.3 (dev)",
                io = UnconfinedTestDispatcher(scope.testScheduler),
            )
        }
    }

    /**
     * `uiState` is `SharingStarted.WhileSubscribed`, so with no collector it
     * never advances past its initial value. Every test subscribes for the
     * duration, exactly as the screen does.
     */
    private fun toolsTest(body: suspend TestScope.(World, ToolsViewModel) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val world = World(this)
        val viewModel = world.build()
        backgroundScope.launch { viewModel.uiState.collect { } }
        runCurrent()
        body(world, viewModel)
    }

    private fun profile(id: Long, nickname: String, active: Boolean = false) =
        ServerProfileEntity(id = id, nickname = nickname, url = "https://host-$id/wellness", isActive = active)

    // ---- force sync ------------------------------------------------------

    @Test
    @DisplayName("force sync asks first — the button never syncs on its own")
    fun forceSyncConfirmsFirst() = toolsTest { world, vm ->
        vm.requestForceSync()
        runCurrent()

        assertEquals(ToolsDialog.ConfirmForceSync, vm.uiState.value.dialog)
    }

    @Test
    @DisplayName("confirming runs both modules and reports their counts")
    fun confirmedForceSyncReportsCounts() = toolsTest { world, vm ->
        vm.requestForceSync()
        vm.confirmForceSync()
        runCurrent()

        val dialog = vm.uiState.value.dialog as ToolsDialog.ForceSyncResult
        assertTrue(dialog.success)
        assertEquals("Coach: 2 days uploaded. Journal: 3 records accepted, 1 conflicts", dialog.message)
        assertEquals(ForceSyncUi.Idle, vm.uiState.value.forceSync, "the button must be usable again")
    }

    @Test
    @DisplayName("the button reads as busy while the cycle runs, and is usable again after")
    fun forceSyncShowsBusy() = toolsTest { world, _ ->
        // The module is held mid-cycle, so "running" is a state the test can
        // actually observe rather than one that comes and goes within a tick.
        val held = CompletableDeferred<Unit>()
        world.held = held
        val vm = world.build()
        backgroundScope.launch { vm.uiState.collect { } }
        runCurrent()

        vm.confirmForceSync()
        runCurrent()
        assertEquals(ForceSyncUi.Running, vm.uiState.value.forceSync)

        held.complete(Unit)
        runCurrent()
        assertEquals(ForceSyncUi.Idle, vm.uiState.value.forceSync)
    }

    @Test
    @DisplayName("both modules failing is reported without success styling")
    fun failedForceSync() = toolsTest { world, _ ->
        world.coachResult = ForceSyncModuleResult.Failed("nope")
        world.journalResult = ForceSyncModuleResult.Failed("nope")
        val vm = world.build()
        backgroundScope.launch { vm.uiState.collect { } }
        runCurrent()
        vm.confirmForceSync()
        runCurrent()

        val dialog = vm.uiState.value.dialog as ToolsDialog.ForceSyncResult
        assertEquals(false, dialog.success)
        assertEquals("Coach: failed. Journal: failed", dialog.message)
    }

    @Test
    @DisplayName("both modules skipping collapses to one sentence")
    fun bothSkipped() = toolsTest { world, _ ->
        world.coachResult = ForceSyncModuleResult.Skipped(ForceSyncSkipReason.OFFLINE)
        world.journalResult = ForceSyncModuleResult.Skipped(ForceSyncSkipReason.OFFLINE)
        val vm = world.build()
        backgroundScope.launch { vm.uiState.collect { } }
        runCurrent()
        vm.confirmForceSync()
        runCurrent()

        assertEquals(
            ForceSyncCopy.NOTHING_SYNCED,
            (vm.uiState.value.dialog as ToolsDialog.ForceSyncResult).message,
        )
    }

    @Test
    @DisplayName("a second confirmation while one is running is ignored")
    fun doubleConfirmIsIgnored() = toolsTest { world, vm ->
        vm.confirmForceSync()
        vm.confirmForceSync()
        runCurrent()

        assertTrue(vm.uiState.value.dialog is ToolsDialog.ForceSyncResult)
    }

    // ---- export and log share --------------------------------------------

    @Test
    @DisplayName("export writes a file and emits a share event pointing at it")
    fun exportEmitsShare() = toolsTest { world, vm ->
        vm.exportAllData()
        runCurrent()

        val event = vm.events.first() as ToolsEvent.Share
        assertEquals("application/json", event.mimeType)
        val file = File(event.path)
        assertTrue(file.exists())
        assertTrue(file.readText().contains("\"version\": 2"), file.readText().take(120))
        assertEquals("wellness-export-2026-08-09-143005.json", file.name)
    }

    @Test
    @DisplayName("an export failure surfaces as a message rather than silence")
    fun exportFailureSurfaces() = toolsTest { world, vm ->
        // A directory where the file should be: the stream cannot open.
        File(cacheRoot, "shared/wellness-export-2026-08-09-143005.json").mkdirs()
        vm.exportAllData()
        runCurrent()

        val event = vm.events.first() as ToolsEvent.Message
        // The PWA swallowed this and left the user believing a file was written.
        assertTrue(event.text.startsWith(ToolsCopy.EXPORT_FAILED), event.text)
    }

    @Test
    @DisplayName("sharing the log writes the same dump the tab renders")
    fun logShareWritesTheDump() = toolsTest { world, vm ->
        world.logDao.rows += DebugLogEntity(id = 1, ts = 1_000L, tag = "journal-sync", message = "sync start")
        vm.shareDebugLog()
        runCurrent()

        val event = vm.events.first() as ToolsEvent.Share
        assertEquals("text/plain", event.mimeType)
        assertEquals("debug-log-2026-08-09-143005.txt", File(event.path).name)
        assertEquals(world.debugLog.dump(), File(event.path).readText())
    }

    // ---- address book ----------------------------------------------------

    @Test
    @DisplayName("the list shows the built-in row plus saved profiles")
    fun listShowsBuiltInAndProfiles() = toolsTest { world, vm ->
        world.profilesDao.rows.value = listOf(profile(1, "Laptop", active = true))
        runCurrent()

        assertEquals(listOf(ToolsCopy.BUILT_IN, "Laptop"), vm.uiState.value.servers.map { it.nickname })
        assertEquals("Laptop", vm.uiState.value.activeNickname)
    }

    @Test
    @DisplayName("a valid new profile is inserted with both fields normalized")
    fun addValidProfile() = toolsTest { world, vm ->
        vm.addProfile()
        vm.editorChanged("  Laptop  ", "  https://laptop/wellness/  ")
        vm.saveProfile()
        runCurrent()

        assertEquals(listOf("insert:Laptop:https://laptop/wellness"), world.profilesDao.calls)
        assertNull(vm.uiState.value.dialog)
    }

    @Test
    @DisplayName("an invalid address keeps the dialog open with the reason attached")
    fun invalidProfileKeepsTheDialog() = toolsTest { world, vm ->
        vm.addProfile()
        vm.editorChanged("Laptop", "https://laptop/wellness?debug=1")
        vm.saveProfile()
        runCurrent()

        val dialog = vm.uiState.value.dialog as ToolsDialog.EditProfile
        assertEquals(ServerProfileValidation.URL_HAS_EXTRAS, dialog.error)
        assertTrue(world.profilesDao.calls.isEmpty())
    }

    @Test
    @DisplayName("typing again clears the error rather than leaving a complaint about replaced text")
    fun editingClearsTheError() = toolsTest { world, vm ->
        vm.addProfile()
        vm.editorChanged("", "https://laptop/wellness")
        vm.saveProfile()
        vm.editorChanged("Laptop", "https://laptop/wellness")
        runCurrent()

        assertNull((vm.uiState.value.dialog as ToolsDialog.EditProfile).error)
    }

    @Test
    @DisplayName("renaming a profile is allowed and never switches servers")
    fun editProfileRenames() = toolsTest { world, vm ->
        val existing = profile(1, "Laptop", active = true)
        world.profilesDao.rows.value = listOf(existing)
        runCurrent()

        vm.editProfile(existing)
        // Same address, new name: moves no data, so it is not a switch.
        vm.editorChanged("Desk", existing.url)
        vm.saveProfile()
        runCurrent()

        assertEquals(listOf("rename:1:Desk:${existing.url}"), world.profilesDao.calls)
        assertTrue(world.switchDao.calls.isEmpty())
    }

    @Test
    @DisplayName("changing the ACTIVE profile's address is refused — only the switch flow may do that")
    fun activeProfileUrlEditIsProhibited() = toolsTest { world, vm ->
        val active = profile(1, "Laptop", active = true)
        world.profilesDao.rows.value = listOf(active)
        runCurrent()

        vm.editProfile(active)
        vm.editorChanged("Laptop", "https://somewhere-else/wellness")
        vm.saveProfile()
        runCurrent()

        // A rename here would have repointed boot resolution at a different
        // server with no quiescence and no wipe, leaving the previous server's
        // dirty rows queued to upload to the new one.
        assertTrue(world.profilesDao.calls.isEmpty(), "the address change must not reach storage")
        assertEquals(
            ToolsCopy.ACTIVE_URL_LOCKED,
            (vm.uiState.value.dialog as ToolsDialog.EditProfile).error,
        )
    }

    @Test
    @DisplayName("the same address change on an INACTIVE profile is fine")
    fun inactiveProfileUrlEditIsAllowed() = toolsTest { world, vm ->
        val inactive = profile(2, "Spare", active = false)
        world.profilesDao.rows.value = listOf(profile(1, "Laptop", active = true), inactive)
        runCurrent()

        vm.editProfile(inactive)
        vm.editorChanged("Spare", "https://spare-2/wellness")
        vm.saveProfile()
        runCurrent()

        // Nothing points at it, so nothing can be mis-uploaded to it.
        assertEquals(listOf("rename:2:Spare:https://spare-2/wellness"), world.profilesDao.calls)
        assertNull(vm.uiState.value.dialog)
    }

    @Test
    @DisplayName("deleting an inactive profile is a plain list edit behind a plain dialog")
    fun deleteInactiveProfile() = toolsTest { world, vm ->
        val target = profile(2, "Old")
        world.profilesDao.rows.value = listOf(profile(1, "Laptop", active = true), target)
        vm.requestDelete(target)
        runCurrent()
        assertEquals(ToolsDialog.ConfirmDelete(target), vm.uiState.value.dialog)

        vm.confirmDelete(target)
        runCurrent()
        assertEquals(listOf("delete:2"), world.profilesDao.calls)
        assertTrue(world.switchDao.calls.isEmpty(), "no wipe for a profile that was not in use")
    }

    @Test
    @DisplayName("deleting the ACTIVE profile routes to the dialog that discloses the wipe")
    fun deleteActiveProfileRoutesToTheWipeDialog() = toolsTest { world, vm ->
        val active = profile(1, "Laptop", active = true)
        world.profilesDao.rows.value = listOf(active)
        vm.requestDelete(active)
        runCurrent()

        assertEquals(ToolsDialog.ConfirmDeleteActive(active), vm.uiState.value.dialog)
    }

    @Test
    @DisplayName("confirming the active delete wipes, deletes and closes the app")
    fun confirmDeleteActive() = toolsTest { world, vm ->
        val active = profile(1, "Laptop", active = true)
        world.profilesDao.rows.value = listOf(active)
        vm.confirmDeleteActive(active)
        runCurrent()

        assertTrue(world.switchDao.calls.contains("deleteProfile:1"))
        assertTrue(world.switchDao.calls.contains("clearActive"))
        assertEquals(ToolsEvent.CloseApp, vm.events.first())
    }

    // ---- switching -------------------------------------------------------

    @Test
    @DisplayName("tapping a non-active row asks before doing anything")
    fun switchAsksFirst() = toolsTest { world, vm ->
        val target = profile(2, "Other")
        world.profilesDao.rows.value = listOf(profile(1, "Laptop", active = true), target)
        vm.requestSwitch(ServerProfileRow(id = 2, nickname = "Other", url = target.url, isActive = false))
        runCurrent()

        assertEquals(ToolsDialog.ConfirmSwitch(target, "Other"), vm.uiState.value.dialog)
        assertTrue(world.switchDao.calls.isEmpty(), "nothing may happen before the confirmation")
    }

    @Test
    @DisplayName("tapping the row that is already active does nothing at all")
    fun tappingTheActiveRowDoesNothing() = toolsTest { world, vm ->
        vm.requestSwitch(ServerProfileRow(id = 1, nickname = "Laptop", url = "https://x", isActive = true))
        runCurrent()

        assertNull(vm.uiState.value.dialog)
    }

    @Test
    @DisplayName("a row deleted between the render and the tap opens no dialog")
    fun switchToAVanishedRow() = toolsTest { world, vm ->
        vm.requestSwitch(ServerProfileRow(id = 99, nickname = "Gone", url = "https://x", isActive = false))
        runCurrent()

        assertNull(vm.uiState.value.dialog)
    }

    @Test
    @DisplayName("confirming a switch commits it and then closes the app")
    fun confirmSwitchClosesTheApp() = toolsTest { world, vm ->
        val target = profile(2, "Other")
        world.profilesDao.rows.value = listOf(profile(1, "Laptop", active = true), target)
        runCurrent()

        vm.confirmSwitch(target)
        runCurrent()

        assertTrue(world.switchDao.calls.contains("activate:2"))
        // Close-and-reopen rather than a task-flag restart, which guarantees no
        // new process is launched at all.
        assertEquals(ToolsEvent.CloseApp, vm.events.first())
    }

    @Test
    @DisplayName("switching back to the built-in server clears the active flag")
    fun switchToBuiltIn() = toolsTest { world, vm ->
        world.profilesDao.rows.value = listOf(profile(1, "Laptop", active = true))
        runCurrent()

        vm.confirmSwitch(null)
        runCurrent()

        assertTrue(world.switchDao.calls.contains("clearActive"))
        assertTrue(
            world.switchDao.calls.contains("log:server switch: Laptop → Built-in"),
            world.switchDao.calls.toString(),
        )
    }

    @Test
    @DisplayName("dismissing any dialog leaves state untouched")
    fun dismissDialog() = toolsTest { world, vm ->
        vm.requestForceSync()
        vm.dismissDialog()
        runCurrent()

        assertNull(vm.uiState.value.dialog)
        assertTrue(world.switchDao.calls.isEmpty())
    }

    // ---- build info ------------------------------------------------------

    @Test
    @DisplayName("the build stamp is rendered from the value the app module supplied")
    fun buildStampIsRendered() = toolsTest { world, vm ->
        assertEquals("1.2.3 (dev)", vm.uiState.value.buildStamp)
    }
}

package dev.jtiisto.wellness.ui.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jtiisto.wellness.core.data.db.DebugLogEntity
import dev.jtiisto.wellness.core.data.db.ServerProfileEntity
import dev.jtiisto.wellness.core.data.db.ServerProfilesDao
import dev.jtiisto.wellness.core.data.export.DataExporter
import dev.jtiisto.wellness.core.data.export.SharedFileStore
import dev.jtiisto.wellness.core.data.network.ProfileValidation
import dev.jtiisto.wellness.core.data.network.ServerBootstrap
import dev.jtiisto.wellness.core.data.network.ServerConfig
import dev.jtiisto.wellness.core.data.network.ServerProfileValidation
import dev.jtiisto.wellness.core.data.network.describeForLog
import dev.jtiisto.wellness.core.data.sync.DebugLog
import dev.jtiisto.wellness.core.data.sync.ForceSyncCopy
import dev.jtiisto.wellness.core.data.sync.ForceSyncOrchestrator
import dev.jtiisto.wellness.core.data.sync.ServerSwitcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Outcome of the server-status probe. */
sealed interface ServerPing {
    data object Idle : ServerPing
    data object InFlight : ServerPing
    data class Reached(val lastModified: String?) : ServerPing
    data class Failed(val message: String) : ServerPing
}

/** Where the force-sync button is in its cycle. */
sealed interface ForceSyncUi {
    data object Idle : ForceSyncUi
    data object Running : ForceSyncUi
}

/** A modal the tab is asking the user to answer. Exactly one at a time. */
sealed interface ToolsDialog {
    data object ConfirmForceSync : ToolsDialog

    data class ForceSyncResult(val success: Boolean, val message: String) : ToolsDialog

    /** Editing an existing profile when [profile] is set; adding one when it is not. */
    data class EditProfile(
        val profile: ServerProfileEntity?,
        val nickname: String,
        val url: String,
        val error: String? = null,
    ) : ToolsDialog

    data class ConfirmDelete(val profile: ServerProfileEntity) : ToolsDialog

    /** Deleting the profile in use — one dialog for both the delete and the wipe. */
    data class ConfirmDeleteActive(val profile: ServerProfileEntity) : ToolsDialog

    /** [target] null means switching back to the built-in server. */
    data class ConfirmSwitch(val target: ServerProfileEntity?, val nickname: String) : ToolsDialog
}

/** One-shot effects: things that happen once and must not survive a rotation. */
sealed interface ToolsEvent {
    /** Hand [path] to a share sheet. The URI is minted by the Activity. */
    data class Share(val path: String, val mimeType: String) : ToolsEvent

    data class Message(val text: String) : ToolsEvent

    /**
     * The switch has committed. The Activity finishes the task and ends the
     * process; the user reopens the app, which is when boot resolution runs
     * against the profile that was just made active.
     */
    data object CloseApp : ToolsEvent
}

data class ToolsUiState(
    val baseUrl: String = "",
    val activeNickname: String = ToolsCopy.BUILT_IN,
    val buildStamp: String = "",
    val ping: ServerPing = ServerPing.Idle,
    val forceSync: ForceSyncUi = ForceSyncUi.Idle,
    val servers: List<ServerProfileRow> = emptyList(),
    val dialog: ToolsDialog? = null,
    val log: List<DebugLogEntity> = emptyList(),
)

/**
 * The Tools tab: force sync, export, log share, build info, and the server
 * address book.
 *
 * Every destructive action goes through a dialog held in [uiState] rather than
 * a `remember` in the composable, so the confirmation and the work it authorizes
 * cannot be separated by a recomposition — and so the copy is assertable
 * without a UI test rig.
 *
 * File writes are one-shot [ToolsEvent]s carrying a path. The Activity turns it
 * into a `content://` URI and a chooser; nothing here touches an `Intent`, which
 * is what keeps the whole class unit-testable.
 *
 * @param probeSyncStatus the server-reachability probe, as a lambda rather than
 *   the `JournalApi` behind it. The tab needs one string from the network layer
 *   and has no other business with it.
 */
class ToolsViewModel(
    private val probeSyncStatus: suspend () -> String?,
    private val serverConfig: ServerConfig,
    private val bootstrap: ServerBootstrap,
    private val profilesDao: ServerProfilesDao,
    private val forceSyncOrchestrator: ForceSyncOrchestrator,
    private val exporter: DataExporter,
    private val sharedFiles: SharedFileStore,
    private val switcher: ServerSwitcher,
    private val debugLog: DebugLog,
    private val buildStamp: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val ping = MutableStateFlow<ServerPing>(ServerPing.Idle)
    private val forceSync = MutableStateFlow<ForceSyncUi>(ForceSyncUi.Idle)
    private val dialog = MutableStateFlow<ToolsDialog?>(null)

    private val _events = Channel<ToolsEvent>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: Flow<ToolsEvent> = _events.receiveAsFlow()

    val uiState: StateFlow<ToolsUiState> = combine(
        ping,
        forceSync,
        dialog,
        profilesDao.observeAll(),
        debugLog.entries(),
    ) { ping, forceSync, dialog, profiles, log ->
        ToolsUiState(
            baseUrl = serverConfig.normalizedBaseUrl,
            activeNickname = ToolsLogic.activeNickname(profiles),
            buildStamp = buildStamp,
            ping = ping,
            forceSync = forceSync,
            servers = ToolsLogic.rows(profiles, bootstrap.builtInUrl),
            dialog = dialog,
            log = log,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = ToolsUiState(baseUrl = serverConfig.normalizedBaseUrl, buildStamp = buildStamp),
    )

    // ---- server status ---------------------------------------------------

    fun pingServer() {
        if (ping.value == ServerPing.InFlight) return
        ping.value = ServerPing.InFlight
        viewModelScope.launch {
            ping.value = try {
                ServerPing.Reached(probeSyncStatus())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                ServerPing.Failed(describe(error))
            }
        }
    }

    // ---- force sync ------------------------------------------------------

    fun requestForceSync() {
        if (forceSync.value == ForceSyncUi.Running) return
        dialog.value = ToolsDialog.ConfirmForceSync
    }

    /**
     * The confirmed force sync.
     *
     * Offline is deliberately **not** checked here: the orchestrator evaluates
     * it per module at each module's own start, so a connection that drops
     * between coach and journal produces an honest split result rather than one
     * blanket refusal taken before either ran.
     */
    fun confirmForceSync() {
        if (forceSync.value == ForceSyncUi.Running) return
        dialog.value = null
        forceSync.value = ForceSyncUi.Running
        viewModelScope.launch {
            try {
                val report = forceSyncOrchestrator.run()
                dialog.value = ToolsDialog.ForceSyncResult(
                    success = ForceSyncCopy.isSuccess(report),
                    message = ForceSyncCopy.message(report),
                )
            } finally {
                forceSync.value = ForceSyncUi.Idle
            }
        }
    }

    // ---- export and log share --------------------------------------------

    /**
     * Write the export and offer it.
     *
     * Failures surface. The PWA caught the error and returned a result object
     * nobody read, so a failed export was indistinguishable from a successful
     * one until you went looking for the file.
     */
    fun exportAllData() {
        viewModelScope.launch {
            try {
                val path = withContext(io) {
                    val file = sharedFiles.prepare(sharedFiles.exportFileName())
                    file.outputStream().buffered().use { exporter.writeTo(it) }
                    file.absolutePath
                }
                _events.send(ToolsEvent.Share(path, DataExporter.MIME_TYPE))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _events.send(ToolsEvent.Message("${ToolsCopy.EXPORT_FAILED}: ${describe(error)}"))
            }
        }
    }

    /** The same dump the tab renders, as a file. One format, two surfaces. */
    fun shareDebugLog() {
        viewModelScope.launch {
            try {
                val dump = debugLog.dump()
                val path = withContext(io) {
                    val file = sharedFiles.prepare(sharedFiles.debugLogFileName())
                    file.writeText(dump)
                    file.absolutePath
                }
                _events.send(ToolsEvent.Share(path, MIME_TEXT))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _events.send(ToolsEvent.Message("${ToolsCopy.LOG_SHARE_FAILED}: ${describe(error)}"))
            }
        }
    }

    // ---- address book ----------------------------------------------------

    fun addProfile() {
        dialog.value = ToolsDialog.EditProfile(profile = null, nickname = "", url = "")
    }

    fun editProfile(profile: ServerProfileEntity) {
        dialog.value = ToolsDialog.EditProfile(profile, profile.nickname, profile.url)
    }

    fun editorChanged(nickname: String, url: String) {
        val current = dialog.value as? ToolsDialog.EditProfile ?: return
        // The error clears as soon as the user starts fixing it; leaving it up
        // would have them reading a complaint about text they have replaced.
        dialog.value = current.copy(nickname = nickname, url = url, error = null)
    }

    fun saveProfile() {
        val current = dialog.value as? ToolsDialog.EditProfile ?: return
        when (val validated = ServerProfileValidation.validate(current.nickname, current.url)) {
            is ProfileValidation.Invalid -> dialog.value = current.copy(error = validated.message)
            is ProfileValidation.Valid -> {
                val existing = current.profile
                // The active profile's ADDRESS is off limits here. Changing it
                // would repoint the app at a different server with none of the
                // switch's quiescence or wipe, leaving the previous server's
                // dirty rows queued to upload to the new one — the switch flow
                // is the only path allowed to change servers. A rename is still
                // fine: it moves no data.
                if (existing != null && existing.isActive && existing.url != validated.url) {
                    dialog.value = current.copy(error = ToolsCopy.ACTIVE_URL_LOCKED)
                    return
                }
                viewModelScope.launch {
                    if (existing == null) {
                        profilesDao.insert(
                            ServerProfileEntity(nickname = validated.nickname, url = validated.url),
                        )
                    } else {
                        profilesDao.rename(existing.id, validated.nickname, validated.url)
                    }
                    dialog.value = null
                }
            }
        }
    }

    /**
     * Tapping a row's delete action.
     *
     * An inactive profile is a list edit and nothing more. The active one takes
     * the whole data with it, so it routes to the dialog that says so.
     */
    fun requestDelete(profile: ServerProfileEntity) {
        dialog.value = if (profile.isActive) {
            ToolsDialog.ConfirmDeleteActive(profile)
        } else {
            ToolsDialog.ConfirmDelete(profile)
        }
    }

    fun confirmDelete(profile: ServerProfileEntity) {
        dialog.value = null
        viewModelScope.launch { profilesDao.delete(profile.id) }
    }

    /** Tapping a row that is not the active one. */
    fun requestSwitch(row: ServerProfileRow) {
        if (row.isActive) return
        viewModelScope.launch {
            val target = row.id?.let { profilesDao.find(it) }
            // The row was deleted between the render and the tap.
            if (row.id != null && target == null) return@launch
            dialog.value = ToolsDialog.ConfirmSwitch(target, row.nickname)
        }
    }

    fun confirmSwitch(target: ServerProfileEntity?) {
        dialog.value = null
        viewModelScope.launch {
            switcher.switchTo(target, fromNickname = uiState.value.activeNickname)
            _events.send(ToolsEvent.CloseApp)
        }
    }

    fun confirmDeleteActive(profile: ServerProfileEntity) {
        dialog.value = null
        viewModelScope.launch {
            switcher.deleteActive(profile)
            _events.send(ToolsEvent.CloseApp)
        }
    }

    fun dismissDialog() {
        dialog.value = null
    }

    /**
     * Same rule as the debug log: a Ktor response exception's `message` is the
     * response body, and these strings land on screen.
     */
    private fun describe(error: Throwable): String = describeForLog(error)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val MIME_TEXT = "text/plain"
    }
}

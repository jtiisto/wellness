package dev.jtiisto.wellness.ui.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jtiisto.wellness.core.data.db.DebugLogEntity
import dev.jtiisto.wellness.core.data.network.JournalApi
import dev.jtiisto.wellness.core.data.network.ServerConfig
import dev.jtiisto.wellness.core.data.sync.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Outcome of the server-status probe. */
sealed interface ServerPing {
    data object Idle : ServerPing
    data object InFlight : ServerPing
    data class Reached(val lastModified: String?) : ServerPing
    data class Failed(val message: String) : ServerPing
}

data class ToolsUiState(
    val baseUrl: String = "",
    val ping: ServerPing = ServerPing.Idle,
    val log: List<DebugLogEntity> = emptyList(),
)

/**
 * The Tools tab. Phase 1 is the round-trip demo: probe the journal sync status
 * endpoint and watch the request appear in the debug log. Force sync and export
 * land in Phase 8.
 */
class ToolsViewModel(
    private val journalApi: JournalApi,
    private val serverConfig: ServerConfig,
    debugLog: DebugLog,
) : ViewModel() {

    private val ping = MutableStateFlow<ServerPing>(ServerPing.Idle)

    val uiState: StateFlow<ToolsUiState> = combine(ping, debugLog.entries()) { ping, log ->
        ToolsUiState(baseUrl = serverConfig.normalizedBaseUrl, ping = ping, log = log)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = ToolsUiState(baseUrl = serverConfig.normalizedBaseUrl),
    )

    fun pingServer() {
        if (ping.value == ServerPing.InFlight) return
        ping.value = ServerPing.InFlight
        viewModelScope.launch {
            ping.value = try {
                ServerPing.Reached(journalApi.syncStatus().lastModified)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                ServerPing.Failed(error.message ?: error::class.simpleName ?: "unknown error")
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

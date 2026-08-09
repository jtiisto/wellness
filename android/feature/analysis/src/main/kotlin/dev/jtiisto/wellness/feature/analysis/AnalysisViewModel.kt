package dev.jtiisto.wellness.feature.analysis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jtiisto.wellness.core.data.analysis.AnalysisEvents
import dev.jtiisto.wellness.core.data.analysis.AnalysisQueryDto
import dev.jtiisto.wellness.core.data.analysis.AnalysisState
import dev.jtiisto.wellness.core.data.analysis.AnalysisStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Screen-local state the store has no business holding.
 *
 * The typed location is deliberately not persisted and not in [AnalysisState]:
 * it belongs to one visit to one card, and a location remembered from last week
 * would silently change what a query means.
 */
data class AnalysisUiState(
    val expandedQueryId: String? = null,
    val locations: Map<String, String> = emptyMap(),
    val confirmDeleteId: Long? = null,
)

/**
 * The Analysis tab's ViewModel — a thin layer, on purpose.
 *
 * Every decision that outlives the screen lives in [AnalysisStore]: the poll
 * survives this ViewModel being cleared, and must, because leaving the tab is
 * not abandoning the query.
 */
class AnalysisViewModel(
    private val store: AnalysisStore,
    events: AnalysisEvents,
    val zone: ZoneId,
) : ViewModel() {

    val state: StateFlow<AnalysisState> = store.state

    /** One snackbar per event, never re-raised by a recomposition. */
    val messages: Flow<String> = events.events.map { it.message }

    private val _ui = MutableStateFlow(AnalysisUiState())
    val ui: StateFlow<AnalysisUiState> = _ui.asStateFlow()

    /**
     * Called from the screen's first composition. The store memoizes it, so a
     * tab switch costs nothing; a cleared ViewModel mid-init costs nothing
     * either, since the work runs on the application scope.
     */
    fun initialize() {
        viewModelScope.launch { store.initialize() }
    }

    /**
     * Tapping a card. A query that takes a location opens instead of running —
     * there is a field to fill in first.
     */
    fun onQueryTap(query: AnalysisQueryDto) {
        if (query.acceptsLocation) {
            _ui.update { it.copy(expandedQueryId = if (it.expandedQueryId == query.id) null else query.id) }
        } else {
            store.submit(query.id, null)
        }
    }

    /** The Run button on an expanded card. A blank field is simply no location. */
    fun onRun(query: AnalysisQueryDto) {
        store.submit(query.id, _ui.value.locations[query.id]?.takeIf { it.isNotBlank() })
        _ui.update { it.copy(expandedQueryId = null) }
    }

    fun onLocationChange(queryId: String, text: String) {
        _ui.update { it.copy(locations = it.locations + (queryId to text)) }
    }

    /** Deviation 7: a location query is never resubmitted without asking. */
    fun onTryAgain(queryId: String) {
        when (AnalysisUiLogic.tryAgainMode(state.value.queries, queryId)) {
            TryAgainMode.RESUBMIT -> store.submit(queryId, null)
            TryAgainMode.NEEDS_LOCATION -> {
                store.openQueries()
                _ui.update { it.copy(expandedQueryId = queryId) }
            }
            TryAgainMode.UNAVAILABLE -> Unit
        }
    }

    fun openReport(id: Long) = store.openReport(id)

    fun openHistory() = store.openHistory()

    fun openQueries() = store.openQueries()

    fun cancelActive() = store.cancelActive()

    fun recheck() = store.recheck()

    fun askDelete(id: Long) {
        _ui.update { it.copy(confirmDeleteId = id) }
    }

    fun dismissDelete() {
        _ui.update { it.copy(confirmDeleteId = null) }
    }

    fun confirmDelete() {
        val id = _ui.value.confirmDeleteId ?: return
        _ui.update { it.copy(confirmDeleteId = null) }
        store.delete(id)
    }
}

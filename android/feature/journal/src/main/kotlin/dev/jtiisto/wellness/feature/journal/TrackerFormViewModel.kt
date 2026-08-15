package dev.jtiisto.wellness.feature.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jtiisto.wellness.core.data.journal.JournalSyncStore
import dev.jtiisto.wellness.core.data.journal.TrackerDto
import dev.jtiisto.wellness.core.data.journal.TrackerType
import dev.jtiisto.wellness.core.data.journal.formatScheduleSummary
import dev.jtiisto.wellness.core.data.journal.getCategories
import dev.jtiisto.wellness.core.data.journal.getScheduleDaysForDate
import dev.jtiisto.wellness.core.data.journal.groupByCategory
import dev.jtiisto.wellness.core.data.journal.trackerType
import dev.jtiisto.wellness.core.data.journal.formatTarget
import dev.jtiisto.wellness.core.data.journal.targetForDate
import dev.jtiisto.wellness.core.data.journal.unitOrNull
import dev.jtiisto.wellness.core.data.journal.POLARITY_VALUES
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

/** The config screen: the grouped tracker list plus whatever is open over it. */
data class TrackerConfigUiState(
    val groups: List<TrackerConfigGroup> = emptyList(),
    val categories: List<String> = emptyList(),
    val isEmpty: Boolean = true,
    val form: TrackerFormState? = null,
    /** Null for a new tracker; the id being edited otherwise. */
    val editingId: String? = null,
    val pendingDeleteId: String? = null,
    val pendingDeleteName: String = "",
)

data class TrackerConfigGroup(val name: String, val trackers: List<TrackerConfigRow>)

/** One row of the config list: the tracker's identity plus its settings, summarised. */
data class TrackerConfigRow(
    val id: String,
    val name: String,
    val summary: String,
)

/**
 * The tracker config screen and its form.
 *
 * The form is held here rather than in composable state so that a rotation or a
 * recomposition cannot lose a half-filled tracker. Its rules live in
 * `TrackerFormLogic`; this class only routes edits into that state and the
 * result into the store.
 */
class TrackerFormViewModel(
    private val store: JournalSyncStore,
    private val today: () -> LocalDate = LocalDate::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {

    private val trackers: StateFlow<List<TrackerDto>> = store.observeTrackers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val editor = MutableStateFlow(EditorState())

    val uiState: StateFlow<TrackerConfigUiState> = combine(trackers, editor) { all, edit ->
        TrackerConfigUiState(
            groups = groupByCategory(all).map { (category, group) ->
                TrackerConfigGroup(category, group.map(::configRow))
            },
            categories = getCategories(all),
            isEmpty = all.isEmpty(),
            form = edit.form,
            editingId = edit.editingId,
            pendingDeleteId = edit.pendingDeleteId,
            pendingDeleteName = all.firstOrNull { it.id == edit.pendingDeleteId }?.name.orEmpty(),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = TrackerConfigUiState(),
    )

    // ---- opening and closing the form --------------------------------------

    fun addTracker() {
        editor.value = EditorState(
            form = seedTrackerForm(null, getCategories(trackers.value), today().toString()),
            editingId = null,
        )
    }

    fun editTracker(id: String) {
        val tracker = trackers.value.firstOrNull { it.id == id } ?: return
        editor.value = EditorState(
            form = seedTrackerForm(tracker, getCategories(trackers.value), today().toString()),
            editingId = id,
        )
    }

    /** Every dismissal path lands here, and every one of them discards silently. */
    fun dismissForm() {
        editor.value = editor.value.copy(form = null, editingId = null)
    }

    fun updateForm(transform: (TrackerFormState) -> TrackerFormState) {
        val current = editor.value.form ?: return
        editor.value = editor.value.copy(form = transform(current))
    }

    /**
     * Validate and write. Returns false when the form still has errors, which
     * is when [TrackerFormState.submitAttempted] starts showing them.
     */
    fun submitForm(): Boolean {
        val state = editor.value.form ?: return false
        val existing = editor.value.editingId?.let { id -> trackers.value.firstOrNull { it.id == id } }
        val submission = buildTrackerSubmission(existing, state, today().toString(), newId)
        if (submission == null) {
            editor.value = editor.value.copy(form = state.copy(submitAttempted = true))
            return false
        }
        viewModelScope.launch {
            if (existing == null) {
                store.addTracker(submission.toNewTracker())
            } else {
                store.updateTracker(existing.id) { submission.applyTo(it) }
            }
        }
        dismissForm()
        return true
    }

    // ---- delete --------------------------------------------------------------

    fun confirmDelete(id: String) {
        editor.value = editor.value.copy(pendingDeleteId = id)
    }

    fun cancelDelete() {
        editor.value = editor.value.copy(pendingDeleteId = null)
    }

    fun deleteConfirmed() {
        val id = editor.value.pendingDeleteId ?: return
        editor.value = editor.value.copy(pendingDeleteId = null)
        viewModelScope.launch { store.deleteTracker(id) }
    }

    private fun configRow(tracker: TrackerDto): TrackerConfigRow {
        val todayStr = today().toString()
        val unit = tracker.unitOrNull()
        val typeLabel = when (tracker.trackerType()) {
            TrackerType.QUANTIFIABLE -> if (unit != null) "Quantifiable ($unit)" else "Quantifiable"
            TrackerType.EVALUATION -> "Evaluation (%)"
            TrackerType.NOTE -> "Note"
            TrackerType.SIMPLE -> "Simple"
        }
        val schedule = formatScheduleSummary(getScheduleDaysForDate(tracker, todayStr))
        val target = formatTarget(targetForDate(tracker, todayStr), unit).takeIf { it.isNotEmpty() }
        val polarity = tracker.polarity
            ?.takeIf { it in POLARITY_VALUES }
            ?.replaceFirstChar { it.uppercase() }
        return TrackerConfigRow(
            id = tracker.id,
            name = tracker.name.orEmpty(),
            summary = listOfNotNull(typeLabel, schedule, target, polarity).joinToString(" • "),
        )
    }

    private data class EditorState(
        val form: TrackerFormState? = null,
        val editingId: String? = null,
        val pendingDeleteId: String? = null,
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

package dev.jtiisto.wellness.ui.journal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jtiisto.wellness.core.data.journal.TrackerDto
import dev.jtiisto.wellness.core.ui.SyncStatusDot
import org.koin.androidx.compose.koinViewModel

/**
 * Phase 2's journal tab: the sync round trip made visible. Ticking a box writes
 * an entry, the dot goes red, and the next cycle turns it green with the row
 * on the server. The real day view lands in Phase 3.
 */
@Composable
fun JournalDebugScreen(viewModel: JournalDebugViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    JournalDebugContent(
        state = state,
        onToggle = viewModel::setCompleted,
        onSyncNow = viewModel::syncNow,
    )
}

@Composable
private fun JournalDebugContent(
    state: JournalDebugUiState,
    onToggle: (String, Boolean) -> Unit,
    onSyncNow: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SyncStatusDot(state.status)
            Text(
                text = state.date,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onSyncNow) { Text("Sync now") }
        }
        HorizontalDivider()

        if (state.trackers.isEmpty()) {
            Text(
                text = "No trackers yet — the first sync will pull them from the server.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(items = state.trackers, key = TrackerDto::id) { tracker ->
                TrackerRow(
                    tracker = tracker,
                    completed = state.entries[tracker.id]?.completed == true,
                    onToggle = { onToggle(tracker.id, it) },
                )
            }
        }
    }
}

@Composable
private fun TrackerRow(tracker: TrackerDto, completed: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(tracker.name ?: tracker.id, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = listOfNotNull(tracker.category, tracker.type).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Checkbox(checked = completed, onCheckedChange = onToggle)
    }
}

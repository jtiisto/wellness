package dev.jtiisto.wellness.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jtiisto.wellness.core.data.db.DebugLogEntity
import dev.jtiisto.wellness.core.data.sync.DebugLogLogic
import org.koin.androidx.compose.koinViewModel

@Composable
fun ToolsScreen(viewModel: ToolsViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ToolsContent(state = state, onPing = viewModel::pingServer)
}

@Composable
private fun ToolsContent(state: ToolsUiState, onPing: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        ServerStatusCard(
            state = state,
            onPing = onPing,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )

        PlaceholderRow("Force sync")
        PlaceholderRow("Export all data")

        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
        Text(
            text = "Debug log",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        DebugLogList(entries = state.log, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun ServerStatusCard(state: ToolsUiState, onPing: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Server status", style = MaterialTheme.typography.titleMedium)
            Text(
                text = state.baseUrl,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Button(onClick = onPing, enabled = state.ping != ServerPing.InFlight) {
                Text("Ping journal sync status")
            }
            when (val ping = state.ping) {
                ServerPing.Idle -> Unit
                ServerPing.InFlight -> CircularProgressIndicator()
                is ServerPing.Reached -> Text(
                    text = "lastModified: ${ping.lastModified ?: "(none)"}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                is ServerPing.Failed -> Text(
                    text = ping.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** A Phase 8 affordance, shown greyed out so the tab reads as unfinished. */
@Composable
private fun PlaceholderRow(label: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(DISABLED_ALPHA)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text("Arrives with the Tools parity phase", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DebugLogList(entries: List<DebugLogEntity>, modifier: Modifier = Modifier) {
    if (entries.isEmpty()) {
        Text(
            text = "No entries in the last hour.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        return
    }
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
        items(items = entries, key = DebugLogEntity::id) { entry ->
            Text(
                text = DebugLogLogic.formatDumpLine(entry),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}

private const val DISABLED_ALPHA = 0.38f

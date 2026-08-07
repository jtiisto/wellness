package dev.jtiisto.wellness.ui.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jtiisto.wellness.core.data.coach.PlanBlockDto
import dev.jtiisto.wellness.core.data.coach.PlanDto
import dev.jtiisto.wellness.core.ui.SyncStatusDot
import kotlinx.serialization.json.JsonObject
import org.koin.androidx.compose.koinViewModel

/**
 * The Phase 4 Coach tab: a debug view, replaced wholesale in Phase 5.
 *
 * It renders today's plan through the typed [PlanDto] (which is the point — it
 * proves the snake_case decode against the real server shape) and today's log
 * as raw JSON, because the log's keys are arbitrary and Phase 5 owns the typed
 * rendering of them.
 */
@Composable
fun CoachDebugScreen(viewModel: CoachDebugViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CoachDebugContent(
        state = state,
        scratchLabel = viewModel.scratchTarget().label,
        onSyncNow = viewModel::syncNow,
        onLogScratchSet = viewModel::logScratchSet,
    )
}

@Composable
private fun CoachDebugContent(
    state: CoachDebugUiState,
    scratchLabel: String,
    onSyncNow: () -> Unit,
    onLogScratchSet: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SyncStatusDot(status = state.syncStatus)
                Text(text = state.date, style = MaterialTheme.typography.titleMedium)
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSyncNow) { Text("Sync now") }
                OutlinedButton(onClick = onLogScratchSet) { Text("Log set: $scratchLabel") }
            }
        }

        item { PlanCard(plan = state.plan) }

        item {
            HorizontalDivider()
            Text("Today's log", style = MaterialTheme.typography.titleMedium)
        }
        logLines(state.log)
    }
}

@Composable
private fun PlanCard(plan: PlanDto?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (plan == null) {
                Text("No plan for today (rest day)", style = MaterialTheme.typography.titleMedium)
                return@Column
            }
            Text(plan.dayName ?: "Workout", style = MaterialTheme.typography.titleMedium)
            Text(
                text = listOfNotNull(plan.location, plan.phase, plan.totalDurationMin?.let { "$it min" })
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
            plan.blocks.forEach { PlanBlock(block = it) }
        }
    }
}

@Composable
private fun PlanBlock(block: PlanBlockDto) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = block.title?.takeIf { it.isNotBlank() } ?: block.blockType,
            style = MaterialTheme.typography.titleSmall,
        )
        block.exercises.forEach { exercise ->
            val targets = exerciseTargets(
                targetSets = exercise.targetSets,
                targetReps = exercise.targetReps,
                targetDurationMin = exercise.targetDurationMin,
                targetDurationSec = exercise.targetDurationSec,
                targetLoad = exercise.targetLoad,
                targetRpe = exercise.targetRpe,
            )
            Text(
                text = if (targets.isEmpty()) exercise.name else "${exercise.name} — $targets",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** The stored day, one `key: value` line each. Raw on purpose. */
private fun LazyListScope.logLines(log: JsonObject?) {
    if (log == null || log.isEmpty()) {
        item { Text("Nothing logged today", style = MaterialTheme.typography.bodyMedium) }
        return
    }
    items(log.entries.toList(), key = { it.key }) { (key, value) ->
        Text(
            text = "$key: $value",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

package dev.jtiisto.wellness.feature.analysis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.jtiisto.wellness.core.data.analysis.ReportSummaryDto
import dev.jtiisto.wellness.core.ui.theme.WellnessShape
import dev.jtiisto.wellness.core.ui.theme.WellnessSpace
import dev.jtiisto.wellness.core.ui.theme.WellnessTheme
import dev.jtiisto.wellness.feature.analysis.AnalysisFormat
import dev.jtiisto.wellness.feature.analysis.AnalysisUiLogic
import java.time.ZoneId

/**
 * The newest fifty reports.
 *
 * There is no pagination because there is no pagination: the server's
 * `LIMIT 50` is hardcoded and nothing accepts an offset. Inventing a "load more"
 * would be a control that could never do anything.
 */
@Composable
fun HistoryList(
    history: List<ReportSummaryDto>,
    zone: ZoneId,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (history.isEmpty()) {
        EmptyState(text = "No reports yet", modifier = modifier)
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(WellnessSpace.xs),
        contentPadding = PaddingValues(bottom = WellnessSpace.lg),
    ) {
        items(history, key = { it.id }) { row ->
            HistoryRow(row = row, zone = zone, onOpen = { onOpen(row.id) }, onDelete = { onDelete(row.id) })
        }
    }
}

@Composable
private fun HistoryRow(
    row: ReportSummaryDto,
    zone: ZoneId,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val palette = WellnessTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(WellnessShape.card)
            .background(palette.card)
            .border(WellnessSpace.hairline, palette.line, WellnessShape.card)
            .clickable(onClick = onOpen)
            .padding(start = WellnessSpace.card, top = WellnessSpace.sm, bottom = WellnessSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.queryLabel,
                style = WellnessTheme.type.title,
                color = palette.textPrimary,
            )
            Text(
                text = AnalysisFormat.formatTimestamp(row.createdAt, zone),
                style = WellnessTheme.type.secondary,
                color = palette.textFaint,
            )
        }
        Text(
            text = AnalysisUiLogic.statusLabel(row.status),
            style = WellnessTheme.type.micro,
            color = statusColor(row.status),
            modifier = Modifier.padding(horizontal = WellnessSpace.sm),
        )
        // Hidden for exactly the two statuses the server refuses to delete. A
        // status neither side recognises stays deletable — otherwise the row
        // could never be removed at all.
        if (AnalysisUiLogic.canDelete(row.status)) {
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Delete report",
                    tint = palette.textFaint,
                    modifier = Modifier.size(DELETE_ICON),
                )
            }
        } else {
            // Keeps the label column the same width whether the control is there
            // or not, so a running row does not reflow when it finishes.
            Spacer(modifier = Modifier.width(WellnessSpace.touchTarget))
        }
    }
}

@Composable
private fun statusColor(wireStatus: String) = when (wireStatus) {
    "completed" -> WellnessTheme.palette.success
    "failed" -> WellnessTheme.palette.error
    else -> WellnessTheme.palette.textSecondary
}

/** Destructive and irreversible, so it asks. */
@Composable
fun DeleteReportDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val palette = WellnessTheme.palette
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.card,
        titleContentColor = palette.textPrimary,
        textContentColor = palette.textSecondary,
        title = { Text("Delete this report?") },
        text = { Text("The report and its contents are removed from the server. This cannot be undone.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = palette.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = palette.textSecondary)
            }
        },
    )
}

private val DELETE_ICON = 18.dp

package dev.jtiisto.wellness.feature.analysis.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jtiisto.wellness.core.data.analysis.ReportSummaryDto
import dev.jtiisto.wellness.core.ui.theme.INK_BANG
import dev.jtiisto.wellness.core.ui.theme.InkJudgment
import dev.jtiisto.wellness.core.ui.theme.InkJudgmentGlyph
import dev.jtiisto.wellness.core.ui.theme.LogbookSpace
import dev.jtiisto.wellness.core.ui.theme.LogbookTheme
import dev.jtiisto.wellness.core.ui.theme.bottomRule
import dev.jtiisto.wellness.feature.analysis.AnalysisFormat
import dev.jtiisto.wellness.feature.analysis.AnalysisUiLogic
import dev.jtiisto.wellness.feature.analysis.HistoryMark
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
        EmptyLine(text = "No reports yet", modifier = modifier)
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = LogbookSpace.group),
    ) {
        items(history, key = { it.id }) { row ->
            HistoryRow(row = row, zone = zone, onOpen = { onOpen(row.id) }, onDelete = { onDelete(row.id) })
        }
    }
}

/**
 * One row: the verdict as a mark, the query, when it landed, and the way to
 * remove it.
 *
 * The status word is gone for the two terminal outcomes — a filled dot says
 * "there is a report here" and an open dot with a bang says "this one broke",
 * which is the same grammar the report body's own status markers use. A row that
 * is still on its way keeps its word, because `PENDING`, `RUNNING` and a status
 * this client has never seen are three different things to someone waiting.
 */
@Composable
private fun HistoryRow(
    row: ReportSummaryDto,
    zone: ZoneId,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val palette = LogbookTheme.palette
    val mark = AnalysisUiLogic.historyMark(row.status)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bottomRule(palette.rule)
            .clickable(onClick = onOpen)
            .padding(vertical = ROW_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 2),
    ) {
        StatusMark(mark = mark, description = AnalysisUiLogic.historyMarkDescription(row.status))
        Text(
            text = row.queryLabel,
            style = LogbookTheme.type.body.copy(fontWeight = FontWeight.Medium),
            color = palette.ink,
            maxLines = TITLE_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (mark == HistoryMark.PENDING) {
            // The word stacks OVER the stamp: side by side, the two unweighted
            // texts out-measure the weighted title and crush it to a letter
            // column (Rows measure unweighted children first — the Round 1
            // lesson, relearned on this row in the Round 4 device pass).
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = AnalysisUiLogic.statusLabel(row.status),
                    style = LogbookTheme.type.eyebrow,
                    color = palette.inkSoft,
                    // Already spoken by the mark beside it.
                    modifier = Modifier.clearAndSetSemantics { },
                )
                Text(
                    text = AnalysisFormat.formatTimestamp(row.createdAt, zone),
                    style = LogbookTheme.type.meta,
                    color = palette.inkSoft,
                    maxLines = 1,
                )
            }
        } else {
            Text(
                text = AnalysisFormat.formatTimestamp(row.createdAt, zone),
                style = LogbookTheme.type.meta,
                color = palette.inkSoft,
                maxLines = 1,
            )
        }
        // Hidden for exactly the two statuses the server refuses to delete. A
        // status neither side recognises stays deletable — otherwise the row
        // could never be removed at all.
        if (AnalysisUiLogic.canDelete(row.status)) {
            Box(
                modifier = Modifier
                    .size(LogbookSpace.touchTarget)
                    .clickable(onClick = onDelete)
                    .semantics { contentDescription = "Delete report" },
                contentAlignment = Alignment.Center,
            ) {
                // inkSoft, not inkFaint: a control has a 3:1 floor and dark
                // inkFaint is the 2.56:1 ghost tier.
                Text(text = DELETE_GLYPH, style = LogbookTheme.type.data, color = palette.inkSoft)
            }
        } else {
            // Keeps the row's right edge in the same place whether the control
            // is there or not, so a running row does not reflow when it finishes.
            Box(modifier = Modifier.width(LogbookSpace.touchTarget))
        }
    }
}

/**
 * The mark, with the word it is announced as.
 *
 * The shape carries the verdict visually and the description carries it aloud —
 * a drawn circle has nothing a screen reader can read, and "completed" is
 * exactly what the fill means.
 */
@Composable
private fun StatusMark(mark: HistoryMark, description: String) {
    val judgment = when (mark) {
        HistoryMark.DONE -> InkJudgment.SETTLED
        HistoryMark.FAILED -> InkJudgment.ATTENTION
        HistoryMark.PENDING -> InkJudgment.PENDING
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        InkJudgmentGlyph(judgment)
        if (mark == HistoryMark.FAILED) {
            Text(
                text = INK_BANG,
                style = LogbookTheme.type.data.copy(fontWeight = FontWeight.Medium),
                color = LogbookTheme.palette.ink,
                // Already spoken by the row's own description.
                modifier = Modifier
                    .padding(start = BANG_GAP)
                    .clearAndSetSemantics { },
            )
        }
    }
}

/** Destructive and irreversible, so it asks. */
@Composable
fun DeleteReportDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // Colours come from the Logbook M3 adapter: paper, ink, and a 2dp
        // corner. There is no error token to spend on the destructive verb, and
        // the sentence above it already says what it destroys.
        title = { Text("Delete this report?") },
        text = { Text("The report and its contents are removed from the server. This cannot be undone.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val DELETE_GLYPH = "✕"

/** Two lines of query name, then the ellipsis — the timestamp keeps its own line. */
private const val TITLE_LINES = 2

private val BANG_GAP = 3.dp
private val ROW_PADDING = 12.dp

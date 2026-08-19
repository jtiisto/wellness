package dev.jtiisto.wellness.feature.analysis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.jtiisto.wellness.core.data.analysis.AnalysisQueryDto
import dev.jtiisto.wellness.core.data.analysis.ReportDetailDto
import dev.jtiisto.wellness.core.data.analysis.ReportStatus
import dev.jtiisto.wellness.core.data.analysis.reportStatus
import dev.jtiisto.wellness.core.ui.theme.InkNotice
import dev.jtiisto.wellness.core.ui.theme.InkOutlineButton
import dev.jtiisto.wellness.core.ui.theme.LogbookSpace
import dev.jtiisto.wellness.core.ui.theme.LogbookTheme
import dev.jtiisto.wellness.feature.analysis.AnalysisFormat
import dev.jtiisto.wellness.feature.analysis.AnalysisUiLogic
import dev.jtiisto.wellness.feature.analysis.TryAgainMode
import dev.jtiisto.wellness.feature.analysis.markdown.AnalysisMarkdown
import java.time.ZoneId

/**
 * A finished report: when it landed, what it was asked, and its body.
 *
 * The stamp reads as the eyebrow over the title — the report's own header block,
 * in the shape every Logbook page uses. A failed one shows the server's error
 * instead, with a Try Again whose behaviour depends on what the query needs —
 * see [TryAgainMode].
 */
@Composable
fun ReportViewScreen(
    report: ReportDetailDto?,
    queries: List<AnalysisQueryDto>,
    zone: ZoneId,
    onTryAgain: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (report == null) {
        EmptyLine(text = "No report selected", modifier = modifier)
        return
    }

    val palette = LogbookTheme.palette
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = LogbookSpace.group),
        verticalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 2),
    ) {
        Text(
            text = AnalysisFormat.formatTimestamp(
                AnalysisUiLogic.reportTimestamp(report.completedAt, report.createdAt),
                zone,
            ).uppercase(),
            style = LogbookTheme.type.eyebrow,
            color = palette.inkSoft,
        )
        Text(
            text = report.queryLabel.uppercase(),
            style = LogbookTheme.type.display,
            color = palette.ink,
        )

        if (report.reportStatus == ReportStatus.FAILED) {
            FailedReport(report, queries, onTryAgain)
            return@Column
        }

        // Parsing is the expensive part of drawing a report, and the body only
        // changes when the report does.
        val blocks = remember(report.responseMarkdown) {
            AnalysisMarkdown.render(report.responseMarkdown)
        }
        if (blocks.isEmpty()) {
            EmptyLine(text = "This report has no content.")
        } else {
            ReportBody(blocks)
        }
    }
}

@Composable
private fun FailedReport(
    report: ReportDetailDto,
    queries: List<AnalysisQueryDto>,
    onTryAgain: (String) -> Unit,
) {
    val mode = AnalysisUiLogic.tryAgainMode(queries, report.queryId)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = LogbookSpace.grid * 2),
        verticalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 3),
    ) {
        // Ink and the mono bang, never a red heading: a failed query is a fact
        // about one report, and turning the page red would make it the loudest
        // thing in the app.
        InkNotice(text = "Query failed")
        Text(
            text = report.errorMessage?.takeIf { it.isNotBlank() } ?: "Unknown error",
            style = LogbookTheme.type.body,
            color = LogbookTheme.palette.inkSoft,
        )
        InkOutlineButton(
            label = when (mode) {
                TryAgainMode.RESUBMIT -> "Try again"
                TryAgainMode.NEEDS_LOCATION -> "Try again…"
                TryAgainMode.UNAVAILABLE -> "Query no longer available"
            },
            onClick = { onTryAgain(report.queryId) },
            enabled = mode != TryAgainMode.UNAVAILABLE,
        )
    }
}

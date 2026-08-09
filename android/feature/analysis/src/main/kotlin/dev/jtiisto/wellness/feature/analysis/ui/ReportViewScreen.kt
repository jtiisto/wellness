package dev.jtiisto.wellness.feature.analysis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import dev.jtiisto.wellness.core.data.analysis.AnalysisQueryDto
import dev.jtiisto.wellness.core.data.analysis.ReportDetailDto
import dev.jtiisto.wellness.core.data.analysis.ReportStatus
import dev.jtiisto.wellness.core.data.analysis.reportStatus
import dev.jtiisto.wellness.core.ui.theme.WellnessDefaults
import dev.jtiisto.wellness.core.ui.theme.WellnessSpace
import dev.jtiisto.wellness.core.ui.theme.WellnessTheme
import dev.jtiisto.wellness.feature.analysis.AnalysisFormat
import dev.jtiisto.wellness.feature.analysis.AnalysisUiLogic
import dev.jtiisto.wellness.feature.analysis.TryAgainMode
import dev.jtiisto.wellness.feature.analysis.markdown.AnalysisMarkdown
import java.time.ZoneId

/**
 * A finished report: its title, when it finished, and its body.
 *
 * A failed one shows the server's error instead, with a Try Again whose
 * behaviour depends on what the query needs — see [TryAgainMode].
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
        EmptyState(text = "No report selected", modifier = modifier)
        return
    }

    val palette = WellnessTheme.palette
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = WellnessSpace.xl),
        verticalArrangement = Arrangement.spacedBy(WellnessSpace.sm),
    ) {
        Text(
            text = report.queryLabel,
            style = WellnessTheme.type.headline,
            color = palette.textPrimary,
        )
        Text(
            text = AnalysisFormat.formatTimestamp(
                AnalysisUiLogic.reportTimestamp(report.completedAt, report.createdAt),
                zone,
            ),
            style = WellnessTheme.type.secondary,
            color = palette.textFaint,
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
            EmptyState(text = "This report has no content.")
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
    val palette = WellnessTheme.palette
    val mode = AnalysisUiLogic.tryAgainMode(queries, report.queryId)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = WellnessSpace.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WellnessSpace.sm),
    ) {
        Text(
            text = "Query failed",
            style = WellnessTheme.type.title,
            color = palette.error,
        )
        Text(
            text = report.errorMessage?.takeIf { it.isNotBlank() } ?: "Unknown error",
            style = WellnessTheme.type.secondary,
            color = palette.textSecondary,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = { onTryAgain(report.queryId) },
            enabled = mode != TryAgainMode.UNAVAILABLE,
            colors = WellnessDefaults.accentButtonColors(),
        ) {
            Text(
                when (mode) {
                    TryAgainMode.RESUBMIT -> "Try again"
                    TryAgainMode.NEEDS_LOCATION -> "Try again…"
                    TryAgainMode.UNAVAILABLE -> "Query no longer available"
                },
            )
        }
    }
}

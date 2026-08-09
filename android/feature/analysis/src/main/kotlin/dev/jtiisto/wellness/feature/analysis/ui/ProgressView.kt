package dev.jtiisto.wellness.feature.analysis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.jtiisto.wellness.core.data.analysis.ActiveReport
import dev.jtiisto.wellness.core.ui.theme.WellnessDefaults
import dev.jtiisto.wellness.core.ui.theme.WellnessSpace
import dev.jtiisto.wellness.core.ui.theme.WellnessTheme
import dev.jtiisto.wellness.feature.analysis.AnalysisFormat
import dev.jtiisto.wellness.feature.analysis.AnalysisUiLogic
import kotlinx.coroutines.delay

/**
 * The spinner, the elapsed clock, and the way out.
 *
 * Cancel abandons the report *client-side only*. The server has no cancel
 * endpoint — the CLI run is already paid for — so the report finishes in its own
 * time and turns up in History. Saying otherwise would be a lie the UI cannot
 * back up.
 */
@Composable
fun ProgressView(
    active: ActiveReport?,
    unknownStalled: Boolean,
    onCancel: () -> Unit,
    onRecheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = WellnessTheme.palette
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = WellnessSpace.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WellnessSpace.md),
    ) {
        if (unknownStalled) {
            Text(
                text = "Status unknown — check History later",
                style = WellnessTheme.type.title,
                color = palette.textPrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "The server stopped reporting a status this app recognises. " +
                    "The report may still finish on its own.",
                style = WellnessTheme.type.secondary,
                color = palette.textSecondary,
                textAlign = TextAlign.Center,
            )
            OutlinedButton(onClick = onRecheck, colors = WellnessDefaults.accentOutlinedButtonColors()) {
                Text("Check again")
            }
        } else {
            CircularProgressIndicator(
                color = WellnessTheme.accent.fill,
                trackColor = palette.line,
                modifier = Modifier.size(SPINNER_SIZE),
            )
            Text(
                text = AnalysisUiLogic.progressLabel(active),
                style = WellnessTheme.type.title,
                color = palette.textPrimary,
                textAlign = TextAlign.Center,
            )
            ElapsedClock(active)
        }

        TextButton(onClick = onCancel, colors = WellnessDefaults.accentTextButtonColors()) {
            Text("Cancel")
        }
    }
}

/**
 * Seconds since the report was created, ticking once a second.
 *
 * A stub has no `created_at` yet — `POST /reports` returns an id and a status —
 * so it reads exactly `0s` until the first poll tick supplies one. That is PWA
 * parity and it is also the honest answer: the client does not know.
 */
@Composable
private fun ElapsedClock(active: ActiveReport?) {
    val createdAt = (active as? ActiveReport.Loaded)?.detail?.createdAt
    var seconds by remember(createdAt) {
        mutableLongStateOf(AnalysisFormat.elapsedSeconds(createdAt, System.currentTimeMillis()))
    }
    LaunchedEffect(createdAt) {
        while (true) {
            seconds = AnalysisFormat.elapsedSeconds(createdAt, System.currentTimeMillis())
            delay(TICK_MS)
        }
    }
    Text(
        text = AnalysisFormat.formatElapsed(seconds),
        style = WellnessTheme.type.stat,
        color = WellnessTheme.palette.textSecondary,
    )
}

private const val TICK_MS = 1_000L
private val SPINNER_SIZE = 44.dp

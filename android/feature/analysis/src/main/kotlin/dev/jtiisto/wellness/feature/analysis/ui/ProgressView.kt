package dev.jtiisto.wellness.feature.analysis.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jtiisto.wellness.core.data.analysis.ActiveReport
import dev.jtiisto.wellness.core.ui.theme.InkNotice
import dev.jtiisto.wellness.core.ui.theme.InkOutlineButton
import dev.jtiisto.wellness.core.ui.theme.LogbookSpace
import dev.jtiisto.wellness.core.ui.theme.LogbookTheme
import dev.jtiisto.wellness.feature.analysis.AnalysisFormat
import kotlinx.coroutines.delay

/**
 * The clock, the sweep, and the way out.
 *
 * The spinner is gone. What a reader wants from a query that takes minutes is
 * how long it has been going, so the elapsed time *is* the indicator — set large
 * in mono, with a hairline sweep under it saying the app is still watching. The
 * query's own name is on the section head above, so nothing repeats it here.
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
    if (unknownStalled) {
        StalledState(onCancel = onCancel, onRecheck = onRecheck, modifier = modifier)
        return
    }

    val palette = LogbookTheme.palette
    Column(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val stroke = LogbookSpace.hairline.toPx()
                drawRect(color = palette.rule, size = Size(size.width, stroke))
                drawRect(
                    color = palette.rule,
                    topLeft = Offset(0f, size.height - stroke),
                    size = Size(size.width, stroke),
                )
            }
            .padding(vertical = BLOCK_PADDING),
        verticalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 3),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            ElapsedClock(active)
            Text(
                text = ELAPSED_LABEL.uppercase(),
                style = LogbookTheme.type.eyebrow,
                color = palette.inkSoft,
                modifier = Modifier.padding(start = LogbookSpace.grid * 3, bottom = CLOCK_BASELINE_LIFT),
            )
            Box(modifier = Modifier.weight(1f))
            InkOutlineButton(label = "Cancel", onClick = onCancel, quiet = true)
        }
        Sweep()
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
        style = LogbookTheme.type.data.copy(
            fontSize = CLOCK_SIZE,
            lineHeight = CLOCK_LINE_HEIGHT,
            fontWeight = FontWeight.Medium,
        ),
        color = LogbookTheme.palette.ink,
    )
}

/**
 * The one thing on the page that moves.
 *
 * A segment travelling along a hairline: it carries no progress information —
 * the server reports none — and says only that the app is still asking. Anything
 * that implied a percentage would be inventing one.
 */
@Composable
private fun Sweep() {
    val palette = LogbookTheme.palette
    val transition = rememberInfiniteTransition(label = "analysis-sweep")
    val travel by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SWEEP_MILLIS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "analysis-sweep-travel",
    )
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(SWEEP_HEIGHT),
    ) {
        drawRect(color = palette.rule)
        val segment = size.width * SWEEP_SEGMENT
        // Starts fully off the left edge and leaves fully off the right, so the
        // ends of the rule never hold a stationary block of ink.
        val x = travel * (size.width + segment) - segment
        drawRect(color = palette.ink, topLeft = Offset(x, 0f), size = Size(segment, size.height))
    }
}

/**
 * The 600 s ceiling's state: the server stopped saying anything this client
 * recognises.
 *
 * Stated as a notice rather than an error — the report may well still finish, so
 * the page offers the two honest moves (ask again, or stop waiting) and claims
 * nothing about which is right.
 */
@Composable
private fun StalledState(onCancel: () -> Unit, onRecheck: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = LogbookSpace.grid * 2),
        verticalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 3),
    ) {
        InkNotice(text = "Status unknown — check History later")
        Text(
            text = "The server stopped reporting a status this app recognises. " +
                "The report may still finish on its own.",
            style = LogbookTheme.type.body,
            color = LogbookTheme.palette.inkSoft,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 2)) {
            InkOutlineButton(label = "Check again", onClick = onRecheck)
            InkOutlineButton(label = "Cancel", onClick = onCancel, quiet = true)
        }
    }
}

private const val TICK_MS = 1_000L
private const val ELAPSED_LABEL = "elapsed"

private const val SWEEP_MILLIS = 1_600
private const val SWEEP_SEGMENT = 0.26f

private val SWEEP_HEIGHT = 2.dp
private val BLOCK_PADDING = 16.dp
private val CLOCK_SIZE = 22.sp
private val CLOCK_LINE_HEIGHT = 24.sp

/** The label sits on the clock's baseline, not on the box the clock's leading draws. */
private val CLOCK_BASELINE_LIFT = 2.dp

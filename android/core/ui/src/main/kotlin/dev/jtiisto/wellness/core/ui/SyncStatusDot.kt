package dev.jtiisto.wellness.core.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.jtiisto.wellness.core.data.sync.SyncStatus
import dev.jtiisto.wellness.core.ui.theme.liveSignalColors

/**
 * The per-module sync indicator.
 *
 * Colour alone would be invisible to a screen reader and ambiguous to anyone
 * who cannot separate red from green, so the state is always also spelled out
 * in the content description.
 */
@Composable
fun SyncStatusDot(
    status: SyncStatus,
    modifier: Modifier = Modifier,
    syncing: Boolean = false,
) {
    val alpha = pulseAlpha(syncing)
    Box(
        modifier = modifier
            .size(12.dp)
            .alpha(alpha)
            .background(dotColor(status), CircleShape)
            .semantics { contentDescription = "Sync status: ${syncStatusLabel(status, syncing)}" },
    )
}

/**
 * The dot with its state written next to it — the PWA's wider-viewport form.
 *
 * Used where there is room for the words (the coach header); the bare
 * [SyncStatusDot] stays the choice for a crowded app bar.
 */
@Composable
fun SyncStatusIndicator(
    status: SyncStatus,
    // The dot's colours are the documented live-signal exception; the label's
    // face and ink are not, so the host states them rather than the component
    // reaching for a theme — the words beside the dot belong to the header they
    // sit in.
    textStyle: TextStyle,
    labelColor: Color,
    modifier: Modifier = Modifier,
    syncing: Boolean = false,
) {
    val label = syncStatusLabel(status, syncing)
    val alpha = pulseAlpha(syncing)
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "Sync status: $label"
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .alpha(alpha)
                .background(dotColor(status), CircleShape),
        )
        Text(
            text = label,
            style = textStyle,
            color = labelColor,
        )
    }
}

/**
 * A slow fade while a sync is in flight.
 *
 * "Working on it" is the one state a static dot cannot express: RED during an
 * upload and RED because the upload failed look identical otherwise.
 *
 * The transition is not merely parked at 1f when idle — it is not composed at
 * all. An infinite animation whose endpoints are equal still holds a frame clock
 * awake for the life of the composition, which is a needless wakeup on a screen
 * that spends nearly all its time not syncing.
 */
@Composable
private fun pulseAlpha(syncing: Boolean): Float {
    if (!syncing) return 1f
    val transition = rememberInfiniteTransition(label = "sync-pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = PULSE_MIN_ALPHA,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = PULSE_MS),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sync-pulse-alpha",
    )
    return alpha
}

/** The PWA's four words, in its order of precedence — syncing outranks the colour. */
private fun syncStatusLabel(status: SyncStatus, syncing: Boolean): String = when {
    syncing -> "Syncing…"
    status == SyncStatus.GREEN -> "Synced"
    status == SyncStatus.RED -> "Pending"
    else -> "Offline"
}

/**
 * The dot's one colour table.
 *
 * These are the live-signal tokens — the second of the design's two documented
 * colour exceptions, the strap's tone dot being the first. Whether the phone is
 * still holding changes is transient device truth rather than a judgement about
 * the log, which is why it gets colour at all on a page that otherwise has none.
 */
@Composable
private fun dotColor(status: SyncStatus): Color {
    val colors = liveSignalColors()
    return when (status) {
        SyncStatus.GREEN -> colors.live
        SyncStatus.RED -> colors.attention
        SyncStatus.GRAY -> colors.idle
    }
}

private const val PULSE_MS = 700
private const val PULSE_MIN_ALPHA = 0.3f

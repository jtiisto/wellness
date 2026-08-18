package dev.jtiisto.wellness.core.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
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
import dev.jtiisto.wellness.core.ui.theme.DarkPalette
import dev.jtiisto.wellness.core.ui.theme.LightPalette
import dev.jtiisto.wellness.core.ui.theme.WellnessPalette
import dev.jtiisto.wellness.core.ui.theme.WellnessTheme

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
    modifier: Modifier = Modifier,
    syncing: Boolean = false,
    // The dot's colours are the documented cross-system exception; the label's
    // face and ink are not, so a Logbook host passes its own and a Graphite
    // host passes nothing. Nullable-with-fallback rather than a default reading
    // the theme: defaults resolve at the callsite, where WellnessTheme may not
    // be the theme in force.
    textStyle: TextStyle? = null,
    labelColor: Color? = null,
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
            style = textStyle ?: WellnessTheme.type.secondary,
            color = labelColor ?: semanticPalette().textSecondary,
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

/** Semantic tokens, so the dot tracks the theme instead of three fixed hues. */
@Composable
private fun dotColor(status: SyncStatus): Color {
    val palette = semanticPalette()
    return when (status) {
        SyncStatus.GREEN -> palette.success
        SyncStatus.RED -> palette.error
        SyncStatus.GRAY -> palette.syncIdle
    }
}

/**
 * The Graphite palette this indicator draws itself from.
 *
 * Taken from the system's dark-mode setting rather than from
 * `LocalWellnessPalette`, because the indicator sits in every module's header
 * and the coach's is now a Logbook subtree — which provides no Graphite palette
 * at all, so the local would answer with its `DarkPalette` default and paint a
 * dark-theme dot and near-white label onto light paper. `WellnessTheme` derives
 * its own palette exactly this way, so nothing under Graphite changes.
 *
 * The status colours themselves stay: they are transient device truth, the
 * second of Logbook's two documented live-signal exceptions.
 */
@Composable
@ReadOnlyComposable
private fun semanticPalette(): WellnessPalette = if (isSystemInDarkTheme()) DarkPalette else LightPalette

private const val PULSE_MS = 700
private const val PULSE_MIN_ALPHA = 0.3f

package dev.jtiisto.wellness.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.jtiisto.wellness.core.data.sync.SyncStatus

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
) {
    val (color, description) = when (status) {
        SyncStatus.GREEN -> DotGreen to "Synced"
        SyncStatus.RED -> DotRed to "Unsynced changes"
        SyncStatus.GRAY -> DotGray to "Not synced yet"
    }
    Box(
        modifier = modifier
            .size(12.dp)
            .background(color, CircleShape)
            .semantics { contentDescription = description },
    )
}

private val DotGreen = Color(0xFF2E7D32)
private val DotRed = Color(0xFFC62828)
private val DotGray = Color(0xFF9E9E9E)

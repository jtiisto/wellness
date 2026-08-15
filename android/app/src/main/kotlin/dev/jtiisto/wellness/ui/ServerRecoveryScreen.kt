package dev.jtiisto.wellness.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.jtiisto.wellness.core.ui.theme.WellnessDefaults
import dev.jtiisto.wellness.core.ui.theme.WellnessShape
import dev.jtiisto.wellness.core.ui.theme.WellnessSpace
import dev.jtiisto.wellness.core.ui.theme.WellnessTheme

/**
 * Where the app stops when it cannot tell which server it belongs to.
 *
 * This is a deliberate dead end, not a degraded mode. The database still holds
 * whatever the previous server sent, dirty rows included, so quietly falling
 * back to the built-in address would upload one server's records to another —
 * a data-integrity failure that would look, from the outside, like the app
 * simply working. Refusing to start is the safe answer, and saying why is the
 * only part that has to be good.
 *
 * Retry re-runs the whole boot: if the address book has since become readable,
 * or the extra active row is gone, the app continues normally from here.
 */
@Composable
fun ServerRecoveryScreen(message: String, onRetry: () -> Unit) {
    val palette = WellnessTheme.palette
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(WellnessSpace.lg),
        verticalArrangement = Arrangement.spacedBy(WellnessSpace.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Server not configured",
            style = WellnessTheme.type.headline,
            color = palette.textPrimary,
        )
        Text(
            text = message,
            style = WellnessTheme.type.body,
            color = palette.textSecondary,
        )
        Text(
            text = "Nothing has been synced or changed. Fix the saved server list and try again.",
            style = WellnessTheme.type.secondary,
            color = palette.textFaint,
        )
        Button(
            onClick = onRetry,
            shape = WellnessShape.card,
            colors = WellnessDefaults.accentButtonColors(),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text("Try again", style = WellnessTheme.type.label)
        }
    }
}

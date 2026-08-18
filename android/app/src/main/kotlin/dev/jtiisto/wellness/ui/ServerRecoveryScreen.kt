package dev.jtiisto.wellness.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import dev.jtiisto.wellness.core.ui.theme.LogbookShapes
import dev.jtiisto.wellness.core.ui.theme.LogbookSpace
import dev.jtiisto.wellness.core.ui.theme.LogbookTheme

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
    val palette = LogbookTheme.palette
    val type = LogbookTheme.type
    Column(
        modifier = Modifier
            .fillMaxSize()
            // This screen renders instead of the Scaffold, so it paints its own
            // paper: the launch window underneath is still the Graphite canvas
            // until the journal round moves it.
            .background(palette.paper)
            .padding(LogbookSpace.group),
        verticalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 3, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "SERVER NOT CONFIGURED",
            style = type.display,
            color = palette.ink,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = type.body,
            color = palette.inkSoft,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Nothing has been synced or changed. Fix the saved server list and try again.",
            style = type.body,
            color = palette.inkFaint,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(
            onClick = onRetry,
            // M3 buttons default to the CornerFull pill regardless of the
            // theme's Shapes — a shape Logbook forbids — so the 2dp cut is
            // passed explicitly.
            shape = LogbookShapes.soft,
            border = BorderStroke(LogbookSpace.hairline, palette.ink),
            modifier = Modifier
                .padding(top = LogbookSpace.grid * 2)
                .heightIn(min = LogbookSpace.touchTarget),
        ) {
            Text("TRY AGAIN", style = type.eyebrow)
        }
    }
}

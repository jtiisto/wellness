package dev.jtiisto.wellness.feature.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.jtiisto.wellness.core.ui.hr.HrCaptureDisplay
import dev.jtiisto.wellness.core.ui.hr.HrToneDot
import dev.jtiisto.wellness.core.ui.hr.logbookColor
import dev.jtiisto.wellness.core.ui.theme.InkButton
import dev.jtiisto.wellness.core.ui.theme.InkOutlineButton
import dev.jtiisto.wellness.core.ui.theme.LogbookShapes
import dev.jtiisto.wellness.core.ui.theme.LogbookSheetHandle
import dev.jtiisto.wellness.core.ui.theme.LogbookSpace
import dev.jtiisto.wellness.core.ui.theme.LogbookTheme

/**
 * "Connect HRM?" — the one question Start Workout asks.
 *
 * Asked every time, with no remembered answer, because the strap is a physical
 * object the user either put on or did not. A remembered "always" would spend
 * fifteen connect attempts and a foreground notification on a strap in a drawer;
 * a remembered "never" would silently stop recording the thing the whole feature
 * exists for.
 *
 * Dismissing it is [onSkip]: the workout starts either way, so a swipe-away must
 * not leave the Start button hanging.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectStrapSheet(prompt: StrapPrompt, onConnect: () -> Unit, onSkip: () -> Unit) {
    val palette = LogbookTheme.palette
    ModalBottomSheet(
        onDismissRequest = onSkip,
        containerColor = palette.paper,
        contentColor = palette.ink,
        shape = LogbookShapes.square,
        dragHandle = { LogbookSheetHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = SHEET_PADDING, end = SHEET_PADDING, bottom = SHEET_BOTTOM),
            verticalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 2),
        ) {
            Text(
                text = CaptureCopy.CONNECT_TITLE.uppercase(),
                style = LogbookTheme.type.section,
                color = palette.ink,
            )
            Text(
                text = CaptureCopy.connectBody(prompt.name),
                style = LogbookTheme.type.body,
                color = palette.inkSoft,
            )
            Row(
                modifier = Modifier.padding(top = LogbookSpace.grid),
                horizontalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 2),
            ) {
                InkButton(
                    label = CaptureCopy.CONNECT,
                    onClick = onConnect,
                    modifier = Modifier.weight(1f),
                )
                InkOutlineButton(
                    label = CaptureCopy.SKIP,
                    onClick = onSkip,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * What is behind the BPM chip: the device, where the link is, and the way out.
 *
 * Everything the chip deliberately omits lands here, which is what lets the chip
 * stay a bare number. [HrCaptureDisplay.detail] is the connect diagnostics —
 * authored text about a Bluetooth link, never an exception message. It used to
 * be tinted warning-amber; Logbook has no such colour and does not need one,
 * because most of what the line says is "retrying" rather than "gave up".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CaptureStatusSheet(
    display: HrCaptureDisplay,
    link: CaptureLink?,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LogbookTheme.palette
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.paper,
        contentColor = palette.ink,
        shape = LogbookShapes.square,
        dragHandle = { LogbookSheetHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = SHEET_PADDING, end = SHEET_PADDING, bottom = SHEET_BOTTOM),
            verticalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 2),
        ) {
            Text(
                text = display.deviceName.uppercase(),
                style = LogbookTheme.type.section,
                color = palette.ink,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 2),
            ) {
                // The one dot on the page that carries a colour, and it carries
                // it for the documented reason: this is an instrument reading.
                HrToneDot(tone = display.tone, color = display.tone.logbookColor())
                Text(
                    text = "${display.bpmText} bpm · ${display.connectionText}",
                    style = LogbookTheme.type.meta,
                    color = palette.ink,
                )
            }

            display.qualityText?.let {
                Text(it, style = LogbookTheme.type.meta, color = palette.inkSoft)
            }
            display.detail?.let {
                // No warning colour to reach for: the sentence says it, and most
                // of what it says is "retrying".
                Text(it, style = LogbookTheme.type.body, color = palette.inkSoft)
            }

            // The question the feature exists to answer, and the only one the
            // rest of this sheet cannot: will these beats correlate with these
            // sets? A recording anchored to another day looks identical without
            // it.
            link?.let {
                Text(
                    text = CaptureCopy.linkText(it),
                    style = LogbookTheme.type.body,
                    color = palette.inkSoft,
                )
            }

            InkOutlineButton(
                label = CaptureCopy.STOP,
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = CaptureCopy.STOP_HINT,
                style = LogbookTheme.type.body,
                color = palette.inkFaint,
            )
        }
    }
}

private val SHEET_PADDING = 20.dp
private val SHEET_BOTTOM = 24.dp

/** The capture surfaces' copy, gathered so the tests can assert it. */
internal object CaptureCopy {

    const val CONNECT_TITLE = "Connect HRM?"
    const val CONNECT = "Connect"
    const val SKIP = "Skip"

    const val STOP = "Stop capture"

    /** Says what Stop does *not* do, because the sheet is opened mid-workout. */
    const val STOP_HINT = "Ends the heart-rate recording. The workout stays open."

    fun connectBody(name: String): String =
        "Record heart rate from $name for this workout? The workout starts either way."

    /**
     * Stated as a fact, not an instruction. There is nothing the user can do
     * about an already-running recording's anchor from this sheet, and telling
     * them to "start it from Start Workout" mid-session would be advice they
     * cannot take.
     */
    fun linkText(link: CaptureLink): String = when (link) {
        CaptureLink.THIS_WORKOUT -> "Linked to this workout."
        CaptureLink.OTHER_WORKOUT -> "Linked to a different workout."
        CaptureLink.UNANCHORED -> "Not linked to a workout."
    }
}

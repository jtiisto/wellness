package dev.jtiisto.wellness.feature.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.jtiisto.wellness.core.ui.hr.HrCaptureDisplay
import dev.jtiisto.wellness.core.ui.hr.HrToneDot
import dev.jtiisto.wellness.core.ui.theme.WellnessDefaults
import dev.jtiisto.wellness.core.ui.theme.WellnessShape
import dev.jtiisto.wellness.core.ui.theme.WellnessSpace
import dev.jtiisto.wellness.core.ui.theme.WellnessTheme

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
    val palette = WellnessTheme.palette
    ModalBottomSheet(
        onDismissRequest = onSkip,
        containerColor = palette.card,
        contentColor = palette.textPrimary,
        shape = WellnessShape.floating,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = WellnessSpace.md, end = WellnessSpace.md, bottom = WellnessSpace.lg),
            verticalArrangement = Arrangement.spacedBy(WellnessSpace.sm),
        ) {
            Text(CaptureCopy.CONNECT_TITLE, style = WellnessTheme.type.title, color = palette.textPrimary)
            Text(
                text = CaptureCopy.connectBody(prompt.name),
                style = WellnessTheme.type.body,
                color = palette.textSecondary,
            )
            Row(
                modifier = Modifier.padding(top = WellnessSpace.xs),
                horizontalArrangement = Arrangement.spacedBy(WellnessSpace.sm),
            ) {
                Button(
                    onClick = onConnect,
                    modifier = Modifier.weight(1f),
                    shape = WellnessShape.card,
                    colors = WellnessDefaults.accentButtonColors(),
                ) {
                    Text(CaptureCopy.CONNECT, style = WellnessTheme.type.label)
                }
                OutlinedButton(
                    onClick = onSkip,
                    modifier = Modifier.weight(1f),
                    shape = WellnessShape.card,
                    colors = WellnessDefaults.accentOutlinedButtonColors(),
                ) {
                    Text(CaptureCopy.SKIP, style = WellnessTheme.type.label)
                }
            }
        }
    }
}

/**
 * What is behind the BPM chip: the device, where the link is, and the way out.
 *
 * Everything the chip deliberately omits lands here, which is what lets the chip
 * stay a bare number. [HrCaptureDisplay.detail] is the connect diagnostics —
 * authored text about a Bluetooth link, never an exception message — and it is
 * shown as a warning rather than an error because most of what it says is
 * "retrying", not "gave up".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CaptureStatusSheet(
    display: HrCaptureDisplay,
    link: CaptureLink?,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = WellnessTheme.palette
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.card,
        contentColor = palette.textPrimary,
        shape = WellnessShape.floating,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = WellnessSpace.md, end = WellnessSpace.md, bottom = WellnessSpace.lg),
            verticalArrangement = Arrangement.spacedBy(WellnessSpace.sm),
        ) {
            Text(display.deviceName, style = WellnessTheme.type.title, color = palette.textPrimary)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WellnessSpace.sm),
            ) {
                HrToneDot(display.tone)
                Text(
                    text = "${display.bpmText} bpm · ${display.connectionText}",
                    style = WellnessTheme.type.body,
                    color = palette.textPrimary,
                )
            }

            display.qualityText?.let {
                Text(it, style = WellnessTheme.type.secondary, color = palette.textSecondary)
            }
            display.detail?.let {
                Text(it, style = WellnessTheme.type.secondary, color = palette.warning)
            }

            // The question the feature exists to answer, and the only one the
            // rest of this sheet cannot: will these beats correlate with these
            // sets? A recording anchored to another day looks identical without
            // it.
            link?.let {
                Text(
                    text = CaptureCopy.linkText(it),
                    style = WellnessTheme.type.secondary,
                    color = if (it == CaptureLink.THIS_WORKOUT) palette.textSecondary else palette.warning,
                )
            }

            OutlinedButton(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
                shape = WellnessShape.card,
                colors = WellnessDefaults.accentOutlinedButtonColors(),
            ) {
                Text(CaptureCopy.STOP, style = WellnessTheme.type.label)
            }
            Text(
                text = CaptureCopy.STOP_HINT,
                style = WellnessTheme.type.secondary,
                color = palette.textFaint,
            )
        }
    }
}

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

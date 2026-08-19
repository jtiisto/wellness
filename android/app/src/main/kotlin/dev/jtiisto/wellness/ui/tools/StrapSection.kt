package dev.jtiisto.wellness.ui.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jtiisto.wellness.core.ble.device.KnownDevice
import dev.jtiisto.wellness.core.ble.scanner.DiscoveredDevice
import dev.jtiisto.wellness.core.ui.hr.HrToneDot
import dev.jtiisto.wellness.core.ui.hr.hrCaptureDisplay
import dev.jtiisto.wellness.core.ui.theme.InkButton
import dev.jtiisto.wellness.core.ui.theme.InkNotice
import dev.jtiisto.wellness.core.ui.theme.InkOutlineButton
import dev.jtiisto.wellness.core.ui.theme.LogbookSection
import dev.jtiisto.wellness.core.ui.theme.LogbookSpace
import dev.jtiisto.wellness.core.ui.theme.LogbookTheme
import dev.jtiisto.wellness.core.ui.theme.bottomRule
import dev.jtiisto.wellness.hr.BlePermissions
import org.koin.androidx.compose.koinViewModel

/**
 * The Tools tab's "Heart rate strap" section: pair a strap, forget one, and
 * record without a workout.
 *
 * Pairing lives here rather than in the coach tab because it is configuration —
 * you do it once, at a table, not between sets. The coach tab's sheet only ever
 * *offers* a strap this screen has already made known.
 *
 * The two permission launchers are the only reason this is not a dumb renderer.
 * Everything they feed back into is [StrapViewModel]; nothing is decided here.
 */
@Composable
fun StrapSection(modifier: Modifier = Modifier, viewModel: StrapViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val bluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        // The raw map goes through untouched. A cancelled interaction arrives
        // here as an empty one, and collapsing it to "not granted" is what would
        // strand the user in Settings — see [BlePermissions.statusAfter].
        viewModel.onPermissionResult(results = results, canAskAgain = context.canAskAgainForBle())
    }

    // Deliberately ignores its result. A notification the user turned down does
    // not stop a capture, and there is nothing else this answer changes.
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                StrapEvent.RequestBluetooth ->
                    bluetoothLauncher.launch(BlePermissions.REQUIRED.toTypedArray())

                StrapEvent.RequestNotifications ->
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    DisposableEffect(viewModel) {
        viewModel.onOpened(granted = context.hasBlePermissions())
        // The scan outlives this composable otherwise: the ViewModel survives
        // scrolling away, and a low-latency scan is not something to leave on.
        onDispose { viewModel.stopScan() }
    }

    StrapContent(
        state = state,
        onScan = viewModel::toggleScan,
        onConnect = viewModel::connect,
        onForget = viewModel::forget,
        onStartCapture = viewModel::startCapture,
        onStopCapture = viewModel::stopCapture,
        modifier = modifier,
    )
}

@Suppress("LongParameterList")
@Composable
private fun StrapContent(
    state: StrapUiState,
    onScan: () -> Unit,
    onConnect: (DiscoveredDevice) -> Unit,
    onForget: (String) -> Unit,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LogbookTheme.palette
    LogbookSection(title = StrapCopy.TITLE, modifier = modifier) {
        if (state.known.isEmpty()) {
            Text(
                text = StrapCopy.EMPTY,
                style = LogbookTheme.type.body.copy(fontStyle = FontStyle.Italic),
                color = palette.inkSoft,
            )
        }
        state.known.forEach { device ->
            KnownStrapRow(
                device = device,
                isCapturing = state.capture.isRunning && state.capture.deviceAddress == device.address,
                onForget = { onForget(device.address) },
            )
        }

        CaptureControls(
            state = state,
            onStartCapture = onStartCapture,
            onStopCapture = onStopCapture,
        )

        InkOutlineButton(
            label = if (state.isScanning) StrapCopy.SCAN_STOP else StrapCopy.SCAN,
            onClick = onScan,
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.isScanning) {
            Text(
                text = if (state.candidates.isEmpty()) StrapCopy.NO_RESULTS else StrapCopy.SCANNING,
                style = LogbookTheme.type.body,
                color = palette.inkSoft,
            )
        }
        state.candidates.forEach { device ->
            DiscoveredStrapRow(device = device, onConnect = { onConnect(device) })
        }

        // A denial or a failed scan: ink behind the mono bang, because the
        // system has no red to spend and this is the one line on the section
        // that has to be read.
        state.message?.let { message -> InkNotice(text = message) }
    }
}

/**
 * The live readout, and the button that ends or begins a capture.
 *
 * The same [hrCaptureDisplay] mapping the coach chip uses, so a running capture
 * cannot describe itself one way here and another way there.
 */
@Composable
private fun CaptureControls(state: StrapUiState, onStartCapture: () -> Unit, onStopCapture: () -> Unit) {
    val palette = LogbookTheme.palette
    val control = state.control ?: return
    val display = hrCaptureDisplay(state.capture)

    if (display != null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LogbookSpace.grid * 2),
        ) {
            // The documented live-signal exception, resolved against the
            // Logbook palette's own mode: whether the instrument is still
            // reading is the one thing on this page worth a colour.
            HrToneDot(tone = display.tone)
            Text(
                text = "${display.bpmText} bpm · ${display.connectionText}",
                style = LogbookTheme.type.body,
                color = palette.ink,
            )
        }
        display.qualityText?.let {
            Text(it, style = LogbookTheme.type.body, color = palette.inkSoft)
        }
        // Was the warning colour. A strap that is drifting is worth noticing,
        // which is what the bang is for.
        display.detail?.let { InkNotice(text = it) }
    }

    if (control.running) {
        InkOutlineButton(
            label = StrapCopy.STOP_CAPTURE,
            onClick = onStopCapture,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        InkButton(
            label = StrapCopy.START_CAPTURE,
            onClick = onStartCapture,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = StrapCopy.CAPTURE_HINT,
            style = LogbookTheme.type.body,
            // A hint is read, not ghosted — prose floor (Round 4 device pass).
            color = palette.inkSoft,
        )
    }
}

/**
 * A paired strap.
 *
 * A running capture rules the row under in ink — the same "this one" the nav
 * bar and the server list draw — rather than washing it in an accent tint.
 */
@Composable
private fun KnownStrapRow(device: KnownDevice, isCapturing: Boolean, onForget: () -> Unit) {
    val palette = LogbookTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LogbookSpace.touchTarget)
            .bottomRule(
                color = if (isCapturing) palette.ink else palette.rule,
                thickness = if (isCapturing) LogbookSpace.sectionUnderline else LogbookSpace.hairline,
            )
            .padding(vertical = LogbookSpace.grid * 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = device.name, style = LogbookTheme.type.body, color = palette.ink)
                if (isCapturing) {
                    Text(
                        text = StrapCopy.CAPTURING_LABEL.uppercase(),
                        style = LogbookTheme.type.eyebrow,
                        color = palette.inkSoft,
                        modifier = Modifier.padding(start = LogbookSpace.grid * 2),
                    )
                }
            }
            Text(text = device.address, style = LogbookTheme.type.meta, color = palette.inkSoft)
        }
        InkOutlineButton(label = StrapCopy.FORGET, onClick = onForget, quiet = true)
    }
}

@Composable
private fun DiscoveredStrapRow(device: DiscoveredDevice, onConnect: () -> Unit) {
    val palette = LogbookTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LogbookSpace.touchTarget)
            .bottomRule(palette.rule)
            .padding(vertical = LogbookSpace.grid * 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = StrapLogic.displayName(device),
                style = LogbookTheme.type.body,
                color = palette.ink,
            )
            Text(
                text = "${device.address} · ${device.rssi} dBm",
                style = LogbookTheme.type.meta,
                color = palette.inkSoft,
            )
        }
        InkOutlineButton(label = StrapCopy.CONNECT, onClick = onConnect)
    }
}

private fun Context.hasBlePermissions(): Boolean =
    BlePermissions.REQUIRED.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

/**
 * Whether the system would still show a dialog, read after a denial.
 *
 * `any`, not `all`: one permission still offering a rationale means the pair can
 * be asked for again. With no Activity to ask — which should not happen from a
 * launcher callback — the optimistic answer keeps the section retryable rather
 * than permanently declaring itself blocked.
 */
private fun Context.canAskAgainForBle(): Boolean {
    val activity = findActivity() ?: return true
    return BlePermissions.REQUIRED.any { activity.shouldShowRequestPermissionRationale(it) }
}

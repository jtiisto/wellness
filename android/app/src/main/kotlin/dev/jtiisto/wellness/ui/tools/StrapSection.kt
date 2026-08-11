package dev.jtiisto.wellness.ui.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jtiisto.wellness.core.ble.device.KnownDevice
import dev.jtiisto.wellness.core.ble.scanner.DiscoveredDevice
import dev.jtiisto.wellness.core.ui.hr.HrToneDot
import dev.jtiisto.wellness.core.ui.hr.hrCaptureDisplay
import dev.jtiisto.wellness.core.ui.theme.WellnessDefaults
import dev.jtiisto.wellness.core.ui.theme.WellnessShape
import dev.jtiisto.wellness.core.ui.theme.WellnessSpace
import dev.jtiisto.wellness.core.ui.theme.WellnessTheme
import dev.jtiisto.wellness.hr.BlePermissions
import org.koin.androidx.compose.koinViewModel

/**
 * The configuration screen's "Heart rate strap" card: pair a strap, forget one,
 * and record without a workout.
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

    StrapCard(
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
private fun StrapCard(
    state: StrapUiState,
    onScan: () -> Unit,
    onConnect: (DiscoveredDevice) -> Unit,
    onForget: (String) -> Unit,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = WellnessTheme.palette
    ToolsCard(modifier = modifier) {
        Text(StrapCopy.TITLE, style = WellnessTheme.type.title, color = palette.textPrimary)

        if (state.known.isEmpty()) {
            Text(StrapCopy.EMPTY, style = WellnessTheme.type.secondary, color = palette.textFaint)
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

        OutlinedButton(
            onClick = onScan,
            shape = WellnessShape.card,
            colors = WellnessDefaults.accentOutlinedButtonColors(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (state.isScanning) StrapCopy.SCAN_STOP else StrapCopy.SCAN,
                style = WellnessTheme.type.label,
            )
        }

        if (state.isScanning) {
            Text(
                text = if (state.candidates.isEmpty()) StrapCopy.NO_RESULTS else StrapCopy.SCANNING,
                style = WellnessTheme.type.secondary,
                color = palette.textFaint,
            )
        }
        state.candidates.forEach { device ->
            DiscoveredStrapRow(device = device, onConnect = { onConnect(device) })
        }

        state.message?.let { message ->
            Text(text = message, style = WellnessTheme.type.secondary, color = palette.error)
        }
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
    val palette = WellnessTheme.palette
    val control = state.control ?: return
    val display = hrCaptureDisplay(state.capture)

    if (display != null) {
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
    }

    if (control.running) {
        OutlinedButton(
            onClick = onStopCapture,
            shape = WellnessShape.card,
            colors = WellnessDefaults.accentOutlinedButtonColors(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(StrapCopy.STOP_CAPTURE, style = WellnessTheme.type.label)
        }
    } else {
        Button(
            onClick = onStartCapture,
            shape = WellnessShape.card,
            colors = WellnessDefaults.accentButtonColors(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(StrapCopy.START_CAPTURE, style = WellnessTheme.type.label)
        }
        Text(StrapCopy.CAPTURE_HINT, style = WellnessTheme.type.secondary, color = palette.textFaint)
    }
}

@Composable
private fun KnownStrapRow(device: KnownDevice, isCapturing: Boolean, onForget: () -> Unit) {
    val palette = WellnessTheme.palette
    val accent = WellnessTheme.accent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = WellnessSpace.touchTarget)
            .clip(WellnessShape.input)
            .background(if (isCapturing) accent.wash else palette.card)
            .padding(horizontal = WellnessSpace.sm, vertical = WellnessSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name,
                style = WellnessTheme.type.body,
                color = if (isCapturing) accent.text else palette.textPrimary,
            )
            Text(
                text = device.address,
                style = WellnessTheme.type.secondary,
                color = palette.textSecondary,
                fontFamily = FontFamily.Monospace,
            )
        }
        TextButton(onClick = onForget, colors = WellnessDefaults.accentTextButtonColors()) {
            Text(StrapCopy.FORGET, style = WellnessTheme.type.label)
        }
    }
}

@Composable
private fun DiscoveredStrapRow(device: DiscoveredDevice, onConnect: () -> Unit) {
    val palette = WellnessTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = WellnessSpace.touchTarget)
            .clip(WellnessShape.input)
            .background(palette.band)
            .padding(horizontal = WellnessSpace.sm, vertical = WellnessSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = StrapLogic.displayName(device),
                style = WellnessTheme.type.body,
                color = palette.textPrimary,
            )
            Text(
                text = "${device.address} · ${device.rssi} dBm",
                style = WellnessTheme.type.secondary,
                color = palette.textSecondary,
                fontFamily = FontFamily.Monospace,
            )
        }
        TextButton(onClick = onConnect, colors = WellnessDefaults.accentTextButtonColors()) {
            Text(StrapCopy.CONNECT, style = WellnessTheme.type.label)
        }
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

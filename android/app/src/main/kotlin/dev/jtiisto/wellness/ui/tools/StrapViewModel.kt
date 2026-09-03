package dev.jtiisto.wellness.ui.tools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.jtiisto.wellness.core.ble.capture.HrCaptureController
import dev.jtiisto.wellness.core.ble.capture.HrCaptureState
import dev.jtiisto.wellness.core.ble.device.KnownDevice
import dev.jtiisto.wellness.core.ble.device.KnownDeviceStore
import dev.jtiisto.wellness.core.ble.model.ConnectionState
import dev.jtiisto.wellness.core.ble.scanner.DiscoveredDevice
import dev.jtiisto.wellness.hr.BlePermissionCopy
import dev.jtiisto.wellness.hr.BlePermissionStatus
import dev.jtiisto.wellness.hr.BlePermissions
import dev.jtiisto.wellness.hr.PermissionAnswer
import dev.jtiisto.wellness.hr.PermissionStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the strap section renders. */
data class StrapUiState(
    val known: List<KnownDevice> = emptyList(),
    /**
     * The pairing scan's lifecycle, as a [ConnectionState] rather than a
     * boolean.
     *
     * [ConnectionState.SCANNING] belongs to this list and nothing else: a
     * capture connects straight at a known address and never scans, which is why
     * the service publishes `CONNECTING` where a reader might expect `SCANNING`.
     */
    val scanState: ConnectionState = ConnectionState.DISCONNECTED,
    val candidates: List<DiscoveredDevice> = emptyList(),
    /** The running capture the Stop button ends, or null while nothing runs. */
    val stopControl: CaptureControl? = null,
    /** Whether a tap on a known strap's row would start a capture on it. */
    val canStart: Boolean = true,
    val capture: HrCaptureState = HrCaptureState(),
    val permission: BlePermissionStatus = BlePermissionStatus.UNKNOWN,
    /** The inline explanation under the section: a denial, or a failed scan. */
    val message: String? = null,
) {
    val isScanning: Boolean get() = scanState == ConnectionState.SCANNING

    /**
     * Whether the section says, under the rows, what tapping one does.
     *
     * It appears exactly where the tap it teaches would work: with a row to tap,
     * and with nothing recording. A hint for an action the section is currently
     * refusing would teach the wrong thing twice over.
     */
    val showCaptureHint: Boolean get() = canStart && known.isNotEmpty()
}

/**
 * One-shot asks that only an Activity can perform.
 *
 * The permission launcher lives in the composable because it has to; the
 * decision to ask lives here because it can be wrong. This channel is the seam.
 */
sealed interface StrapEvent {

    /** `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT`. Blocking: the tap waits on the answer. */
    data object RequestBluetooth : StrapEvent

    /**
     * `POST_NOTIFICATIONS`, on the way into a capture that has already started.
     * Never blocking — the spec is explicit that a denial costs the notification
     * and nothing else.
     */
    data object RequestNotifications : StrapEvent
}

/**
 * The strap section of the configuration screen: pairing, and capture without a
 * workout.
 *
 * This is where a strap becomes known to the app at all, which makes it the
 * first runtime-permission flow the app has ever had. The rule the whole section
 * is built around: **a tap never fails silently.** Every path either does the
 * thing, asks for what it needs first, or says on screen why it cannot — see
 * [BlePermissions].
 *
 * The one deliberate exception is a tap on a row that is no longer current — the
 * strap forgotten between the render and the tap, or a capture started elsewhere
 * while the permission dialog was up. That tap does nothing and says nothing,
 * because what it would do is no longer what the user saw.
 *
 * Nothing here saves a device. The service does that, on the first `CONNECTED`,
 * because a strap that never connects must not end up in the list of straps you
 * can connect to.
 */
class StrapViewModel(
    private val knownDevices: KnownDeviceStore,
    private val scan: () -> Flow<DiscoveredDevice>,
    private val controller: HrCaptureController,
    private val captureState: StateFlow<HrCaptureState>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val scanState = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val discovered = MutableStateFlow(emptyList<DiscoveredDevice>())
    private val permission = MutableStateFlow(BlePermissionStatus.UNKNOWN)
    private val message = MutableStateFlow<String?>(null)

    private val _events = Channel<StrapEvent>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: Flow<StrapEvent> = _events.receiveAsFlow()

    private var scanJob: Job? = null

    /** What to run once the permission request comes back granted. */
    private var pending: PendingAction? = null

    private val scanInputs = combine(scanState, discovered) { state, list -> state to list }
    private val permissionInputs = combine(permission, message) { status, text -> status to text }

    val uiState: StateFlow<StrapUiState> = combine(
        knownDevices.devices,
        scanInputs,
        permissionInputs,
        captureState,
    ) { known, (scanning, found), (status, text), capture ->
        StrapUiState(
            known = known,
            scanState = scanning,
            candidates = StrapLogic.unpaired(found, known),
            stopControl = StrapLogic.stopControl(capture),
            canStart = StrapLogic.canStart(capture),
            capture = capture,
            permission = status,
            message = text,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = StrapUiState(),
    )

    /**
     * The section came on screen.
     *
     * [KnownDeviceStore] deliberately does not load its map on construction, so
     * this is the call that fills the list — off the main thread, because it is
     * a `SharedPreferences` read.
     */
    fun onOpened(granted: Boolean) {
        syncPermission(granted)
        viewModelScope.launch { withContext(io) { knownDevices.refresh() } }
    }

    /**
     * Reconcile with what the system actually holds.
     *
     * Only ever promotes to [BlePermissionStatus.GRANTED]. A "not granted" here
     * is not a denial — nobody has been asked — and letting it overwrite a
     * recorded [BlePermissionStatus.BLOCKED] would put the section back to
     * asking for a dialog the system will refuse to show.
     */
    fun syncPermission(granted: Boolean) {
        when {
            granted -> permission.value = BlePermissionStatus.GRANTED
            // Revoked from Settings while the app was alive: forget the grant so
            // the next tap asks again rather than walking into a SecurityException.
            permission.value == BlePermissionStatus.GRANTED ->
                permission.value = BlePermissionStatus.UNKNOWN
        }
    }

    /**
     * The blocking half of the flow: the tap that waited resumes here.
     *
     * Takes the raw result map rather than a `granted` boolean, because the
     * distinction that boolean destroys is the one that matters — a dismissed
     * dialog and a refused one arrive as "not granted" alike, and only one of
     * them may ever move the section towards [BlePermissionStatus.BLOCKED].
     *
     * @param canAskAgain `shouldShowRequestPermissionRationale`, read after the
     *   answer. Meaningless unless the answer was a real denial; see
     *   [BlePermissions.statusAfter].
     */
    fun onPermissionResult(results: Map<String, Boolean>, canAskAgain: Boolean) {
        val answer = BlePermissions.answerFrom(results)
        // Dropped either way: an action left pending across an unanswered
        // request would fire on some later, unrelated answer.
        val resumed = pending
        pending = null

        // No verdict — the dialog was dismissed, or never shown. Nothing is
        // recorded and nothing is said, because nothing happened from the user's
        // point of view either. The next tap asks again.
        val status = BlePermissions.statusAfter(answer, canAskAgain) ?: return

        permission.value = status
        message.value = BlePermissions.explanation(status)
        if (answer == PermissionAnswer.GRANTED && resumed != null) run(resumed)
    }

    // ---- pairing -------------------------------------------------------------

    fun toggleScan() {
        if (scanJob?.isActive == true) stopScan() else gate(PendingAction.Scan)
    }

    /**
     * Stop the scan. Called on the section's dispose as well as on the button,
     * which is what keeps `SCAN_MODE_LOW_LATENCY` bounded by "how long the
     * screen was open" — the bound its own KDoc claims, and one the ViewModel
     * outliving the composable would otherwise break.
     */
    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        scanState.value = ConnectionState.DISCONNECTED
    }

    /**
     * Connect to a scan result, which is also how a strap becomes known.
     *
     * The name travels with the intent because the service is what saves it, and
     * by the time it has a proven link the advertisement it came from is gone.
     *
     * Refused while a capture runs, exactly as a known row's tap is: nothing in
     * this section starts a second capture. Checked again when the action runs,
     * because a Connect can be waiting on the permission dialog too.
     */
    fun connect(device: DiscoveredDevice) {
        if (!canStartCapture()) return
        gate(PendingAction.Capture(device.address, StrapLogic.displayName(device)))
    }

    fun forget(address: String) {
        if (StrapLogic.forgetStopsCapture(address, captureState.value)) controller.stop()
        viewModelScope.launch { withContext(io) { knownDevices.forget(address) } }
    }

    // ---- capture without a workout --------------------------------------------

    /**
     * Record from the strap whose row was tapped.
     *
     * Asked here so a tap that cannot succeed never opens a permission dialog
     * for a capture that will not happen, and asked again in [run] once the
     * answer arrives: the store and the service are both free to move while the
     * dialog is on screen.
     */
    fun startCapture(address: String) {
        val device = startableKnownDevice(address) ?: return
        gate(PendingAction.StartKnown(device.address))
    }

    /**
     * Whether the section may start a capture at all.
     *
     * The service is single-source and refuses a second start, and
     * `controller.start` reports only that the intent went out — so a start it
     * quietly drops would still be followed by a notification-permission dialog
     * for a capture nobody has. Every path into [beginCapture] asks this, at the
     * tap and again when the action runs.
     *
     * Read from the capture flow rather than from [uiState], which stops
     * publishing five seconds after the last collector goes: a tap must mean the
     * same thing whether or not the state flow happens to be warm.
     */
    private fun canStartCapture(): Boolean = StrapLogic.canStart(captureState.value)

    /**
     * The remembered strap a row's tap may record from, or null.
     *
     * Null when no capture may start at all, and null when the store no longer
     * holds the address — in which case starting it would put the strap back in
     * the list on the first `CONNECTED`, because the service saves what it
     * connects to. Read from the source flow for the same reason as
     * [canStartCapture].
     */
    private fun startableKnownDevice(address: String): KnownDevice? {
        if (!canStartCapture()) return null
        return knownDevices.devices.value.firstOrNull { it.address == address }
    }

    fun stopCapture() {
        stopScan()
        controller.stop()
    }

    fun dismissMessage() {
        message.value = null
    }

    // ---- plumbing ---------------------------------------------------------------

    private fun gate(action: PendingAction) {
        when (BlePermissions.stepFor(permission.value)) {
            PermissionStep.PROCEED -> run(action)
            PermissionStep.REQUEST -> {
                pending = action
                _events.trySend(StrapEvent.RequestBluetooth)
            }
            // Asking again would show no dialog and change nothing, so the tap
            // has to answer itself.
            PermissionStep.EXPLAIN -> message.value = BlePermissions.explanation(permission.value)
        }
    }

    /**
     * A grant takes as long as the user takes to answer, so a capture start is
     * re-decided here rather than replayed: the strap may have been forgotten,
     * or a capture may have begun elsewhere, while the dialog stood.
     */
    private fun run(action: PendingAction) {
        when (action) {
            PendingAction.Scan -> beginScan()
            // An unknown device is unknown by design on this path, so there is
            // no membership to re-check — only whether a capture may start.
            is PendingAction.Capture -> {
                if (!canStartCapture()) return
                beginCapture(action.address, action.name)
            }

            is PendingAction.StartKnown -> {
                val device = startableKnownDevice(action.address) ?: return
                beginCapture(device.address, device.name)
            }
        }
    }

    private fun beginScan() {
        if (scanJob?.isActive == true) return
        discovered.value = emptyList()
        message.value = null
        scanState.value = ConnectionState.SCANNING
        scanJob = viewModelScope.launch {
            try {
                scan().collect { found -> discovered.update { StrapLogic.merge(it, found) } }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // The scanner closes its flow rather than going quiet when the
                // adapter is off or the grant vanished mid-scan. Saying so is
                // the whole difference between "no straps here" and "we never
                // actually looked".
                message.value = StrapCopy.SCAN_FAILED
            } finally {
                scanState.value = ConnectionState.DISCONNECTED
                scanJob = null
            }
        }
    }

    private fun beginCapture(address: String, name: String) {
        // Asked again at the moment of use. The gate above answers from what the
        // system said when the section opened, and a grant revoked from Settings
        // in between would otherwise reach a service that refuses the start and
        // logs it where nobody is looking.
        if (!controller.canStart()) {
            syncPermission(granted = false)
            message.value = BlePermissionCopy.DENIED
            return
        }
        // A pairing scan and a GATT connect to one of its results compete for
        // the same radio; the list has served its purpose either way.
        stopScan()
        message.value = null
        // A refused start has already put its own sentence on the snackbar, so
        // there is nothing to add here — but there is no capture to notify about
        // either, and asking for the notification permission on the way out of a
        // failure would be the app's most confusing possible dialog.
        if (!controller.start(address, name)) return
        // The first capture start is where the notification permission is asked
        // for, and it is asked for *after* the start deliberately: the service is
        // already going up, so a denial can only cost the notification.
        _events.trySend(StrapEvent.RequestNotifications)
    }

    /** The tap that was waiting on a permission answer. */
    private sealed interface PendingAction {

        data object Scan : PendingAction

        /**
         * A scan result. The name travels with the address because the store
         * has none: this is the one path allowed to name a device it does not
         * know, since connecting is what makes it known.
         */
        data class Capture(val address: String, val name: String) : PendingAction

        /**
         * A known strap's row. Only the address travels — the name, and whether
         * the strap is still known and still startable at all, are re-read at
         * the moment the action runs.
         */
        data class StartKnown(val address: String) : PendingAction
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

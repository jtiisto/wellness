package dev.jtiisto.wellness.ui.tools

import dev.jtiisto.wellness.core.ble.capture.HrCaptureController
import dev.jtiisto.wellness.core.ble.capture.HrCaptureState
import dev.jtiisto.wellness.core.ble.device.KnownDeviceStorage
import dev.jtiisto.wellness.core.ble.device.KnownDeviceStore
import dev.jtiisto.wellness.core.ble.model.BleDevice
import dev.jtiisto.wellness.core.ble.model.ConnectionState
import dev.jtiisto.wellness.core.ble.scanner.DiscoveredDevice
import dev.jtiisto.wellness.hr.BlePermissionCopy
import dev.jtiisto.wellness.hr.BlePermissions
import dev.jtiisto.wellness.hr.BlePermissionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The strap section: pairing, and capture without a workout.
 *
 * The property the whole class is built around is that **a tap never fails
 * silently** — every path either does the thing, asks for what it needs first,
 * or puts a sentence on screen. Most of these tests are one instance of that.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StrapViewModelTest {

    private val strapMap = linkedMapOf<String, String>()

    private val knownDevices = KnownDeviceStore(
        storage = object : KnownDeviceStorage {
            override fun load(): Map<String, String> = LinkedHashMap(strapMap)
            override fun put(address: String, name: String) {
                strapMap[address] = name
            }

            override fun remove(address: String) {
                strapMap.remove(address)
            }
        },
        state = MutableStateFlow(emptyList()),
    )

    /**
     * The scan, as the one function the ViewModel is given.
     *
     * Set per test. Default: a scan that finds nothing and never completes,
     * which is also what a scan in an empty room does.
     */
    private var scan: () -> Flow<DiscoveredDevice> = { callbackFlow { awaitClose { } } }

    private val captureState = MutableStateFlow(HrCaptureState())

    private class FakeController : HrCaptureController {
        val calls = mutableListOf<String>()
        var permitted = true

        /** What the platform makes of a start request. False = it refused. */
        var startSucceeds = true

        override fun canStart(): Boolean = permitted

        override fun start(
            address: String,
            name: String?,
            workoutDate: String?,
            workoutSessionId: Long?,
        ): Boolean {
            calls += "start:$address:$name:$workoutDate:$workoutSessionId"
            return startSucceeds
        }

        override fun stop() {
            calls += "stop"
        }
    }

    private val controller = FakeController()

    @AfterEach
    fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun TestScope.viewModel() = StrapViewModel(
        knownDevices = knownDevices,
        scan = { scan() },
        controller = controller,
        captureState = captureState,
        io = StandardTestDispatcher(testScheduler),
    )

    private fun found(address: String, name: String? = "HRM-Pro", rssi: Int = -55) =
        DiscoveredDevice(BleDevice(address = address, name = name), rssi)

    /** The result map for a request the user answered yes to. */
    private fun granted(): Map<String, Boolean> = BlePermissions.REQUIRED.associateWith { true }

    /** The result map for one they answered no to. */
    private fun refused(): Map<String, Boolean> = BlePermissions.REQUIRED.associateWith { false }

    /**
     * Installs Main and keeps the state and the events hot, as the screen does.
     *
     * [granted] is what the system says at the moment the section opens, which is
     * the only input the ViewModel has before anyone has been asked anything.
     */
    private fun runSectionTest(
        granted: Boolean = true,
        body: suspend TestScope.(StrapViewModel, MutableList<StrapEvent>) -> Unit,
    ) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = viewModel()
        val events = mutableListOf<StrapEvent>()
        backgroundScope.launch { viewModel.uiState.collect { } }
        backgroundScope.launch { viewModel.events.collect { events += it } }
        runCurrent()
        viewModel.onOpened(granted = granted)
        runCurrent()
        body(viewModel, events)
    }

    // ---- opening the section --------------------------------------------------

    @Test
    @DisplayName("opening the section is what loads the remembered straps at all")
    fun opensByRefreshingTheKnownList() = runSectionTest { viewModel, _ ->
        // KnownDeviceStore does not read storage on construction — a
        // SharedPreferences load on the main thread — so nothing has it yet.
        assertTrue(viewModel.uiState.value.known.isEmpty())

        strapMap["AA:01"] = "HRM-Pro"
        viewModel.onOpened(granted = true)
        runCurrent()

        assertEquals(listOf("HRM-Pro"), viewModel.uiState.value.known.map { it.name })
    }

    @Test
    @DisplayName("a grant revoked from Settings drops back to asking rather than to a SecurityException")
    fun revokedGrantIsForgotten() = runSectionTest { viewModel, _ ->
        assertEquals(BlePermissionStatus.GRANTED, viewModel.uiState.value.permission)

        viewModel.syncPermission(granted = false)
        runCurrent()

        assertEquals(BlePermissionStatus.UNKNOWN, viewModel.uiState.value.permission)
    }

    @Test
    @DisplayName("a permanent denial survives a re-open — the system would show no dialog anyway")
    fun blockedIsNotResetByReopening() = runSectionTest(granted = false) { viewModel, _ ->
        viewModel.onPermissionResult(refused(), canAskAgain = false)
        runCurrent()

        viewModel.onOpened(granted = false)
        runCurrent()

        assertEquals(BlePermissionStatus.BLOCKED, viewModel.uiState.value.permission)
    }

    // ---- the permission gate ----------------------------------------------------

    @Test
    @DisplayName("scanning without the grant asks for it first, and does not scan")
    fun scanAsksBeforeScanning() = runSectionTest(granted = false) { viewModel, events ->
        viewModel.toggleScan()
        runCurrent()

        assertEquals(listOf(StrapEvent.RequestBluetooth), events)
        assertEquals(ConnectionState.DISCONNECTED, viewModel.uiState.value.scanState)
    }

    @Test
    @DisplayName("the tap that waited on the dialog runs once it is granted")
    fun grantResumesThePendingScan() = runSectionTest(granted = false) { viewModel, _ ->
        scan = { flow { emit(found("AA:01")) } }

        viewModel.toggleScan()
        runCurrent()
        viewModel.onPermissionResult(granted(), canAskAgain = false)
        runCurrent()

        assertEquals(listOf("AA:01"), viewModel.uiState.value.candidates.map { it.address })
    }

    @Test
    @DisplayName("a refusal explains itself and scans nothing")
    fun denialExplainsItself() = runSectionTest(granted = false) { viewModel, _ ->
        viewModel.toggleScan()
        runCurrent()
        viewModel.onPermissionResult(refused(), canAskAgain = true)
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(BlePermissionStatus.DENIED, state.permission)
        assertEquals(BlePermissionCopy.DENIED, state.message)
        assertEquals(ConnectionState.DISCONNECTED, state.scanState)
    }

    @Test
    @DisplayName("after a permanent denial the button stops asking and says where the switch is")
    fun blockedTapExplainsWithoutAsking() = runSectionTest(granted = false) { viewModel, events ->
        viewModel.toggleScan()
        runCurrent()
        viewModel.onPermissionResult(refused(), canAskAgain = false)
        runCurrent()
        events.clear()

        viewModel.toggleScan()
        runCurrent()

        // No second dialog: the system would not show one, and a button that
        // silently does nothing is the failure this whole gate exists to avoid.
        assertTrue(events.isEmpty())
        assertEquals(BlePermissionCopy.BLOCKED, viewModel.uiState.value.message)
    }

    @Test
    @DisplayName("an interrupted request does not block the section, and the next tap asks again")
    fun interruptedRequestStaysAskable() = runSectionTest(granted = false) { viewModel, events ->
        viewModel.toggleScan()
        runCurrent()
        assertEquals(listOf(StrapEvent.RequestBluetooth), events)
        events.clear()

        // The dialog was swiped away, or the activity was recreated under it:
        // Android delivers an empty map. shouldShowRequestPermissionRationale is
        // false here too — as it is before any dialog has ever been shown — so
        // trusting it would have declared this permanently denied on the very
        // first tap and stranded the user in Settings.
        viewModel.onPermissionResult(emptyMap(), canAskAgain = false)
        runCurrent()

        val afterInterruption = viewModel.uiState.value
        assertTrue(afterInterruption.permission != BlePermissionStatus.BLOCKED)
        // Nothing happened from the user's point of view, so nothing is said.
        assertNull(afterInterruption.message)

        viewModel.toggleScan()
        runCurrent()

        assertEquals(listOf(StrapEvent.RequestBluetooth), events)
    }

    @Test
    @DisplayName("an interrupted request drops the tap that was waiting on it")
    fun interruptedRequestDropsThePendingAction() = runSectionTest(granted = false) { viewModel, _ ->
        scan = { flow { emit(found("AA:01")) } }
        viewModel.toggleScan()
        runCurrent()

        viewModel.onPermissionResult(emptyMap(), canAskAgain = false)
        runCurrent()

        // The scan must not run — nothing was granted — and the pending action
        // must not survive to fire on some later, unrelated answer.
        assertTrue(viewModel.uiState.value.candidates.isEmpty())
        assertEquals(ConnectionState.DISCONNECTED, viewModel.uiState.value.scanState)
    }

    @Test
    @DisplayName("a half-answered request is treated as a refusal, not as a grant")
    fun partialGrantDoesNotProceed() = runSectionTest(granted = false) { viewModel, _ ->
        scan = { flow { emit(found("AA:01")) } }
        viewModel.toggleScan()
        runCurrent()

        // Scan without connect: the list would fill with straps none of which
        // could be connected to.
        viewModel.onPermissionResult(
            mapOf(BlePermissions.REQUIRED.first() to true),
            canAskAgain = true,
        )
        runCurrent()

        assertTrue(viewModel.uiState.value.candidates.isEmpty())
    }

    // ---- scanning ---------------------------------------------------------------

    @Test
    @DisplayName("the discovered list streams in, and the scan owns ConnectionState.SCANNING")
    fun scanStreamsIntoTheList() = runSectionTest { viewModel, _ ->
        val advertisements = MutableSharedFlow<DiscoveredDevice>(replay = 0, extraBufferCapacity = 8)
        scan = { advertisements }

        viewModel.toggleScan()
        runCurrent()
        assertEquals(ConnectionState.SCANNING, viewModel.uiState.value.scanState)

        advertisements.tryEmit(found("AA:01", rssi = -70))
        advertisements.tryEmit(found("AA:02"))
        advertisements.tryEmit(found("AA:01", rssi = -50))
        runCurrent()

        val candidates = viewModel.uiState.value.candidates
        assertEquals(listOf("AA:01", "AA:02"), candidates.map { it.address })
        assertEquals(-50, candidates.first().rssi)
    }

    @Test
    @DisplayName("tapping the button again stops the scan")
    fun toggleStopsTheScan() = runSectionTest { viewModel, _ ->
        var stopped = false
        scan = { callbackFlow { awaitClose { stopped = true } } }

        viewModel.toggleScan()
        runCurrent()
        viewModel.toggleScan()
        runCurrent()

        assertTrue(stopped, "the scan flow's collector must be gone")
        assertEquals(ConnectionState.DISCONNECTED, viewModel.uiState.value.scanState)
    }

    @Test
    @DisplayName("leaving the screen stops the scan — the ViewModel outlives the composable")
    fun disposeStopsTheScan() = runSectionTest { viewModel, _ ->
        var stopped = false
        scan = { callbackFlow { awaitClose { stopped = true } } }

        viewModel.toggleScan()
        runCurrent()
        viewModel.stopScan()
        runCurrent()

        assertTrue(stopped)
        assertEquals(ConnectionState.DISCONNECTED, viewModel.uiState.value.scanState)
    }

    @Test
    @DisplayName("a scan that could not run says so, rather than looking like an empty room")
    fun scanFailureIsReported() = runSectionTest { viewModel, _ ->
        // The scanner closes its flow when the adapter is off; a silent empty
        // list would read as "no straps here" instead of "we never looked".
        scan = { flow { throw IllegalStateException("Bluetooth LE scanner not available") } }

        viewModel.toggleScan()
        runCurrent()

        assertEquals(StrapCopy.SCAN_FAILED, viewModel.uiState.value.message)
        assertEquals(ConnectionState.DISCONNECTED, viewModel.uiState.value.scanState)
    }

    // ---- connecting and capturing -------------------------------------------------

    @Test
    @DisplayName("Connect starts an unanchored capture and stops the scan competing for the radio")
    fun connectStartsCapture() = runSectionTest { viewModel, events ->
        var stopped = false
        scan = { callbackFlow { awaitClose { stopped = true } } }
        viewModel.toggleScan()
        runCurrent()

        viewModel.connect(found("AA:01"))
        runCurrent()

        // No workout date and no hook session: a capture started here is a
        // session in its own right.
        assertEquals(listOf("start:AA:01:HRM-Pro:null:null"), controller.calls)
        assertTrue(stopped)
        assertEquals(ConnectionState.DISCONNECTED, viewModel.uiState.value.scanState)
        assertTrue(StrapEvent.RequestNotifications in events)
    }

    @Test
    @DisplayName("the notification permission is asked for after the start, so a denial cannot block it")
    fun notificationsAreAskedForNonBlockingly() = runSectionTest { viewModel, events ->
        viewModel.connect(found("AA:01"))
        runCurrent()

        assertEquals(1, controller.calls.size)
        assertEquals(listOf(StrapEvent.RequestNotifications), events)
    }

    @Test
    @DisplayName("an unnamed strap is connected to under a label rather than a blank name")
    fun connectNamesAnUnnamedStrap() = runSectionTest { viewModel, _ ->
        viewModel.connect(found("AA:01", name = null))
        runCurrent()

        assertEquals(listOf("start:AA:01:${StrapCopy.UNNAMED}:null:null"), controller.calls)
    }

    @Test
    @DisplayName("tapping a row records from that strap, under the name it is remembered by")
    fun startCaptureUsesTheTappedStrap() = runSectionTest { viewModel, _ ->
        strapMap["AA:02"] = "Beta"
        strapMap["AA:01"] = "Alpha"
        viewModel.onOpened(granted = true)
        runCurrent()

        // Deliberately not the first row: "Beta" sorts after "Alpha", and a
        // selector that only ever reaches the first row is not a selector.
        viewModel.startCapture("AA:02")
        runCurrent()

        assertEquals(listOf("start:AA:02:Beta:null:null"), controller.calls)
    }

    @Test
    @DisplayName("a strap forgotten between the render and the tap is not silently re-paired")
    fun startCaptureIgnoresAForgottenStrap() = runSectionTest { viewModel, _ ->
        strapMap["AA:01"] = "Alpha"
        viewModel.onOpened(granted = true)
        runCurrent()

        viewModel.forget("AA:01")
        runCurrent()

        // The row is gone but the tap was already in flight. Starting anyway
        // would put the strap back in the list on the first CONNECTED.
        viewModel.startCapture("AA:01")
        runCurrent()

        assertTrue(controller.calls.isEmpty())
    }

    @Test
    @DisplayName("with nothing paired there is nothing to stop, and a tap on an unknown address does nothing")
    fun startCaptureNeedsAStrap() = runSectionTest { viewModel, _ ->
        val state = viewModel.uiState.value
        assertNull(state.stopControl)
        assertTrue(state.canStart)

        viewModel.startCapture("AA:01")
        runCurrent()

        assertTrue(controller.calls.isEmpty())
    }

    @Test
    @DisplayName("a running capture is stoppable and names its strap, whatever is paired")
    fun runningCaptureOffersStop() = runSectionTest { viewModel, _ ->
        captureState.value = HrCaptureState(isRunning = true, deviceAddress = "AA:09", deviceName = "Gone")
        runCurrent()

        val stop = viewModel.uiState.value.stopControl
        assertNotNull(stop)
        assertEquals("Gone", stop!!.name)

        viewModel.stopCapture()
        runCurrent()

        assertEquals(listOf("stop"), controller.calls)
    }

    @Test
    @DisplayName("no row starts a second capture while one is already running")
    fun startCaptureIsRefusedWhileRunning() = runSectionTest { viewModel, _ ->
        strapMap["AA:01"] = "Alpha"
        strapMap["AA:02"] = "Beta"
        viewModel.onOpened(granted = true)
        captureState.value = HrCaptureState(isRunning = true, deviceAddress = "AA:01")
        runCurrent()

        // The rows render inert, and the tap is refused behind them too: the
        // service is single-source, and this is where that is mirrored.
        assertFalse(viewModel.uiState.value.canStart)

        viewModel.startCapture("AA:02")
        runCurrent()

        assertTrue(controller.calls.isEmpty())
    }

    @Test
    @DisplayName("Connect is refused while a capture runs — connecting is a start too")
    fun connectIsRefusedWhileRunning() = runSectionTest { viewModel, events ->
        captureState.value = HrCaptureState(isRunning = true, deviceAddress = "AA:09", deviceName = "Gym")
        runCurrent()

        viewModel.connect(found("AA:01"))
        runCurrent()

        assertTrue(controller.calls.isEmpty())
        assertFalse(StrapEvent.RequestNotifications in events)
    }

    @Test
    @DisplayName("a capture started elsewhere while the dialog was up cancels a waiting Connect too")
    fun grantDoesNotConnectDuringARunningCapture() = runSectionTest(granted = false) { viewModel, events ->
        viewModel.connect(found("AA:01"))
        runCurrent()
        assertEquals(listOf(StrapEvent.RequestBluetooth), events)

        captureState.value = HrCaptureState(isRunning = true, deviceAddress = "AA:09", deviceName = "Gym")
        viewModel.onPermissionResult(granted(), canAskAgain = false)
        runCurrent()

        assertTrue(controller.calls.isEmpty())
        assertFalse(StrapEvent.RequestNotifications in events)
    }

    @Test
    @DisplayName("the tap that waited on the dialog records from the strap it was made on")
    fun grantResumesTheTappedStrap() = runSectionTest(granted = false) { viewModel, events ->
        strapMap["AA:01"] = "Alpha"
        viewModel.onOpened(granted = false)
        runCurrent()

        viewModel.startCapture("AA:01")
        runCurrent()
        assertEquals(listOf(StrapEvent.RequestBluetooth), events)

        viewModel.onPermissionResult(granted(), canAskAgain = false)
        runCurrent()

        assertEquals(listOf("start:AA:01:Alpha:null:null"), controller.calls)
    }

    @Test
    @DisplayName("a strap forgotten while the dialog was up is not started by the grant that follows")
    fun grantDoesNotResumeAForgottenStrap() = runSectionTest(granted = false) { viewModel, _ ->
        strapMap["AA:01"] = "Alpha"
        viewModel.onOpened(granted = false)
        runCurrent()
        viewModel.startCapture("AA:01")
        runCurrent()

        // The dialog stands for as long as the user takes to answer it, and the
        // section behind it stays live.
        viewModel.forget("AA:01")
        runCurrent()
        viewModel.onPermissionResult(granted(), canAskAgain = false)
        runCurrent()

        // Starting it anyway would put the strap back in the list on CONNECTED.
        assertTrue(controller.calls.isEmpty())
    }

    @Test
    @DisplayName("a capture started elsewhere while the dialog was up cancels the tap that was waiting")
    fun grantDoesNotStartASecondCapture() = runSectionTest(granted = false) { viewModel, events ->
        strapMap["AA:01"] = "Alpha"
        viewModel.onOpened(granted = false)
        runCurrent()
        viewModel.startCapture("AA:01")
        runCurrent()

        // The coach tab's sheet got there first.
        captureState.value = HrCaptureState(isRunning = true, deviceAddress = "AA:09", deviceName = "Gym")
        viewModel.onPermissionResult(granted(), canAskAgain = false)
        runCurrent()

        // The service refuses a second start, but start() reports only that the
        // intent went out — so without this check the ask that follows a start
        // would be a notification dialog for a capture that never began.
        assertTrue(controller.calls.isEmpty())
        assertFalse(StrapEvent.RequestNotifications in events)
    }

    @Test
    @DisplayName("the hint under the rows appears only where the tap it teaches would work")
    fun captureHintFollowsTheTap() = runSectionTest { viewModel, _ ->
        // Nothing paired: no row to tap, and the empty state says its own thing.
        assertFalse(viewModel.uiState.value.showCaptureHint)

        strapMap["AA:01"] = "Alpha"
        viewModel.onOpened(granted = true)
        runCurrent()

        assertTrue(viewModel.uiState.value.showCaptureHint)

        captureState.value = HrCaptureState(isRunning = true, deviceAddress = "AA:01")
        runCurrent()

        assertFalse(viewModel.uiState.value.showCaptureHint)
    }

    @Test
    @DisplayName("a grant revoked between opening the section and tapping is caught before the service is")
    fun revokedGrantIsCaughtAtTheTap() = runSectionTest { viewModel, _ ->
        // The section opened with the permission in hand; Nearby devices was
        // turned off in Settings before the tap. Without this check the intent
        // would reach a service that refuses it and logs where nobody looks.
        controller.permitted = false

        viewModel.connect(found("AA:01"))
        runCurrent()

        assertTrue(controller.calls.isEmpty())
        assertEquals(BlePermissionCopy.DENIED, viewModel.uiState.value.message)
        assertEquals(BlePermissionStatus.UNKNOWN, viewModel.uiState.value.permission)
    }

    @Test
    @DisplayName("a start the platform refused does not go on to ask about notifications")
    fun refusedStartSkipsTheNotificationAsk() = runSectionTest { viewModel, events ->
        // The controller has already put its own sentence on the snackbar. What
        // must not happen is a notification-permission dialog for a capture that
        // does not exist — the app's most confusing possible prompt.
        controller.startSucceeds = false

        viewModel.connect(found("AA:01"))
        runCurrent()

        assertEquals(1, controller.calls.size)
        assertTrue(events.isEmpty())
    }

    // ---- forgetting -----------------------------------------------------------------

    @Test
    @DisplayName("Forget drops the strap from the list")
    fun forgetRemovesTheStrap() = runSectionTest { viewModel, _ ->
        strapMap["AA:01"] = "Alpha"
        viewModel.onOpened(granted = true)
        runCurrent()

        viewModel.forget("AA:01")
        runCurrent()

        assertTrue(viewModel.uiState.value.known.isEmpty())
        assertTrue(controller.calls.isEmpty())
    }

    @Test
    @DisplayName("forgetting the strap that is recording stops the recording first")
    fun forgetStopsItsOwnCapture() = runSectionTest { viewModel, _ ->
        strapMap["AA:01"] = "Alpha"
        viewModel.onOpened(granted = true)
        captureState.value = HrCaptureState(isRunning = true, deviceAddress = "AA:01")
        runCurrent()

        viewModel.forget("AA:01")
        runCurrent()

        assertEquals(listOf("stop"), controller.calls)
        assertTrue(viewModel.uiState.value.known.isEmpty())
    }

    @Test
    @DisplayName("forgetting a different strap leaves the recording running")
    fun forgetLeavesOtherCapturesAlone() = runSectionTest { viewModel, _ ->
        strapMap["AA:01"] = "Alpha"
        strapMap["AA:02"] = "Beta"
        viewModel.onOpened(granted = true)
        captureState.value = HrCaptureState(isRunning = true, deviceAddress = "AA:02")
        runCurrent()

        viewModel.forget("AA:01")
        runCurrent()

        assertTrue(controller.calls.isEmpty())
    }

    @Test
    @DisplayName("a paired strap disappears from the scan results rather than being offered twice")
    fun pairedStrapsLeaveTheCandidateList() = runSectionTest { viewModel, _ ->
        val advertisements = MutableSharedFlow<DiscoveredDevice>(replay = 0, extraBufferCapacity = 8)
        scan = { advertisements }
        viewModel.toggleScan()
        runCurrent()
        advertisements.tryEmit(found("AA:01"))
        runCurrent()
        assertEquals(1, viewModel.uiState.value.candidates.size)

        // The service saved it on the first CONNECTED, and the store republished.
        strapMap["AA:01"] = "HRM-Pro"
        viewModel.onOpened(granted = true)
        runCurrent()

        assertTrue(viewModel.uiState.value.candidates.isEmpty())
    }
}

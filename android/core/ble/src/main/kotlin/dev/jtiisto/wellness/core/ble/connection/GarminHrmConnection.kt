package dev.jtiisto.wellness.core.ble.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.SystemClock
import dev.jtiisto.wellness.core.ble.BleLog
import dev.jtiisto.wellness.core.ble.model.ConnectionState
import dev.jtiisto.wellness.core.ble.model.HeartRateSample
import dev.jtiisto.wellness.core.ble.reconnect.ReconnectionStrategy
import dev.jtiisto.wellness.core.ble.scanner.HrmAdvertisementFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * One GATT link to one heart-rate strap, with the watchdogs that make it
 * survive a real training session.
 *
 * Ported from pulse-bridge essentially unchanged, because every timeout in here
 * was paid for on a device and none of it is rediscoverable from first
 * principles. The three rules worth stating out loud:
 *
 * 1. **Every failure routes to `disconnect()`.** Service discovery failing, the
 *    HRM characteristic being absent, the CCCD write being refused — each one
 *    leaves a link that is CONNECTED and will never produce a byte. Returning
 *    early from any of them is a silent permanent stall, so they all disconnect
 *    and let the reconnect path deal with it.
 * 2. **A connect is not healthy until data flows, and it stops being healthy the
 *    moment data stops.** The retry budget resets on the first *sample*, not on
 *    `STATE_CONNECTED`, or repeated post-connect failures would defeat the
 *    attempt bound entirely — and the same watchdog keeps running afterwards, so
 *    a subscription that dies mid-session is caught by the rule that caught it
 *    dying at the start (see [StreamLivenessPolicy]).
 * 3. **A dropped notification is recorded in the data**, as a gap marker on the
 *    next sample that gets through plus a cumulative counter, rather than only
 *    in a log line nobody correlates.
 *
 * Device-only glue: excluded from the coverage gate. The decisions that are not
 * glue live in [ConnectDiagnostics], [HrmCharacteristicParser],
 * [ReconnectionStrategy] and [StreamLivenessPolicy], all of which are covered.
 *
 * @param scope owns the watchdogs and the backoff. It is the capture service's
 *   scope, and it *should* die with the service — a GATT handle outliving its
 *   owner is a leak, unlike the sample buffer, which deliberately outlives it.
 * @param advertisementProbe the address-filtered scan run alongside each connect
 *   attempt, so a watchdog abort can say which failure happened. Null disables
 *   probing, and the verdict then stays agnostic rather than guessing.
 * @param now wall clock, and only ever used to **stamp data**: a sample's receipt
 *   time is a real instant that has to survive into the database.
 * @param elapsed monotonic source for the watchdogs, which measure durations and
 *   must not be readable by a clock adjustment. Same split the capture service
 *   already makes for the signal tracker.
 */
class GarminHrmConnection(
    private val context: Context,
    private val address: String,
    private val scope: CoroutineScope,
    private val log: BleLog = BleLog {},
    private val reconnectionStrategy: ReconnectionStrategy = ReconnectionStrategy(),
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    private val firstSampleTimeoutMs: Long = StreamLivenessPolicy.FIRST_SAMPLE_TIMEOUT_MS,
    private val staleAfterMs: Long = StreamLivenessPolicy.STALE_AFTER_MS,
    private val advertisementProbe: ((String) -> Flow<Int>)? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
) {

    companion object {
        val HRM_SERVICE_UUID: UUID = HrmAdvertisementFilter.HRM_SERVICE_UUID
        val HRM_MEASUREMENT_UUID: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** More than three seconds without a notification is a discontinuity. */
        private const val GAP_THRESHOLD_MS = 3000L

        /**
         * Android takes about thirty seconds to report a failed direct connect,
         * and sometimes never calls back at all. Aborting sooner is what keeps
         * the retry loop responsive enough to be worth having.
         */
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000L
    }

    val deviceId: String = address

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /**
     * About seventeen minutes of beats at 1 Hz. A consumer stalled longer than
     * that has a bigger problem than the overflow, and any overflow is
     * gap-marked in the data and counted below.
     */
    private val _heartRateData = MutableSharedFlow<HeartRateSample>(extraBufferCapacity = 1024)
    val heartRateData: Flow<HeartRateSample> = _heartRateData.asSharedFlow()

    /** Connect progress or failure detail for the UI; null when healthy. */
    private val _connectionDetail = MutableStateFlow<String?>(null)
    val connectionDetail: StateFlow<String?> = _connectionDetail.asStateFlow()

    /**
     * Whether the beat stream has gone quiet — a *different fact* from
     * [connectionState], and the whole point of this class's liveness rule.
     *
     * "The link is up" and "beats are arriving" were read as one thing, and a
     * subscription can die while the link stays perfectly healthy. This is set
     * when [StreamLivenessPolicy] declares the stream dead and cleared **only by
     * a sample** — never by a connect and never by a state change, because an
     * attempt that has not delivered anything has not disproved it. The UI reads
     * it to stop drawing a reading it no longer has.
     */
    private val _streamStale = MutableStateFlow(false)
    val streamStale: StateFlow<Boolean> = _streamStale.asStateFlow()

    private val droppedSamples = AtomicInteger(0)
    private val lastSampleTimestamp = AtomicLong(0L)

    @Volatile
    private var dropGapPending = false

    @Volatile
    private var awaitingFirstSample = false

    /**
     * Elapsed-time mark of the last sign of life on this connection: the connect
     * itself, then every sample. What the sample watchdog measures silence from,
     * kept separate from [lastSampleTimestamp] because that one is wall-clock
     * *data* and deliberately survives across reconnects to gap-mark them.
     */
    @Volatile
    private var streamActivityAtMs = 0L

    private var gatt: BluetoothGatt? = null
    private var reconnectJob: Job? = null
    private var connectWatchdogJob: Job? = null
    private var sampleWatchdogJob: Job? = null
    private var probeJob: Job? = null

    @Volatile
    private var probeState = ProbeState.INACTIVE

    @Volatile
    private var probeRssi: Int? = null

    /** The verdict from the most recent watchdog abort, for the retry messages. */
    @Volatile
    private var lastFailureVerdict: String? = null

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            log.log("gatt state change status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    cancelConnectWatchdog()
                    stopAdvertisementProbe()
                    _connectionState.value = ConnectionState.CONNECTED
                    startSampleWatchdog()
                    // null means the call was refused outright and has already
                    // routed to disconnect; false means the stack was busy. A
                    // CONNECTED link with no services never produces data, so
                    // both force the retry path.
                    if (gatt.routingFailures("discoverServices") { discoverServices() } == false) {
                        gatt.disconnectSafely()
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> handleLinkLost(gatt)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val characteristic = if (status == BluetoothGatt.GATT_SUCCESS) {
                gatt.routingFailures("getService") {
                    getService(HRM_SERVICE_UUID)?.getCharacteristic(HRM_MEASUREMENT_UUID)
                }
            } else {
                null
            }
            log.log("services discovered status=$status hrmCharacteristic=${characteristic != null}")
            if (characteristic == null) {
                gatt.disconnectSafely()
                return
            }
            when (
                gatt.routingFailures("setCharacteristicNotification") {
                    setCharacteristicNotification(characteristic, true)
                }
            ) {
                // Refused — already disconnected on the way out.
                null -> return
                false -> {
                    log.log("enabling notifications failed — disconnecting")
                    gatt.disconnectSafely()
                    return
                }
                true -> Unit
            }

            val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
            if (descriptor == null) {
                log.log("CCCD descriptor missing — disconnecting")
                gatt.disconnectSafely()
                return
            }
            val writeResult = gatt.routingFailures("writeDescriptor") {
                writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } ?: return
            if (writeResult != BluetoothStatusCodes.SUCCESS) {
                log.log("CCCD write failed (result=$writeResult) — disconnecting")
                gatt.disconnectSafely()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != CLIENT_CHARACTERISTIC_CONFIG) return
            log.log("CCCD write acknowledged status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // Notifications were never actually enabled; without this the
                // link would sit "connected" with no data forever.
                gatt.disconnectSafely()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            // Same identity rule as handleLinkLost: only the current handle may
            // feed the stream. A notification from an abandoned handle would
            // re-arm the liveness mark, clear streamStale and emit a sample
            // into the record as if the successor link had delivered it.
            if (gatt !== this@GarminHrmConnection.gatt) return
            if (characteristic.uuid == HRM_MEASUREMENT_UUID) handleHrmData(value)
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun connect() {
        gatt?.closeSafely()
        gatt = null

        _connectionState.value = ConnectionState.CONNECTING
        // Each attempt owns its verdict: a stale one must not label a failure
        // the probe never observed.
        lastFailureVerdict = null
        log.log("connecting to $address")

        val newGatt = try {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val device: BluetoothDevice = manager.adapter.getRemoteDevice(address)
            // Deprecated as of API 37 in favour of the BluetoothGattConnectionSettings
            // overload, which does not exist below it — and minSdk here is 35.
            // The replacement lands when minSdk does; a runtime-gated second
            // path would double the connect logic to save a warning.
            @Suppress("DEPRECATION")
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.log("connectGatt threw ${e.javaClass.simpleName}")
            null
        }

        if (newGatt == null) {
            // Adapter off, or the address is not one the stack accepts. No
            // callback will ever arrive, so this must not sit in CONNECTING.
            log.log("connectGatt unavailable — scheduling retry")
            _connectionState.value = ConnectionState.DISCONNECTED
            attemptReconnect()
            return
        }

        gatt = newGatt
        startAdvertisementProbe()
        startConnectWatchdog()
    }

    /** Deliberate teardown: no reconnect follows, and the budget resets. */
    @SuppressLint("MissingPermission")
    suspend fun disconnect() {
        log.log("disconnect requested")
        reconnectJob?.cancel()
        reconnectJob = null
        cancelConnectWatchdog()
        cancelSampleWatchdog()
        stopAdvertisementProbe()
        lastFailureVerdict = null
        reconnectionStrategy.reset()
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectionDetail.value = null
        // A deliberate teardown is not a dead stream, and the next capture starts
        // from this object's published values.
        _streamStale.value = false
        gatt?.disconnectSafely()
        gatt?.closeSafely()
        gatt = null
    }

    private fun handleHrmData(value: ByteArray) {
        val parsed = HrmCharacteristicParser.parse(value) ?: return
        val receivedAtMs = now()
        val previous = lastSampleTimestamp.getAndSet(receivedAtMs)
        val silenceGap = previous > 0 && (receivedAtMs - previous) > GAP_THRESHOLD_MS
        val gapFromDrop = dropGapPending

        val sample = HeartRateSample(
            deviceId = address,
            receivedAtMs = receivedAtMs,
            heartRateBpm = parsed.heartRateBpm,
            rrIntervalsMs = parsed.rrIntervalsMs,
            isGapBefore = silenceGap || gapFromDrop,
        )

        // This *is* the re-arm: the watchdog measures silence from here and
        // recomputes its wait on every wake, so a beat needs no timer of its own.
        streamActivityAtMs = elapsed()
        _streamStale.value = false

        if (awaitingFirstSample) {
            // Not a cancel — the watchdog keeps running, on the longer clock a
            // proven link is held to from here on.
            awaitingFirstSample = false
            // Data flowing is the only real definition of a healthy link.
            reconnectionStrategy.reset()
            _connectionDetail.value = null
            lastFailureVerdict = null
            log.log("first sample received — link healthy")
        }

        if (_heartRateData.tryEmit(sample)) {
            if (gapFromDrop) dropGapPending = false
        } else {
            // The measurement is lost. Record it where the analysis will see it
            // — a gap marker on the next stored sample — and count it.
            dropGapPending = true
            val total = droppedSamples.incrementAndGet()
            log.log("sample DROPPED (total=$total) — buffer full; next stored sample carries a gap marker")
        }
    }

    private fun attemptReconnect() {
        val verdict = lastFailureVerdict
        if (!reconnectionStrategy.hasAttemptsRemaining) {
            val message = ConnectDiagnostics.giveUpDetail(reconnectionStrategy.currentAttempt, verdict)
            log.log(message)
            _connectionDetail.value = message
            return
        }
        _connectionState.value = ConnectionState.RECONNECTING

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val backoff = reconnectionStrategy.nextDelay()
            val message = ConnectDiagnostics.retryDetail(reconnectionStrategy.currentAttempt, verdict)
            log.log("$message in $backoff")
            _connectionDetail.value = message
            delay(backoff)
            // Anything that moved the state since — a deliberate disconnect
            // most of all — cancels the retry by making this false.
            if (_connectionState.value == ConnectionState.RECONNECTING) connect()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startConnectWatchdog() {
        connectWatchdogJob?.cancel()
        connectWatchdogJob = scope.launch {
            delay(connectTimeoutMs)
            if (_connectionState.value != ConnectionState.CONNECTING) return@launch
            // Read the verdict BEFORE tearing the probe down.
            lastFailureVerdict = ConnectDiagnostics.verdict(probeState, probeRssi)
            stopAdvertisementProbe()
            log.log("connect watchdog fired after ${connectTimeoutMs}ms — aborting")
            gatt?.closeSafely()
            gatt = null
            _connectionState.value = ConnectionState.DISCONNECTED
            attemptReconnect()
        }
    }

    private fun cancelConnectWatchdog() {
        connectWatchdogJob?.cancel()
        connectWatchdogJob = null
    }

    /**
     * The link's liveness clock, running for as long as the link is up.
     *
     * One timer rather than one per beat: it sleeps for whatever
     * [StreamLivenessPolicy] says is left, then re-reads how long the stream has
     * actually been quiet and sleeps again. A beat therefore re-arms it by
     * moving [streamActivityAtMs], with no job churn at 1 Hz, and the timeout
     * switching from the first-sample clock to the steady-state one is picked up
     * on the next wake.
     */
    @SuppressLint("MissingPermission")
    private fun startSampleWatchdog() {
        awaitingFirstSample = true
        streamActivityAtMs = elapsed()
        sampleWatchdogJob?.cancel()
        sampleWatchdogJob = scope.launch {
            while (true) {
                val timeout = StreamLivenessPolicy.timeoutMs(
                    hasDelivered = !awaitingFirstSample,
                    firstSampleTimeoutMs = firstSampleTimeoutMs,
                    staleAfterMs = staleAfterMs,
                )
                val silence = elapsed() - streamActivityAtMs
                if (StreamLivenessPolicy.isStale(silence, timeout)) break
                delay(StreamLivenessPolicy.remainingMs(silence, timeout))
            }
            // Anything that moved the state since — a real drop, a deliberate
            // stop — has its own teardown running and must not get a second one.
            if (_connectionState.value != ConnectionState.CONNECTED) return@launch

            log.log("stream silent — the link is up and delivering nothing; dropping it to retry")
            // Published before the teardown, and cleared only by a sample: the
            // DISCONNECTED→RECONNECTING pair below is two writes to one
            // StateFlow, so a collector is not guaranteed to see the state that
            // would have told it to stop drawing a reading.
            _streamStale.value = true

            // Ask the stack to drop the link, then run the lost-link path
            // ourselves rather than waiting for onConnectionStateChange — the
            // failure being handled is a stack that has stopped answering, and
            // recovery cannot be conditional on it answering now.
            val handle = gatt
            handle?.disconnectSafely()
            // This cancels the coroutine it is running in. Safe, and the reason
            // nothing suspends afterwards: the reconnect it schedules is a child
            // of the scope, not of this job.
            handleLinkLost(handle)
        }
    }

    private fun cancelSampleWatchdog() {
        awaitingFirstSample = false
        sampleWatchdogJob?.cancel()
        sampleWatchdogJob = null
    }

    /**
     * The link is gone: stand everything down and hand over to the backoff.
     *
     * Shared by the two ways that can be true — the peer or the platform saying
     * so, and the sample watchdog concluding it — so a stale stream is recovered
     * from by exactly the machinery a real drop is, rather than by a second
     * lifecycle written alongside it.
     */
    private fun handleLinkLost(handle: BluetoothGatt?) {
        // Only the connection's CURRENT handle may stand the machinery down.
        // The sample watchdog tears a stale link down proactively and the
        // platform's own STATE_DISCONNECTED for that same disconnect can still
        // arrive later, on the shared callback object — after the backoff has
        // already opened a successor. Without this identity check that late
        // echo would cancel the successor's watchdogs, null the field out from
        // under it (leaking a live handle that keeps delivering into the same
        // callback), and publish DISCONNECTED over a healthy link. A handle
        // that is not the current one is a zombie: close it again (idempotent)
        // and touch nothing else.
        if (handle == null || handle !== gatt) {
            handle?.closeSafely()
            return
        }
        cancelConnectWatchdog()
        cancelSampleWatchdog()
        _connectionState.value = ConnectionState.DISCONNECTED
        handle.closeSafely()
        gatt = null
        attemptReconnect()
    }

    private fun startAdvertisementProbe() {
        val probe = advertisementProbe ?: return
        probeJob?.cancel()
        probeState = ProbeState.LISTENING
        probeRssi = null
        probeJob = scope.launch {
            try {
                // first() cancels the underlying scan after one advertisement —
                // a single confirmation is all the verdict needs.
                val rssi = probe(address).first()
                probeRssi = rssi
                probeState = ProbeState.HEARD
                log.log("probe: advertisement heard rssi=$rssi")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The scan failed or ended empty. The verdict has to stay
                // agnostic — this must never read as "the strap is silent".
                if (probeState == ProbeState.LISTENING) {
                    probeState = ProbeState.UNAVAILABLE
                    log.log("probe unavailable: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun stopAdvertisementProbe() {
        probeJob?.cancel()
        probeJob = null
        probeState = ProbeState.INACTIVE
    }

    /**
     * Run a GATT operation, routing a refusal to the disconnect path.
     *
     * Every method on [BluetoothGatt] requires `BLUETOOTH_CONNECT`, and the user
     * can revoke it mid-capture. That usually kills the process outright, but it
     * is not guaranteed to — and if it does not, an unguarded `SecurityException`
     * thrown inside a `BluetoothGattCallback` escapes onto the Binder thread
     * instead of reaching any of this class's failure handling. The link would
     * then sit CONNECTED, producing nothing, with no retry ever scheduled.
     *
     * @return the operation's value, or **null when it was refused** — which is a
     *   third answer distinct from the operation's own true/false, and callers
     *   have to tell them apart because a refusal has already disconnected.
     */
    private fun <T> BluetoothGatt.routingFailures(what: String, block: BluetoothGatt.() -> T): T? = try {
        block()
    } catch (e: SecurityException) {
        log.log("gatt $what refused: ${e.javaClass.simpleName} — disconnecting")
        disconnectSafely()
        null
    }

    /**
     * The teardown calls, which cannot route anywhere: they *are* the failure
     * path, so a throw here has nothing left to fall back on and would only
     * strand the handle it was trying to release.
     *
     * These two catch **any** non-fatal exception, unlike [routingFailures] which
     * catches only the permission failure it knows how to answer. A revoked grant
     * is not the only way these throw: an adapter switched off underneath a live
     * handle raises `IllegalStateException` from deep in the Bluetooth stack, and
     * from inside a `BluetoothGattCallback` that escapes onto a Binder thread and
     * takes the process with it. There is nothing to distinguish here — every
     * outcome is "the link is gone", which is what was being asked for.
     *
     * Only the class name is logged. The shareable debug log never carries a
     * platform message.
     */
    @SuppressLint("MissingPermission")
    private fun BluetoothGatt.disconnectSafely() {
        try {
            disconnect()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.log("gatt disconnect failed: ${e.javaClass.simpleName}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothGatt.closeSafely() {
        try {
            close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.log("gatt close failed: ${e.javaClass.simpleName}")
        }
    }
}

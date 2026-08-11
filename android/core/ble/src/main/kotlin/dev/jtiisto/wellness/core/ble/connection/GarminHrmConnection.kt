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
 * 2. **A connect is not healthy until data flows.** The retry budget resets on
 *    the first *sample*, not on `STATE_CONNECTED`, or repeated post-connect
 *    failures would defeat the attempt bound entirely.
 * 3. **A dropped notification is recorded in the data**, as a gap marker on the
 *    next sample that gets through plus a cumulative counter, rather than only
 *    in a log line nobody correlates.
 *
 * Device-only glue: excluded from the coverage gate. The decisions that are not
 * glue live in [ConnectDiagnostics], [HrmCharacteristicParser] and
 * [ReconnectionStrategy], all of which are covered.
 *
 * @param scope owns the watchdogs and the backoff. It is the capture service's
 *   scope, and it *should* die with the service — a GATT handle outliving its
 *   owner is a leak, unlike the sample buffer, which deliberately outlives it.
 * @param advertisementProbe the address-filtered scan run alongside each connect
 *   attempt, so a watchdog abort can say which failure happened. Null disables
 *   probing, and the verdict then stays agnostic rather than guessing.
 */
class GarminHrmConnection(
    private val context: Context,
    private val address: String,
    private val scope: CoroutineScope,
    private val log: BleLog = BleLog {},
    private val reconnectionStrategy: ReconnectionStrategy = ReconnectionStrategy(),
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
    private val firstSampleTimeoutMs: Long = DEFAULT_FIRST_SAMPLE_TIMEOUT_MS,
    private val advertisementProbe: ((String) -> Flow<Int>)? = null,
    private val now: () -> Long = System::currentTimeMillis,
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

        /**
         * A link is only proven once real data flows: discovery, the CCCD write
         * and notification delivery can each fail *after* STATE_CONNECTED.
         */
        const val DEFAULT_FIRST_SAMPLE_TIMEOUT_MS = 10_000L
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

    private val droppedSamples = AtomicInteger(0)
    private val lastSampleTimestamp = AtomicLong(0L)

    @Volatile
    private var dropGapPending = false

    @Volatile
    private var awaitingFirstSample = false

    private var gatt: BluetoothGatt? = null
    private var reconnectJob: Job? = null
    private var connectWatchdogJob: Job? = null
    private var firstSampleWatchdogJob: Job? = null
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
                    startFirstSampleWatchdog()
                    // null means the call was refused outright and has already
                    // routed to disconnect; false means the stack was busy. A
                    // CONNECTED link with no services never produces data, so
                    // both force the retry path.
                    if (gatt.routingFailures("discoverServices") { discoverServices() } == false) {
                        gatt.disconnectSafely()
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    cancelConnectWatchdog()
                    cancelFirstSampleWatchdog()
                    _connectionState.value = ConnectionState.DISCONNECTED
                    gatt.closeSafely()
                    this@GarminHrmConnection.gatt = null
                    attemptReconnect()
                }
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
        cancelFirstSampleWatchdog()
        stopAdvertisementProbe()
        lastFailureVerdict = null
        reconnectionStrategy.reset()
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectionDetail.value = null
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

        if (awaitingFirstSample) {
            awaitingFirstSample = false
            cancelFirstSampleWatchdog()
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

    @SuppressLint("MissingPermission")
    private fun startFirstSampleWatchdog() {
        awaitingFirstSample = true
        firstSampleWatchdogJob?.cancel()
        firstSampleWatchdogJob = scope.launch {
            delay(firstSampleTimeoutMs)
            if (awaitingFirstSample && _connectionState.value == ConnectionState.CONNECTED) {
                log.log("no data ${firstSampleTimeoutMs}ms after connect — disconnecting to retry")
                gatt?.disconnectSafely()
            }
        }
    }

    private fun cancelFirstSampleWatchdog() {
        awaitingFirstSample = false
        firstSampleWatchdogJob?.cancel()
        firstSampleWatchdogJob = null
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

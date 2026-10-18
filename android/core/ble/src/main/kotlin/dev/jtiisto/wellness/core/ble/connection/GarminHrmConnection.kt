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
import kotlinx.coroutines.yield
import java.util.UUID

/**
 * One GATT link to one heart-rate strap, with the watchdogs that make it
 * survive a real training session.
 *
 * Ported from pulse-bridge essentially unchanged, because every timeout in here
 * was paid for on a device and none of it is rediscoverable from first
 * principles. The rules worth stating out loud:
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
 * 4. **One thread owns the connection** (amended 2026-10-17). `connect()`, the
 *    watchdogs, the backoff *and every `BluetoothGattCallback` body* run on
 *    [scope]'s dispatcher — the capture service's `Dispatchers.Main.immediate`.
 *    Each callback captures what it must on the Binder thread and posts the rest
 *    (see [post]). Because `connect()` does not suspend between `connectGatt()`
 *    returning and the attempt's probe and watchdog starting, no callback body
 *    can run against a half-set-up attempt; compound transitions are atomic
 *    with respect to each other; and every field below is confined to that one
 *    thread, so none needs `@Volatile`. `connect()` and `disconnect()` must
 *    therefore be called on that thread too, which every caller does.
 * 5. **Only the current handle is listened to.** Every callback checks on
 *    arrival that its `gatt` is the connection's current one; a stray handle is
 *    closed again (idempotent) and ignored (see [isCurrent], [handleLinkLost]).
 *
 * Device-only glue: excluded from the coverage gate. The decisions that are not
 * glue live in [ConnectDiagnostics], [GattStatus], [HrmCharacteristicParser],
 * [ReconnectionStrategy], [ReconnectNotice] and [StreamLivenessPolicy], all of
 * which are covered.
 *
 * @param scope owns the watchdogs, the backoff and the callback bodies. It is
 *   the capture service's scope, and it *should* die with the service — a GATT
 *   handle outliving its owner is a leak, unlike the sample buffer, which
 *   deliberately outlives it. The service runs [disconnect] before cancelling
 *   it, which closes the current handle; a callback posted after cancellation
 *   is dropped, and the only handles that could still call back by then are
 *   ones this class has already closed.
 * @param advertisementProbe the address-filtered scan run alongside each connect
 *   attempt, so a watchdog abort can say which failure happened. Null disables
 *   probing, and the verdict then stays agnostic rather than guessing.
 * @param now wall clock, and only ever used to **stamp data**: a sample's receipt
 *   time is a real instant that has to survive into the database.
 * @param elapsed monotonic source for the watchdogs and the drop notice, which
 *   measure durations and must not be readable by a clock adjustment. Same
 *   split the capture service already makes for the signal tracker.
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

    /**
     * Connect progress or failure detail for the UI; null when healthy — and
     * also null through a link loss that is not news yet (see [ReconnectNotice]).
     */
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

    // Everything below is confined to [scope]'s thread (rule 4 above).

    private var droppedSamples = 0
    private var lastSampleTimestamp = 0L
    private var dropGapPending = false
    private var awaitingFirstSample = false

    /**
     * Elapsed-time mark of the last sign of life on this connection: the connect
     * itself, then every sample. What the sample watchdog measures silence from,
     * kept separate from [lastSampleTimestamp] because that one is wall-clock
     * *data* and deliberately survives across reconnects to gap-mark them.
     *
     * A sample's mark is taken on the Binder thread at receipt, not when its
     * posted body runs, so a janky main thread delays a sample's processing but
     * never its time. The watchdog's wake is a main-thread message too, and the
     * queue runs in time order, so a beat received before the deadline is always
     * processed before the watchdog looks.
     */
    private var streamActivityAtMs = 0L

    private var gatt: BluetoothGatt? = null
    private var reconnectJob: Job? = null
    private var connectWatchdogJob: Job? = null
    private var sampleWatchdogJob: Job? = null
    private var probeJob: Job? = null

    /** The drop-notice timer; armed when a proven link is lost (see [beginOutage]). */
    private var noticeJob: Job? = null

    /**
     * Elapsed-time mark of the loss of a proven link, or null outside such an
     * outage. Cleared by the first sample after it, a give-up, or a deliberate
     * stop — never by a failed attempt, which is still the same outage.
     */
    private var outageStartedAtMs: Long? = null

    private var probeState = ProbeState.INACTIVE
    private var probeRssi: Int? = null

    /**
     * Which connect attempt of the current outage is in flight — 1 for the
     * first `connectGatt()` of a session or after a drop. Counted in [connect],
     * reset by the first sample and by [disconnect].
     *
     * Deliberately separate from [reconnectionStrategy]'s count, which is the
     * backoff *budget*: the loss of a proven link spends a backoff step without
     * any attempt having failed, so reporting the budget count made the first
     * failed reconnect after a drop read "Connect attempt 2 failed".
     */
    private var connectAttempt = 0

    /** The verdict from the most recent watchdog abort, for the retry messages. */
    private var lastFailureVerdict: String? = null

    /**
     * The framework calls these on a Binder thread. Each captures only what it
     * must there and posts the body to [scope] (rule 4).
     */
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            // Taken here for the same reason a sample's is: a loss reported at
            // t=0 and processed at t=5 is a five-second-old outage, and the
            // drop notice must measure it from the report, not the processing.
            val receivedElapsedMs = elapsed()
            post { onStateChange(gatt, status, newState, receivedElapsedMs) }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            post { onServices(gatt, status) }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val uuid = descriptor.uuid
            post { onDescriptorWritten(gatt, uuid, status) }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            // The one thing that must not wait for the main queue: when the
            // beat arrived, on both clocks. The parse and the emit may wait.
            val receivedAtMs = now()
            val receivedElapsedMs = elapsed()
            val uuid = characteristic.uuid
            // Copied because the body runs later: the framework makes no
            // promise not to reuse the array it handed us.
            val bytes = value.copyOf()
            post { onNotification(gatt, uuid, bytes, receivedAtMs, receivedElapsedMs) }
        }
    }

    /**
     * Run a callback body on [scope]'s thread. From a Binder thread
     * `Dispatchers.Main.immediate` always dispatches, so this is a post to the
     * main queue — in arrival order, behind whatever the connection was already
     * doing.
     */
    private fun post(body: () -> Unit) {
        scope.launch { body() }
    }

    @SuppressLint("MissingPermission")
    private fun onStateChange(handle: BluetoothGatt, status: Int, newState: Int, receivedElapsedMs: Long) {
        log.log("gatt state change status=${GattStatus.describe(status)} newState=${GattStatus.describeState(newState)}")
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                // A late CONNECTED from an abandoned handle must not publish
                // CONNECTED, restart the sample watchdog, or run discovery on a
                // handle nothing owns.
                if (!isCurrent(handle, "connected")) return
                cancelConnectWatchdog()
                stopAdvertisementProbe()
                _connectionState.value = ConnectionState.CONNECTED
                startSampleWatchdog()
                // null means the call was refused outright and has already
                // routed to disconnect; false means the stack was busy. A
                // CONNECTED link with no services never produces data, so
                // both force the retry path.
                if (handle.routingFailures("discoverServices") { discoverServices() } == false) {
                    handle.disconnectSafely("discoverServices not started")
                }
            }

            BluetoothProfile.STATE_DISCONNECTED -> handleLinkLost(handle, lossAt = receivedElapsedMs)
        }
    }

    @SuppressLint("MissingPermission")
    private fun onServices(handle: BluetoothGatt, status: Int) {
        if (!isCurrent(handle, "services discovered")) return
        val characteristic = if (status == BluetoothGatt.GATT_SUCCESS) {
            handle.routingFailures("getService") {
                getService(HRM_SERVICE_UUID)?.getCharacteristic(HRM_MEASUREMENT_UUID)
            }
        } else {
            null
        }
        log.log("services discovered status=$status hrmCharacteristic=${characteristic != null}")
        if (characteristic == null) {
            handle.disconnectSafely("no HRM characteristic")
            return
        }
        when (
            handle.routingFailures("setCharacteristicNotification") {
                setCharacteristicNotification(characteristic, true)
            }
        ) {
            // Refused — already disconnected on the way out.
            null -> return
            false -> {
                log.log("enabling notifications failed — disconnecting")
                handle.disconnectSafely("notifications not enabled")
                return
            }
            true -> Unit
        }

        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
        if (descriptor == null) {
            log.log("CCCD descriptor missing — disconnecting")
            handle.disconnectSafely("CCCD missing")
            return
        }
        val writeResult = handle.routingFailures("writeDescriptor") {
            writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } ?: return
        if (writeResult != BluetoothStatusCodes.SUCCESS) {
            log.log("CCCD write failed (result=$writeResult) — disconnecting")
            handle.disconnectSafely("CCCD write not started")
        }
    }

    private fun onDescriptorWritten(handle: BluetoothGatt, descriptorUuid: UUID, status: Int) {
        if (!isCurrent(handle, "descriptor write")) return
        if (descriptorUuid != CLIENT_CHARACTERISTIC_CONFIG) return
        log.log("CCCD write acknowledged status=$status")
        if (status != BluetoothGatt.GATT_SUCCESS) {
            // Notifications were never actually enabled; without this the
            // link would sit "connected" with no data forever.
            handle.disconnectSafely("CCCD write rejected")
        }
    }

    private fun onNotification(
        handle: BluetoothGatt,
        characteristicUuid: UUID,
        value: ByteArray,
        receivedAtMs: Long,
        receivedElapsedMs: Long,
    ) {
        // Same identity rule as everywhere: only the current handle may feed
        // the stream. A notification from an abandoned handle would re-arm the
        // liveness mark, clear streamStale and emit a sample into the record as
        // if the successor link had delivered it.
        if (!isCurrent(handle, "notification")) return
        if (characteristicUuid == HRM_MEASUREMENT_UUID) handleHrmData(value, receivedAtMs, receivedElapsedMs)
    }

    /**
     * Whether [handle] is the connection's current one. A stray is closed again
     * (idempotent — the framework ignores a second close) and its callback is
     * ignored; [handleLinkLost] applies the same rule to its own entry.
     */
    private fun isCurrent(handle: BluetoothGatt, from: String): Boolean {
        if (handle === gatt) return true
        handle.closeSafely("stale handle ($from)")
        return false
    }

    @SuppressLint("MissingPermission")
    suspend fun connect() {
        gatt?.closeSafely("superseded by a new attempt")
        gatt = null

        _connectionState.value = ConnectionState.CONNECTING
        // Each attempt owns its verdict: a stale one must not label a failure
        // the probe never observed.
        lastFailureVerdict = null
        // Counted before connectGatt(): a call that throws or returns null is
        // still this attempt, and fails as it.
        connectAttempt++
        log.log("connecting to $address (attempt $connectAttempt)")

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
            attemptReconnect(attemptFailed = true)
            return
        }

        // No suspension point between connectGatt() returning and these three
        // lines, and every callback body is posted to this same thread — so by
        // the time any callback for this attempt runs, its probe and watchdog
        // exist and a CONNECTED can stop them (rule 4).
        gatt = newGatt
        startAdvertisementProbe()
        startConnectWatchdog()
    }

    /** Deliberate teardown: no reconnect follows, and the budget resets. */
    @SuppressLint("MissingPermission")
    suspend fun disconnect() {
        log.log("disconnect requested: deliberate")
        reconnectJob?.cancel()
        reconnectJob = null
        cancelConnectWatchdog()
        cancelSampleWatchdog()
        stopAdvertisementProbe()
        endOutage()
        lastFailureVerdict = null
        reconnectionStrategy.reset()
        connectAttempt = 0
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectionDetail.value = null
        // A deliberate teardown is not a dead stream, and the next capture starts
        // from this object's published values.
        _streamStale.value = false
        gatt?.disconnectSafely("deliberate")
        gatt?.closeSafely("deliberate")
        gatt = null
    }

    private fun handleHrmData(value: ByteArray, receivedAtMs: Long, receivedElapsedMs: Long) {
        val parsed = HrmCharacteristicParser.parse(value) ?: return
        val previous = lastSampleTimestamp
        lastSampleTimestamp = receivedAtMs
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
        // The receipt mark, not the processing time (see streamActivityAtMs) —
        // and never moved backwards, so the connect's own mark is a floor.
        streamActivityAtMs = maxOf(streamActivityAtMs, receivedElapsedMs)
        _streamStale.value = false

        if (awaitingFirstSample) {
            // Not a cancel — the watchdog keeps running, on the longer clock a
            // proven link is held to from here on.
            awaitingFirstSample = false
            // Data flowing is the only real definition of a healthy link, and
            // the only thing that ends an outage.
            reconnectionStrategy.reset()
            connectAttempt = 0
            endOutage()
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
            val total = ++droppedSamples
            log.log("sample DROPPED (total=$total) — buffer full; next stored sample carries a gap marker")
        }
    }

    /**
     * Hand over to the backoff.
     *
     * @param attemptFailed whether a connect attempt has in fact failed — the
     *   connect watchdog, `connectGatt()` refusing, or a link lost before it
     *   delivered a sample (rule 2: a connect is not healthy until data flows).
     *   False only for the loss of a proven link, whose notice [ReconnectNotice]
     *   holds back until the outage outlasts the gap threshold.
     */
    private fun attemptReconnect(attemptFailed: Boolean) {
        val verdict = lastFailureVerdict
        // The attempt that just failed, captured before the next connect()
        // counts its successor. Unused for a drop, where nothing failed.
        val failedAttempt = connectAttempt
        // A failed attempt's own diagnosis supersedes the drop notice, so the
        // timer must not overwrite it with the vaguer sentence later.
        if (attemptFailed) cancelNoticeTimer()
        if (!reconnectionStrategy.hasAttemptsRemaining) {
            // Terminal: nothing may keep listening or counting after this.
            stopAdvertisementProbe()
            endOutage()
            val message = ConnectDiagnostics.giveUpDetail(reconnectionStrategy.currentAttempt, verdict)
            log.log(message)
            _connectionDetail.value = message
            return
        }
        // Published at once, with or without a notice: the state is the truth
        // and the display rules depend on it.
        _connectionState.value = ConnectionState.RECONNECTING

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val backoff = reconnectionStrategy.nextDelay()
            val sinceLossMs = outageStartedAtMs?.let { elapsed() - it } ?: 0L
            val message = ReconnectNotice.detail(sinceLossMs, failedAttempt, verdict, attemptFailed)
            val budget = "backoff ${reconnectionStrategy.currentAttempt}"
            if (message != null) {
                log.log("$message in $backoff ($budget)")
            } else {
                log.log("link dropped — reconnecting in $backoff ($budget; notice held for ${GAP_THRESHOLD_MS}ms)")
            }
            _connectionDetail.value = message
            delay(backoff)
            // Anything that moved the state since — a deliberate disconnect
            // most of all — cancels the retry by making this false.
            if (_connectionState.value == ConnectionState.RECONNECTING) connect()
        }
    }

    /**
     * A proven link has been lost: start the outage clock and arm the notice.
     *
     * @param lossAt elapsed-time mark the outage is measured from — the moment
     *   the loss was reported, or for a stale stream the last sample, because
     *   that outage has already been running for the whole stale window.
     *
     * The timer sleeps until [ReconnectNotice] says the loss is news and then
     * publishes the drop text — unless the first sample, a failed attempt, a
     * give-up or a deliberate stop cancelled it first, each of which owns the
     * detail from then on.
     */
    private fun beginOutage(lossAt: Long) {
        outageStartedAtMs = lossAt
        noticeJob?.cancel()
        noticeJob = scope.launch {
            while (true) {
                val since = elapsed() - lossAt
                if (ReconnectNotice.isPastThreshold(since)) break
                delay(ReconnectNotice.remainingMs(since))
            }
            val sinceLossMs = elapsed() - lossAt
            val message = ReconnectNotice.detail(
                sinceLossMs = sinceLossMs,
                failedAttempt = connectAttempt,
                verdict = lastFailureVerdict,
                attemptFailed = false,
            )
            log.log("link still down after ${sinceLossMs}ms — notice published")
            _connectionDetail.value = message
        }
    }

    private fun cancelNoticeTimer() {
        noticeJob?.cancel()
        noticeJob = null
    }

    private fun endOutage() {
        cancelNoticeTimer()
        outageStartedAtMs = null
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
            gatt?.closeSafely("connect watchdog")
            gatt = null
            _connectionState.value = ConnectionState.DISCONNECTED
            attemptReconnect(attemptFailed = true)
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
            // The handle this watchdog guards; it is (re)started on CONNECTED,
            // when that handle has just been confirmed current.
            val owner = gatt

            /** Null once the stream is stale, else how long until it could be. */
            fun waitOrNullIfStale(): Long? {
                val timeout = StreamLivenessPolicy.timeoutMs(
                    hasDelivered = !awaitingFirstSample,
                    firstSampleTimeoutMs = firstSampleTimeoutMs,
                    staleAfterMs = staleAfterMs,
                )
                val silence = elapsed() - streamActivityAtMs
                return if (StreamLivenessPolicy.isStale(silence, timeout)) {
                    null
                } else {
                    StreamLivenessPolicy.remainingMs(silence, timeout)
                }
            }

            while (true) {
                val wait = waitOrNullIfStale()
                if (wait != null) {
                    delay(wait)
                    continue
                }
                // Stale by what has been *processed* — but beats received on
                // the Binder thread may still be queued behind this wake. A main
                // thread stalled from t=1 s to t=26 s, with beats arriving all
                // along, wakes this at t=26 having processed only those up to
                // the stall; tearing down here would close a healthy link and
                // then reject the queued beats as stale-handle traffic. So go
                // to the back of the queue once: on the Android Main dispatcher
                // yield() re-posts to the Handler, behind every post already
                // waiting (a launch from Main.immediate would run inline and
                // not wait). Then ask again — a beat that was waiting has moved
                // streamActivityAtMs and the loop goes back to sleep.
                yield()
                if (waitOrNullIfStale() == null) break
            }
            // Anything that moved the state since — a real drop, a deliberate
            // stop, possibly processed during the yield — has its own teardown
            // running and must not get a second one.
            if (_connectionState.value != ConnectionState.CONNECTED || gatt !== owner) return@launch

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
            handle?.disconnectSafely("stream silent")
            // This cancels the coroutine it is running in. Safe, and the reason
            // nothing suspends afterwards: the reconnect it schedules is a child
            // of the scope, not of this job. The outage is dated from the last
            // beat: the record has been silent for the whole stale window, so
            // the loss is already news and the notice publishes at once.
            handleLinkLost(handle, lossAt = streamActivityAtMs)
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
     *
     * A link that had delivered is a *drop*: the notice waits for the gap
     * threshold ([beginOutage]), measured from [lossAt] — the Binder-thread
     * receipt of the platform's report, or the last beat for a stale stream —
     * never from when this body happened to run. A link lost before its first sample is a
     * *failed attempt* (rule 2), and says so at once, as it always has.
     */
    private fun handleLinkLost(handle: BluetoothGatt?, lossAt: Long) {
        // Only the connection's CURRENT handle may stand the machinery down.
        // The sample watchdog tears a stale link down proactively and the
        // platform's own STATE_DISCONNECTED for that same disconnect can still
        // arrive later — after the backoff has already opened a successor.
        // Without this identity check that late echo would cancel the
        // successor's watchdogs, null the field out from under it (leaking a
        // live handle that keeps delivering into the same callback), and
        // publish DISCONNECTED over a healthy link. A handle that is not the
        // current one is a zombie: close it again (idempotent) and touch
        // nothing else.
        if (handle == null) return
        if (handle !== gatt) {
            handle.closeSafely("stale handle (disconnected)")
            return
        }
        // Read before the cancels below reset awaitingFirstSample.
        val proven = _connectionState.value == ConnectionState.CONNECTED && !awaitingFirstSample
        cancelConnectWatchdog()
        cancelSampleWatchdog()
        // A probe still running here (a loss while CONNECTING) would otherwise
        // outlive its attempt: nothing else on this path stops it.
        stopAdvertisementProbe()
        _connectionState.value = ConnectionState.DISCONNECTED
        handle.closeSafely("link lost")
        gatt = null
        if (proven) beginOutage(lossAt)
        attemptReconnect(attemptFailed = !proven)
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
     * thrown inside a callback body escapes the posted coroutine into a scope
     * with no handler for it instead of reaching any of this class's failure
     * handling. The link would then sit CONNECTED, producing nothing, with no
     * retry ever scheduled.
     *
     * @return the operation's value, or **null when it was refused** — which is a
     *   third answer distinct from the operation's own true/false, and callers
     *   have to tell them apart because a refusal has already disconnected.
     */
    private fun <T> BluetoothGatt.routingFailures(what: String, block: BluetoothGatt.() -> T): T? = try {
        block()
    } catch (e: SecurityException) {
        log.log("gatt $what refused: ${e.javaClass.simpleName} — disconnecting")
        disconnectSafely("$what refused")
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
     * from inside a callback body that escapes into the service scope and takes
     * the process with it. There is nothing to distinguish here — every outcome
     * is "the link is gone", which is what was being asked for.
     *
     * Each logs its [reason] first — every deliberate disconnect and close is in
     * the shared log with why it happened, which is half of reading a drop back
     * afterwards. Reasons are authored words; only the class name of a failure
     * is logged, never a platform message.
     */
    @SuppressLint("MissingPermission")
    private fun BluetoothGatt.disconnectSafely(reason: String) {
        log.log("gatt disconnect: $reason")
        try {
            disconnect()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.log("gatt disconnect failed: ${e.javaClass.simpleName}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothGatt.closeSafely(reason: String) {
        log.log("close: $reason")
        try {
            close()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.log("gatt close failed: ${e.javaClass.simpleName}")
        }
    }
}

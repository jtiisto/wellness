package dev.jtiisto.wellness.hr

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.app.ServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import dev.jtiisto.wellness.core.ble.BleLog
import dev.jtiisto.wellness.core.ble.buffer.IntervalBuffer
import dev.jtiisto.wellness.core.ble.capture.HrCaptureState
import dev.jtiisto.wellness.core.ble.capture.withCaptureSession
import dev.jtiisto.wellness.core.ble.capture.InactivityAction
import dev.jtiisto.wellness.core.ble.capture.InactivityPolicy
import dev.jtiisto.wellness.core.ble.connection.GarminHrmConnection
import dev.jtiisto.wellness.core.ble.device.KnownDeviceStore
import dev.jtiisto.wellness.core.ble.di.HrCaptureStateQualifier
import dev.jtiisto.wellness.core.ble.model.ConnectionState
import dev.jtiisto.wellness.core.ble.quality.SignalQualityTracker
import dev.jtiisto.wellness.core.ble.scanner.BleScanner
import dev.jtiisto.wellness.core.data.hr.CaptureStopResult
import dev.jtiisto.wellness.core.data.hr.HrCaptureStore
import dev.jtiisto.wellness.core.data.network.ServerBootstrap
import dev.jtiisto.wellness.core.data.network.ServerResolution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.util.concurrent.atomic.AtomicInteger

/**
 * The foreground service a heart-rate capture runs in.
 *
 * It owns exactly the things that must not outlive a capture — the GATT link,
 * the wake lock, the notification, the inactivity countdown — and deliberately
 * owns none of the things that must: the sample buffer runs on an app-lived Koin
 * scope, and the session row lives in Room. That split is what makes "capture
 * survives app death, and sync survives capture death" true rather than
 * aspirational.
 *
 * **`START_STICKY` with an open-session resume.** Killed under memory pressure,
 * the service is restarted with a null intent, finds the newest session nobody
 * closed, and reattaches to it. Without that the row would stay open forever and
 * the beats after the restart would belong to a session with no start.
 *
 * **Device-only glue**, excluded from the coverage gate. Every decision it makes
 * that could be wrong has been moved somewhere that is covered:
 * [CaptureStartGate], [InactivityPolicy], [HrCaptureNotificationText], and
 * `HrCaptureStore` for the whole of the session lifecycle.
 *
 * Started only from a foreground user action with `BLUETOOTH_CONNECT` already
 * granted — the pairing and Start Workout flows guarantee it, and
 * [CaptureStartGate] checks it anyway, because the `START_STICKY` restart and a
 * mid-session permission revocation both reach here without passing through
 * that UI.
 */
/**
 * Whether a start intent that lost the claim still has something to contribute.
 *
 * Almost every refused start is a duplicate delivery and should do nothing. The
 * exception is a Start Workout tap landing while a capture is still getting
 * going: the strap and the session are already covered, but the workout anchor
 * is new, and it is the link that makes the recording mean anything afterwards.
 */
internal fun refusedStartCarriesAnchor(workoutDate: String?): Boolean = !workoutDate.isNullOrBlank()

class HrCaptureService : Service() {

    private val captureState: MutableStateFlow<HrCaptureState> by inject(HrCaptureStateQualifier)
    private val captureStore: HrCaptureStore by inject()
    private val intervalBuffer: IntervalBuffer by inject()
    private val knownDevices: KnownDeviceStore by inject()
    private val scanner: BleScanner by inject()
    private val bootstrap: ServerBootstrap by inject()
    private val bleLog: BleLog by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notification by lazy { HrCaptureNotification(this) }

    private var connection: GarminHrmConnection? = null
    private var collectJob: Job? = null
    private var stateJob: Job? = null
    private var sessionJob: Job? = null
    private var detailJob: Job? = null
    private var inactivityJob: Job? = null
    private var startupJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** Start/stop bookkeeping, extracted so its decisions are covered. */
    private val lifecycle = CaptureLifecycle()

    /** Rows stored by this run, for the closing log line. Written off the main thread. */
    private val storedRows = AtomicInteger(0)

    /**
     * The session this service published, so a failed startup can give it back.
     * Null until [beginCapture] has one.
     */
    private var startedSessionId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notification.createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when {
            intent?.action == ACTION_STOP -> stopCapture()

            intent?.getStringExtra(EXTRA_DEVICE_ADDRESS) != null -> startCapture(
                address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS),
                name = intent.getStringExtra(EXTRA_DEVICE_NAME),
                workoutDate = intent.getStringExtra(EXTRA_WORKOUT_DATE),
                workoutSessionId = intent.takeIf { it.hasExtra(EXTRA_WORKOUT_SESSION_ID) }
                    ?.getLongExtra(EXTRA_WORKOUT_SESSION_ID, 0L),
            )

            // Null intent: the system restarted us. The database is the only
            // record of what was being captured.
            else -> resumeOpenSession(startId)
        }
        return START_STICKY
    }

    /**
     * Promote to the foreground and start capturing.
     *
     * [resumedSessionId] non-null means the session row already exists and must
     * not be recreated — the rows captured before the restart belong to it.
     */
    private fun startCapture(
        address: String?,
        name: String?,
        workoutDate: String? = null,
        workoutSessionId: Long? = null,
        resumedSessionId: String? = null,
    ): Boolean {
        // First, and before the gate: a duplicate intent for a capture that is
        // already running must do nothing at all. Evaluating the gate ahead of
        // this would let a permission revoked mid-session turn a redundant start
        // into a teardown of the live capture.
        //
        // onStartCommand runs on the main thread, so the claim closes the
        // double-start window before any async work is launched.
        if (connection != null || !lifecycle.claimStart()) {
            // Not entirely redundant, though. A Start Workout tap that arrives
            // while a resume-startup is still in flight loses the claim, and its
            // workout anchor is the one thing in the intent the running capture
            // does not already have. Dropping it silently loses the workout link
            // for the whole session.
            if (refusedStartCarriesAnchor(workoutDate)) {
                serviceScope.launch {
                    runCatching { captureStore.anchorToWorkout(requireNotNull(workoutDate), workoutSessionId) }
                        .onFailure { bleLog.log("late anchor failed: ${it.javaClass.simpleName}") }
                }
            }
            return false
        }

        val refusal = CaptureStartGate.evaluate(
            address = address,
            hasConnectPermission = hasConnectPermission(),
            serverResolved = bootstrap.state.value is ServerResolution.Resolved,
        )
        if (refusal != CaptureRefusal.NONE) {
            bleLog.log("capture ${CaptureStartGate.reason(refusal)}")
            // Through the single exit, so the claim just taken is released — a
            // refusal that kept it would lock the service out of every later start.
            finishTeardown()
            return false
        }
        val deviceAddress = requireNotNull(address)

        // CONNECTING, not SCANNING: capture goes straight at a known address.
        // SCANNING belongs to the pairing list, which is the only thing here
        // that actually scans.
        val initial = HrCaptureState(
            isRunning = true,
            connectionState = ConnectionState.CONNECTING,
            deviceAddress = deviceAddress,
            deviceName = name ?: deviceAddress,
        )
        captureState.value = initial

        if (!promoteToForeground(initial)) {
            finishTeardown()
            return false
        }
        acquireWakeLock()

        startupJob = serviceScope.launch {
            var connected = false
            try {
                connected = beginCapture(deviceAddress, workoutDate, workoutSessionId, resumedSessionId)
            } catch (e: CancellationException) {
                bleLog.log("capture startup cancelled")
                throw e
            } catch (e: Exception) {
                // The foreground state and the wake lock are already held, so a
                // startup failure has to roll all of it back — otherwise the
                // start claim stays set with nobody left to release it.
                bleLog.log("capture startup FAILED: ${e.javaClass.simpleName}")
            } finally {
                // Roll back only a startup that failed on its own. When a stop is
                // what ended it — a cancellation, or an abort at a checkpoint —
                // that stop's teardown owns the cleanup, and running a second one
                // here would release the lifecycle claim out from under it.
                // NonCancellable so a cancelled scope still gets the wake lock back.
                if (!connected && lifecycle.mayContinueStartup()) {
                    withContext(NonCancellable) {
                        disconnectQuietly()
                        // The session this startup opened, or resumed into, has
                        // nothing recording for it. Left current it would put a
                        // dead id on every set event and make the next sticky
                        // restart reattach to a capture that never ran.
                        startedSessionId?.let { captureStore.abandonSession(it) }
                        finishTeardown()
                    }
                }
            }
        }
        return true
    }

    /**
     * Open the session, wire the collectors, connect.
     *
     * Checks [CaptureLifecycle.mayContinueStartup] at both points it comes back
     * from a suspension. A stop decided while the session write was in flight
     * would otherwise be followed, moments later, by this resuming and opening a
     * GATT link that the teardown has already finished looking for — a strap
     * recording into a capture the app believes is over.
     *
     * @return true when the connection was actually started, which is what tells
     *   the caller whether there is anything to roll back
     */
    private suspend fun beginCapture(
        address: String,
        workoutDate: String?,
        workoutSessionId: Long?,
        resumedSessionId: String?,
    ): Boolean {
        val sessionId = resumedSessionId
            ?: captureStore.startSession(address, workoutDate, workoutSessionId)
        startedSessionId = sessionId
        if (!lifecycle.mayContinueStartup()) {
            // The row exists and is published as current, so the stop that is
            // already running closes it — with a final flush, and with the right
            // answer about the buffer timer. Closing it here as well would leave
            // that stop looking at nothing.
            bleLog.log("capture startup aborted after the session opened — a stop arrived first")
            return false
        }
        storedRows.set(0)

        // The session id and its workout anchor are mirrored from the store
        // rather than from the Intent that started this, because the resume path
        // has no Intent — the anchor comes back off the row — and because a
        // later anchorToWorkout() from the coach tab reaches the store directly.
        // One collector covers all three, and the store stays the single source.
        sessionJob = serviceScope.launch {
            captureStore.current.collect { session -> publish { it.withCaptureSession(session) } }
        }

        val tracker = SignalQualityTracker(clock = SystemClock::elapsedRealtime)

        val conn = GarminHrmConnection(
            context = this,
            address = address,
            scope = serviceScope,
            log = bleLog,
            advertisementProbe = scanner::advertisements,
        )
        connection = conn

        stateJob = serviceScope.launch {
            conn.connectionState.collect { connectionState ->
                publish { it.copy(connectionState = connectionState, bpm = bpmFor(connectionState, it.bpm)) }
                if (connectionState == ConnectionState.CONNECTED) {
                    // Remembered on a proven link, not on the attempt: a strap
                    // that never connects must not end up in the known list.
                    // Off the main thread — this is a SharedPreferences write.
                    val name = captureState.value.deviceName
                    withContext(Dispatchers.IO) { knownDevices.save(address, name) }
                }
                when (InactivityPolicy.actionFor(connectionState)) {
                    InactivityAction.ARM -> armInactivityTimer()
                    InactivityAction.CANCEL -> cancelInactivityTimer()
                    InactivityAction.LEAVE -> Unit
                }
            }
        }

        detailJob = serviceScope.launch {
            conn.connectionDetail.collect { detail -> publish { it.copy(detail = detail) } }
        }

        intervalBuffer.start()
        collectJob = serviceScope.launch(Dispatchers.Default) {
            conn.heartRateData.collect { sample ->
                // A beat is the strongest possible evidence the link is alive,
                // stronger than the connection state, which can lag a stall.
                cancelInactivityTimer()
                tracker.add(sample)
                storedRows.addAndGet(intervalBuffer.add(sample, sessionId))
                publish { it.copy(bpm = sample.heartRateBpm, signalQuality = tracker.quality()) }
            }
        }

        if (!lifecycle.mayContinueStartup()) {
            // The last checkpoint, and the one that matters most: connect() runs
            // to connectGatt without suspending, so a cancellation delivered
            // during this stretch would not be observed until the link was
            // already open.
            bleLog.log("capture startup aborted before connecting — a stop arrived first")
            return false
        }
        conn.connect()
        return true
    }

    /**
     * The `START_STICKY` restart path: reattach to whatever was left open.
     *
     * `stopSelf(startId)` rather than `stopSelf()`, and the difference is the
     * whole race. The store's resume refuses to displace a capture that started
     * while its query was in flight, and reports that as "nothing to resume" —
     * so this branch can be reached with a real capture running on the same
     * service. The plain overload would stop it. The id-scoped one stops only if
     * no newer start has arrived, which is exactly the question being asked.
     */
    private fun resumeOpenSession(startId: Int) {
        serviceScope.launch {
            val open = captureStore.resumeOpenSession()
            if (open == null) {
                bleLog.log("capture restart found nothing to resume")
                stopSelf(startId)
                return@launch
            }
            // Through the same start gate as a user-initiated capture: a sticky
            // restart is not a way around a revoked permission or an unresolved
            // server, and the address comes off the row rather than an Intent.
            var started = false
            try {
                val name = withContext(Dispatchers.IO) { knownDevices.nameOf(open.deviceId) }
                started = startCapture(
                    address = open.deviceId,
                    name = name,
                    resumedSessionId = open.sessionId,
                )
            } finally {
                // The resume published this session before knowing whether a
                // capture would follow. A refusal — or a throw reading the
                // device name — must hand it back, or it stays current with
                // nothing feeding it.
                if (!started) {
                    withContext(NonCancellable) {
                        captureStore.abandonSession(open.sessionId)
                        stopSelf(startId)
                    }
                }
            }
        }
    }

    /**
     * Stop capturing: last buffer out, close the session, drop the link.
     *
     * **Every step is guarded and the exit is a `finally`.** A `SecurityException`
     * from `disconnect()` — `BLUETOOTH_CONNECT` revoked while a capture was
     * running is the realistic way to get one — used to skip the teardown
     * entirely, leaking the wake lock and the foreground notification, and
     * leaving the stop flag set so every later attempt was refused as a
     * duplicate. The capture then could not be stopped at all.
     *
     * The in-flight startup is cancelled **and joined** before anything else.
     * Without the join, a startup suspended in its session write would resume
     * after the teardown had already looked for a connection to close, and open
     * one afterwards.
     */
    private fun stopCapture() {
        if (!lifecycle.claimStop()) return
        serviceScope.launch {
            try {
                // Cancel and wait: nothing may publish a session or open a link
                // after the stop has been decided.
                runCatching { startupJob?.cancelAndJoin() }
                startupJob = null

                val result = try {
                    captureStore.stopSession(intervalBuffer::flush)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A closed gate is the realistic one: a server switch
                    // confirmed mid-capture. The rows are about to be wiped with
                    // their table, so this is treated exactly like a flush that
                    // never landed.
                    bleLog.log("capture stop FAILED: ${e.javaClass.simpleName}")
                    CaptureStopResult.LEFT_OPEN
                }
                if (bufferTimerStops(result)) intervalBuffer.stop()
                bleLog.log("capture stopped ($result, ${storedRows.get()} rows this run)")

                disconnectQuietly()
            } finally {
                finishTeardown()
            }
        }
    }

    /**
     * Drop the GATT link without letting its failure escape.
     *
     * `disconnect()` reaches the Bluetooth stack, so it can throw a
     * `SecurityException` for a revoked permission or an `IllegalStateException`
     * from an adapter that has been turned off underneath it. Neither is
     * recoverable and neither may stop the teardown: the link is going away with
     * the process either way, and the wake lock is not.
     */
    private suspend fun disconnectQuietly() {
        val conn = connection ?: return
        try {
            conn.disconnect()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            bleLog.log("disconnect failed during teardown: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Release everything and stop the service. Idempotent, and never throws.
     *
     * The single exit for every path out of a capture — a refused foreground
     * promotion, a failed startup, an aborted one, a normal stop — so that
     * "the wake lock is released and the service goes away" has exactly one
     * implementation to get right.
     */
    private fun finishTeardown() {
        releaseCaptureResources()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    /**
     * Idempotent teardown, shared by the normal stop and by system-initiated
     * destruction. The shared state flow must never be left holding a dead
     * service's session, and [CaptureLifecycle.released] must be reached however
     * this was entered — a claim left standing refuses every later stop.
     *
     * Nothing in here throws. It is called from `finally` blocks whose whole
     * purpose is to run when something already went wrong.
     */
    private fun releaseCaptureResources() {
        collectJob?.cancel()
        collectJob = null
        stateJob?.cancel()
        stateJob = null
        sessionJob?.cancel()
        sessionJob = null
        detailJob?.cancel()
        detailJob = null
        startupJob?.cancel()
        startupJob = null
        cancelInactivityTimer()
        connection = null
        startedSessionId = null
        storedRows.set(0)
        lifecycle.released()
        releaseWakeLock()
        captureState.value = HrCaptureState()
    }

    private fun armInactivityTimer() {
        if (inactivityJob?.isActive == true) return
        inactivityJob = serviceScope.launch {
            delay(InactivityPolicy.TIMEOUT)
            bleLog.log("capture inactive for ${InactivityPolicy.TIMEOUT} — stopping")
            stopCapture()
        }
    }

    private fun cancelInactivityTimer() {
        inactivityJob?.cancel()
        inactivityJob = null
    }

    /**
     * @return false when the platform refused the promotion, which is terminal
     *   for this start — a service that cannot go foreground has seconds to live
     */
    private fun promoteToForeground(state: HrCaptureState): Boolean = try {
        startForeground(
            HrCaptureNotification.NOTIFICATION_ID,
            notification.build(state),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        true
    } catch (e: ServiceStartNotAllowedException) {
        // Covers the whole family: a background start the platform refuses, and
        // a foreground service type it considers missing or invalid.
        bleLog.log("foreground start not allowed: ${e.javaClass.simpleName}")
        false
    } catch (e: SecurityException) {
        // The connectedDevice type needs a Bluetooth grant, and it can be
        // revoked between the gate check and here.
        bleLog.log("foreground start refused: ${e.javaClass.simpleName}")
        false
    }

    /**
     * Apply an update to the shared state and redraw the notification.
     *
     * Atomic, because two collectors write it from different dispatchers — the
     * sample stream on [Dispatchers.Default] and the connection state on the
     * main thread — and a read-modify-write between them would drop one.
     *
     * Updates are ignored once the capture is torn down. A sample already in
     * flight when the collector was cancelled must not resurrect a finished
     * session: the BPM chip keys its visibility off `isRunning`, so a stale
     * true would leave a ghost chip on screen with nothing behind it.
     */
    private fun publish(update: (HrCaptureState) -> HrCaptureState) {
        var committed = false
        val updated = captureState.updateAndGet { current ->
            // The lambda can run more than once under contention; the last run
            // is the one that won, so this ends up describing that attempt.
            committed = current.isRunning
            if (current.isRunning) update(current) else current
        }
        if (!notificationFollows(committed, updated, captureState.value)) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(HrCaptureNotification.NOTIFICATION_ID, notification.build(updated))
        // Paint, then verify. A teardown that landed between the check above and
        // the call just above would have removed the notification and had it put
        // straight back — and a teardown *plus a restart* would have left this
        // capture's content sitting on the next capture's notification.
        val live = captureState.value
        when (paintVerdict(updated, live)) {
            PaintVerdict.KEEP -> Unit
            PaintVerdict.CANCEL -> manager.cancel(HrCaptureNotification.NOTIFICATION_ID)
            PaintVerdict.REPAINT ->
                manager.notify(HrCaptureNotification.NOTIFICATION_ID, notification.build(live))
        }
    }

    /**
     * A BPM survives a reconnect attempt but not a confirmed disconnect: the
     * notification must never show a stale number as though it were live.
     */
    private fun bpmFor(state: ConnectionState, current: Int?): Int? =
        if (state == ConnectionState.DISCONNECTED) null else current

    private fun hasConnectPermission(): Boolean =
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /**
     * No timeout on the wake lock: it is released by [releaseCaptureResources]
     * on every path out of a capture, including [onDestroy], and a capture is
     * legitimately longer than any timeout worth setting.
     */
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            ?.apply { acquire() }
    }

    /**
     * Guarded because `release()` throws when the lock is already released — a
     * real possibility once teardown can be entered twice — and a teardown that
     * threw here would skip `stopSelf` and leave the service running.
     */
    private fun releaseWakeLock() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
    }

    override fun onDestroy() {
        // System-initiated destruction bypasses stopCapture entirely. The buffer
        // is NOT stopped: it runs on its own app-lived scope and keeps retrying
        // to persist whatever rows this service left behind.
        try {
            connection?.let { conn ->
                bleLog.log("capture destroyed with an active link — emergency teardown")
                // disconnect() suspends but does no blocking work: it cancels
                // jobs and closes the GATT handle. Guarded for the same reason
                // the stop path's is — a revoked Bluetooth grant must not take
                // the wake lock down with it.
                runCatching { runBlocking { conn.disconnect() } }
            }
        } finally {
            releaseCaptureResources()
            serviceScope.cancel()
            super.onDestroy()
        }
    }

    companion object {
        const val EXTRA_DEVICE_ADDRESS = "hr.deviceAddress"
        const val EXTRA_DEVICE_NAME = "hr.deviceName"
        const val EXTRA_WORKOUT_DATE = "hr.workoutDate"
        const val EXTRA_WORKOUT_SESSION_ID = "hr.workoutSessionId"
        const val ACTION_STOP = "dev.jtiisto.wellness.hr.action.STOP_CAPTURE"

        private const val WAKE_LOCK_TAG = "wellness:hr-capture"

        /**
         * @param workoutDate local `YYYY-MM-DD`, set when capture was started
         *   from the Start Workout sheet
         * @param workoutSessionId the coach workout-hook session id, when there
         *   is one. Omitted rather than sent as a sentinel — an Intent extra
         *   cannot carry a null Long.
         */
        fun startIntent(
            context: Context,
            address: String,
            name: String?,
            workoutDate: String? = null,
            workoutSessionId: Long? = null,
        ): Intent = Intent(context, HrCaptureService::class.java).apply {
            putExtra(EXTRA_DEVICE_ADDRESS, address)
            putExtra(EXTRA_DEVICE_NAME, name ?: address)
            workoutDate?.let { putExtra(EXTRA_WORKOUT_DATE, it) }
            workoutSessionId?.let { putExtra(EXTRA_WORKOUT_SESSION_ID, it) }
        }

        fun stopIntent(context: Context): Intent =
            Intent(context, HrCaptureService::class.java).apply { action = ACTION_STOP }
    }
}

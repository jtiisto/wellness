package dev.jtiisto.wellness.hr

import android.Manifest
import android.app.ServiceStartNotAllowedException
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import dev.jtiisto.wellness.core.ble.capture.HrCaptureController
import dev.jtiisto.wellness.core.data.sync.SyncErrorEvents

/**
 * [HrCaptureController] over [HrCaptureService], and the only place in the app
 * that builds a capture Intent.
 *
 * Device-only glue by construction: platform calls and no decision of its own.
 * What the callers actually decide — whether to offer a capture at all, which
 * strap, whether a workout anchors it — is upstream in `StrapViewModel` and
 * `CoachViewModel`, where it is unit-tested; what a refusal *says* is
 * [CaptureNoticeCopy], where it is likewise.
 *
 * **Both platform calls are guarded, and the start one is the load-bearing
 * guard.** `startForegroundService` throws in the caller's stack, before the
 * service exists and therefore before any of the service's own handling can run
 * — so an unguarded call here crashes the app on exactly the two conditions
 * `HrCaptureService.promoteToForeground` was written to survive.
 */
class ServiceHrCaptureController(
    private val context: Context,
    private val errors: SyncErrorEvents,
) : HrCaptureController {

    override fun canStart(): Boolean =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /**
     * @return false when the platform refused the start, in which case the user
     *   has already been told why. The same two exception types the service's
     *   own foreground promotion handles, caught here because here is where they
     *   are actually thrown for a service that has not started yet.
     */
    override fun start(
        address: String,
        name: String?,
        workoutDate: String?,
        workoutSessionId: Long?,
    ): Boolean = try {
        context.startForegroundService(
            HrCaptureService.startIntent(context, address, name, workoutDate, workoutSessionId),
        )
        true
    } catch (e: ServiceStartNotAllowedException) {
        // Covers ForegroundServiceStartNotAllowedException under it: the app
        // stopped being foreground between the tap and the dispatch.
        refuse(CaptureStartRefusal.NOT_ALLOWED, e)
    } catch (e: SecurityException) {
        // The connectedDevice type needs a Bluetooth grant, and it can be
        // revoked between `canStart` and here.
        refuse(CaptureStartRefusal.PERMISSION, e)
    }

    /**
     * Say so, and say it from the refusal rather than from [e].
     *
     * The exception contributes its class name to logcat and nothing else —
     * `HrCaptureService.promoteToForeground` logs the same family the same way,
     * and a `Throwable.message` is the one field neither of them may forward.
     */
    private fun refuse(refusal: CaptureStartRefusal, e: Exception): Boolean {
        Log.w(TAG, "capture start refused by the platform: ${e.javaClass.simpleName}")
        errors.postMessage(CaptureNoticeCopy.startRefused(refusal))
        return false
    }

    /**
     * `startService`, not `startForegroundService`: this intent's whole job is
     * to make the service stop, and promising a `startForeground` call that the
     * stop path deliberately never makes is how a service gets killed for
     * timing out instead of shutting down.
     *
     * Wrapped because the platform refuses a background service start. Every
     * call site here is a foreground tap on a control that is only drawn while a
     * capture is running, so the throw is unreachable in practice — and if it
     * ever were reached, the service it failed to reach is not running either.
     */
    override fun stop() {
        runCatching { context.startService(HrCaptureService.stopIntent(context)) }
    }

    private companion object {
        const val TAG = "HrCapture"
    }
}

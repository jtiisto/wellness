package dev.jtiisto.wellness.hr

/** Why a capture start was refused, or [NONE] when it was not. */
enum class CaptureRefusal {
    NONE,

    /** The intent carried no strap address, and no open session could supply one. */
    MISSING_DEVICE,

    /** `BLUETOOTH_CONNECT` is not granted, so the GATT connect would throw. */
    MISSING_BLUETOOTH_PERMISSION,

    /**
     * The app has not resolved which server it talks to. Capture would produce
     * rows destined for a server nobody has chosen yet.
     */
    SERVER_UNRESOLVED,
}

/**
 * Whether the capture service may start, checked at the top of every start path.
 *
 * The UI is supposed to make all three true before it ever sends the intent —
 * the pairing flow requests the permission, the sheet only offers a known strap,
 * and an unresolved server puts the app on its recovery screen. This is the
 * defensive second check, and it exists because the paths that reach the service
 * without passing through that UI are real: a `START_STICKY` restart after a
 * process kill, and a permission revoked while a session was running.
 *
 * The server check is the least obvious and the most load-bearing.
 * `HrCaptureStore`'s upload nudge resolves `HrSyncStore`, which resolves the
 * server config, which **throws** when nothing is resolved — so a service that
 * started without it would die on its first buffer flush, after the wake lock
 * and the foreground notification were already up.
 */
object CaptureStartGate {

    fun evaluate(
        address: String?,
        hasConnectPermission: Boolean,
        serverResolved: Boolean,
    ): CaptureRefusal = when {
        address.isNullOrBlank() -> CaptureRefusal.MISSING_DEVICE
        !hasConnectPermission -> CaptureRefusal.MISSING_BLUETOOTH_PERMISSION
        !serverResolved -> CaptureRefusal.SERVER_UNRESOLVED
        else -> CaptureRefusal.NONE
    }

    /** The debug-log line for a refusal. Never shown to the user. */
    fun reason(refusal: CaptureRefusal): String = when (refusal) {
        CaptureRefusal.NONE -> "start permitted"
        CaptureRefusal.MISSING_DEVICE -> "refused: no strap address"
        CaptureRefusal.MISSING_BLUETOOTH_PERMISSION -> "refused: BLUETOOTH_CONNECT not granted"
        CaptureRefusal.SERVER_UNRESOLVED -> "refused: no server resolved"
    }
}

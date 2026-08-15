package dev.jtiisto.wellness.hr

import dev.jtiisto.wellness.core.ble.capture.HrCaptureState
import dev.jtiisto.wellness.core.ble.model.ConnectionState

/**
 * What the ongoing capture notification says.
 *
 * Split out of the builder because the builder is device-only glue and this is
 * not: the notification is the *only* surface a capture has while the app is
 * closed, and it has to answer "is this still working?" at a glance. Getting it
 * wrong is invisible in a screenshot and obvious at the gym.
 *
 * Literal strings rather than resources, as everywhere else in this app — there
 * is one locale and `stringResource` appears nowhere in the codebase.
 */
object HrCaptureNotificationText {

    /** Constant: the strap's name belongs in the body, where it can change. */
    const val TITLE: String = "Heart rate capture"

    /**
     * Connection state, plus the live BPM once there is one.
     *
     * BPM only appears under [ConnectionState.CONNECTED]. A number left over
     * from before a dropout would be the worst thing this line could show —
     * a stale reading that looks live is indistinguishable from a working strap.
     */
    fun contentText(state: HrCaptureState): String = when (state.connectionState) {
        ConnectionState.CONNECTED -> state.bpm?.let { "Connected — $it bpm" } ?: "Connected"
        ConnectionState.SCANNING -> "Scanning…"
        ConnectionState.CONNECTING -> "Connecting…"
        ConnectionState.RECONNECTING -> "Reconnecting…"
        ConnectionState.DISCONNECTED -> "Disconnected"
    }

    /**
     * The strap being recorded, for the second line — its name, falling back to
     * the address, and absent entirely before a device is known.
     */
    fun subText(state: HrCaptureState): String? =
        state.deviceName?.takeIf { it.isNotBlank() } ?: state.deviceAddress
}

package dev.jtiisto.wellness.core.ui.hr

import dev.jtiisto.wellness.core.ble.capture.HrCaptureState
import dev.jtiisto.wellness.core.ble.model.ConnectionState
import dev.jtiisto.wellness.core.ble.quality.SignalQuality
import dev.jtiisto.wellness.core.ble.quality.SignalQualityLevel

/**
 * How healthy the strap link is — the one thing the chip's colour says.
 *
 * Three tones rather than five states, because the two that differ only in *why*
 * we are waiting ([ConnectionState.CONNECTING] and
 * [ConnectionState.RECONNECTING]) look identical to someone glancing at a number
 * mid-set. The distinction survives in [HrCaptureDisplay.connectionText], where
 * there is room to read it.
 */
enum class HrTone {
    /** Beats are arriving. */
    LIVE,

    /** Connecting, reconnecting, or scanning — no verdict yet. */
    WAITING,

    /** The link is down while a capture is still open. */
    LOST,
}

/** Everything the BPM chip and the capture sheet draw, already resolved to text. */
data class HrCaptureDisplay(
    val bpmText: String,
    val tone: HrTone,
    val deviceName: String,
    val connectionText: String,
    val detail: String?,
    val qualityText: String?,
)

/**
 * The capture as the UI shows it, or **null when nothing is capturing**.
 *
 * That null is the spec's "no chip when idle" rule, expressed once. Making
 * absence the same fact as "there is nothing to draw" is what stops the
 * visibility test and the content from drifting apart later.
 */
fun hrCaptureDisplay(state: HrCaptureState): HrCaptureDisplay? {
    if (!state.isRunning) return null
    return HrCaptureDisplay(
        // A capture that has not had its first beat yet shows a placeholder
        // rather than a zero: zero is a heart rate, and a wrong one. So is a
        // number the strap stopped confirming — the same placeholder, for as
        // long as the stream stays quiet, which includes the whole reconnect
        // that a stale stream triggers.
        bpmText = state.bpm?.takeUnless { state.isStreamStale }?.toString()
            ?: HrCaptureCopy.NO_READING,
        tone = toneFor(state.connectionState, state.isStreamStale),
        // The address is the fallback the whole naming scheme rests on — an
        // unnamed advertisement is still a strap you connected to on purpose.
        deviceName = state.deviceName?.takeIf { it.isNotBlank() }
            ?: state.deviceAddress
            ?: HrCaptureCopy.UNKNOWN_DEVICE,
        connectionText = connectionText(state.connectionState),
        detail = state.detail?.takeIf { it.isNotBlank() },
        // Nothing is rated while nothing is arriving. The last verdict is the
        // one thing a frozen stream must not keep asserting — "Good signal ·
        // 100% RR coverage" over a dead strap is the most confident lie the
        // sheet can tell. It comes back, honestly, on the first beat after the
        // outage: that sample carries a gap marker, which vetoes its window.
        qualityText = if (state.isStreamStale) null else qualityText(state.signalQuality),
    )
}

/**
 * Staleness can only take the tone *away* from [HrTone.LIVE]. A link that is
 * already saying something worse — down, backing off — keeps its own word; the
 * stream having gone quiet is not news on top of that.
 */
private fun toneFor(state: ConnectionState, streamStale: Boolean): HrTone = when (state) {
    ConnectionState.CONNECTED -> if (streamStale) HrTone.WAITING else HrTone.LIVE
    ConnectionState.CONNECTING, ConnectionState.RECONNECTING, ConnectionState.SCANNING -> HrTone.WAITING
    ConnectionState.DISCONNECTED -> HrTone.LOST
}

private fun connectionText(state: ConnectionState): String = when (state) {
    ConnectionState.CONNECTED -> "Connected"
    ConnectionState.CONNECTING -> "Connecting…"
    ConnectionState.RECONNECTING -> "Reconnecting…"
    ConnectionState.SCANNING -> "Scanning…"
    ConnectionState.DISCONNECTED -> "Disconnected"
}

/**
 * Signal quality, or null while the tracker is still filling its window.
 *
 * [SignalQualityLevel.MEASURING] is not a verdict and must not be drawn as one:
 * a row saying "Measuring" beside a live BPM reads as a fault when it only means
 * the first few seconds have not passed.
 */
private fun qualityText(quality: SignalQuality): String? {
    val level = when (quality.level) {
        SignalQualityLevel.MEASURING -> return null
        SignalQualityLevel.POOR -> "Poor"
        SignalQualityLevel.FAIR -> "Fair"
        SignalQualityLevel.GOOD -> "Good"
    }
    return "$level signal · ${quality.rrCoveragePercent}% RR coverage"
}

object HrCaptureCopy {
    const val NO_READING = "—"
    const val UNKNOWN_DEVICE = "Heart-rate strap"

    /** The chip's accessible name; the visible chip is a bare number. */
    fun chipDescription(display: HrCaptureDisplay): String =
        "Heart rate ${display.bpmText}, ${display.connectionText.trimEnd('…')}"
}

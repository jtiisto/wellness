package dev.jtiisto.wellness.feature.analysis

import dev.jtiisto.wellness.core.data.sync.DebugLog
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The three strings the Analysis screens show that are not report content.
 *
 * Timestamps are **parsed** here, which is the documented exception to the
 * project's never-parse rule. That rule governs sync tokens, which are compared
 * lexically and must never be interpreted; Analysis has no sync tokens, and a
 * report's `created_at` exists to be read by a person.
 *
 * Nothing in here throws. A history list that crashed on one corrupt cached row
 * would take the whole screen with it, and the row is the only thing at fault.
 */
object AnalysisFormat {

    /**
     * `Aug 9, 2026, 3:05 PM`.
     *
     * en-US is hardcoded, as it is in the PWA. Report bodies are en-US prose
     * written by the model, and a date in another locale beside them would read
     * as a bug rather than as localisation.
     */
    fun formatTimestamp(iso: String?, zone: ZoneId, debugLog: DebugLog? = null): String {
        if (iso.isNullOrBlank()) return ""
        val formatted = timeOrNull { FORMATTER.format(Instant.parse(iso).atZone(zone)) }
        if (formatted == null) debugLog?.log(TAG, "unparseable timestamp")
        return formatted.orEmpty()
    }

    /**
     * Whole seconds between [createdAt] and [nowMillis], never negative.
     *
     * The clamp is not defensive padding: the timestamp comes from the server's
     * clock and the comparison uses the device's, so a few seconds of skew would
     * otherwise render the progress screen counting *up* from a negative number.
     */
    fun elapsedSeconds(createdAt: String?, nowMillis: Long, debugLog: DebugLog? = null): Long {
        if (createdAt.isNullOrBlank()) return 0
        val started = timeOrNull { Instant.parse(createdAt).toEpochMilli() }
        if (started == null) {
            debugLog?.log(TAG, "unparseable created_at")
            return 0
        }
        return ((nowMillis - started) / MILLIS_PER_SECOND).coerceAtLeast(0)
    }

    /**
     * `45s`, `2m 5s`, `90m 0s`.
     *
     * There is no hour case, deliberately — the longest query the server will
     * run is under seven minutes, and PWA parity keeps a runaway one legible as
     * a minute count rather than hiding it behind an hour digit.
     */
    fun formatElapsed(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0)
        if (safe < SECONDS_PER_MINUTE) return "${safe}s"
        return "${safe / SECONDS_PER_MINUTE}m ${safe % SECONDS_PER_MINUTE}s"
    }

    /**
     * The two ways a date string can defeat `java.time`, and only those.
     *
     * Catching them by name rather than catching `Exception` keeps the
     * project's cancellation rule uninteresting here: nothing broader can be
     * swallowed, so there is nothing to reason about.
     */
    private inline fun <T> timeOrNull(block: () -> T): T? = try {
        block()
    } catch (_: DateTimeException) {
        null
    } catch (_: ArithmeticException) {
        null
    }

    private const val TAG = "analysis"
    private const val MILLIS_PER_SECOND = 1_000L
    private const val SECONDS_PER_MINUTE = 60L

    private val FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a", Locale.US)
}

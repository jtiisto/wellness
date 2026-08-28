package dev.jtiisto.wellness.core.data.trends

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The two payloads of the server's `garmin` module — the on-demand sync a Trends
 * pull asks for, and the status it then watches.
 *
 * They live beside the Trends DTOs because Trends is their only consumer, and in
 * a file of their own because they are **not** trends aggregates: the spec's
 * closed "Omitted keys" inventory covers the twelve `/api/trends` endpoints, and
 * these two carry their own optional keys under their own note.
 *
 * Wire shape is snake_case, matching trends rather than hr — the consuming
 * surface decides, and this one is consumed by Trends.
 *
 * **Every optional key here is omitted when absent, never null** (the shared
 * non-negotiable), which is why each carries a default: absence is the value.
 */
@Serializable
data class GarminSyncTrigger(
    /** `started` | `running` | `cooldown` | `unconfigured`. */
    val status: String,
    /**
     * Seconds left on the cooldown window — present only for `cooldown`.
     *
     * Decoded but unused: a cooldown simply skips the watch phase, and the number
     * exists so the contract is stated rather than guessed at. `Double` because
     * it is a remainder off a clock, not a count of anything — the trends typing
     * rule ("`Int` only for things that count") applies here too, and an `Int`
     * property would fail the whole decode the first time the server emits
     * `540.3`.
     */
    @SerialName("retry_in_sec") val retryInSec: Double? = null,
)

@Serializable
data class GarminSyncStatus(
    val running: Boolean,
    /** Epoch millis, integral on the wire (server-confirmed). */
    @SerialName("last_finished_at") val lastFinishedAt: Long? = null,
    /** `ok` | `failed`. */
    @SerialName("last_outcome") val lastOutcome: String? = null,
    /** Epoch millis of garmy's own last successful sync, integral on the wire. */
    @SerialName("last_synced_at") val lastSyncedAt: Long? = null,
)

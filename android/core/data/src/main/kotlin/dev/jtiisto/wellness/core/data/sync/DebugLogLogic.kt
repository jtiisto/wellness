package dev.jtiisto.wellness.core.data.sync

import dev.jtiisto.wellness.core.data.db.DebugLogEntity
import java.time.Instant
import kotlinx.serialization.json.JsonElement

/**
 * The pure retention and formatting rules behind [DebugLog], ported from
 * `debug-log.js`. `DebugLogDao` implements the same predicates in SQL; these
 * are what the tests pin, so the two must be changed together.
 */
object DebugLogLogic {

    /** Ring-buffer cap, `debug-log.js` MAX_ENTRIES. */
    const val MAX_ENTRIES = 500

    /** Retention window, `debug-log.js` TTL_MS. */
    const val TTL_MS = 60L * 60L * 1000L

    /**
     * How often the live view re-applies the TTL filter. Without this an entry
     * visible when collection started could outlive its TTL on screen until
     * the next write happened to prune it.
     */
    const val TTL_RECHECK_MS = 30_000L

    /** Stands in for a `data` value that could not be encoded. */
    const val UNSERIALIZABLE = "\"<unserializable>\""

    /** Oldest timestamp still inside the retention window at [now]. */
    fun ttlCutoff(now: Long): Long = now - TTL_MS

    /** Mirrors `WHERE ts > :cutoff`: strictly newer than the cutoff survives. */
    fun isRetained(ts: Long, cutoff: Long): Boolean = ts > cutoff

    /**
     * `debug-log.js` keeps entries while `(now - ts) < TTL_MS`, so an entry
     * exactly one TTL old is already gone.
     */
    fun isExpired(ts: Long, now: Long): Boolean = !isRetained(ts, ttlCutoff(now))

    /**
     * What the log holds after appending, expressed over a list. Mirrors
     * `DebugLogDao.insertAndPrune`: drop expired rows, then keep the newest
     * [MAX_ENTRIES] by insertion order.
     */
    fun prune(entries: List<DebugLogEntity>, now: Long): List<DebugLogEntity> {
        val live = entries.filter { !isExpired(it.ts, now) }
        return if (live.size <= MAX_ENTRIES) live else live.subList(live.size - MAX_ENTRIES, live.size)
    }

    /**
     * Encode a log payload, substituting [UNSERIALIZABLE] when [encode] fails —
     * a debug line must never be lost to its own attachment.
     */
    fun serializeData(data: JsonElement?, encode: (JsonElement) -> String): String? {
        if (data == null) return null
        return try {
            encode(data)
        } catch (_: Throwable) {
            UNSERIALIZABLE
        }
    }

    /** One dump line: UTC timestamp, tag, message, encoded data. */
    fun formatDumpLine(entry: DebugLogEntity): String = buildString {
        append(Instant.ofEpochMilli(entry.ts))
        append(' ')
        append(entry.tag)
        append(' ')
        append(entry.message)
        entry.dataJson?.let {
            append(' ')
            append(it)
        }
    }
}

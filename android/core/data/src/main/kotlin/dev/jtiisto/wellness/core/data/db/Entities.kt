package dev.jtiisto.wellness.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Raw JSON payloads for the read-only modules (trends, analysis), kept so the
 * app renders something useful offline. Decoded on read, so server field
 * additions never invalidate the cache.
 *
 * [fetchedAt] is a local epoch-millis clock value, not a server SyncStamp.
 */
@Entity(tableName = "payload_cache", primaryKeys = ["module", "key"])
data class PayloadCacheEntity(
    val module: String,
    val key: String,
    val payloadJson: String,
    val fetchedAt: Long,
)

/**
 * One debug-log line. [ts] is local epoch millis; [id] is autoincrement, so it
 * doubles as the insertion order used for newest-first reads and cap pruning.
 */
@Entity(tableName = "debug_log")
data class DebugLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val tag: String,
    val message: String,
    val dataJson: String? = null,
)

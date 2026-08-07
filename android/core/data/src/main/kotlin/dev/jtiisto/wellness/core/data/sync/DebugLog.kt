package dev.jtiisto.wellness.core.data.sync

import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.db.DebugLogDao
import dev.jtiisto.wellness.core.data.db.DebugLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Ring-buffer diagnostics for sync issues — a Room-backed port of
 * `debug-log.js`. Capped at [DebugLogLogic.MAX_ENTRIES] rows with a
 * [DebugLogLogic.TTL_MS] retention window.
 *
 * **Privacy**: never log request or response bodies, headers, or any journal or
 * coach content. HTTP logging is pinned at method + URL + status for exactly
 * this reason; the dump is shareable, the data behind it is not.
 *
 * @param scope app-lived, `SupervisorJob() + Dispatchers.IO` — writes are
 *   fire-and-forget and must never cancel sibling work.
 */
class DebugLog(
    private val dao: DebugLogDao,
    private val scope: CoroutineScope,
    private val json: Json = WellnessJson,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Serializes append+prune so concurrent writers cannot overshoot the cap. */
    private val writeMutex = Mutex()

    /**
     * Append a line. Fire-and-forget: callers never await it and it never
     * throws — a failed write is swallowed, as in the JS original.
     */
    fun log(tag: String, message: String, data: JsonElement? = null) {
        val entry = DebugLogEntity(
            ts = now(),
            tag = tag,
            message = message,
            dataJson = DebugLogLogic.serializeData(data) { json.encodeToString(JsonElement.serializer(), it) },
        )
        scope.launch {
            try {
                writeMutex.withLock {
                    dao.insertAndPrune(entry, DebugLogLogic.ttlCutoff(entry.ts), DebugLogLogic.MAX_ENTRIES)
                }
            } catch (_: Throwable) {
                // Debug logging must not break the app.
            }
        }
    }

    /**
     * Live view, newest first. The TTL filter is re-applied on every database
     * change AND every [DebugLogLogic.TTL_RECHECK_MS] tick, so an entry that
     * expires while the screen is open disappears within one tick even when no
     * write ever prunes it.
     */
    fun entries(): Flow<List<DebugLogEntity>> {
        val ticker = flow {
            while (true) {
                emit(Unit)
                delay(DebugLogLogic.TTL_RECHECK_MS)
            }
        }
        return dao.observeAll()
            .combine(ticker) { list, _ ->
                val cutoff = DebugLogLogic.ttlCutoff(now())
                list.filter { DebugLogLogic.isRetained(it.ts, cutoff) }
            }
            .distinctUntilChanged()
    }

    /** The whole live window, oldest first, one line per entry. */
    suspend fun dump(): String = dao.listSince(DebugLogLogic.ttlCutoff(now()))
        .joinToString(separator = "\n", transform = DebugLogLogic::formatDumpLine)
}

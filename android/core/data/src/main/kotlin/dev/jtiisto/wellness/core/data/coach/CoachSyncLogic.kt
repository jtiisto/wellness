package dev.jtiisto.wellness.core.data.coach

import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.data.network.SyncStamp
import dev.jtiisto.wellness.core.data.sync.DirtySetLogic
import dev.jtiisto.wellness.core.data.sync.DirtyState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The pure decision functions of coach sync — a 1:1 port of
 * `public/js/coach/sync-logic.js`, pinned by `CoachSyncLogicTest` (the
 * transcribed `coach-sync-logic.test.js`).
 *
 * No Room, no Ktor, no clock: `(state, inputs) -> nextState | decision`. The
 * store keeps the read → call → write wrappers.
 *
 * Everything operates on the raw wire day as a [JsonObject], because the wire
 * unit *is* the day: arbitrary exercise keys alongside `session_feedback` and
 * `_`-prefixed meta. Key order is preserved throughout — the upload has to be
 * reproducible byte for byte, and these functions build it.
 *
 * Coach arbitration is per record inside that day, on opaque server-issued
 * `_lastModified` tokens echoed back as `_baseLastModifiedAt`.
 */

/**
 * The day's feedback record. It is arbitrated on the DAY token rather than one
 * of its own, which is why the two always move together.
 */
private const val SESSION_FEEDBACK_KEY = "session_feedback"

/** The base token a record echoes on upload. The server consumes it. */
private const val BASE_TOKEN_KEY = "_baseLastModifiedAt"

/** Keys that are never an exercise entry, whatever their value. */
private val NON_EXERCISE_KEYS = setOf("_lastModifiedAt", "_lastModifiedBy", SESSION_FEEDBACK_KEY)

// ---- content predicates --------------------------------------------------

/**
 * Does the day carry real logged data? Completion is derived from data now, so
 * an exercise has content when it has logged sets, checked items, or a
 * duration — and `duration_min: 0` counts, which is why the check is
 * "present and not null" rather than truthiness.
 */
fun logHasExerciseContent(log: JsonObject): Boolean = log.any { (key, value) ->
    if (key in NON_EXERCISE_KEYS) return@any false
    val entry = value as? JsonObject ?: return@any false
    entry.arrayLength("sets") > 0 ||
        entry.arrayLength("completed_items") > 0 ||
        entry["duration_min"].let { it != null && it != JsonNull }
}

/**
 * An entry the user deliberately deleted — a local tombstone awaiting upload.
 * The server hard-deletes the row and the adopted `results` day (which lacks
 * the key) clears the tombstone.
 */
fun isDeletedEntry(entry: JsonElement?): Boolean {
    val obj = entry as? JsonObject ?: return false
    return obj["_deleted"].isTruthy()
}

/**
 * A day carrying at least one pending local tombstone. Such a day must upload
 * even when it has no other content and was never synced — the delete intent
 * has to reach the server.
 */
fun logHasPendingDeletions(log: JsonObject): Boolean = log.values.any(::isDeletedEntry)

/**
 * Merge a content write into one exercise entry. Normally a shallow merge over
 * the existing entry — EXCEPT when the existing entry is a pending delete
 * tombstone: writing over a tombstone is a deliberate RE-ADD. The `_deleted`
 * flag is dropped (a plain spread would keep it and the server would process
 * the re-added session as a deletion, silently discarding it), the tombstone's
 * server stamp is KEPT, and the entry is marked `_readd: true`:
 *  - delete not yet uploaded → the server row still exists; the kept stamp is
 *    the base token that lets the re-add win as a normal update (a token-less
 *    create would be rejected as a hard cutover and the OLD session would come
 *    back).
 *  - delete already accepted server-side → the row is gone and a server
 *    tombstone exists; `_readd` tells the resurrection guard this client SAW
 *    the delete (it authored it), so the insert is accepted and the tombstone
 *    cleared — while stale edits without the marker stay rejected.
 *
 * `_readd` is transient: the server never stores or echoes it, so adopting any
 * sync result clears it. Pure; returns a new log.
 */
fun withEntryUpdated(log: JsonObject, exerciseId: String, data: JsonObject): JsonObject {
    val previous = log[exerciseId]
    if (!isDeletedEntry(previous)) {
        val merged = buildJsonObject {
            (previous as? JsonObject)?.forEach { (key, value) -> put(key, value) }
            data.forEach { (key, value) -> put(key, value) }
        }
        return JsonObject(log + (exerciseId to merged))
    }
    val tombstone = previous as JsonObject
    val readd = buildJsonObject {
        data.forEach { (key, value) -> put(key, value) }
        put("_readd", true)
        tombstone["_lastModified"]?.takeIf { it.isTruthy() }?.let { put("_lastModified", it) }
    }
    return JsonObject(log + (exerciseId to readd))
}

/**
 * Delete one exercise entry from a day's log: the entry becomes a `_deleted`
 * tombstone. A synced entry keeps its server `_lastModified` stamp so
 * [withBaseTokens] echoes it and the server arbitrates the delete against
 * concurrent edits. An UNSTAMPED entry (uploaded but the response was lost, or
 * never uploaded) still becomes a tombstone — without a base token the server
 * rejects the delete against an existing row (hard cutover) and returns the
 * row, which the client adopts; the user deletes once more with the fresh stamp
 * and converges. Removing the key instead silently resurrected the server's
 * copy on the next pull with no local record of the delete intent.
 *
 * Returns the **same instance** when there is nothing to delete. Callers rely
 * on that identity to skip the write entirely rather than dirty a day they did
 * not change.
 */
fun withEntryDeleted(log: JsonObject, exerciseId: String): JsonObject {
    val entry = log[exerciseId]
    if (entry == null || entry is JsonNull || entry is JsonPrimitive) return log
    val stamp = (entry as? JsonObject)?.get("_lastModified")?.takeIf { it.isTruthy() }
    val tombstone = buildJsonObject {
        put("_deleted", true)
        if (stamp != null) put("_lastModified", stamp)
    }
    return JsonObject(log + (exerciseId to tombstone))
}

fun hasFeedbackContent(log: JsonObject): Boolean {
    val feedback = log["session_feedback"] as? JsonObject ?: return false
    return feedback.hasTrimmedText("pain_discomfort") || feedback.hasTrimmedText("general_notes")
}

/**
 * A log is worth uploading if it carries real exercise data OR non-empty
 * session feedback. Feedback-only logs upload safely because the server
 * arbitrates per record: a feedback-only payload updates only the feedback
 * record and never touches the day's already-logged exercises.
 */
fun logHasUploadableContent(log: JsonObject): Boolean =
    logHasExerciseContent(log) || hasFeedbackContent(log)

/**
 * A log the server already knows: its day stamp was adopted from a previous
 * sync. An EMPTY log in this state expresses deletion — the user logged
 * content, synced, then removed it — and must still be uploaded so the server
 * clears its copy (each emptied record carries its base token, so per-record
 * arbitration protects against clobbering newer remote data). Without this, an
 * emptied synced day was skipped forever: dirty flag stuck red, the poll
 * re-syncing in a loop, and the server keeping deleted sets.
 */
fun logIsSyncedToServer(log: JsonObject): Boolean = log["_lastModified"].isTruthy()

// ---- dirty-date state transitions ----------------------------------------

/**
 * Decide which dirty dates to clear after an upload. Delegates to the shared
 * generation algorithm — one implementation for journal trackers, journal
 * entries and coach dates.
 */
fun nextDirtyAfterApply(
    appliedDates: Collection<DateString>,
    snapshotGens: Map<DateString, Long>?,
    dirtyDates: List<DateString>,
    dirtyDateGenerations: Map<DateString, Long>,
): DirtyState = DirtySetLogic.clearApplied(
    state = DirtyState(dirtyDates, dirtyDateGenerations),
    appliedKeys = appliedDates,
    snapshotGens = snapshotGens,
)

/** What [selectLogsToUpload] decided about the dirty set. */
data class SelectedUploads(
    val logsToUpload: Map<DateString, JsonObject> = emptyMap(),
    val uploadedDates: List<DateString> = emptyList(),
    val unsatisfiableDates: List<DateString> = emptyList(),
)

/**
 * Build the upload set from dirty dates + local logs. A date's log is included
 * when it carries uploadable content, when the server already knows the day
 * (token-bearing empty log = deletion update), or when it holds a pending
 * tombstone. [SelectedUploads.uploadedDates] is exactly the set actually sent —
 * the invariant that keeps "dirty cleared == sent".
 *
 * [SelectedUploads.unsatisfiableDates] are dirty dates that can NEVER become
 * uploadable: the log is missing entirely (window-pruned), or empty and never
 * synced (nothing to send and nothing server-side to delete). Leaving them
 * dirty wedges the client — permanent red status and a full sync every poll —
 * so the caller drops them from the dirty set, with a debug breadcrumb.
 */
fun selectLogsToUpload(
    dirtyDates: Collection<DateString>,
    localLogs: Map<DateString, JsonObject>,
): SelectedUploads {
    val logsToUpload = LinkedHashMap<DateString, JsonObject>()
    val unsatisfiableDates = mutableListOf<DateString>()
    for (date in dirtyDates) {
        val log = localLogs[date]
        if (log == null) {
            unsatisfiableDates += date // pruned out of the window
            continue
        }
        if (logHasUploadableContent(log) || logIsSyncedToServer(log) || logHasPendingDeletions(log)) {
            logsToUpload[date] = withBaseTokens(log)
        } else {
            unsatisfiableDates += date // empty + never synced
        }
    }
    return SelectedUploads(
        logsToUpload = logsToUpload,
        uploadedDates = logsToUpload.keys.toList(),
        unsatisfiableDates = unsatisfiableDates,
    )
}

/**
 * Echo the last-seen server stamps as base tokens for upload: the day's
 * `_lastModified` becomes the feedback record's `_baseLastModifiedAt`, and each
 * exercise's `_lastModified` becomes that exercise's. A record with no stamp (a
 * brand-new local date or exercise) omits the token, so the server inserts it.
 * The original `_lastModified` keys stay in the payload — the server ignores
 * the day-level one by its non-dict check.
 */
fun withBaseTokens(log: JsonObject): JsonObject = buildJsonObject {
    for ((key, value) in log) {
        val entry = value as? JsonObject
        val stamp = entry?.get("_lastModified")?.takeIf { it.isTruthy() }
        if (entry != null && stamp != null) {
            put(
                key,
                buildJsonObject {
                    entry.forEach { (entryKey, entryValue) -> put(entryKey, entryValue) }
                    put("_baseLastModifiedAt", stamp)
                },
            )
        } else {
            put(key, value)
        }
    }
    log["_lastModified"]?.takeIf { it.isTruthy() }?.let { put("_baseLastModifiedAt", it) }
}

/**
 * Adopt the merged server day returned per uploaded date. For each date NOT
 * re-modified mid-sync (its generation still matches [snapshotGens]), replace
 * the local log with the reconciled server row — which embeds each record's
 * fresh `_lastModified` token. A re-modified date stays dirty and re-uploads
 * next cycle, and is reconciled one record at a time — see
 * [advanceRecordTokens], where the difference between "the user re-edited this"
 * and "the server ruled on this" is decided.
 *
 * Two nullabilities are load-bearing and different:
 * - [snapshotGens] `null` disables re-modification detection entirely, so every
 *   result adopts wholesale. An **empty map is not the same thing**: against it
 *   any date with a live generation compares unequal and counts as re-modified.
 * - [uploadedLogs] `null` means nothing is known to have been uploaded, so no
 *   record of a re-modified date may have a verdict applied to it: every one is
 *   treated as touched mid-sync and its local content preserved.
 */
fun adoptUploadResults(
    localLogs: Map<DateString, JsonObject>,
    results: Map<DateString, JsonElement>?,
    snapshotGens: Map<DateString, Long>?,
    dirtyDateGenerations: Map<DateString, Long>,
    uploadedLogs: Map<DateString, JsonObject>? = null,
): Map<DateString, JsonObject> {
    if (results == null) return localLogs
    val next = LinkedHashMap(localLogs)
    for ((date, serverRow) in results) {
        if (serverRow !is JsonObject) continue
        val reModified = snapshotGens != null && dirtyDateGenerations[date] != snapshotGens[date]
        next[date] = if (reModified) {
            // The generation says the DAY moved, not which record did, so the
            // verdict is applied per record — see [advanceRecordTokens].
            advanceRecordTokens(localLogs[date] ?: JsonObject(emptyMap()), serverRow, uploadedLogs?.get(date))
        } else {
            serverRow
        }
    }
    return next
}

/**
 * Apply the server's arbitration to a date the user re-modified mid-sync,
 * record by record.
 *
 * Re-modification is a property of the DATE — one generation counter for the
 * whole day — so it says nothing about WHICH record the user touched. Deciding
 * per record is the whole of this function, and getting it wrong destroys data:
 *
 * - A record that was uploaded and is **still byte-identical to what went on
 *   the wire** has been arbitrated, and the server row is its verdict, so adopt
 *   it. Merely advancing such a record's token to the winning stamp left stale
 *   local content sitting behind a fresh base: next cycle it passed the
 *   server's `stored <= base` test and overwrote the other client's newer edit,
 *   silently and on every device.
 * - A record that genuinely differs from the uploaded copy IS the mid-sync
 *   re-edit. Keep it (the date stays dirty and re-uploads) and ADVANCE its
 *   token, or the retry would echo the stale pre-sync base that the
 *   now-advanced server rejects — losing the re-edit.
 *
 * Two records do not follow the plain rule:
 * - `session_feedback` is arbitrated on the DAY token, so its verdict and that
 *   token move together. The day token advances either way: with the verdict
 *   when the feedback settled, ahead of the re-edit when it did not.
 * - A tombstone's token is **never** advanced. Either its verdict applies — the
 *   server row carrying the key means the delete was REJECTED (a newer remote
 *   edit won), so adopt the surviving record; the key being absent means it was
 *   ACCEPTED, so drop the tombstone — or it was created mid-sync, has not been
 *   arbitrated at all, and waits verbatim with its original base for the next
 *   cycle.
 */
private fun advanceRecordTokens(
    localLog: JsonObject,
    serverRow: JsonObject,
    uploadedLog: JsonObject?,
): JsonObject {
    val out = LinkedHashMap<String, JsonElement>(localLog)
    if (wasArbitrated(localLog, uploadedLog, SESSION_FEEDBACK_KEY)) {
        applyVerdict(out, serverRow, SESSION_FEEDBACK_KEY)
    }
    serverRow["_lastModified"]?.takeIf { it.isTruthy() }?.let { out["_lastModified"] = it }

    for ((key, value) in localLog) {
        if (key in NON_EXERCISE_KEYS || value !is JsonObject) continue
        if (wasArbitrated(localLog, uploadedLog, key)) {
            applyVerdict(out, serverRow, key)
            continue
        }
        if (isDeletedEntry(value)) continue // unarbitrated tombstone: base untouched
        val stamp = (serverRow[key] as? JsonObject)?.get("_lastModified")?.takeIf { it.isTruthy() }
            ?: continue
        out[key] = buildJsonObject {
            value.forEach { (entryKey, entryValue) -> put(entryKey, entryValue) }
            put("_lastModified", stamp)
        }
    }
    return JsonObject(out)
}

/**
 * Was this record part of the upload and untouched since it was built? The
 * uploaded copy differs from the stored one by exactly the echoed base token,
 * so stripping that makes the two comparable.
 *
 * A null [uploadedLog] means nothing is known to have been sent, and a record
 * absent from it was created after the payload was built. Neither has been
 * arbitrated, so neither may have a verdict applied.
 */
private fun wasArbitrated(localLog: JsonObject, uploadedLog: JsonObject?, key: String): Boolean {
    val uploaded = uploadedLog?.get(key) as? JsonObject ?: return false
    val local = localLog[key] ?: return false
    return JsonObject(uploaded - BASE_TOKEN_KEY) == local
}

/**
 * Take the server's outcome for one record: its reconciled row, or — when the
 * merged day does not carry the key at all — its removal.
 */
private fun applyVerdict(out: MutableMap<String, JsonElement>, serverRow: JsonObject, key: String) {
    val record = serverRow[key]
    if (record is JsonObject) out[key] = record else out.remove(key)
}

// ---- window pruning + plan version ---------------------------------------

/** Keep only entries whose date key is >= [cutoff] (the server's window boundary). */
fun <T> pruneOlderThan(dateKeyedMap: Map<DateString, T>, cutoff: DateString): Map<DateString, T> =
    dateKeyedMap.filterKeys { it >= cutoff }

/** Latest plan `_lastModified` across [plans], never below [currentMax]. */
fun maxPlanVersion(plans: Map<DateString, JsonObject>, currentMax: SyncStamp?): SyncStamp? {
    var max = currentMax
    for (plan in plans.values) {
        val version = plan["_lastModified"].stampOrNull() ?: continue
        if (max == null || version > max) max = version
    }
    return max
}

// ---- JSON helpers --------------------------------------------------------

/**
 * JavaScript truthiness, which the ported predicates are written in terms of:
 * absent, null, `false`, `0` and `""` are falsy; every object and array is
 * truthy.
 */
private fun JsonElement?.isTruthy(): Boolean = when (this) {
    null, JsonNull -> false
    is JsonPrimitive -> when {
        isString -> content.isNotEmpty()
        else -> content != "false" && content.toDoubleOrNull()?.equals(0.0) != true
    }
    else -> true
}

/** A non-empty string stamp, or null — what the token comparisons need. */
private fun JsonElement?.stampOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content

private fun JsonObject.arrayLength(key: String): Int = (this[key] as? JsonArray)?.size ?: 0

private fun JsonObject.hasTrimmedText(key: String): Boolean {
    val primitive = this[key] as? JsonPrimitive ?: return false
    return primitive.isString && primitive.content.isNotBlank()
}

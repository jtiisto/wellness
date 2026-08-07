package dev.jtiisto.wellness.core.data.journal

import dev.jtiisto.wellness.core.data.network.DateString
import dev.jtiisto.wellness.core.data.sync.DirtySetLogic
import dev.jtiisto.wellness.core.data.sync.DirtyState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.LocalDate
import kotlin.math.floor

/**
 * The pure decision functions of journal sync — a port of
 * `public/js/journal/sync-logic.js`, pinned by `JournalSyncLogicTest` (the
 * transcribed `journal-sync-logic.test.js`).
 *
 * No Room, no Ktor, no clock: `(state, inputs) -> nextState | decision`. The
 * store keeps the read → call → write wrappers.
 *
 * Journal sync is optimistic concurrency on an opaque server-issued
 * `lastModifiedAt` token, echoed on upload as `_baseLastModifiedAt`.
 *
 * Two places take a `locallyDeletedIds` set the JS did not need: the PWA keeps
 * its pending delete as a `_deleted` field *inside* the tracker object, while
 * ours lives in the entity's `deleted` column, and a [TrackerDto] cannot
 * express it (`_deleted` is protocol-reserved).
 */

/** The genesis date a migrated legacy schedule is stamped with. */
const val SCHEDULE_GENESIS_DATE: DateString = "0000-01-01"

/** How many days of entries the client keeps. Older non-dirty rows are pruned. */
const val ENTRY_WINDOW_DAYS: Int = 7

/**
 * The PWA's composite dirty-entry key. Tracker ids are generated UUIDs and
 * never contain `|`, so the first separator splits it unambiguously.
 */
fun entryKey(date: DateString, trackerId: String): String = "$date|$trackerId"

fun entryKeyDate(key: String): DateString = key.substringBefore('|')

fun entryKeyTrackerId(key: String): String = key.substringAfter('|')

// ---- normalization -------------------------------------------------------

/** [config] with legacy schedules migrated, plus the ids that actually changed. */
data class NormalizedConfig(val config: List<TrackerDto>, val changedIds: List<String>)

/**
 * Normalize a tracker's legacy `frequency` / `weeklyDay` (which ride in
 * [TrackerDto.extras]) into the canonical `scheduleHistory`, idempotently.
 * Returns the SAME instance when there is nothing to change, so callers can
 * mark dirty only on a real change and converge after one upload.
 *
 * - legacy `frequency: "weekly"` with no existing history → one genesis segment
 * - `frequency: "daily"` / absent → no history (absence means daily)
 * - an existing `scheduleHistory` is preserved
 * - `frequency` / `weeklyDay` are always stripped
 */
fun normalizeTrackerSchedule(tracker: TrackerDto): TrackerDto {
    if (!tracker.extras.containsKey("frequency") && !tracker.extras.containsKey("weeklyDay")) {
        return tracker
    }
    val hasSchedule = !tracker.scheduleHistory.isNullOrEmpty()
    val wasWeekly = (tracker.extras["frequency"] as? JsonPrimitive)?.contentOrNull == "weekly"
    val weeklyDay = weeklyDayOrNull(tracker.extras["weeklyDay"])
    return tracker.copy(
        scheduleHistory = if (!hasSchedule && wasWeekly && weeklyDay != null) {
            listOf(ScheduleSegmentDto(SCHEDULE_GENESIS_DATE, listOf(weeklyDay)))
        } else {
            tracker.scheduleHistory
        },
        extras = JsonObject(tracker.extras.filterKeys { it != "frequency" && it != "weeklyDay" }),
    )
}

/**
 * [normalizeTrackerSchedule] across a config array. Returns null when nothing
 * changed — the converged, idempotent steady state — so the caller can skip the
 * write and the dirtying entirely.
 *
 * Trackers with a pending local delete are left untouched; they are on their
 * way out.
 */
fun computeNormalizedConfig(
    config: List<TrackerDto>,
    locallyDeletedIds: Set<String>,
): NormalizedConfig? {
    var changed = false
    val changedIds = mutableListOf<String>()
    val next = config.map { tracker ->
        if (tracker.id in locallyDeletedIds) return@map tracker
        val normalized = normalizeTrackerSchedule(tracker)
        if (normalized !== tracker) {
            changed = true
            changedIds += tracker.id
        }
        normalized
    }
    return if (changed) NormalizedConfig(next, changedIds) else null
}

private fun weeklyDayOrNull(element: JsonElement?): Int? {
    val number = (element as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: return null
    if (!number.isFinite() || number != floor(number)) return null
    val day = number.toInt()
    return if (day in 0..6) day else null
}

// ---- upload --------------------------------------------------------------

/** The dirty sets an upload is built from, plus the client identity. */
data class UploadMeta(
    val clientId: String,
    val dirtyTrackers: List<String> = emptyList(),
    val dirtyEntries: List<String> = emptyList(),
)

/**
 * A tracker as the upload builder sees it: its stored server-form JSON, plus
 * the local soft-delete flag that lives outside the JSON.
 */
data class UploadTracker(
    val id: String,
    val data: JsonObject,
    val locallyDeleted: Boolean = false,
)

data class UploadPayloadResult(
    val payload: JsonObject,
    val dirtyTrackerIds: List<String>,
    val dirtyEntryKeys: List<String>,
)

/**
 * Build the upload body for the current dirty set. Each record carries its
 * `_baseLastModifiedAt` (the last server stamp) as the concurrency token;
 * brand-new records omit it so the server inserts-if-absent. The reserved
 * top-level `lastModifiedAt` is never echoed back.
 *
 * Two shapes are load-bearing and easy to lose:
 * - `value` and `completed` are ALWAYS present on an entry, as explicit nulls
 *   when unset — the PWA's `{value, completed}` object survives
 *   `JSON.stringify`, and the server reads `data.get("value")`. They are put as
 *   [JsonNull] rather than left to the serializer, which is configured to omit
 *   nulls.
 * - `_deleted: true` is injected from [UploadTracker.locallyDeleted]. It exists
 *   nowhere in the stored JSON, so without this a pending delete would upload
 *   as an ordinary upsert and the tracker would come straight back.
 *
 * A dirty key with no matching row is skipped (the JS `find` miss).
 */
fun computeUploadPayload(
    meta: UploadMeta,
    trackerConfig: List<UploadTracker>,
    dailyLogs: Map<DateString, Map<String, EntryDto>>,
): UploadPayloadResult {
    val config = mutableListOf<JsonObject>()
    val days = linkedMapOf<DateString, MutableMap<String, JsonElement>>()
    val dirtyTrackerIds = mutableListOf<String>()
    val dirtyEntryKeys = mutableListOf<String>()

    for (trackerId in meta.dirtyTrackers) {
        val tracker = trackerConfig.find { it.id == trackerId } ?: continue
        config += buildJsonObject {
            for ((key, element) in tracker.data) {
                if (key == "lastModifiedAt") continue
                put(key, element)
            }
            (tracker.data["lastModifiedAt"] as? JsonPrimitive)?.contentOrNull?.let {
                put("_baseLastModifiedAt", it)
            }
            if (tracker.locallyDeleted) put("_deleted", true)
        }
        dirtyTrackerIds += trackerId
    }

    for (key in meta.dirtyEntries) {
        val date = entryKeyDate(key)
        val trackerId = entryKeyTrackerId(key)
        val entry = dailyLogs[date]?.get(trackerId) ?: continue
        days.getOrPut(date) { linkedMapOf() }[trackerId] = buildJsonObject {
            put("value", entry.value ?: JsonNull)
            put("completed", entry.completed?.let { JsonPrimitive(it) } ?: JsonNull)
            entry.lastModifiedAt?.let { put("_baseLastModifiedAt", it) }
        }
        dirtyEntryKeys += key
    }

    val payload = buildJsonObject {
        put("clientId", meta.clientId)
        put("config", JsonArray(config))
        put("days", JsonObject(days.mapValues { (_, day) -> JsonObject(day) }))
    }
    return UploadPayloadResult(payload, dirtyTrackerIds, dirtyEntryKeys)
}

// ---- dirty clearing ------------------------------------------------------

/** Tracker and entry dirty sets travelling together. */
data class DirtyMeta(
    val trackers: DirtyState = DirtyState(),
    val entries: DirtyState = DirtyState(),
)

/**
 * The dirty state after an upload, for trackers and entries at once. Two
 * applications of the one shared algorithm — an item clears only if its
 * generation still matches the pre-upload snapshot.
 */
fun computeClearedDirtyState(
    trackers: DirtyState,
    entries: DirtyState,
    uploadedTrackerIds: List<String> = emptyList(),
    uploadedEntryKeys: List<String> = emptyList(),
    snapshotTrackerGens: Map<String, Long>? = null,
    snapshotEntryGens: Map<String, Long>? = null,
): DirtyMeta = DirtyMeta(
    trackers = DirtySetLogic.clearApplied(trackers, uploadedTrackerIds, snapshotTrackerGens),
    entries = DirtySetLogic.clearApplied(entries, uploadedEntryKeys, snapshotEntryGens),
)

// ---- upload response application ----------------------------------------

/** Config and logs after an accepted/rejected application. */
data class AppliedState(
    val trackerConfig: List<TrackerDto>,
    val dailyLogs: Map<DateString, Map<String, EntryDto>>,
)

/**
 * Stamp the server-issued `lastModifiedAt` from an accepted upload back onto
 * the matching rows, so the next edit uploads against the right base token.
 * Entries no longer present locally are skipped (the row went away between
 * upload and apply).
 *
 * Returns the SAME references when a section has nothing to apply.
 */
fun computeAcceptedApply(
    acceptedTrackers: List<AcceptedTrackerDto>,
    acceptedEntries: List<AcceptedEntryDto>,
    trackerConfig: List<TrackerDto>,
    dailyLogs: Map<DateString, Map<String, EntryDto>>,
): AppliedState {
    var nextConfig = trackerConfig
    if (acceptedTrackers.isNotEmpty()) {
        val stampById = acceptedTrackers.associate { it.id to it.lastModifiedAt }
        nextConfig = trackerConfig.map { tracker ->
            stampById[tracker.id]?.let { tracker.copy(lastModifiedAt = it) } ?: tracker
        }
    }

    var nextLogs = dailyLogs
    if (acceptedEntries.isNotEmpty()) {
        val logs = LinkedHashMap(dailyLogs)
        for (accepted in acceptedEntries) {
            val day = logs[accepted.date] ?: continue
            val entry = day[accepted.trackerId] ?: continue
            logs[accepted.date] = day + (accepted.trackerId to entry.copy(lastModifiedAt = accepted.lastModifiedAt))
        }
        nextLogs = logs
    }

    return AppliedState(nextConfig, nextLogs)
}

/** [AppliedState] plus the soft-deleted server rows that need full cleanup. */
data class RejectedApplyState(
    val trackerConfig: List<TrackerDto>,
    val dailyLogs: Map<DateString, Map<String, EntryDto>>,
    val trackerIdsToDelete: List<String>,
)

/**
 * Apply the `serverRow` of rejected uploads so the client recovers in-cycle
 * without a follow-up delta pull.
 *
 * A non-deleted serverRow upserts. A soft-deleted one is deliberately NOT
 * upserted: its id goes to [RejectedApplyState.trackerIdsToDelete] so the
 * caller routes it through the full delete cleanup (tracker + entries + dirty
 * state). Rejections with no serverRow are skipped.
 *
 * Returns the SAME references when a section has nothing to apply.
 */
fun computeRejectedApply(
    rejectedTrackers: List<RejectedTrackerDto>,
    rejectedEntries: List<RejectedEntryDto>,
    trackerConfig: List<TrackerDto>,
    dailyLogs: Map<DateString, Map<String, EntryDto>>,
): RejectedApplyState {
    val trackerIdsToDelete = mutableListOf<String>()

    var nextConfig = trackerConfig
    if (rejectedTrackers.isNotEmpty()) {
        val updated = trackerConfig.toMutableList()
        for (rejected in rejectedTrackers) {
            val serverRow = rejected.serverRow ?: continue
            if (serverRow.deleted == true) {
                trackerIdsToDelete += rejected.id
                continue
            }
            val index = updated.indexOfFirst { it.id == rejected.id }
            if (index >= 0) updated[index] = serverRow else updated += serverRow
        }
        nextConfig = updated
    }

    var nextLogs = dailyLogs
    if (rejectedEntries.isNotEmpty()) {
        val logs = LinkedHashMap(dailyLogs)
        for (rejected in rejectedEntries) {
            val serverRow = rejected.serverRow ?: continue
            val day = logs[rejected.date] ?: emptyMap()
            logs[rejected.date] = day + (
                rejected.trackerId to EntryDto(
                    value = serverRow.value,
                    completed = serverRow.completed,
                    lastModifiedAt = serverRow.lastModifiedAt,
                )
                )
        }
        nextLogs = logs
    }

    return RejectedApplyState(nextConfig, nextLogs, trackerIdsToDelete)
}

// ---- deletion ------------------------------------------------------------

data class DropDeletedState(
    val trackerConfig: List<TrackerDto>,
    val dailyLogs: Map<DateString, Map<String, EntryDto>>,
    val meta: DirtyMeta,
    val logsChanged: Boolean,
    val dirtyChanged: Boolean,
)

/**
 * Apply a server-side tracker delete locally: drop the trackers, drop all their
 * entries, and purge any dirty state — tracker AND entry level, generations
 * included — that would otherwise leave the client stuck red forever with rows
 * it can never upload.
 *
 * The dirty purge and [DropDeletedState.dirtyChanged] fire only when a dirty
 * key actually matched a deleted id; entry keys match on their trackerId half.
 */
fun computeDropDeletedTrackers(
    deletedIds: List<String>,
    trackerConfig: List<TrackerDto>,
    dailyLogs: Map<DateString, Map<String, EntryDto>>,
    meta: DirtyMeta,
): DropDeletedState {
    val idSet = deletedIds.toSet()

    val nextConfig = trackerConfig.filter { it.id !in idSet }

    val logs = LinkedHashMap(dailyLogs)
    var logsChanged = false
    for (date in logs.keys.toList()) {
        val day = logs.getValue(date)
        val nextDay = day.filterKeys { it !in idSet }
        if (nextDay.size != day.size) {
            logs[date] = nextDay
            logsChanged = true
        }
    }

    val nextTrackerKeys = meta.trackers.keys.filter { it !in idSet }
    val nextEntryKeys = meta.entries.keys.filter { entryKeyTrackerId(it) !in idSet }

    var trackerGenerations = meta.trackers.generations
    var entryGenerations = meta.entries.generations
    var dirtyChanged = false
    if (nextTrackerKeys.size != meta.trackers.keys.size || nextEntryKeys.size != meta.entries.keys.size) {
        dirtyChanged = true
        trackerGenerations = trackerGenerations.filterKeys { it !in idSet }
        entryGenerations = entryGenerations.filterKeys { entryKeyTrackerId(it) !in idSet }
    }

    return DropDeletedState(
        trackerConfig = nextConfig,
        dailyLogs = logs,
        meta = DirtyMeta(
            trackers = DirtyState(nextTrackerKeys, trackerGenerations),
            entries = DirtyState(nextEntryKeys, entryGenerations),
        ),
        logsChanged = logsChanged,
        dirtyChanged = dirtyChanged,
    )
}

data class PruneDeletedState(
    val trackerConfig: List<TrackerDto>,
    val dailyLogs: Map<DateString, Map<String, EntryDto>>,
    val logsChanged: Boolean,
)

/**
 * Drop locally-soft-deleted trackers — and their entries — after a successful
 * sync. Returns null when there is nothing to prune, so the caller can
 * early-return without touching storage.
 */
fun computePruneDeletedTrackers(
    trackerConfig: List<TrackerDto>,
    locallyDeletedIds: Set<String>,
    dailyLogs: Map<DateString, Map<String, EntryDto>>,
): PruneDeletedState? {
    val deletedIds = trackerConfig.map { it.id }.filter { it in locallyDeletedIds }
    if (deletedIds.isEmpty()) return null
    val idSet = deletedIds.toSet()

    val nextConfig = trackerConfig.filter { it.id !in idSet }

    val logs = LinkedHashMap(dailyLogs)
    var logsChanged = false
    for (date in logs.keys.toList()) {
        val day = logs.getValue(date)
        val nextDay = day.filterKeys { it !in idSet }
        if (nextDay.size != day.size) {
            logs[date] = nextDay
            logsChanged = true
        }
    }

    return PruneDeletedState(nextConfig, logs, logsChanged)
}

// ---- the retention window ------------------------------------------------

/**
 * Whether [dateStr] falls inside the last [days] days — a verbatim port of
 * `journal/utils.js`. The boundary is inclusive at the cutoff (today minus
 * [days], midnight local) and open-ended in the future, and it is pinned by
 * test: do not re-derive it.
 */
fun isWithinLastNDays(dateStr: DateString, today: LocalDate, days: Int = ENTRY_WINDOW_DAYS): Boolean =
    !LocalDate.parse(dateStr).isBefore(today.minusDays(days.toLong()))

/**
 * The oldest date [isWithinLastNDays] keeps — the SQL twin of that predicate.
 * `YYYY-MM-DD` compares lexically, so `date < windowStart` is exactly the set
 * the JS deletes.
 */
fun localDataWindowStart(today: LocalDate, days: Int = ENTRY_WINDOW_DAYS): DateString =
    today.minusDays(days.toLong()).toString()

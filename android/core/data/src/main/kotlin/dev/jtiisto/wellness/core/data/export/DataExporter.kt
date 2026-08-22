package dev.jtiisto.wellness.core.data.export

import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.db.CoachDao
import dev.jtiisto.wellness.core.data.db.ExportDao
import dev.jtiisto.wellness.core.data.db.ExportSnapshot
import dev.jtiisto.wellness.core.data.db.JournalDao
import dev.jtiisto.wellness.core.data.journal.JournalUiPrefs
import dev.jtiisto.wellness.core.data.journal.entryKey
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.OutputStream
import java.time.Instant

/**
 * "Export all data" — a port of `shared/data-export.js` onto Room.
 *
 * The PWA's v2 envelope is a raw key/value dump of its two LocalForage
 * instances, so the file's shape is really a description of the PWA's storage.
 * Native storage is a normalized database, so every key here is *reconstructed*
 * to the shape the PWA would have written. That is deliberate: the export's
 * whole value is being readable by the same eyes and scripts as a PWA export,
 * and an Android-shaped file that happened to contain the same facts would not
 * be.
 *
 * Three deliberate departures from the PWA's file, all narrowing:
 *
 * - **No device fingerprint.** The PWA writes `navigator.userAgent`; this
 *   writes `client: "android/<versionName>"`. The build is worth knowing; the
 *   device is not.
 * - **No `app_schema_version`.** That key describes the PWA's IndexedDB
 *   migration state. Substituting Room's schema number would put a number in a
 *   field that means something else — a lie that reads as data.
 * - **No `server_profiles`.** The address book holds tailnet URLs, which are
 *   configuration rather than data, and an export is a file people hand around.
 *
 * The debug log is excluded too, as it is in the PWA, because it has its own
 * share action.
 *
 * The `hr` section has no PWA counterpart to be faithful to — the module is
 * headless there — so it is written the way the wire protocol it feeds is
 * written: camelCase row fields, optional fields omitted rather than null. Raw
 * RR samples are the one thing summarized instead of dumped; see [hr].
 */
class DataExporter(
    private val dao: ExportDao,
    private val clientVersion: String,
    private val json: Json = WellnessJson,
    private val now: () -> String = { Instant.now().toString() },
) {

    /**
     * Read a snapshot and stream it out as JSON.
     *
     * The bounded model is built inside this call and handed straight to
     * [Json.encodeToStream], so the object graph and its encoded form never
     * exist at the same time — `encodeToString` would have doubled the peak by
     * materializing the whole file as a `String` first.
     *
     * Nothing is caught here. A failed export must surface: the PWA swallowed
     * the error and left the user believing a file had been written.
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun writeTo(sink: OutputStream) {
        PRETTY.encodeToStream(JsonObject.serializer(), buildModel(dao.snapshot()), sink)
    }

    /** The envelope, column by column. Public for the mapping tests. */
    fun buildModel(snapshot: ExportSnapshot): JsonObject = buildJsonObject {
        putJsonObject(KEY_EXPORT) {
            put("version", EXPORT_VERSION)
            put("exportedAt", now())
            put("client", "android/$clientVersion")
        }
        put("journal", journal(snapshot))
        put("coach", coach(snapshot))
        put("hr", hr(snapshot))
        // Structural parity only: the PWA persists which module was last open,
        // and native navigation does not. An absent key would read as an older
        // export format; an invented value would read as a fact.
        putJsonObject("app") { put("activeModule", JsonNull) }
    }

    private fun journal(snapshot: ExportSnapshot): JsonObject = buildJsonObject {
        putJsonArray("tracker_config") {
            for (row in snapshot.trackers) {
                val stored = parseObject(row.dataJson)
                // The delete flag is a local column, never part of `dataJson`.
                // The upload builder injects it the same way; the export has to,
                // or a pending delete would read as an ordinary live tracker.
                add(if (row.deleted) JsonObject(stored + ("_deleted" to JsonPrimitive(true))) else stored)
            }
        }

        putJsonObject("daily_logs") {
            for ((date, day) in snapshot.entries.groupBy { it.date }) {
                putJsonObject(date) {
                    for (entry in day) {
                        putJsonObject(entry.trackerId) {
                            // An explicitly-null value and an absent one are
                            // different states everywhere else in this codebase,
                            // and the export keeps them apart too.
                            entry.valueJson?.let { put("value", json.parseToJsonElement(it)) }
                            entry.completed?.let { put("completed", it) }
                            entry.lastModifiedAt?.let { put("lastModifiedAt", it) }
                        }
                    }
                }
            }
        }

        putJsonObject("app_metadata") {
            put("lastServerSyncTime", nullable(snapshot.journalMeta[JournalDao.KEY_LAST_SERVER_SYNC_TIME]))
            val dirtyTrackers = snapshot.trackers.filter { it.isDirty }
            val dirtyEntries = snapshot.entries.filter { it.isDirty }
            putJsonArray("dirtyTrackers") { dirtyTrackers.forEach { add(it.id) } }
            putJsonArray("dirtyEntries") { dirtyEntries.forEach { add(entryKey(it.date, it.trackerId)) } }
            putJsonObject("dirtyEntryGenerations") {
                dirtyEntries.forEach { put(entryKey(it.date, it.trackerId), it.dirtyGeneration) }
            }
            putJsonObject("dirtyTrackerGenerations") {
                dirtyTrackers.forEach { put(it.id, it.dirtyGeneration) }
            }
        }

        put("client_id", nullable(snapshot.journalMeta[JournalDao.KEY_CLIENT_ID]))

        // Native has both of these — the PWA's own export was missing them for
        // a while because its key list had drifted — so they are included.
        put(
            "expanded_categories",
            snapshot.journalMeta[JournalUiPrefs.KEY_EXPANDED_CATEGORIES]?.let(::parseOrNull)
                ?: JsonArray(emptyList()),
        )
        put(
            "tracker_value_updated_times",
            snapshot.journalMeta[JournalUiPrefs.KEY_VALUE_UPDATED_TIMES]?.let(::parseOrNull)
                ?: JsonObject(emptyMap()),
        )
    }

    private fun coach(snapshot: ExportSnapshot): JsonObject = buildJsonObject {
        putJsonObject("workout_plans") {
            for (row in snapshot.plans) put(row.date, parseObject(row.planJson))
        }
        putJsonObject("workout_logs") {
            for (row in snapshot.logs) put(row.date, parseObject(row.logJson))
        }
        putJsonObject("coach_metadata") {
            put("lastServerSyncTime", nullable(snapshot.coachMeta[CoachDao.KEY_LAST_SERVER_SYNC_TIME]))
            val dirty = snapshot.logs.filter { it.isDirty }
            putJsonArray("dirtyDates") { dirty.forEach { add(it.date) } }
            putJsonObject("dirtyDateGenerations") { dirty.forEach { put(it.date, it.dirtyGeneration) } }
            put("earliestDate", nullable(snapshot.coachMeta[CoachDao.KEY_EARLIEST_DATE]))
        }
        put("coach_client_id", nullable(snapshot.coachMeta[CoachDao.KEY_CLIENT_ID]))
    }

    /**
     * Capture sessions, the toggle log, and **counts** of the RR samples.
     *
     * The samples themselves are deliberately not here. An hour of capture is
     * several thousand rows of raw interval data whose only reader is the
     * server's analysis stage; dumping a week of them would multiply the file
     * by an order of magnitude and bury the state someone opened it to check.
     * What that reader actually wants from a device export is how much is held
     * and whether it has left — which is what the counts say, and they are
     * aggregated in SQL so the rows never reach memory either.
     *
     * `isSynced`, `isQuarantined` and `dirtyGeneration` are exported on every
     * row rather than filtered on: "this session never uploaded, and here is
     * whether the server rejected it or nobody tried" is the fact the file
     * exists to record, and the generation is what distinguishes a session
     * nobody has sent from one being rewritten faster than it can upload. The
     * coach section exports its generations for the same reason.
     */
    private fun hr(snapshot: ExportSnapshot): JsonObject = buildJsonObject {
        putJsonObject("sessions") {
            for (row in snapshot.hrSessions) {
                putJsonObject(row.sessionId) {
                    put("deviceId", row.deviceId)
                    put("startedAtMs", row.startedAtMs)
                    row.endedAtMs?.let { put("endedAtMs", it) }
                    row.workoutDate?.let { put("workoutDate", it) }
                    row.workoutSessionId?.let { put("workoutSessionId", it) }
                    put("isSynced", row.isSynced)
                    put("isQuarantined", row.isQuarantined)
                    put("dirtyGeneration", row.dirtyGeneration)
                }
            }
        }
        putJsonObject("sample_counts") {
            for (row in snapshot.hrSampleSummaries) {
                putJsonObject(row.sessionId) {
                    put("total", row.total)
                    put("pending", row.pending)
                    put("quarantined", row.quarantined)
                    put("firstTimestampMs", row.firstTimestampMs)
                    put("lastTimestampMs", row.lastTimestampMs)
                }
            }
        }
        putJsonArray("set_events") {
            for (row in snapshot.setEvents) {
                add(
                    buildJsonObject {
                        put("eventId", row.eventId)
                        put("date", row.date)
                        put("exerciseKey", row.exerciseKey)
                        row.setNum?.let { put("setNum", it) }
                        row.itemKey?.let { put("itemKey", it) }
                        put("action", row.action)
                        put("clientTimestampMs", row.clientTimestampMs)
                        row.sessionId?.let { put("sessionId", it) }
                        put("isSynced", row.isSynced)
                        put("isQuarantined", row.isQuarantined)
                    },
                )
            }
        }
        putJsonArray("guide_events") {
            for (row in snapshot.guideEvents) {
                add(
                    buildJsonObject {
                        put("eventId", row.eventId)
                        put("date", row.date)
                        put("exerciseKey", row.exerciseKey)
                        put("action", row.action)
                        put("clientTimestampMs", row.clientTimestampMs)
                        put("sessionId", row.sessionId)
                        row.extensionSec?.let { put("extensionSec", it) }
                        row.timelineJson?.let { put("timelineJson", it) }
                        put("isSynced", row.isSynced)
                        put("isQuarantined", row.isQuarantined)
                    },
                )
            }
        }
    }

    private fun nullable(value: String?): JsonElement =
        if (value == null) JsonNull else JsonPrimitive(value)

    private fun parseObject(text: String): JsonObject =
        json.parseToJsonElement(text) as? JsonObject ?: JsonObject(emptyMap())

    /**
     * A stored blob that will not parse is UI state or a corrupted row, not a
     * reason to abandon the whole export — the rest of the file is still the
     * thing the user is trying to look at.
     */
    private fun parseOrNull(text: String): JsonElement? =
        runCatching { json.parseToJsonElement(text) }.getOrNull()

    companion object {
        const val EXPORT_VERSION = 2
        const val KEY_EXPORT = "_export"
        const val MIME_TYPE = "application/json"

        /** Two-space indent, as `JSON.stringify(data, null, 2)` produces. */
        private val PRETTY = Json(WellnessJson) {
            prettyPrint = true
            prettyPrintIndent = "  "
        }
    }
}

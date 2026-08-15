package dev.jtiisto.wellness.core.data.export

import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.db.CoachDao
import dev.jtiisto.wellness.core.data.db.CoachLogEntity
import dev.jtiisto.wellness.core.data.db.CoachMetaEntity
import dev.jtiisto.wellness.core.data.db.CoachPlanEntity
import dev.jtiisto.wellness.core.data.db.ExportDao
import dev.jtiisto.wellness.core.data.db.HrSampleSummary
import dev.jtiisto.wellness.core.data.db.HrSessionEntity
import dev.jtiisto.wellness.core.data.db.JournalDao
import dev.jtiisto.wellness.core.data.db.JournalEntryEntity
import dev.jtiisto.wellness.core.data.db.JournalMetaEntity
import dev.jtiisto.wellness.core.data.db.JournalTrackerEntity
import dev.jtiisto.wellness.core.data.db.SetEventEntity
import dev.jtiisto.wellness.core.data.journal.JournalUiPrefs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * The export envelope, column by column against the mapping table.
 *
 * The file's whole value is being readable beside a PWA export, so the assertions
 * are about *key names and shapes* rather than about the numbers inside them —
 * a native-shaped file carrying the same facts would be worthless.
 */
class DataExporterTest {

    private val json = WellnessJson

    private class FakeExportDao : ExportDao() {
        var trackers = listOf<JournalTrackerEntity>()
        var entries = listOf<JournalEntryEntity>()
        var journalMeta = listOf<JournalMetaEntity>()
        var plans = listOf<CoachPlanEntity>()
        var logs = listOf<CoachLogEntity>()
        var coachMeta = listOf<CoachMetaEntity>()
        var hrSessions = listOf<HrSessionEntity>()
        var hrSampleSummaries = listOf<HrSampleSummary>()
        var setEvents = listOf<SetEventEntity>()

        /** Ordered read log — the atomicity assertion reads this. */
        val reads = mutableListOf<String>()
        var throwOnPlans: Throwable? = null

        override suspend fun trackers(): List<JournalTrackerEntity> {
            reads += "trackers"
            return trackers
        }

        override suspend fun entries(): List<JournalEntryEntity> {
            reads += "entries"
            return entries
        }

        override suspend fun journalMeta(): List<JournalMetaEntity> {
            reads += "journalMeta"
            return journalMeta
        }

        override suspend fun plans(): List<CoachPlanEntity> {
            reads += "plans"
            throwOnPlans?.let { throw it }
            return plans
        }

        override suspend fun logs(): List<CoachLogEntity> {
            reads += "logs"
            return logs
        }

        override suspend fun coachMeta(): List<CoachMetaEntity> {
            reads += "coachMeta"
            return coachMeta
        }

        override suspend fun hrSessions(): List<HrSessionEntity> {
            reads += "hrSessions"
            return hrSessions
        }

        override suspend fun hrSampleSummaries(): List<HrSampleSummary> {
            reads += "hrSampleSummaries"
            return hrSampleSummaries
        }

        override suspend fun setEvents(): List<SetEventEntity> {
            reads += "setEvents"
            return setEvents
        }
    }

    private fun tracker(
        id: String,
        name: String = "Water",
        deleted: Boolean = false,
        isDirty: Boolean = false,
        generation: Long = 0,
    ) = JournalTrackerEntity(
        id = id,
        name = name,
        category = "Habits",
        type = "simple",
        deleted = deleted,
        lastModifiedAt = "s1",
        dataJson = """{"id":"$id","name":"$name","category":"Habits","type":"simple","lastModifiedAt":"s1"}""",
        isDirty = isDirty,
        dirtyGeneration = generation,
    )

    private fun entry(
        date: String,
        trackerId: String,
        valueJson: String? = null,
        completed: Boolean? = null,
        isDirty: Boolean = false,
        generation: Long = 0,
    ) = JournalEntryEntity(
        date = date,
        trackerId = trackerId,
        valueJson = valueJson,
        completed = completed,
        lastModifiedAt = "s1",
        isDirty = isDirty,
        dirtyGeneration = generation,
    )

    private fun exporter(dao: ExportDao) =
        DataExporter(dao = dao, clientVersion = "9.9.9", json = json, now = { "2026-08-09T12:00:00Z" })

    private suspend fun exportOf(dao: FakeExportDao): JsonObject =
        exporter(dao).buildModel(dao.snapshot())

    // ---- envelope --------------------------------------------------------

    @Test
    @DisplayName("_export carries version 2, the timestamp, and the client — but no device fingerprint")
    fun envelope() = runTest {
        val model = exportOf(FakeExportDao())

        val export = model.getValue("_export").jsonObject
        assertEquals(2, export.getValue("version").jsonPrimitive.content.toInt())
        assertEquals("2026-08-09T12:00:00Z", export.getValue("exportedAt").jsonPrimitive.content)
        assertEquals("android/9.9.9", export.getValue("client").jsonPrimitive.content)
        // The PWA writes navigator.userAgent here. The build is worth knowing;
        // the device is not.
        assertFalse(export.containsKey("userAgent"))
    }

    @Test
    @DisplayName("app_schema_version is omitted rather than filled in with Room's number")
    fun appSchemaVersionOmitted() = runTest {
        val model = exportOf(FakeExportDao())

        // That key describes the PWA's IndexedDB migration state. Room's schema
        // number in that field would be a number that means something else.
        assertFalse(model.getValue("journal").jsonObject.containsKey("app_schema_version"))
    }

    @Test
    @DisplayName("server_profiles never appears anywhere in the file")
    fun profilesExcluded() = runTest {
        val model = exportOf(FakeExportDao())

        assertFalse(model.toString().contains("server_profiles"))
    }

    @Test
    @DisplayName("the debug log is excluded, as it is in the PWA — it has its own share action")
    fun debugLogExcluded() = runTest {
        val model = exportOf(FakeExportDao())

        assertFalse(model.toString().contains("debug_log"))
    }

    @Test
    @DisplayName("app.activeModule is an explicit null: structural parity without an invented value")
    fun activeModuleIsExplicitNull() = runTest {
        val model = exportOf(FakeExportDao())

        assertEquals(JsonNull, model.getValue("app").jsonObject.getValue("activeModule"))
    }

    // ---- journal ---------------------------------------------------------

    @Test
    @DisplayName("tracker_config is the stored wire object, verbatim")
    fun trackerConfigIsTheStoredJson() = runTest {
        val dao = FakeExportDao().apply { trackers = listOf(tracker("t1", name = "Water")) }

        val config = exportOf(dao).getValue("journal").jsonObject.getValue("tracker_config").jsonArray

        assertEquals(1, config.size)
        assertEquals("Water", config[0].jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("s1", config[0].jsonObject.getValue("lastModifiedAt").jsonPrimitive.content)
    }

    @Test
    @DisplayName("a pending delete carries _deleted: true, which lives in a column and not in dataJson")
    fun pendingDeleteIsInjected() = runTest {
        val dao = FakeExportDao().apply {
            trackers = listOf(tracker("t1", deleted = false), tracker("t2", deleted = true))
        }

        val config = exportOf(dao).getValue("journal").jsonObject.getValue("tracker_config").jsonArray

        assertFalse(config[0].jsonObject.containsKey("_deleted"))
        assertTrue(config[1].jsonObject.getValue("_deleted").jsonPrimitive.content.toBoolean())
    }

    @Test
    @DisplayName("daily_logs nests date then tracker, with the value preserved as its own JSON type")
    fun dailyLogsShape() = runTest {
        val dao = FakeExportDao().apply {
            entries = listOf(
                entry("2026-08-06", "t1", valueJson = "12.5", completed = true),
                entry("2026-08-06", "t2", valueJson = "\"a note\""),
                entry("2026-08-05", "t1", completed = false),
            )
        }

        val logs = exportOf(dao).getValue("journal").jsonObject.getValue("daily_logs").jsonObject

        assertEquals(setOf("2026-08-06", "2026-08-05"), logs.keys)
        val day = logs.getValue("2026-08-06").jsonObject
        assertEquals(12.5, day.getValue("t1").jsonObject.getValue("value").jsonPrimitive.content.toDouble())
        assertEquals(true, day.getValue("t1").jsonObject.getValue("completed").jsonPrimitive.content.toBoolean())
        assertEquals("s1", day.getValue("t1").jsonObject.getValue("lastModifiedAt").jsonPrimitive.content)
        assertEquals("a note", day.getValue("t2").jsonObject.getValue("value").jsonPrimitive.content)
    }

    @Test
    @DisplayName("an absent entry value stays absent, and an explicit null stays null")
    fun absentAndNullValuesStayDistinct() = runTest {
        val dao = FakeExportDao().apply {
            entries = listOf(
                entry("2026-08-06", "absent", valueJson = null, completed = true),
                entry("2026-08-06", "explicit", valueJson = "null", completed = true),
            )
        }

        val day = exportOf(dao).getValue("journal").jsonObject
            .getValue("daily_logs").jsonObject.getValue("2026-08-06").jsonObject

        // The same distinction storage keeps everywhere else: the checkbox seeds
        // a default into an absent value and must not overwrite a null.
        assertFalse(day.getValue("absent").jsonObject.containsKey("value"))
        assertEquals(JsonNull, day.getValue("explicit").jsonObject.getValue("value"))
    }

    @Test
    @DisplayName("app_metadata carries the watermark and the dirty sets under the PWA's key names")
    fun appMetadataShape() = runTest {
        val dao = FakeExportDao().apply {
            trackers = listOf(tracker("t1", isDirty = true, generation = 3), tracker("t2"))
            entries = listOf(
                entry("2026-08-06", "t1", isDirty = true, generation = 7),
                entry("2026-08-06", "t2"),
            )
            journalMeta = listOf(JournalMetaEntity(JournalDao.KEY_LAST_SERVER_SYNC_TIME, "s-wm"))
        }

        val meta = exportOf(dao).getValue("journal").jsonObject.getValue("app_metadata").jsonObject

        assertEquals("s-wm", meta.getValue("lastServerSyncTime").jsonPrimitive.content)
        assertEquals(listOf("t1"), meta.getValue("dirtyTrackers").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(
            listOf("2026-08-06|t1"),
            meta.getValue("dirtyEntries").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            3,
            meta.getValue("dirtyTrackerGenerations").jsonObject.getValue("t1").jsonPrimitive.content.toInt(),
        )
        assertEquals(
            7,
            meta.getValue("dirtyEntryGenerations").jsonObject
                .getValue("2026-08-06|t1").jsonPrimitive.content.toInt(),
        )
    }

    @Test
    @DisplayName("generation maps hold only the dirty keys, as the PWA's do")
    fun generationsCoverOnlyDirtyKeys() = runTest {
        val dao = FakeExportDao().apply {
            trackers = listOf(tracker("t1", isDirty = true, generation = 3), tracker("clean", generation = 9))
        }

        val meta = exportOf(dao).getValue("journal").jsonObject.getValue("app_metadata").jsonObject

        assertEquals(setOf("t1"), meta.getValue("dirtyTrackerGenerations").jsonObject.keys)
    }

    @Test
    @DisplayName("the client ids are verbatim scalars under their own keys")
    fun clientIds() = runTest {
        val dao = FakeExportDao().apply {
            journalMeta = listOf(JournalMetaEntity(JournalDao.KEY_CLIENT_ID, "journal-uuid"))
            coachMeta = listOf(CoachMetaEntity(CoachDao.KEY_CLIENT_ID, "coach-uuid"))
        }

        val model = exportOf(dao)

        assertEquals(
            "journal-uuid",
            model.getValue("journal").jsonObject.getValue("client_id").jsonPrimitive.content,
        )
        assertEquals(
            "coach-uuid",
            model.getValue("coach").jsonObject.getValue("coach_client_id").jsonPrimitive.content,
        )
    }

    @Test
    @DisplayName("the ui.* preference keys are exported under the PWA's names")
    fun uiPrefsAreIncluded() = runTest {
        val dao = FakeExportDao().apply {
            journalMeta = listOf(
                JournalMetaEntity(JournalUiPrefs.KEY_EXPANDED_CATEGORIES, """["Habits","Mind"]"""),
                JournalMetaEntity(
                    JournalUiPrefs.KEY_VALUE_UPDATED_TIMES,
                    """{"2026-08-06|t1":"2026-08-06T10:00:00Z"}""",
                ),
            )
        }

        val journal = exportOf(dao).getValue("journal").jsonObject

        assertEquals(
            listOf("Habits", "Mind"),
            journal.getValue("expanded_categories").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            "2026-08-06T10:00:00Z",
            journal.getValue("tracker_value_updated_times").jsonObject
                .getValue("2026-08-06|t1").jsonPrimitive.content,
        )
    }

    @Test
    @DisplayName("a corrupted preference blob degrades to empty rather than failing the export")
    fun corruptedPrefsDoNotFailTheExport() = runTest {
        val dao = FakeExportDao().apply {
            journalMeta = listOf(JournalMetaEntity(JournalUiPrefs.KEY_EXPANDED_CATEGORIES, "{not json"))
        }

        val journal = exportOf(dao).getValue("journal").jsonObject

        assertEquals(JsonArray(emptyList()), journal.getValue("expanded_categories"))
    }

    // ---- coach -----------------------------------------------------------

    @Test
    @DisplayName("workout_plans and workout_logs are date-keyed decoded objects")
    fun coachCollections() = runTest {
        val dao = FakeExportDao().apply {
            plans = listOf(CoachPlanEntity("2026-08-06", """{"_lastModified":"p1","session":{}}""", "p1"))
            logs = listOf(CoachLogEntity("2026-08-06", """{"ex_1":{"sets":[]}}""", false, 0))
        }

        val coach = exportOf(dao).getValue("coach").jsonObject

        assertEquals(
            "p1",
            coach.getValue("workout_plans").jsonObject.getValue("2026-08-06").jsonObject
                .getValue("_lastModified").jsonPrimitive.content,
        )
        assertTrue(
            coach.getValue("workout_logs").jsonObject.getValue("2026-08-06").jsonObject.containsKey("ex_1"),
        )
    }

    @Test
    @DisplayName("coach_metadata carries the watermark, earliestDate and the dirty dates")
    fun coachMetadataShape() = runTest {
        val dao = FakeExportDao().apply {
            logs = listOf(
                CoachLogEntity("2026-08-06", "{}", isDirty = true, dirtyGeneration = 4),
                CoachLogEntity("2026-08-05", "{}", isDirty = false, dirtyGeneration = 0),
            )
            coachMeta = listOf(
                CoachMetaEntity(CoachDao.KEY_LAST_SERVER_SYNC_TIME, "s-coach"),
                CoachMetaEntity(CoachDao.KEY_EARLIEST_DATE, "2026-06-10"),
            )
        }

        val meta = exportOf(dao).getValue("coach").jsonObject.getValue("coach_metadata").jsonObject

        assertEquals("s-coach", meta.getValue("lastServerSyncTime").jsonPrimitive.content)
        assertEquals("2026-06-10", meta.getValue("earliestDate").jsonPrimitive.content)
        assertEquals(listOf("2026-08-06"), meta.getValue("dirtyDates").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(
            4,
            meta.getValue("dirtyDateGenerations").jsonObject.getValue("2026-08-06").jsonPrimitive.content.toInt(),
        )
    }

    @Test
    @DisplayName("an empty database still produces every key, with nulls where a scalar is missing")
    fun emptyDatabaseStillHasTheWholeShape() = runTest {
        val model = exportOf(FakeExportDao())

        val journal = model.getValue("journal").jsonObject
        val coach = model.getValue("coach").jsonObject
        assertEquals(JsonNull, journal.getValue("client_id"))
        assertEquals(JsonNull, journal.getValue("app_metadata").jsonObject.getValue("lastServerSyncTime"))
        assertEquals(JsonNull, coach.getValue("coach_client_id"))
        assertEquals(JsonNull, coach.getValue("coach_metadata").jsonObject.getValue("earliestDate"))
        assertTrue(journal.getValue("tracker_config").jsonArray.isEmpty())
        assertTrue(coach.getValue("workout_plans").jsonObject.isEmpty())
    }

    // ---- snapshot and streaming ------------------------------------------

    @Test
    @DisplayName("all nine tables are read through one snapshot call")
    fun snapshotReadsEverythingTogether() = runTest {
        val dao = FakeExportDao()

        dao.snapshot()

        // A sync landing between two of these would produce a file describing a
        // state the device was never in; the @Transaction is what prevents it,
        // and this pins that every table goes through it.
        assertEquals(
            listOf(
                "trackers", "entries", "journalMeta", "plans", "logs", "coachMeta",
                "hrSessions", "hrSampleSummaries", "setEvents",
            ),
            dao.reads,
        )
    }

    @Test
    @DisplayName("a read failure propagates rather than producing half a file")
    fun snapshotFailurePropagates() = runTest {
        val dao = FakeExportDao().apply { throwOnPlans = IllegalStateException("database locked") }

        assertThrows<IllegalStateException> { exporter(dao).writeTo(ByteArrayOutputStream()) }
    }

    @Test
    @DisplayName("the stream is pretty-printed with two spaces, as JSON.stringify(data, null, 2) is")
    fun streamIsPrettyPrinted() = runTest {
        val dao = FakeExportDao().apply { trackers = listOf(tracker("t1")) }
        val sink = ByteArrayOutputStream()

        exporter(dao).writeTo(sink)

        val text = sink.toString(Charsets.UTF_8)
        assertTrue(text.startsWith("{\n  \"_export\""), "expected a two-space indent, got:\n${text.take(80)}")
        // And it is still valid JSON that round-trips to the same model.
        assertEquals(exportOf(dao), json.parseToJsonElement(text).jsonObject)
    }

    @Test
    @DisplayName("a failing sink surfaces: the user must not be told a file was written")
    fun writeFailureSurfaces() = runTest {
        val failing = object : OutputStream() {
            override fun write(b: Int) = throw IOException("disk full")
        }

        // The PWA swallowed this and returned a result object nobody read.
        assertThrows<IOException> { exporter(FakeExportDao()).writeTo(failing) }
    }

    @Test
    @DisplayName("no native storage detail leaks into the two sections the PWA also writes")
    fun noNativeOnlyTablesLeak() = runTest {
        val model = exportOf(FakeExportDao())

        // Scoped to journal and coach on purpose. Those two are reconstructions
        // of what the PWA would have written, and a Room table name appearing
        // inside them means the reconstruction leaked. `hr` has no PWA
        // counterpart to be faithful to, so it names its own tables.
        val text = listOf("journal", "coach").joinToString { model.getValue(it).toString() }
        assertNull(
            listOf("payload_cache", "trends_meta", "journal_trackers").firstOrNull { text.contains(it) },
            "a native storage detail reached the v2 envelope",
        )
    }

    // ---- hr --------------------------------------------------------------

    @Test
    @DisplayName("sessions are keyed by id, with the optional anchors omitted rather than null")
    fun hrSessionsShape() = runTest {
        val dao = FakeExportDao().apply {
            hrSessions = listOf(
                HrSessionEntity(
                    sessionId = "s-anchored", deviceId = "AA:BB:CC:DD:EE:FF",
                    startedAtMs = 1_769_999_990_000, endedAtMs = 1_770_000_500_000,
                    workoutDate = "2030-01-03", workoutSessionId = 42, isSynced = true,
                ),
                HrSessionEntity(
                    sessionId = "s-rejected", deviceId = "AA:BB:CC:DD:EE:FF",
                    startedAtMs = 1_770_000_900_000, isQuarantined = true, dirtyGeneration = 4,
                ),
                HrSessionEntity(
                    sessionId = "s-loose", deviceId = "AA:BB:CC:DD:EE:FF",
                    startedAtMs = 1_770_001_000_000,
                ),
            )
        }

        val sessions = exportOf(dao).getValue("hr").jsonObject.getValue("sessions").jsonObject

        val anchored = sessions.getValue("s-anchored").jsonObject
        assertEquals("2030-01-03", anchored.getValue("workoutDate").jsonPrimitive.content)
        assertEquals(42, anchored.getValue("workoutSessionId").jsonPrimitive.content.toInt())
        assertTrue(anchored.getValue("isSynced").jsonPrimitive.content.toBoolean())

        // Omitted, never null — the same rule the wire protocol these rows feed
        // is written under.
        val loose = sessions.getValue("s-loose").jsonObject
        assertFalse(loose.containsKey("endedAtMs"))
        assertFalse(loose.containsKey("workoutDate"))
        assertFalse(loose.containsKey("workoutSessionId"))
        // And "never uploaded" is exactly the fact this file exists to record —
        // with the two flags apart, so "the server rejected it" reads
        // differently from "nobody has tried yet".
        assertFalse(loose.getValue("isSynced").jsonPrimitive.content.toBoolean())
        assertFalse(loose.getValue("isQuarantined").jsonPrimitive.content.toBoolean())
        val rejected = sessions.getValue("s-rejected").jsonObject
        assertTrue(rejected.getValue("isQuarantined").jsonPrimitive.content.toBoolean())
        // The generation is what separates "nobody sent this" from "it is being
        // rewritten faster than it uploads"; the coach section exports its own
        // for the same reason.
        assertEquals(4, rejected.getValue("dirtyGeneration").jsonPrimitive.content.toInt())
    }

    @Test
    @DisplayName("RR samples are counted per session, never dumped row by row")
    fun hrSamplesAreSummarized() = runTest {
        val dao = FakeExportDao().apply {
            hrSampleSummaries = listOf(
                HrSampleSummary(
                    sessionId = "s-anchored", total = 3, pending = 1, quarantined = 1,
                    firstTimestampMs = 1_770_000_000_000, lastTimestampMs = 1_770_000_004_100,
                ),
            )
        }

        val model = exportOf(dao)

        val counts = model.getValue("hr").jsonObject.getValue("sample_counts").jsonObject
            .getValue("s-anchored").jsonObject
        assertEquals(3, counts.getValue("total").jsonPrimitive.content.toInt())
        assertEquals(1, counts.getValue("pending").jsonPrimitive.content.toInt())
        assertEquals(1, counts.getValue("quarantined").jsonPrimitive.content.toInt())
        assertEquals(1_770_000_000_000L, counts.getValue("firstTimestampMs").jsonPrimitive.content.toLong())
        assertEquals(1_770_000_004_100L, counts.getValue("lastTimestampMs").jsonPrimitive.content.toLong())
        // An hour of capture is thousands of RR rows and the export holds the
        // whole model in memory; a key named for them would mean it stopped
        // being bounded by anything a user cannot make bigger.
        assertFalse(model.getValue("hr").jsonObject.containsKey("samples"))
    }

    @Test
    @DisplayName("set events are an ordered array carrying the toggle that produced each one")
    fun hrSetEventsShape() = runTest {
        val dao = FakeExportDao().apply {
            setEvents = listOf(
                SetEventEntity(
                    eventId = "e-set", date = "2030-01-03", exerciseKey = "fixture-adhoc-lift",
                    setNum = 1, action = SetEventEntity.ACTION_CHECK,
                    clientTimestampMs = 1_770_000_010_000, sessionId = "s-anchored", isSynced = true,
                ),
                SetEventEntity(
                    eventId = "e-item", date = "2030-01-04", exerciseKey = "fixture-adhoc-checklist",
                    itemKey = "fixture-item-a", action = SetEventEntity.ACTION_UNCHECK,
                    clientTimestampMs = 1_770_000_020_000, isQuarantined = true,
                ),
            )
        }

        val events = exportOf(dao).getValue("hr").jsonObject.getValue("set_events").jsonArray

        assertEquals(listOf("e-set", "e-item"), events.map { it.jsonObject.getValue("eventId").jsonPrimitive.content })
        val set = events[0].jsonObject
        assertEquals(1, set.getValue("setNum").jsonPrimitive.content.toInt())
        assertFalse(set.containsKey("itemKey"))
        assertEquals("s-anchored", set.getValue("sessionId").jsonPrimitive.content)

        val item = events[1].jsonObject
        assertEquals("fixture-item-a", item.getValue("itemKey").jsonPrimitive.content)
        assertFalse(item.containsKey("setNum"))
        // No capture was running, so there is nothing to correlate against.
        assertFalse(item.containsKey("sessionId"))
        assertEquals("uncheck", item.getValue("action").jsonPrimitive.content)
        assertTrue(item.getValue("isQuarantined").jsonPrimitive.content.toBoolean())
    }

    @Test
    @DisplayName("a device that has never captured still writes the hr section, empty")
    fun hrSectionExistsWhenEmpty() = runTest {
        val hr = exportOf(FakeExportDao()).getValue("hr").jsonObject

        // An absent section would read as an older export format rather than as
        // "no captures", which is the same reason `app.activeModule` is a null.
        assertTrue(hr.getValue("sessions").jsonObject.isEmpty())
        assertTrue(hr.getValue("sample_counts").jsonObject.isEmpty())
        assertTrue(hr.getValue("set_events").jsonArray.isEmpty())
    }
}

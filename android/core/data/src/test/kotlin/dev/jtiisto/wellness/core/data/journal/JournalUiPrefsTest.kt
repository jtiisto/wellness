package dev.jtiisto.wellness.core.data.journal

import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.db.JournalEntryEntity
import dev.jtiisto.wellness.core.data.db.JournalTrackerEntity
import dev.jtiisto.wellness.core.data.network.JournalApi
import dev.jtiisto.wellness.core.data.network.ServerConfig
import dev.jtiisto.wellness.core.data.network.buildHttpClient
import dev.jtiisto.wellness.core.data.sync.ServerSessionGate
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * [JournalUiPrefs] and the caption it feeds.
 *
 * The load-bearing test is the first one: these keys share a table with the
 * sync watermark, and the whole design rests on the upload builder reading only
 * the tracker and entry tables. If that ever stops being true, local UI state
 * starts going to the server.
 */
class JournalUiPrefsTest {

    private val today = LocalDate.parse("2026-08-06")
    private val utc = ZoneId.of("UTC")

    @Test
    @DisplayName("`ui.` keys never reach an upload body")
    fun uiKeysStayLocal() = runTest {
        val world = World()
        world.prefs.toggleCategoryExpanded("Supplements")
        world.prefs.markValueUpdated("2026-08-06", "t1")
        world.seedDirtyTracker()
        world.seedDirtyEntry()

        world.store.triggerSync()

        val body = world.bodies.last()
        assertFalse(body.contains("ui."), "an upload body must carry no UI preference key: $body")
        assertFalse(body.contains("expandedCategories"))
        assertFalse(body.contains("valueUpdatedTimes"))
        assertTrue(body.contains("t1"), "sanity: the dirty tracker really did upload")
    }

    // ---- expanded categories ----------------------------------------------

    @Test
    @DisplayName("categories start collapsed and toggle on and off")
    fun toggleCategories() = runTest {
        val world = World()
        assertEquals(emptySet<String>(), world.prefs.expandedCategories.first())

        world.prefs.toggleCategoryExpanded("Habits")
        assertEquals(setOf("Habits"), world.prefs.expandedCategories.first())

        world.prefs.toggleCategoryExpanded("Supplements")
        assertEquals(setOf("Habits", "Supplements"), world.prefs.expandedCategories.first())

        world.prefs.toggleCategoryExpanded("Habits")
        assertEquals(setOf("Supplements"), world.prefs.expandedCategories.first())
    }

    @Test
    @DisplayName("the expanded set round-trips through storage as JSON")
    fun toggleSerialization() = runTest {
        val world = World()
        world.prefs.toggleCategoryExpanded("Habits")
        world.prefs.toggleCategoryExpanded("Supplements")

        assertEquals(
            """["Habits","Supplements"]""",
            world.dao.meta[JournalUiPrefs.KEY_EXPANDED_CATEGORIES],
        )
    }

    @Test
    @DisplayName("a corrupted preference blob resets rather than taking the tab down")
    fun corruptedBlob() = runTest {
        val world = World()
        world.dao.meta[JournalUiPrefs.KEY_EXPANDED_CATEGORIES] = "{not json"
        world.dao.meta[JournalUiPrefs.KEY_VALUE_UPDATED_TIMES] = "]["

        assertEquals(emptySet<String>(), world.prefs.expandedCategories.first())
        assertEquals(emptyMap<String, String>(), world.prefs.valueUpdatedTimes.first())
    }

    // ---- value-updated stamps ----------------------------------------------

    @Test
    @DisplayName("markValueUpdated stamps the entry key with a UTC instant")
    fun stampsEntryKey() = runTest {
        val world = World(now = Instant.parse("2026-08-06T15:42:07Z"))

        world.prefs.markValueUpdated("2026-08-06", "t1")

        assertEquals(
            mapOf("2026-08-06|t1" to "2026-08-06T15:42:07Z"),
            world.prefs.valueUpdatedTimes.first(),
        )
    }

    @Test
    @DisplayName("stamps are pruned at the same inclusive cutoff as the entries they annotate")
    fun prunesAtTheEntryCutoff() = runTest {
        val world = World()
        val cutoff = localDataWindowStart(today)
        assertEquals("2026-07-30", cutoff, "sanity: the window starts seven days back")

        world.seedStamps(
            "2026-07-29|t1" to "old", // before the cutoff — dropped
            "$cutoff|t1" to "edge", // on the cutoff — kept, the boundary is inclusive
            "2026-08-05|t1" to "recent",
        )

        world.prefs.markValueUpdated("2026-08-06", "t2")

        val stamps = world.prefs.valueUpdatedTimes.first()
        assertEquals(
            setOf("$cutoff|t1", "2026-08-05|t1", "2026-08-06|t2"),
            stamps.keys,
        )
        // The same predicate the entry prune uses, so the two can never diverge.
        assertTrue(stamps.keys.all { isWithinLastNDays(entryKeyDate(it), today) })
        assertFalse(isWithinLastNDays("2026-07-29", today))
    }

    @Test
    @DisplayName("re-stamping the same key overwrites rather than accumulating")
    fun restampOverwrites() = runTest {
        val world = World(now = Instant.parse("2026-08-06T10:00:00Z"))
        world.prefs.markValueUpdated("2026-08-06", "t1")
        world.now = Instant.parse("2026-08-06T18:30:00Z")

        world.prefs.markValueUpdated("2026-08-06", "t1")

        assertEquals(
            mapOf("2026-08-06|t1" to "2026-08-06T18:30:00Z"),
            world.prefs.valueUpdatedTimes.first(),
        )
    }

    // ---- caption formatting -------------------------------------------------

    @Test
    @DisplayName("formatLastUpdated: same local day shows the time alone")
    fun captionSameDay() {
        assertEquals(
            "3:42 PM",
            formatLastUpdated("2026-08-06T15:42:07Z", "2026-08-06", utc, Locale.US),
        )
    }

    @Test
    @DisplayName("formatLastUpdated: another day shows a short date too")
    fun captionOtherDay() {
        assertEquals(
            "Jul 3, 3:42 PM",
            formatLastUpdated("2026-07-03T15:42:07Z", "2026-08-06", utc, Locale.US),
        )
    }

    @Test
    @DisplayName("formatLastUpdated: a 24-hour locale gets a 24-hour clock and its own date order")
    fun captionRespectsTheLocale() {
        // The caption is the locale's, not en-US's with a translated month.
        assertEquals(
            "15:42",
            formatLastUpdated("2026-08-06T15:42:07Z", "2026-08-06", utc, Locale.GERMANY),
        )
        assertEquals(
            "03.07, 15:42",
            formatLastUpdated("2026-07-03T15:42:07Z", "2026-08-06", utc, Locale.GERMANY),
        )
    }

    @Test
    @DisplayName("formatLastUpdated: the year is dropped — every stamp is inside a seven-day window")
    fun captionOmitsTheYear() {
        for (locale in listOf(Locale.US, Locale.UK, Locale.GERMANY, Locale.FRANCE, Locale.JAPAN)) {
            val caption = requireNotNull(
                formatLastUpdated("2026-07-03T15:42:07Z", "2026-08-06", utc, locale),
            )
            assertFalse(caption.contains("2026"), "$locale caption carried the year: $caption")
        }
    }

    @Test
    @DisplayName("formatLastUpdated: the comparison is against the LOCAL date, not the UTC one")
    fun captionCrossesUtcMidnight() {
        // 01:30 UTC on the 7th is 18:30 on the 6th in Los Angeles: viewing the
        // 6th, this is "today at 6:30 PM", not a stamp from another day.
        val la = ZoneId.of("America/Los_Angeles")
        assertEquals(
            "6:30 PM",
            formatLastUpdated("2026-08-07T01:30:00Z", "2026-08-06", la, Locale.US),
        )
        // The same instant read in UTC falls on the 7th, so viewing the 6th it
        // is a different day and carries its date.
        assertEquals(
            "Aug 7, 1:30 AM",
            formatLastUpdated("2026-08-07T01:30:00Z", "2026-08-06", utc, Locale.US),
        )
    }

    @Test
    @DisplayName("formatLastUpdated: no stamp, or an unreadable one, has no caption")
    fun captionAbsentOrCorrupt() {
        assertNull(formatLastUpdated(null, "2026-08-06", utc, Locale.US))
        assertNull(formatLastUpdated("", "2026-08-06", utc, Locale.US))
        assertNull(formatLastUpdated("not-a-timestamp", "2026-08-06", utc, Locale.US))
    }

    // ---- the server-switch fence -------------------------------------------

    @Test
    @DisplayName("both writers are refused after the session closes — `journal_meta` is a wiped table")
    fun postCloseWritesAreRefused() = runTest {
        val world = World()
        world.session.close()

        world.prefs.toggleCategoryExpanded("Habits")
        world.prefs.markValueUpdated("2026-08-06", "t1")

        // Device-local is not server-independent: both values are keyed by
        // tracker id, and a stamp written after the wipe annotates a tracker the
        // new server has never sent.
        assertTrue(world.dao.meta.isEmpty(), "post-wipe UI state reached journal_meta: ${world.dao.meta}")
    }

    @Test
    @DisplayName("the refusal does not escape into the tap handler that made it")
    fun refusalDoesNotEscape() = runTest {
        val world = World()
        world.prefs.toggleCategoryExpanded("Habits")
        world.session.close()

        // A crash here would take the journal tab down in the two seconds
        // between confirming a switch and the process exiting — for a write
        // whose row is about to be deleted anyway.
        world.prefs.toggleCategoryExpanded("Supplements")

        assertEquals(setOf("Habits"), world.prefs.expandedCategories.first())
    }

    private inner class World(var now: Instant = Instant.parse("2026-08-06T12:00:00Z")) {
        val dao = FakeJournalDao()
        val bodies = mutableListOf<String>()

        /** Open unless a test closes it — the server-switch fence. */
        val session = ServerSessionGate()

        val prefs = JournalUiPrefs(
            dao = dao,
            session = session,
            json = WellnessJson,
            now = { now },
            today = { today },
        )

        private val api: JournalApi by lazy {
            val config = ServerConfig("http://localhost:9001/wellness")
            val engine = MockEngine { request ->
                val payload = if (request.method == HttpMethod.Post) {
                    bodies += request.body.toByteArray().decodeToString()
                    """{"serverTime":"s1","acceptedTrackers":[],"acceptedEntries":[],""" +
                        """"rejectedTrackers":[],"rejectedEntries":[]}"""
                } else {
                    """{"config":[],"days":{},"deletedTrackers":[],"serverTime":"s1"}"""
                }
                respond(
                    content = payload,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
            JournalApi(buildHttpClient(engine, config, WellnessJson, mockk(relaxed = true)), config)
        }

        val store: JournalSyncStore by lazy {
            JournalSyncStore(
                session = session,
                dao = dao,
                api = api,
                isOnline = { true },
                json = WellnessJson,
                today = { today },
                newClientId = { "fixed-client" },
            )
        }

        fun seedStamps(vararg entries: Pair<String, String>) {
            dao.meta[JournalUiPrefs.KEY_VALUE_UPDATED_TIMES] =
                kotlinx.serialization.json.JsonObject(entries.toMap().mapValues { JsonPrimitive(it.value) })
                    .toString()
        }

        fun seedDirtyTracker() {
            val dto = TrackerDto(id = "t1", name = "Water", category = "Habits", type = "simple")
            dao.trackers["t1"] = JournalTrackerEntity(
                id = "t1",
                name = "Water",
                category = "Habits",
                type = "simple",
                deleted = false,
                lastModifiedAt = "s0",
                dataJson = TrackerDtoSerializer.toJson(dto, WellnessJson).toString(),
                isDirty = true,
                dirtyGeneration = 1,
            )
        }

        fun seedDirtyEntry() {
            dao.entries["2026-08-06|t1"] = JournalEntryEntity(
                date = "2026-08-06",
                trackerId = "t1",
                valueJson = "5",
                completed = true,
                lastModifiedAt = "s0",
                isDirty = true,
                dirtyGeneration = 1,
            )
        }
    }
}

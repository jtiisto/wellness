package dev.jtiisto.wellness.core.data.journal

import dev.jtiisto.wellness.core.data.sync.DirtyState
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * `test/js/journal-sync-logic.test.js`, transcribed. Pins the
 * optimistic-concurrency upload contract and the dual tracker+entry
 * dirty-clearing rule (the edit-during-sync race).
 *
 * The JS operates on plain objects; the two places this port takes an extra
 * `locallyDeletedIds` argument are the ones where the PWA reads a `_deleted`
 * field off the tracker itself — a reserved key our DTO cannot carry.
 */
class JournalSyncLogicTest {

    // ---- computeUploadPayload (the _baseLastModifiedAt contract) ---------

    @Test
    @DisplayName("computeUploadPayload: existing tracker sends base token, never echoes lastModifiedAt")
    fun uploadSendsBaseToken() {
        val meta = UploadMeta(clientId = "c1", dirtyTrackers = listOf("t1"))
        val config = listOf(
            uploadTracker("t1") {
                put("name", "Water")
                put("lastModifiedAt", "2026-05-01T00:00:00Z")
            },
        )

        val result = computeUploadPayload(meta, config, emptyMap())

        val items = result.payload.configItems()
        assertEquals(1, items.size)
        assertEquals("2026-05-01T00:00:00Z", items[0]["_baseLastModifiedAt"]?.string())
        assertFalse("lastModifiedAt" in items[0], "top-level lastModifiedAt must not be echoed")
        assertEquals("Water", items[0]["name"]?.string())
        assertEquals(listOf("t1"), result.dirtyTrackerIds)
    }

    @Test
    @DisplayName("computeUploadPayload: brand-new tracker omits _baseLastModifiedAt (insert-if-absent)")
    fun uploadOmitsTokenForNewTracker() {
        val meta = UploadMeta(clientId = "c1", dirtyTrackers = listOf("t1"))
        val config = listOf(uploadTracker("t1") { put("name", "New") })

        val result = computeUploadPayload(meta, config, emptyMap())

        assertFalse("_baseLastModifiedAt" in result.payload.configItems()[0])
    }

    @Test
    @DisplayName("computeUploadPayload: dirty tracker missing from config is skipped (orphan guard)")
    fun uploadSkipsOrphanTracker() {
        val meta = UploadMeta(clientId = "c1", dirtyTrackers = listOf("gone"))

        val result = computeUploadPayload(meta, emptyList(), emptyMap())

        assertEquals(0, result.payload.configItems().size)
        assertEquals(emptyList<String>(), result.dirtyTrackerIds)
    }

    @Test
    @DisplayName("computeUploadPayload: entries carry base token; new entry omits it; orphan skipped")
    fun uploadEntryTokens() {
        val meta = UploadMeta(
            clientId = "c1",
            dirtyEntries = listOf("2026-05-01|t1", "2026-05-01|t2", "2026-05-02|gone"),
        )
        val logs = mapOf(
            "2026-05-01" to mapOf(
                "t1" to EntryDto(JsonPrimitive(5), completed = true, lastModifiedAt = "2026-05-01T00:00:00Z"),
                "t2" to EntryDto(JsonPrimitive(3), completed = false),
            ),
        )

        val result = computeUploadPayload(meta, emptyList(), logs)

        val day = result.payload.days().getValue("2026-05-01").jsonObject
        assertEquals("2026-05-01T00:00:00Z", day.getValue("t1").jsonObject["_baseLastModifiedAt"]?.string())
        assertEquals("5", day.getValue("t1").jsonObject["value"]?.string())
        assertFalse("_baseLastModifiedAt" in day.getValue("t2").jsonObject)
        assertNull(result.payload.days()["2026-05-02"], "orphan dirty entry must be skipped")
        assertEquals(listOf("2026-05-01|t1", "2026-05-01|t2"), result.dirtyEntryKeys.sorted())
    }

    // ---- the explicit-null and _deleted shapes the JS gets for free ------

    @Test
    @DisplayName("computeUploadPayload: an entry always carries value and completed, as explicit nulls")
    fun uploadEntryKeepsExplicitNulls() {
        val meta = UploadMeta(clientId = "c1", dirtyEntries = listOf("2026-05-01|t1"))
        val logs = mapOf("2026-05-01" to mapOf("t1" to EntryDto()))

        val result = computeUploadPayload(meta, emptyList(), logs)

        val entry = result.payload.days().getValue("2026-05-01").jsonObject.getValue("t1").jsonObject
        assertEquals(JsonNull, entry["value"])
        assertEquals(JsonNull, entry["completed"])
    }

    @Test
    @DisplayName("computeUploadPayload: a locally deleted tracker uploads _deleted alongside its base token")
    fun uploadInjectsDeletedFlag() {
        val meta = UploadMeta(clientId = "c1", dirtyTrackers = listOf("t1"))
        val config = listOf(
            UploadTracker(
                id = "t1",
                data = buildJsonObject {
                    put("id", "t1")
                    put("name", "Water")
                    put("lastModifiedAt", "s1")
                },
                locallyDeleted = true,
            ),
        )

        val item = computeUploadPayload(meta, config, emptyMap()).payload.configItems()[0]

        assertEquals(true, item["_deleted"]?.jsonPrimitiveOrNull()?.content?.toBoolean())
        assertEquals("s1", item["_baseLastModifiedAt"]?.string())
    }

    // ---- computeClearedDirtyState (dual tracker+entry dirty-clearing) ----

    @Test
    @DisplayName("computeClearedDirtyState: uploaded + gen unchanged clears; gen advanced stays dirty")
    fun clearedDirtyStateHonoursGenerations() {
        val next = computeClearedDirtyState(
            // t1 and e2 were re-modified mid-sync.
            trackers = DirtyState(listOf("t1", "t2"), mapOf("t1" to 2L, "t2" to 1L)),
            entries = DirtyState(listOf("d|e1", "d|e2"), mapOf("d|e1" to 1L, "d|e2" to 2L)),
            uploadedTrackerIds = listOf("t1", "t2"),
            uploadedEntryKeys = listOf("d|e1", "d|e2"),
            snapshotTrackerGens = mapOf("t1" to 1L, "t2" to 1L),
            snapshotEntryGens = mapOf("d|e1" to 1L, "d|e2" to 1L),
        )

        assertEquals(listOf("t1"), next.trackers.keys)
        assertEquals(listOf("d|e2"), next.entries.keys)
        assertEquals(mapOf("t1" to 2L), next.trackers.generations)
        assertEquals(mapOf("d|e2" to 2L), next.entries.generations)
    }

    @Test
    @DisplayName("computeClearedDirtyState: not-uploaded items stay dirty")
    fun clearedDirtyStateKeepsUnuploaded() {
        val next = computeClearedDirtyState(
            trackers = DirtyState(listOf("t1", "t2"), mapOf("t1" to 1L, "t2" to 1L)),
            entries = DirtyState(listOf("d|e1"), mapOf("d|e1" to 1L)),
            uploadedTrackerIds = listOf("t1"),
            uploadedEntryKeys = emptyList(),
            snapshotTrackerGens = mapOf("t1" to 1L),
            snapshotEntryGens = emptyMap(),
        )

        assertEquals(listOf("t2"), next.trackers.keys)
        assertEquals(listOf("d|e1"), next.entries.keys)
        assertEquals(mapOf("t2" to 1L), next.trackers.generations)
        assertEquals(mapOf("d|e1" to 1L), next.entries.generations)
    }

    // ---- computeAcceptedApply (stamp server tokens onto accepted rows) ---

    @Test
    @DisplayName("computeAcceptedApply: stamps lastModifiedAt onto matching trackers and entries")
    fun acceptedApplyStamps() {
        val config = listOf(
            TrackerDto(id = "t1", name = "Water", lastModifiedAt = "old"),
            TrackerDto(id = "t2", name = "Sleep", lastModifiedAt = "old"),
        )
        val logs = mapOf(
            "2026-05-01" to mapOf("t1" to EntryDto(JsonPrimitive(5), completed = true, lastModifiedAt = "old")),
        )

        val next = computeAcceptedApply(
            listOf(AcceptedTrackerDto("t1", "new-t1")),
            listOf(AcceptedEntryDto("2026-05-01", "t1", "new-e1")),
            config,
            logs,
        )

        assertEquals("new-t1", next.trackerConfig.first { it.id == "t1" }.lastModifiedAt)
        assertEquals("old", next.trackerConfig.first { it.id == "t2" }.lastModifiedAt)
        val entry = next.dailyLogs.getValue("2026-05-01").getValue("t1")
        assertEquals("new-e1", entry.lastModifiedAt)
        assertEquals(JsonPrimitive(5), entry.value)
    }

    @Test
    @DisplayName("computeAcceptedApply: skips accepted entries not present locally")
    fun acceptedApplySkipsMissingEntries() {
        val logs = mapOf("2026-05-01" to mapOf("t1" to EntryDto(JsonPrimitive(1), completed = false)))

        val next = computeAcceptedApply(
            emptyList(),
            listOf(AcceptedEntryDto("2026-05-01", "gone", "x")),
            emptyList(),
            logs,
        )

        assertNull(next.dailyLogs.getValue("2026-05-01")["gone"])
        assertEquals(JsonPrimitive(1), next.dailyLogs.getValue("2026-05-01").getValue("t1").value)
    }

    @Test
    @DisplayName("computeAcceptedApply: empty inputs return the same references (no spurious write)")
    fun acceptedApplyIsIdentityWhenEmpty() {
        val config = listOf(TrackerDto(id = "t1"))
        val logs = mapOf("2026-05-01" to emptyMap<String, EntryDto>())

        val next = computeAcceptedApply(emptyList(), emptyList(), config, logs)

        assertSame(config, next.trackerConfig)
        assertSame(logs, next.dailyLogs)
    }

    // ---- computeRejectedApply (recover from rejected uploads in-cycle) ---

    @Test
    @DisplayName("computeRejectedApply: upserts non-deleted serverRow; appends when absent")
    fun rejectedApplyUpserts() {
        val config = listOf(TrackerDto(id = "t1", name = "stale"))

        val next = computeRejectedApply(
            listOf(
                RejectedTrackerDto("t1", serverRow = TrackerDto(id = "t1", name = "server-wins", lastModifiedAt = "s1")),
                RejectedTrackerDto("t2", serverRow = TrackerDto(id = "t2", name = "brand-new", lastModifiedAt = "s2")),
            ),
            emptyList(),
            config,
            emptyMap(),
        )

        assertEquals("server-wins", next.trackerConfig.first { it.id == "t1" }.name)
        assertEquals("brand-new", next.trackerConfig.first { it.id == "t2" }.name)
        assertEquals(emptyList<String>(), next.trackerIdsToDelete)
    }

    @Test
    @DisplayName("computeRejectedApply: soft-deleted serverRow routes to trackerIdsToDelete, not upsert")
    fun rejectedApplyRoutesDeletes() {
        val config = listOf(TrackerDto(id = "t1", name = "keep-me"))

        val next = computeRejectedApply(
            listOf(RejectedTrackerDto("t1", serverRow = TrackerDto(id = "t1", deleted = true))),
            emptyList(),
            config,
            emptyMap(),
        )

        assertEquals(listOf("t1"), next.trackerIdsToDelete)
        // Not upserted: the caller removes it through the full delete cleanup.
        assertEquals("keep-me", next.trackerConfig.first { it.id == "t1" }.name)
    }

    @Test
    @DisplayName("computeRejectedApply: overwrites rejected entry with serverRow value/completed/stamp")
    fun rejectedApplyOverwritesEntry() {
        val logs = mapOf("2026-05-01" to mapOf("t1" to EntryDto(JsonPrimitive(99), completed = false)))

        val next = computeRejectedApply(
            emptyList(),
            listOf(
                RejectedEntryDto(
                    date = "2026-05-01",
                    trackerId = "t1",
                    serverRow = EntryDto(JsonPrimitive(7), completed = true, lastModifiedAt = "s1"),
                ),
            ),
            emptyList(),
            logs,
        )

        assertEquals(
            EntryDto(JsonPrimitive(7), completed = true, lastModifiedAt = "s1"),
            next.dailyLogs.getValue("2026-05-01").getValue("t1"),
        )
    }

    @Test
    @DisplayName("computeRejectedApply: rejection with no serverRow is skipped")
    fun rejectedApplySkipsMissingServerRow() {
        val config = listOf(TrackerDto(id = "t1", name = "orig"))

        val next = computeRejectedApply(listOf(RejectedTrackerDto("t1")), emptyList(), config, emptyMap())

        assertEquals("orig", next.trackerConfig.first { it.id == "t1" }.name)
        assertEquals(emptyList<String>(), next.trackerIdsToDelete)
    }

    // ---- computeDropDeletedTrackers (server-side delete cleanup) ---------

    @Test
    @DisplayName("computeDropDeletedTrackers: drops config + entries + dirty (tracker & entry-level) + gens")
    fun dropDeletedPurgesEverything() {
        val config = listOf(TrackerDto(id = "t1"), TrackerDto(id = "t2"))
        val logs = mapOf(
            "2026-05-01" to mapOf("t1" to EntryDto(JsonPrimitive(1)), "t2" to EntryDto(JsonPrimitive(2))),
            "2026-05-02" to mapOf("t2" to EntryDto(JsonPrimitive(3))),
        )
        val meta = DirtyMeta(
            trackers = DirtyState(listOf("t1", "t2"), mapOf("t1" to 1L, "t2" to 1L)),
            entries = DirtyState(
                listOf("2026-05-01|t1", "2026-05-02|t2"),
                mapOf("2026-05-01|t1" to 1L, "2026-05-02|t2" to 1L),
            ),
        )

        val next = computeDropDeletedTrackers(listOf("t1"), config, logs, meta)

        assertEquals(listOf(TrackerDto(id = "t2")), next.trackerConfig)
        assertFalse("t1" in next.dailyLogs.getValue("2026-05-01"))
        assertEquals(JsonPrimitive(2), next.dailyLogs.getValue("2026-05-01").getValue("t2").value)
        assertTrue(next.logsChanged)
        assertTrue(next.dirtyChanged)
        assertEquals(listOf("t2"), next.meta.trackers.keys)
        assertEquals(listOf("2026-05-02|t2"), next.meta.entries.keys)
        assertEquals(mapOf("t2" to 1L), next.meta.trackers.generations)
        assertEquals(mapOf("2026-05-02|t2" to 1L), next.meta.entries.generations)
    }

    @Test
    @DisplayName("computeDropDeletedTrackers: dirtyChanged false when no dirty matched the deleted id")
    fun dropDeletedReportsNoDirtyChange() {
        val config = listOf(TrackerDto(id = "t1"), TrackerDto(id = "t2"))
        val logs = mapOf("2026-05-01" to mapOf("t1" to EntryDto(JsonPrimitive(1))))
        val meta = DirtyMeta(
            trackers = DirtyState(listOf("t2"), mapOf("t2" to 1L)),
            entries = DirtyState(listOf("2026-05-01|t2"), mapOf("2026-05-01|t2" to 1L)),
        )

        val next = computeDropDeletedTrackers(listOf("t1"), config, logs, meta)

        assertEquals(listOf(TrackerDto(id = "t2")), next.trackerConfig)
        assertTrue(next.logsChanged)
        assertFalse(next.dirtyChanged)
        assertEquals(mapOf("t2" to 1L), next.meta.trackers.generations)
        assertEquals(mapOf("2026-05-01|t2" to 1L), next.meta.entries.generations)
    }

    // ---- computePruneDeletedTrackers (local delete cleanup post-sync) ----

    @Test
    @DisplayName("computePruneDeletedTrackers: removes locally deleted trackers and their entries")
    fun pruneDeletedRemovesTrackersAndEntries() {
        val config = listOf(TrackerDto(id = "t1"), TrackerDto(id = "t2"))
        val logs = mapOf(
            "2026-05-01" to mapOf("t1" to EntryDto(JsonPrimitive(1)), "t2" to EntryDto(JsonPrimitive(2))),
            "2026-05-02" to mapOf("t2" to EntryDto(JsonPrimitive(3))),
        )

        val next = requireNotNull(computePruneDeletedTrackers(config, setOf("t1"), logs))

        assertEquals(listOf(TrackerDto(id = "t2")), next.trackerConfig)
        assertFalse("t1" in next.dailyLogs.getValue("2026-05-01"))
        assertEquals(JsonPrimitive(2), next.dailyLogs.getValue("2026-05-01").getValue("t2").value)
        assertEquals(JsonPrimitive(3), next.dailyLogs.getValue("2026-05-02").getValue("t2").value)
        assertTrue(next.logsChanged)
    }

    @Test
    @DisplayName("computePruneDeletedTrackers: returns null when nothing is locally deleted")
    fun pruneDeletedReturnsNull() {
        val logs = mapOf("2026-05-01" to mapOf("t1" to EntryDto()))

        assertNull(computePruneDeletedTrackers(listOf(TrackerDto(id = "t1")), emptySet(), logs))
    }

    @Test
    @DisplayName("computePruneDeletedTrackers: logsChanged false when the deleted tracker has no entries")
    fun pruneDeletedReportsNoLogChange() {
        val config = listOf(TrackerDto(id = "t1"), TrackerDto(id = "t2"))
        val logs = mapOf("2026-05-01" to mapOf("t2" to EntryDto(JsonPrimitive(2))))

        val next = requireNotNull(computePruneDeletedTrackers(config, setOf("t1"), logs))

        assertEquals(listOf(TrackerDto(id = "t2")), next.trackerConfig)
        assertFalse(next.logsChanged)
    }

    // ---- computeNormalizedConfig (legacy frequency/weeklyDay migration) --

    @Test
    @DisplayName("computeNormalizedConfig: null when nothing to normalize (steady state)")
    fun normalizeConverged() {
        val config = listOf(
            TrackerDto(id = "t1", scheduleHistory = listOf(ScheduleSegmentDto("0000-01-01", listOf(1)))),
            TrackerDto(id = "t2"),
        )

        assertNull(computeNormalizedConfig(config, emptySet()))
    }

    @Test
    @DisplayName("computeNormalizedConfig: converts legacy trackers and reports changedIds")
    fun normalizeConvertsLegacy() {
        val config = listOf(
            TrackerDto(id = "t1", extras = buildJsonObject { put("frequency", "weekly"); put("weeklyDay", 2) }),
            TrackerDto(id = "t2", scheduleHistory = listOf(ScheduleSegmentDto("0000-01-01", listOf(1)))),
            TrackerDto(id = "t3", extras = buildJsonObject { put("frequency", "daily") }),
        )

        val result = requireNotNull(computeNormalizedConfig(config, emptySet()))

        assertEquals(listOf("t1", "t3"), result.changedIds)
        val t1 = result.config.first { it.id == "t1" }
        assertEquals(listOf(ScheduleSegmentDto("0000-01-01", listOf(2))), t1.scheduleHistory)
        assertFalse("frequency" in t1.extras)
        assertFalse("weeklyDay" in t1.extras)
        // Untouched tracker keeps its identity.
        assertSame(config[1], result.config[1])
    }

    @Test
    @DisplayName("computeNormalizedConfig: leaves locally deleted trackers untouched")
    fun normalizeSkipsDeleted() {
        val config = listOf(
            TrackerDto(id = "t1", extras = buildJsonObject { put("frequency", "weekly"); put("weeklyDay", 1) }),
        )

        assertNull(computeNormalizedConfig(config, setOf("t1")))
    }

    @Test
    @DisplayName("normalizeTrackerSchedule: an existing schedule survives, the legacy pair still goes")
    fun normalizeKeepsExistingSchedule() {
        val existing = listOf(ScheduleSegmentDto("2026-01-01", listOf(0, 6)))
        val tracker = TrackerDto(
            id = "t1",
            scheduleHistory = existing,
            extras = buildJsonObject { put("frequency", "weekly"); put("weeklyDay", 2) },
        )

        val normalized = normalizeTrackerSchedule(tracker)

        assertEquals(existing, normalized.scheduleHistory)
        assertEquals(JsonObject(emptyMap()), normalized.extras)
    }

    @Test
    @DisplayName("normalizeTrackerSchedule: an out-of-range weeklyDay strips the pair without inventing a schedule")
    fun normalizeRejectsBadWeeklyDay() {
        val tracker = TrackerDto(
            id = "t1",
            extras = buildJsonObject { put("frequency", "weekly"); put("weeklyDay", 9) },
        )

        val normalized = normalizeTrackerSchedule(tracker)

        assertNull(normalized.scheduleHistory)
        assertEquals(JsonObject(emptyMap()), normalized.extras)
    }

    // ---- the retention window --------------------------------------------

    @Test
    @DisplayName("isWithinLastNDays: the cutoff day itself is inside the window, the day before is not")
    fun retentionBoundary() {
        val today = LocalDate.parse("2026-08-06")

        assertTrue(isWithinLastNDays("2026-07-30", today))
        assertFalse(isWithinLastNDays("2026-07-29", today))
        assertTrue(isWithinLastNDays("2026-08-06", today))
        // Open-ended forward: a future-dated entry is never pruned.
        assertTrue(isWithinLastNDays("2026-08-10", today))
        assertEquals("2026-07-30", localDataWindowStart(today))
    }

    // ---- helpers ---------------------------------------------------------

    private fun uploadTracker(id: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        UploadTracker(id, buildJsonObject { put("id", id); build() })

    private fun JsonObject.configItems(): List<JsonObject> = getValue("config").jsonArray.map { it.jsonObject }

    private fun JsonObject.days(): Map<String, kotlinx.serialization.json.JsonElement> =
        getValue("days").jsonObject

    private fun kotlinx.serialization.json.JsonElement.string(): String? = jsonPrimitiveOrNull()?.content

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive
}

package dev.jtiisto.wellness.core.data.coach

import dev.jtiisto.wellness.core.data.WellnessJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The transcribed `test/js/coach-sync-logic.test.js` — all 27 cases, 1:1.
 *
 * The fixtures are the JS ones verbatim (they are synthetic: `d1`/`ex_1`
 * placeholders and made-up tokens). Where the port's shape differs from the
 * JS — `nextDirtyAfterApply` returns the shared `DirtyState` rather than a
 * coach-specific pair — the assertion follows the port; nothing else moves.
 *
 * The tombstone-verdict and per-record arbitration cases go beyond the
 * transcription: they pin decisions the JS never had to make, and each one
 * marks a way a mid-sync edit silently destroyed another device's data.
 */
class CoachSyncLogicTest {

    private fun obj(text: String): JsonObject = WellnessJson.parseToJsonElement(text).jsonObject

    private fun logs(vararg pairs: Pair<String, String>): Map<String, JsonObject> =
        pairs.associate { (date, text) -> date to obj(text) }

    // ---- content predicates ----------------------------------------------

    @Test
    @DisplayName("logHasExerciseContent: true only for real logged data")
    fun logHasExerciseContentOnlyForRealData() {
        assertFalse(logHasExerciseContent(obj("{}")))
        assertFalse(logHasExerciseContent(obj("""{"_lastModifiedAt":"x","_lastModifiedBy":"y"}""")))
        assertFalse(logHasExerciseContent(obj("""{"session_feedback":{"general_notes":"hi"}}""")))
        assertFalse(logHasExerciseContent(obj("""{"ex_1":{"sets":[]}}""")))
        assertFalse(logHasExerciseContent(obj("""{"ex_1":{"duration_min":null}}""")))
        assertTrue(logHasExerciseContent(obj("""{"ex_1":{"sets":[{"reps":5}]}}""")))
        assertTrue(logHasExerciseContent(obj("""{"ex_1":{"completed_items":["a"]}}""")))
        assertTrue(logHasExerciseContent(obj("""{"ex_1":{"duration_min":15}}""")))
    }

    @Test
    @DisplayName("hasFeedbackContent: true only for non-empty feedback")
    fun hasFeedbackContentOnlyForNonEmpty() {
        assertFalse(hasFeedbackContent(obj("{}")))
        assertFalse(hasFeedbackContent(obj("""{"session_feedback":{}}""")))
        assertFalse(hasFeedbackContent(obj("""{"session_feedback":{"general_notes":"   "}}""")))
        assertTrue(hasFeedbackContent(obj("""{"session_feedback":{"pain_discomfort":"sore"}}""")))
        assertTrue(hasFeedbackContent(obj("""{"session_feedback":{"general_notes":"felt good"}}""")))
    }

    @Test
    @DisplayName("logHasUploadableContent: exercise data OR feedback, but not empty")
    fun uploadableContentIsDataOrFeedback() {
        assertFalse(logHasUploadableContent(obj("""{"session_feedback":{}}""")))
        assertFalse(logHasUploadableContent(obj("""{"_lastModifiedAt":"x"}""")))
        // feedback-only
        assertTrue(logHasUploadableContent(obj("""{"session_feedback":{"general_notes":"note"}}""")))
        assertTrue(logHasUploadableContent(obj("""{"ex_1":{"sets":[{"reps":5}]}}""")))
    }

    // ---- nextDirtyAfterApply (the clear-on-upload rule) ------------------

    @Test
    @DisplayName("nextDirtyAfterApply: applied + generation unchanged → cleared")
    fun clearsWhenGenerationUnchanged() {
        val next = nextDirtyAfterApply(
            appliedDates = listOf("2026-05-01", "2026-05-02"),
            snapshotGens = mapOf("2026-05-01" to 1L, "2026-05-02" to 1L),
            dirtyDates = listOf("2026-05-01", "2026-05-02"),
            dirtyDateGenerations = mapOf("2026-05-01" to 1L, "2026-05-02" to 1L),
        )

        assertEquals(emptyList<String>(), next.keys)
        assertEquals(emptyMap<String, Long>(), next.generations)
    }

    @Test
    @DisplayName("nextDirtyAfterApply: re-modified during sync (gen advanced) stays dirty")
    fun reModifiedStaysDirty() {
        val next = nextDirtyAfterApply(
            appliedDates = listOf("2026-05-01", "2026-05-02"),
            snapshotGens = mapOf("2026-05-01" to 1L, "2026-05-02" to 1L),
            dirtyDates = listOf("2026-05-01", "2026-05-02"),
            // 05-01 edited mid-sync
            dirtyDateGenerations = mapOf("2026-05-01" to 2L, "2026-05-02" to 1L),
        )

        assertEquals(listOf("2026-05-01"), next.keys)
        assertEquals(mapOf("2026-05-01" to 2L), next.generations)
    }

    @Test
    @DisplayName("nextDirtyAfterApply: a not-applied dirty date is kept")
    fun notAppliedDateIsKept() {
        val next = nextDirtyAfterApply(
            appliedDates = listOf("2026-05-01"),
            snapshotGens = mapOf("2026-05-01" to 1L),
            dirtyDates = listOf("2026-05-01", "2026-05-02"),
            dirtyDateGenerations = mapOf("2026-05-01" to 1L, "2026-05-02" to 1L),
        )

        assertEquals(listOf("2026-05-02"), next.keys)
        assertEquals(mapOf("2026-05-02" to 1L), next.generations)
    }

    // ---- selectLogsToUpload (the "cleared == sent" invariant) ------------

    @Test
    @DisplayName("selectLogsToUpload: sends content + feedback-only; empty-never-synced and missing are unsatisfiable")
    fun selectSendsContentAndFeedbackOnly() {
        val local = logs(
            "d1" to """{"ex_1":{"sets":[{"reps":5}]}}""", // exercise content → sent
            "d2" to """{"session_feedback":{}}""", // empty, never synced → unsatisfiable
            "d3" to """{"session_feedback":{"general_notes":"n"}}""", // feedback-only → sent
            // d4 has no local log object at all (window-pruned) → unsatisfiable
        )

        val result = selectLogsToUpload(listOf("d1", "d2", "d3", "d4"), local)

        assertEquals(listOf("d1", "d3"), result.logsToUpload.keys.sorted())
        assertEquals(listOf("d1", "d3"), result.uploadedDates.sorted())
        // Unsatisfiable dates are reported so the caller can drop them from the
        // dirty set instead of leaving the client wedged red forever.
        assertEquals(listOf("d2", "d4"), result.unsatisfiableDates.sorted())
    }

    @Test
    @DisplayName("selectLogsToUpload: a token-bearing EMPTY log uploads as a deletion update")
    fun emptyButSyncedLogStillUploads() {
        // The user logged a set, synced (so the day carries server tokens), then
        // deleted the set. The emptied log must still upload so the server clears
        // its copy — per-record base tokens make the overwrite arbitration-safe.
        val local = logs(
            "d1" to """{"_lastModified":"day-tok","session_feedback":{},""" +
                """"ex_1":{"_lastModified":"ex-tok","sets":[]}}""",
        )

        val result = selectLogsToUpload(listOf("d1"), local)

        assertEquals(listOf("d1"), result.uploadedDates)
        assertEquals(emptyList<String>(), result.unsatisfiableDates)
        val sent = result.logsToUpload.getValue("d1")
        assertEquals(JsonPrimitive("day-tok"), sent["_baseLastModifiedAt"])
        assertEquals(JsonPrimitive("ex-tok"), sent.getValue("ex_1").jsonObject["_baseLastModifiedAt"])
        assertEquals(obj("""{"sets":[]}""")["sets"], sent.getValue("ex_1").jsonObject["sets"])
    }

    // ---- per-record token protocol ---------------------------------------

    @Test
    @DisplayName("withBaseTokens: echoes day + per-exercise _lastModified as _baseLastModifiedAt")
    fun withBaseTokensEchoesStamps() {
        val log = obj(
            """{"_lastModified":"day1",""" +
                """"session_feedback":{"general_notes":"n"},""" + // object, no token → unchanged
                """"ex_1":{"_lastModified":"ex1","sets":[{"reps":5}]},""" +
                """"ex_new":{"sets":[{"reps":3}]}}""", // no token → insert
        )

        val out = withBaseTokens(log)

        assertEquals(JsonPrimitive("day1"), out["_baseLastModifiedAt"])
        assertEquals(JsonPrimitive("ex1"), out.getValue("ex_1").jsonObject["_baseLastModifiedAt"])
        assertFalse("_baseLastModifiedAt" in out.getValue("ex_new").jsonObject)
        assertFalse("_baseLastModifiedAt" in out.getValue("session_feedback").jsonObject)
    }

    @Test
    @DisplayName("selectLogsToUpload: attaches per-record base tokens to sent logs")
    fun selectAttachesBaseTokens() {
        val local = logs(
            "d1" to """{"_lastModified":"day1","ex_1":{"_lastModified":"ex1","sets":[{"reps":5}]}}""",
        )

        val sent = selectLogsToUpload(listOf("d1"), local).logsToUpload.getValue("d1")

        assertEquals(JsonPrimitive("day1"), sent["_baseLastModifiedAt"])
        assertEquals(JsonPrimitive("ex1"), sent.getValue("ex_1").jsonObject["_baseLastModifiedAt"])
    }

    @Test
    @DisplayName("adoptUploadResults: adopts merged serverRow for non-re-modified dates")
    fun adoptsServerRowWholesale() {
        val local = logs("d1" to """{"ex_1":{"local":true}}""")
        val results = logs("d1" to """{"ex_1":{"server":true,"_lastModified":"s1"}}""")

        val next = adoptUploadResults(local, results, mapOf("d1" to 1L), mapOf("d1" to 1L))

        assertEquals(results.getValue("d1"), next.getValue("d1")) // adopted wholesale
        assertEquals(obj("""{"ex_1":{"local":true}}"""), local.getValue("d1")) // input not mutated
    }

    // ---- re-modified dates are reconciled PER RECORD ---------------------

    @Test
    @DisplayName("adoptUploadResults re-modified: a record nobody re-edited adopts the server's verdict")
    fun reModifiedAdoptsTheVerdictForUntouchedRecords() {
        // The DATE moved, but this record is still byte-for-byte what went on
        // the wire, so the server has ruled on it: reps 28 is the remote edit
        // that beat this upload. Keeping the local 32 behind the advanced token
        // left stale content with a winning base — next cycle it passed the
        // server's `stored <= base` test and destroyed that edit.
        val uploaded = logs(
            "d2" to """{"ex_1":{"reps":32,"_lastModified":"old-ex","_baseLastModifiedAt":"old-ex"}}""",
        )
        val local = logs("d2" to """{"_lastModified":"old-day","ex_1":{"reps":32,"_lastModified":"old-ex"}}""")
        val results = logs("d2" to """{"_lastModified":"srv-day","ex_1":{"reps":28,"_lastModified":"srv-ex"}}""")

        val next = adoptUploadResults(local, results, mapOf("d2" to 1L), mapOf("d2" to 2L), uploaded)

        val day = next.getValue("d2")
        assertEquals(obj("""{"reps":28,"_lastModified":"srv-ex"}"""), day["ex_1"])
        assertEquals(JsonPrimitive("srv-day"), day["_lastModified"])
    }

    @Test
    @DisplayName("adoptUploadResults re-modified: a record genuinely re-edited keeps its content and advances")
    fun reModifiedKeepsTheReEditedRecordAndAdvancesItsToken() {
        // reps 99 landed after the payload was built, so it is newer intent
        // than anything the server ruled on. It stays — and its base advances,
        // or the retry echoes a token the server has already moved past and the
        // re-edit is rejected away.
        val uploaded = logs(
            "d2" to """{"ex_1":{"reps":32,"_lastModified":"old-ex","_baseLastModifiedAt":"old-ex"}}""",
        )
        val local = logs("d2" to """{"_lastModified":"old-day","ex_1":{"reps":99,"_lastModified":"old-ex"}}""")
        val results = logs("d2" to """{"_lastModified":"srv-day","ex_1":{"reps":28,"_lastModified":"srv-ex"}}""")

        val next = adoptUploadResults(local, results, mapOf("d2" to 1L), mapOf("d2" to 2L), uploaded)

        val day = next.getValue("d2")
        assertEquals(JsonPrimitive(99), day.getValue("ex_1").jsonObject["reps"])
        assertEquals(JsonPrimitive("srv-ex"), day.getValue("ex_1").jsonObject["_lastModified"])
        assertEquals(JsonPrimitive("srv-day"), day["_lastModified"])
    }

    @Test
    @DisplayName("adoptUploadResults re-modified: one day, one record ruled on and one re-edited")
    fun reModifiedDecidesPerRecordNotPerDate() {
        // The generation counter is per DATE, so it cannot say which record the
        // user touched. Treating the whole day as re-edited shielded ex_a from
        // its own verdict.
        val uploaded = logs(
            "d1" to """{"ex_a":{"reps":5,"_lastModified":"a1","_baseLastModifiedAt":"a1"},""" +
                """"ex_b":{"reps":8,"_lastModified":"b1","_baseLastModifiedAt":"b1"}}""",
        )
        val local = logs(
            "d1" to """{"_lastModified":"old-day","ex_a":{"reps":5,"_lastModified":"a1"},""" +
                """"ex_b":{"reps":12,"_lastModified":"b1"}}""", // only ex_b was re-edited
        )
        val results = logs(
            "d1" to """{"_lastModified":"srv-day","ex_a":{"reps":6,"_lastModified":"a2"},""" +
                """"ex_b":{"reps":8,"_lastModified":"b2"}}""", // ex_a's upload lost to a remote edit
        )

        val next = adoptUploadResults(local, results, mapOf("d1" to 1L), mapOf("d1" to 2L), uploaded)

        val day = next.getValue("d1")
        assertEquals(obj("""{"reps":6,"_lastModified":"a2"}"""), day["ex_a"], "the verdict on an untouched record was discarded")
        assertEquals(JsonPrimitive(12), day.getValue("ex_b").jsonObject["reps"], "the mid-sync re-edit was clobbered")
        assertEquals(JsonPrimitive("b2"), day.getValue("ex_b").jsonObject["_lastModified"])
    }

    @Test
    @DisplayName("adoptUploadResults re-modified: a record created mid-sync is kept, unarbitrated")
    fun reModifiedKeepsARecordThatWasNeverUploaded() {
        // Absent from the upload → created after the payload was built → no
        // verdict exists for it, whatever the server row happens to say.
        val uploaded = logs("d1" to """{"ex_a":{"reps":5,"_baseLastModifiedAt":"a1"}}""")
        val local = logs("d1" to """{"_lastModified":"old-day","ex_a":{"reps":5},"ex_new":{"reps":3}}""")
        val results = logs("d1" to """{"_lastModified":"srv-day","ex_a":{"reps":5,"_lastModified":"a2"}}""")

        val next = adoptUploadResults(local, results, mapOf("d1" to 1L), mapOf("d1" to 2L), uploaded)

        assertEquals(obj("""{"reps":3}"""), next.getValue("d1")["ex_new"])
    }

    @Test
    @DisplayName("adoptUploadResults re-modified: with no uploaded copy known, no record gets a verdict")
    fun unknownUploadKeepsEveryRecord() {
        val local = logs("d1" to """{"_lastModified":"old-day","ex_1":{"reps":32,"_lastModified":"old-ex"}}""")
        val results = logs("d1" to """{"_lastModified":"srv-day","ex_1":{"reps":28,"_lastModified":"srv-ex"}}""")

        // uploadedLogs omitted: nothing can be shown to have been arbitrated,
        // so every record is treated as touched mid-sync and keeps its content.
        val next = adoptUploadResults(local, results, mapOf("d1" to 1L), mapOf("d1" to 2L))

        val entry = next.getValue("d1").getValue("ex_1").jsonObject
        assertEquals(JsonPrimitive(32), entry["reps"])
        assertEquals(JsonPrimitive("srv-ex"), entry["_lastModified"])
    }

    @Test
    @DisplayName("adoptUploadResults re-modified: untouched feedback adopts the server's, since the DAY token is its base")
    fun reModifiedAdoptsUntouchedFeedback() {
        // `session_feedback` is arbitrated on the day-level token, so advancing
        // that token while keeping the local feedback armed exactly the same
        // overwrite one cycle later.
        val uploaded = logs(
            "d1" to """{"session_feedback":{"general_notes":"mine"},"_baseLastModifiedAt":"old-day"}""",
        )
        val local = logs("d1" to """{"_lastModified":"old-day","session_feedback":{"general_notes":"mine"}}""")
        val results = logs("d1" to """{"_lastModified":"srv-day","session_feedback":{"general_notes":"theirs"}}""")

        val next = adoptUploadResults(local, results, mapOf("d1" to 1L), mapOf("d1" to 2L), uploaded)

        val day = next.getValue("d1")
        assertEquals(JsonPrimitive("theirs"), day.getValue("session_feedback").jsonObject["general_notes"])
        assertEquals(JsonPrimitive("srv-day"), day["_lastModified"])
    }

    @Test
    @DisplayName("adoptUploadResults re-modified: feedback edited mid-sync is kept, and the day token still advances")
    fun reModifiedKeepsReEditedFeedback() {
        val uploaded = logs("d1" to """{"session_feedback":{},"_baseLastModifiedAt":"old-day"}""")
        val local = logs("d1" to """{"_lastModified":"old-day","session_feedback":{"general_notes":"mid-sync"}}""")
        val results = logs("d1" to """{"_lastModified":"srv-day","session_feedback":{"general_notes":"theirs"}}""")

        val next = adoptUploadResults(local, results, mapOf("d1" to 1L), mapOf("d1" to 2L), uploaded)

        val day = next.getValue("d1")
        assertEquals(JsonPrimitive("mid-sync"), day.getValue("session_feedback").jsonObject["general_notes"])
        // The token has to move even here, or the re-edit's retry is rejected.
        assertEquals(JsonPrimitive("srv-day"), day["_lastModified"])
    }

    @Test
    @DisplayName("adoptUploadResults re-modified: a settled re-add sheds its _readd marker with the verdict")
    fun settledReaddShedsItsMarker() {
        // `_readd` is transient — the server never stores or echoes it, so
        // adopting any result is what retires it. The keep-and-advance path
        // carried the marker forward past the cycle that settled it.
        val uploaded = logs(
            "d1" to """{"extra_zone2":{"duration_min":45,"_readd":true,"_lastModified":"t1",""" +
                """"_baseLastModifiedAt":"t1"}}""",
        )
        val local = logs(
            "d1" to """{"_lastModified":"old-day","session_feedback":{"general_notes":"mid-sync"},""" +
                """"extra_zone2":{"duration_min":45,"_readd":true,"_lastModified":"t1"}}""",
        )
        val results = logs(
            "d1" to """{"_lastModified":"srv-day","session_feedback":{},""" +
                """"extra_zone2":{"duration_min":45,"_lastModified":"t2"}}""",
        )

        val next = adoptUploadResults(local, results, mapOf("d1" to 1L), mapOf("d1" to 2L), uploaded)

        val entry = next.getValue("d1").getValue("extra_zone2").jsonObject
        assertFalse("_readd" in entry, "a settled re-add kept a marker that no longer means anything")
        assertEquals(JsonPrimitive("t2"), entry["_lastModified"])
    }

    // ---- entry deletion (tombstones) -------------------------------------

    @Test
    @DisplayName("isDeletedEntry: true only for _deleted-marked entry objects")
    fun isDeletedEntryOnlyForMarkedObjects() {
        assertTrue(isDeletedEntry(obj("""{"_deleted":true,"_lastModified":"t"}""")))
        assertFalse(isDeletedEntry(obj("""{"duration_min":45}""")))
        assertFalse(isDeletedEntry(JsonNull))
        assertFalse(isDeletedEntry(null))
        assertFalse(isDeletedEntry(JsonPrimitive("str")))
    }

    @Test
    @DisplayName("withEntryDeleted: synced entry becomes a tombstone keeping its server stamp")
    fun deleteKeepsServerStamp() {
        val log = obj(
            """{"_lastModified":"day-tok","session_feedback":{},""" +
                """"extra_zone2":{"duration_min":45,"avg_hr":128,"_lastModified":"ex-tok"}}""",
        )

        val next = withEntryDeleted(log, "extra_zone2")

        assertEquals(obj("""{"_deleted":true,"_lastModified":"ex-tok"}"""), next["extra_zone2"])
        // input not mutated
        assertEquals(JsonPrimitive(45), log.getValue("extra_zone2").jsonObject["duration_min"])
    }

    @Test
    @DisplayName("withEntryDeleted: unstamped entry still becomes a tombstone (no silent resurrection)")
    fun deleteUnstampedStillTombstones() {
        // The entry may exist server-side (upload response lost) — removing the
        // key outright let the server copy resurrect on the next pull with no
        // record of the delete intent.
        val log = obj("""{"session_feedback":{},"extra_zone2":{"duration_min":45}}""")

        val next = withEntryDeleted(log, "extra_zone2")

        assertEquals(obj("""{"_deleted":true}"""), next["extra_zone2"])
    }

    @Test
    @DisplayName("withEntryDeleted: missing key is a no-op")
    fun deleteMissingKeyIsANoOp() {
        val log = obj("""{"session_feedback":{}}""")

        // Reference identity, not just equality: the store keys its "do nothing
        // at all" decision off exactly this.
        assertSame(log, withEntryDeleted(log, "nope"))
    }

    @Test
    @DisplayName("withEntryUpdated: plain merge over a normal entry")
    fun updateMergesOverNormalEntry() {
        val log = obj("""{"ex_1":{"duration_min":30,"_lastModified":"t1"}}""")

        val next = withEntryUpdated(log, "ex_1", obj("""{"avg_hr":128}"""))

        assertEquals(obj("""{"duration_min":30,"_lastModified":"t1","avg_hr":128}"""), next["ex_1"])
    }

    @Test
    @DisplayName("withEntryUpdated: write over a pending tombstone becomes a marked re-add keeping the stamp")
    fun updateOverTombstoneIsAMarkedReadd() {
        // Spreading over the tombstone kept `_deleted: true` (and the old
        // stamp), so the server processed the re-added session as a deletion.
        // The re-add drops `_deleted`, KEEPS the stamp (the base token that wins
        // over the still-live server row when the delete never uploaded), and
        // carries `_readd` so the resurrection guard accepts it when it did.
        val log = obj("""{"_lastModified":"day-tok","extra_zone2":{"_deleted":true,"_lastModified":"ex-tok"}}""")

        val next = withEntryUpdated(log, "extra_zone2", obj("""{"duration_min":45,"avg_hr":128}"""))

        assertEquals(
            obj("""{"duration_min":45,"avg_hr":128,"_readd":true,"_lastModified":"ex-tok"}"""),
            next["extra_zone2"],
        )
        assertFalse("_deleted" in next.getValue("extra_zone2").jsonObject)
    }

    @Test
    @DisplayName("withEntryUpdated: re-add over an UNSTAMPED tombstone carries no stamp")
    fun readdOverUnstampedTombstone() {
        val log = obj("""{"extra_zone2":{"_deleted":true}}""")

        val next = withEntryUpdated(log, "extra_zone2", obj("""{"duration_min":45}"""))

        assertEquals(obj("""{"duration_min":45,"_readd":true}"""), next["extra_zone2"])
    }

    @Test
    @DisplayName("logHasPendingDeletions + selectLogsToUpload: a tombstone-only never-synced day uploads")
    fun tombstoneOnlyNeverSyncedDayUploads() {
        val local = logs("d1" to """{"session_feedback":{},"extra_zone2":{"_deleted":true}}""")

        assertTrue(logHasPendingDeletions(local.getValue("d1")))
        val result = selectLogsToUpload(listOf("d1"), local)

        assertEquals(listOf("d1"), result.uploadedDates)
        assertEquals(emptyList<String>(), result.unsatisfiableDates)
        // No stamp → no base token → the server arbitrates it as a hard cutover.
        assertFalse(
            "_baseLastModifiedAt" in
                result.logsToUpload.getValue("d1").getValue("extra_zone2").jsonObject,
        )
    }

    @Test
    @DisplayName("tombstone day uploads and echoes the tombstone base token")
    fun tombstoneDayEchoesBaseToken() {
        // The tombstone-only day carries no exercise content, but the day was
        // synced (day `_lastModified` present) so it rides the deletion path.
        val local = logs(
            "d1" to """{"_lastModified":"day-tok","session_feedback":{},""" +
                """"extra_zone2":{"_deleted":true,"_lastModified":"ex-tok"}}""",
        )

        val result = selectLogsToUpload(listOf("d1"), local)

        assertEquals(listOf("d1"), result.uploadedDates)
        val tombstone = result.logsToUpload.getValue("d1").getValue("extra_zone2").jsonObject
        assertEquals(JsonPrimitive(true), tombstone["_deleted"])
        assertEquals(JsonPrimitive("ex-tok"), tombstone["_baseLastModifiedAt"])
    }

    @Test
    @DisplayName("adoptUploadResults: adopting the serverRow (without the key) clears the tombstone")
    fun wholesaleAdoptionClearsTheTombstone() {
        val local = logs(
            "d1" to """{"_lastModified":"day-tok","session_feedback":{},""" +
                """"extra_zone2":{"_deleted":true,"_lastModified":"ex-tok"}}""",
        )
        // Accepted delete: the reconciled server day simply lacks the key.
        val results = logs("d1" to """{"session_feedback":{},"_lastModified":"srv-day"}""")

        val next = adoptUploadResults(local, results, mapOf("d1" to 1L), mapOf("d1" to 1L))

        assertFalse("extra_zone2" in next.getValue("d1"))
        assertEquals(JsonPrimitive("srv-day"), next.getValue("d1")["_lastModified"])
    }

    @Test
    @DisplayName(
        "adoptUploadResults re-modified: REJECTED delete adopts the surviving server record " +
            "(never advances the tombstone token)",
    )
    fun rejectedDeleteAdoptsSurvivingRecord() {
        // Advancing the tombstone's token to the server stamp made the next
        // retry's base match, turning the rejected delete into an accepted one —
        // destroying the other client's newer edit. An uploaded tombstone has
        // been arbitrated: the server row carrying the key IS the verdict.
        val uploaded = logs(
            "d1" to """{"extra_zone2":{"_deleted":true,"_lastModified":"t1","_baseLastModifiedAt":"t1"}}""",
        )
        val local = logs(
            "d1" to """{"_lastModified":"day-tok","session_feedback":{"general_notes":"mid-sync edit"},""" +
                """"extra_zone2":{"_deleted":true,"_lastModified":"t1"}}""",
        )
        val results = logs(
            "d1" to """{"session_feedback":{},"_lastModified":"srv-day",""" +
                """"extra_zone2":{"duration_min":60,"_lastModified":"t2"}}""", // remote edit won
        )

        val next = adoptUploadResults(local, results, mapOf("d1" to 1L), mapOf("d1" to 2L), uploaded)

        assertEquals(obj("""{"duration_min":60,"_lastModified":"t2"}"""), next.getValue("d1")["extra_zone2"])
        assertEquals(
            JsonPrimitive("mid-sync edit"), // re-edit kept
            next.getValue("d1").getValue("session_feedback").jsonObject["general_notes"],
        )
    }

    @Test
    @DisplayName("adoptUploadResults re-modified: ACCEPTED delete drops the tombstone")
    fun acceptedDeleteDropsTheTombstone() {
        val uploaded = logs(
            "d1" to """{"extra_zone2":{"_deleted":true,"_lastModified":"t1","_baseLastModifiedAt":"t1"}}""",
        )
        val local = logs(
            "d1" to """{"_lastModified":"day-tok","session_feedback":{"general_notes":"mid-sync edit"},""" +
                """"extra_zone2":{"_deleted":true,"_lastModified":"t1"}}""",
        )
        val results = logs("d1" to """{"session_feedback":{},"_lastModified":"srv-day"}""") // key gone

        val next = adoptUploadResults(local, results, mapOf("d1" to 1L), mapOf("d1" to 2L), uploaded)

        assertFalse("extra_zone2" in next.getValue("d1"))
        assertEquals(JsonPrimitive("srv-day"), next.getValue("d1")["_lastModified"])
    }

    @Test
    @DisplayName("adoptUploadResults re-modified: a tombstone created MID-SYNC is kept, token untouched")
    fun midSyncTombstoneIsKeptVerbatim() {
        // Not in the upload → not yet arbitrated. It must survive with its
        // original base so the next cycle arbitrates it properly.
        val uploaded = logs("d1" to """{"ex_other":{"reps":5,"_baseLastModifiedAt":"o1"}}""")
        val local = logs(
            "d1" to """{"_lastModified":"day-tok","session_feedback":{},""" +
                """"extra_zone2":{"_deleted":true,"_lastModified":"t1"}}""", // deleted mid-sync
        )
        val results = logs(
            "d1" to """{"session_feedback":{},"_lastModified":"srv-day",""" +
                """"extra_zone2":{"duration_min":45,"_lastModified":"t1"}}""",
        )

        val next = adoptUploadResults(local, results, mapOf("d1" to 1L), mapOf("d1" to 2L), uploaded)

        assertEquals(obj("""{"_deleted":true,"_lastModified":"t1"}"""), next.getValue("d1")["extra_zone2"])
    }

    // ---- pruneOlderThan / maxPlanVersion ---------------------------------

    @Test
    @DisplayName("pruneOlderThan: keeps dates >= cutoff")
    fun pruneKeepsFromCutoff() {
        val map = mapOf("2026-04-30" to "a", "2026-05-01" to "b", "2026-05-02" to "c")

        assertEquals(mapOf("2026-05-01" to "b", "2026-05-02" to "c"), pruneOlderThan(map, "2026-05-01"))
    }

    @Test
    @DisplayName("maxPlanVersion: latest _lastModified, never below currentMax")
    fun maxPlanVersionNeverGoesBackwards() {
        val plans = logs(
            "d1" to """{"_lastModified":"2026-05-01T00:00:00Z"}""",
            "d2" to """{"_lastModified":"2026-05-03T00:00:00Z"}""",
        )

        assertEquals("2026-05-03T00:00:00Z", maxPlanVersion(plans, null))
        assertEquals("2026-05-09T00:00:00Z", maxPlanVersion(plans, "2026-05-09T00:00:00Z"))
        assertEquals("2026-05-01T00:00:00Z", maxPlanVersion(emptyMap(), "2026-05-01T00:00:00Z"))
    }
}

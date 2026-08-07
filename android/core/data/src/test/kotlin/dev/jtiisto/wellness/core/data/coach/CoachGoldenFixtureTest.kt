package dev.jtiisto.wellness.core.data.coach

import dev.jtiisto.wellness.core.data.WellnessJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Contract tests against payloads the dev server actually produced
 * (`testdata/golden/coach/` — see the README there for provenance).
 *
 * Hand-written test JSON pins what we *think* the protocol is; these pin what
 * it *is*. Three laws:
 * 1. every fixture decodes;
 * 2. the captured upload regenerates byte-for-byte from `selectLogsToUpload`;
 * 3. the plan fixture decodes through [PlanDto] with every typed field intact.
 *
 * `plan-day.json` is hand-built (the dev server has no plan rows) but the test
 * is **never conditional on that** — the snake_case decode is the whole reason
 * [PlanDto] carries a `@SerialName` on every property.
 */
class CoachGoldenFixtureTest {

    private val json = WellnessJson

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/golden/coach/$name")) {
            "missing golden fixture golden/coach/$name"
        }.use { it.readBytes().decodeToString() }

    private fun obj(name: String): JsonObject = json.parseToJsonElement(fixture(name)).jsonObject

    private fun sync(name: String): CoachSyncResponseDto =
        json.decodeFromString(CoachSyncResponseDto.serializer(), fixture(name))

    private fun post(name: String): CoachSyncPostResponseDto =
        json.decodeFromString(CoachSyncPostResponseDto.serializer(), fixture(name))

    // ---- decode ----------------------------------------------------------

    @Test
    @DisplayName("the full pull decodes: both fixture days, the window start and a watermark")
    fun fullPullDecodes() {
        val response = sync("sync-get-full.json")

        assertEquals(listOf(D1, D2), response.logs.keys.sorted())
        assertEquals("2026-06-08", response.earliestDate)
        assertNotNull(response.serverTime)
        assertTrue(response.deletedPlanDates.isEmpty())

        val day = response.logs.getValue(D1)
        assertEquals(
            "fixture-felt-good",
            day.getValue("session_feedback").jsonObject["general_notes"]?.jsonPrimitive?.content,
        )
        // The day stamp is the FEEDBACK record's token; each exercise carries
        // its own. Conflating them is what per-record arbitration hinges on.
        assertNotNull(day["_lastModified"]?.jsonPrimitive?.content)
        assertNotNull(day.getValue("extra_zone2").jsonObject["_lastModified"]?.jsonPrimitive?.content)
        // The lean shape: `completed: false` is dropped from a set entirely.
        val sets = day.getValue("fixture-adhoc-lift").jsonObject.getValue("sets")
        assertFalse("completed" in sets.jsonObject(1), "the lean shape must omit completed:false")
        assertTrue("completed" in sets.jsonObject(0))
    }

    @Test
    @DisplayName("the incremental pull decodes to the same shape as the full one")
    fun incrementalPullDecodes() {
        val response = sync("sync-get-incremental.json")

        assertEquals(listOf(D1, D2), response.logs.keys.sorted())
        assertTrue(response.plans.isEmpty())
        assertNotNull(response.serverTime)
    }

    @Test
    @DisplayName("the accepted upload response returns the whole merged day per date, freshly stamped")
    fun acceptedPostDecodes() {
        val response = post("sync-post-response.json")

        assertTrue(response.success)
        assertEquals(listOf(D1, D2), response.results.keys.sorted())
        val serverTime = requireNotNull(response.serverTime)
        // Every record accepted in this upload carries the same write stamp.
        for (day in response.results.values) {
            assertEquals(serverTime, day["_lastModified"]?.jsonPrimitive?.content)
        }
    }

    @Test
    @DisplayName("a stale base leaves the server's own version in place — there is no whole-upload rejection")
    fun rejectedPostKeepsTheServerVersion() {
        val accepted = post("sync-post-response.json")
        val rejected = post("sync-post-response-rejected.json")

        // The replay carried CHANGED content against the now-advanced tokens.
        assertTrue(rejected.success, "a rejection is per record; the request still succeeds")
        assertEquals(accepted.results, rejected.results, "the stale write must have changed nothing")
        // Its own serverTime moved (it is the request's write stamp) while no
        // record's token did — exactly why the POST stamp is not a watermark.
        assertTrue(
            requireNotNull(rejected.serverTime) > requireNotNull(accepted.serverTime),
            "expected a later write stamp on the rejected request",
        )
    }

    @Test
    @DisplayName("an accepted delete comes back as the key's ABSENCE from the merged day")
    fun tombstoneVerdictDecodes() {
        val roundtrip = obj("sync-post-tombstone-roundtrip.json")
        val request = roundtrip.getValue("request").jsonObject
        val response = json.decodeFromString(
            CoachSyncPostResponseDto.serializer(),
            roundtrip.getValue("response").toString(),
        )

        // The upload: a tombstone echoing the record's own token as its base.
        val uploaded = request.getValue("logs").jsonObject.getValue(D1).jsonObject
            .getValue("extra_zone2").jsonObject
        assertEquals(true, uploaded["_deleted"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(uploaded["_lastModified"], uploaded["_baseLastModifiedAt"])

        val merged = response.results.getValue(D1)
        assertFalse("extra_zone2" in merged, "an accepted delete must not echo the key back")
        assertTrue("fixture-adhoc-lift" in merged, "the day's other records are untouched")

        // What the client does with that: adopting the merged day clears the
        // tombstone, which is the whole tombstone lifecycle in one call.
        val local = mapOf(D1 to sync("sync-get-full.json").logs.getValue(D1))
        val next = adoptUploadResults(local, response.results, mapOf(D1 to 1L), mapOf(D1 to 1L))
        assertFalse("extra_zone2" in next.getValue(D1))
    }

    @Test
    @DisplayName("the plans-version probe decodes")
    fun plansVersionDecodes() {
        val version = json.decodeFromString(PlansVersionDto.serializer(), fixture("plans-version.json")).version

        assertNotNull(version)
    }

    // ---- the upload law --------------------------------------------------

    @Test
    @DisplayName("the captured upload regenerates byte-identically from selectLogsToUpload")
    fun uploadRequestRegenerates() {
        val expected = fixture("sync-post-request.json").trim()
        val request = json.parseToJsonElement(expected).jsonObject
        val clientId = request.getValue("clientId").jsonPrimitive.content
        val sentLogs = request.getValue("logs").jsonObject

        // The store rows these were built from: the adopted server days, which
        // are exactly the payload minus the base tokens `withBaseTokens` added.
        val localLogs = sentLogs.mapValues { (_, day) -> stripBaseTokens(day.jsonObject) }

        val selected = selectLogsToUpload(localLogs.keys, localLogs)

        assertEquals(emptyList<String>(), selected.unsatisfiableDates)
        val body = buildJsonObject {
            put("clientId", clientId)
            put("logs", JsonObject(selected.logsToUpload))
        }
        // Byte-for-byte, key order included: the server arbitrates on what is
        // inside these objects and the client must be able to reproduce a day
        // it stored without reordering it.
        assertEquals(expected, json.encodeToString(JsonObject.serializer(), body))
    }

    @Test
    @DisplayName("every base token in the captured upload echoes that record's own last-seen stamp")
    fun baseTokensEchoTheRecordStamp() {
        val sentLogs = obj("sync-post-request.json").getValue("logs").jsonObject

        for ((date, dayElement) in sentLogs) {
            val day = dayElement.jsonObject
            assertEquals(day["_lastModified"], day["_baseLastModifiedAt"], "day token mismatch on $date")
            for ((key, entry) in day) {
                val record = entry as? JsonObject ?: continue
                val base = record["_baseLastModifiedAt"] ?: continue
                assertEquals(record["_lastModified"], base, "record token mismatch on $date/$key")
            }
            // A brand-new record omits the token so the server inserts it; the
            // feedback record here has never had one of its own.
            assertFalse("_baseLastModifiedAt" in day.getValue("session_feedback").jsonObject)
        }
    }

    // ---- the plan decode law ---------------------------------------------

    @Test
    @DisplayName("the plan fixture decodes through PlanDto with every typed snake_case field intact")
    fun planDecodes() {
        val plan = json.decodeFromString(PlanDto.serializer(), fixture("plan-day.json"))

        assertEquals(4101L, plan.sessionId)
        assertEquals("fixture-Upper Push", plan.dayName)
        assertEquals("fixture-Home Gym", plan.location)
        assertEquals("fixture-Foundation", plan.phase)
        assertEquals(60, plan.totalDurationMin)
        assertEquals(listOf(0, 1, 2), plan.blocks.map { it.blockIndex })

        val warmup = plan.blocks[0]
        assertEquals("warmup", warmup.blockType)
        assertEquals(8, warmup.durationMin)
        assertEquals("", warmup.restGuidance)
        assertNull(warmup.rounds)
        assertEquals(
            listOf("fixture-Cat-Cow x10", "fixture-Band Pull-Apart x15"),
            warmup.exercises.single().items,
        )

        val bench = plan.blocks[1].exercises[0]
        assertEquals("strength_1_1", bench.id)
        assertEquals("strength", bench.type)
        assertEquals(4, bench.targetSets)
        // Free-form coaching text, not numbers — the whole reason these three
        // are String? and a Double? would fail to decode them.
        assertEquals("8-10", bench.targetReps)
        assertEquals("6-7", bench.targetRpe)
        assertEquals("70%", bench.targetLoad)
        assertEquals("3-1-2-0", bench.tempo)
        assertEquals("A", bench.supersetGroup)
        assertEquals("HEAVY", bench.exposure)
        assertEquals("bench_press", bench.canonicalSlug)
        assertEquals("fixture-brace before each rep", bench.guidanceNote)

        val hold = plan.blocks[1].exercises[1]
        assertEquals(45, hold.targetDurationSec)
        assertEquals(true, hold.hideWeight)
        assertEquals(true, hold.showTime)

        val circuit = plan.blocks[2]
        assertEquals(3, circuit.rounds)
        assertEquals(40, circuit.workDurationSec)
        assertEquals(20, circuit.restDurationSec)
        val spin = circuit.exercises.single()
        assertEquals(12, spin.targetDurationMin)
        assertEquals(3, spin.rounds)
        assertEquals(40, spin.workDurationSec)
        assertEquals(20, spin.restDurationSec)
        assertNull(spin.targetSets)
    }

    @Test
    @DisplayName("a plan blob keeps its _lastModified for the version projection")
    fun planCarriesItsVersion() {
        assertEquals(
            "2026-08-07T12:00:00.000000Z",
            obj("plan-day.json")["_lastModified"]?.jsonPrimitive?.content,
        )
    }

    /** The inverse of `withBaseTokens`: what the store row held before upload. */
    private fun stripBaseTokens(day: JsonObject): JsonObject = JsonObject(
        day.filterKeys { it != BASE_TOKEN }
            .mapValues { (_, value) ->
                (value as? JsonObject)?.let { JsonObject(it.filterKeys { key -> key != BASE_TOKEN }) } ?: value
            },
    )

    private fun kotlinx.serialization.json.JsonElement.jsonObject(index: Int): JsonObject =
        (this as kotlinx.serialization.json.JsonArray)[index].jsonObject

    private companion object {
        const val D1 = "2030-01-03"
        const val D2 = "2030-01-04"
        const val BASE_TOKEN = "_baseLastModifiedAt"
    }
}

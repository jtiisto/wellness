package dev.jtiisto.wellness.core.data.sync

import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.db.DebugLogEntity
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Retention rules for the debug log. `DebugLogDao` re-states these in SQL, so a
 * change here without a matching change there is a silent drift.
 */
class DebugLogLogicTest {

    private val now = 1_754_000_000_000L

    private fun entry(id: Long, ts: Long) = DebugLogEntity(id = id, ts = ts, tag = "t", message = "m")

    @Test
    @DisplayName("TTL boundary: an entry exactly one hour old is already expired")
    fun ttlBoundary() {
        assertFalse(DebugLogLogic.isExpired(now - DebugLogLogic.TTL_MS + 1, now))
        assertTrue(DebugLogLogic.isExpired(now - DebugLogLogic.TTL_MS, now))
        assertTrue(DebugLogLogic.isExpired(now - DebugLogLogic.TTL_MS - 1, now))
    }

    @Test
    @DisplayName("the SQL cutoff predicate agrees with the expiry predicate at the boundary")
    fun cutoffMatchesExpiry() {
        val cutoff = DebugLogLogic.ttlCutoff(now)
        for (offset in -2L..2L) {
            val ts = now - DebugLogLogic.TTL_MS + offset
            assertEquals(
                !DebugLogLogic.isExpired(ts, now),
                DebugLogLogic.isRetained(ts, cutoff),
                "disagreement at offset $offset",
            )
        }
    }

    @Test
    @DisplayName("prune drops expired rows and keeps the rest")
    fun pruneDropsExpired() {
        val entries = listOf(
            entry(1, now - DebugLogLogic.TTL_MS - 1),
            entry(2, now - DebugLogLogic.TTL_MS),
            entry(3, now - DebugLogLogic.TTL_MS + 1),
            entry(4, now),
        )
        assertEquals(listOf(3L, 4L), DebugLogLogic.prune(entries, now).map { it.id })
    }

    @Test
    @DisplayName("cap boundary: 500 rows survive untouched, 501 drops the oldest")
    fun capBoundary() {
        val atCap = (1L..500L).map { entry(it, now) }
        assertEquals(500, DebugLogLogic.prune(atCap, now).size)
        assertEquals(1L, DebugLogLogic.prune(atCap, now).first().id)

        val overCap = (1L..501L).map { entry(it, now) }
        val pruned = DebugLogLogic.prune(overCap, now)
        assertEquals(500, pruned.size)
        assertEquals(2L, pruned.first().id)
        assertEquals(501L, pruned.last().id)
    }

    @Test
    @DisplayName("the cap is applied after the TTL filter, never to expired rows")
    fun capAppliedAfterTtl() {
        val expired = (1L..400L).map { entry(it, now - DebugLogLogic.TTL_MS - 1) }
        val live = (401L..900L).map { entry(it, now) }
        val pruned = DebugLogLogic.prune(expired + live, now)
        assertEquals(500, pruned.size)
        assertEquals(401L, pruned.first().id)
    }

    @Test
    @DisplayName("data that cannot be encoded becomes the unserializable marker")
    fun unserializableMarker() {
        val payload = buildJsonObject { put("trigger", "poll") }

        val encoded = DebugLogLogic.serializeData(payload) {
            WellnessJson.encodeToString(JsonElement.serializer(), it)
        }
        assertEquals("""{"trigger":"poll"}""", encoded)

        val failed = DebugLogLogic.serializeData(payload) { error("boom") }
        assertEquals(DebugLogLogic.UNSERIALIZABLE, failed)

        assertNull(DebugLogLogic.serializeData(null) { error("never called") })
    }

    @Test
    @DisplayName("dump lines are UTC and stable: timestamp, tag, message, data")
    fun dumpLineFormat() {
        val withData = DebugLogEntity(
            id = 1,
            ts = 1_754_000_000_000L,
            tag = "journal-scheduler",
            message = "sync triggered",
            dataJson = """{"trigger":"poll"}""",
        )
        assertEquals(
            """2025-07-31T22:13:20Z journal-scheduler sync triggered {"trigger":"poll"}""",
            DebugLogLogic.formatDumpLine(withData),
        )
        assertEquals(
            "2025-07-31T22:13:20Z journal-scheduler sync triggered",
            DebugLogLogic.formatDumpLine(withData.copy(dataJson = null)),
        )
    }
}

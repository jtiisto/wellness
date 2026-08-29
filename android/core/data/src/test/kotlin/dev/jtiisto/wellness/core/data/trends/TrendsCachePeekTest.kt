package dev.jtiisto.wellness.core.data.trends

import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.db.PayloadCacheDao
import dev.jtiisto.wellness.core.data.db.PayloadCacheEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The widget's cache-only, live read of the sleep ledger.
 *
 * Two properties carry the whole class. The first is **freshest-decodable
 * wins**: the widget watches its own hourly key and the app's range key at
 * once, and whichever copy carries the newer stamp is drawn — a fixed
 * preference would let the home screen trail a copy the app just fetched. The
 * second is that the read is a **flow**: a Glance session recomposes without
 * re-running its setup, so only a live emission can keep the surface honest —
 * and a new row landing in the cache must surface as a new value.
 *
 * All payloads are invented and dated on the far-future `2030-01-*` fixture
 * convention.
 */
class TrendsCachePeekTest {

    private val dao = FakeCacheDao()
    private val peek = TrendsCachePeek(cacheDao = dao, json = WellnessJson)

    private val widgetKey = TrendsRepository.sleepKey("widget")
    private val rangeKey = TrendsRepository.sleepKey("12w")
    private val keys = listOf(widgetKey, rangeKey)

    // ---- freshest wins ------------------------------------------------------

    @Test
    @DisplayName("the freshest decodable copy wins, whichever key it sits under")
    fun freshestWins() = runTest {
        // The widget's own copy is an hour older than the one the app's Health
        // tab just fetched. Serving the widget key by preference here is the
        // bug the rule replaces: a home screen contradicting the app it
        // belongs to for the rest of the hour.
        store(widgetKey, sleepPayload(needMin = 505.0), fetchedAt = 1_893_456_000_000L)
        store(rangeKey, sleepPayload(needMin = 999.0), fetchedAt = 1_893_459_600_000L)

        assertEquals(999.0, peek.sleepFlow(keys).first()?.dto?.tonight?.needMin)
    }

    @Test
    @DisplayName("a widget placed before its worker's first run reads the app's copy")
    fun rangeKeyAloneServes() = runTest {
        // Only the app's own copy exists, and it carries the identical
        // range-independent `tonight`.
        store(rangeKey, sleepPayload(needMin = 505.0))

        assertEquals(505.0, peek.sleepFlow(keys).first()?.dto?.tonight?.needMin)
    }

    @Test
    @DisplayName("a new row landing in the cache surfaces as a new emission")
    fun liveUpdateSurfaces() = runTest {
        // The reason this is a flow at all: the Glance session collects it in
        // composition, so the fetch the worker or the app lands mid-session
        // must redraw the widget without the session restarting.
        store(widgetKey, sleepPayload(needMin = 505.0), fetchedAt = 1_893_456_000_000L)
        val flow = peek.sleepFlow(keys)
        assertEquals(505.0, flow.first()?.dto?.tonight?.needMin)

        store(rangeKey, sleepPayload(needMin = 999.0), fetchedAt = 1_893_459_600_000L)
        assertEquals(999.0, flow.first()?.dto?.tonight?.needMin)
    }

    // ---- failure posture ----------------------------------------------------

    @Test
    @DisplayName("a fresher copy this build cannot decode must not shadow an older one that decodes")
    fun decodeFailureSkipped() = runTest {
        // A payload from a build that spoke a different shape: valid JSON, no
        // `days`, so the whole decode fails — and it is the NEWER row.
        store(widgetKey, """{"available":true}""", fetchedAt = 1_893_459_600_000L)
        store(rangeKey, sleepPayload(needMin = 505.0), fetchedAt = 1_893_456_000_000L)

        assertEquals(505.0, peek.sleepFlow(keys).first()?.dto?.tonight?.needMin)
        assertNotNull(
            dao.rows[TrendsRepository.MODULE to widgetKey],
            "the row stays: what this build cannot read, the next one may",
        )
        assertEquals(0, dao.writes, "a peek is a read; it must never touch the cache")
        assertEquals(0, dao.deletes)
    }

    @Test
    @DisplayName("every key a miss — and no keys at all — is null, not an error")
    fun allMissIsNull() = runTest {
        assertNull(peek.sleepFlow(keys).first())
        assertNull(peek.sleepFlow(emptyList()).first(), "no keys must still emit, or priming hangs")
    }

    @Test
    @DisplayName("a database that cannot be read errors the flow — the collector owns the pending floor")
    fun readFailurePropagates() = runTest {
        // The no-error-surface rule lives at the widget's collection seam,
        // which catches, logs the class name, and emits the element's absence.
        // Flattening it HERE would also flatten cancellation, which must
        // propagate untouched.
        dao.readFailure = RuntimeException("database is not openable")

        assertThrows<RuntimeException> { peek.sleepFlow(keys).first() }
    }

    // ---- the stamp ----------------------------------------------------------

    @Test
    @DisplayName("fetchedAt is the row's own stamp, passed through untouched")
    fun fetchedAtVerbatim() = runTest {
        // The freshness *rule* is the widget's (a 90-minute window); this class
        // only reports the age it found, and rewriting the stamp here would take
        // that decision away from the one place that can make it.
        store(widgetKey, sleepPayload(needMin = 505.0), fetchedAt = 1_893_500_000_000L)

        assertEquals(1_893_500_000_000L, peek.sleepFlow(keys).first()?.fetchedAt)
    }

    @Test
    @DisplayName("the payload decodes whole — this is the same DTO the card renders")
    fun payloadDecodesWhole() = runTest {
        store(widgetKey, sleepPayload(needMin = 505.0))

        val dto = peek.sleepFlow(keys).first()?.dto

        assertTrue(dto?.available == true)
        assertEquals("2030-01-06", dto?.asOf)
        assertEquals("2030-01-06", dto?.tonight?.date)
        assertEquals(71.0, dto?.tonight?.debtMin)
        assertEquals(12.6, dto?.tonight?.strainEst)
        assertTrue(dto?.tonight?.strainPartial == true)
        assertEquals(1, dto?.days?.size)
    }

    // ---- fixtures -----------------------------------------------------------

    private fun store(key: String, payload: String, fetchedAt: Long = 1_893_456_000_000L) {
        dao.put(PayloadCacheEntity(TrendsRepository.MODULE, key, payload, fetchedAt))
    }

    /** An invented ledger response; `needMin` is the marker of *which* copy. */
    private fun sleepPayload(needMin: Double): String =
        """
        {"available":true,"as_of":"2030-01-06",
         "tonight":{"date":"2030-01-06","need_min":$needMin,"debt_min":71,
                    "strain_est":12.6,"strain_partial":true},
         "days":[{"date":"2030-01-06","need_min":480,"slept_min":455,
                  "debt_min":12.5,"strain_est":10.1}]}
        """.trimIndent()

    /**
     * Backed by one StateFlow per key, so a test can land a row mid-collection
     * and watch the peek's combine re-emit — the shape Room's own flows have.
     */
    private class FakeCacheDao : PayloadCacheDao {
        val rows = mutableMapOf<Pair<String, String>, PayloadCacheEntity>()
        private val streams = mutableMapOf<Pair<String, String>, MutableStateFlow<Long>>()
        var readFailure: Throwable? = null
        var writes = 0
        var deletes = 0

        fun put(entry: PayloadCacheEntity) {
            rows[entry.module to entry.key] = entry
            stream(entry.module to entry.key).value += 1
        }

        private fun stream(id: Pair<String, String>): MutableStateFlow<Long> =
            streams.getOrPut(id) { MutableStateFlow(0L) }

        override suspend fun upsert(entry: PayloadCacheEntity) {
            writes += 1
            put(entry)
        }

        override suspend fun find(module: String, key: String): PayloadCacheEntity? {
            readFailure?.let { throw it }
            return rows[module to key]
        }

        override fun observe(module: String, key: String): Flow<PayloadCacheEntity?> =
            stream(module to key).map {
                readFailure?.let { failure -> throw failure }
                rows[module to key]
            }

        override suspend fun delete(module: String, key: String) {
            deletes += 1
            rows.remove(module to key)
            stream(module to key).value += 1
        }

        override suspend fun clearModule(module: String) {
            deletes += 1
            rows.keys.removeAll { it.first == module }
        }
    }
}

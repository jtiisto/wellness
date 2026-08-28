package dev.jtiisto.wellness.core.data.trends

import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.db.PayloadCacheDao
import dev.jtiisto.wellness.core.data.db.PayloadCacheEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The widget's cache-only read of the sleep ledger.
 *
 * Two properties carry the whole class. The first is **key order**: the widget
 * asks for its own hourly copy and then for whatever range the user last looked
 * at, and a peek that reached for the second while the first was sitting there
 * would quietly draw an older number. The second is that **nothing here may
 * fail loudly** — the caller is a launcher render with no error surface, so a
 * database that will not open and a payload this build cannot read must both
 * come back as "nothing to draw" rather than as an exception.
 *
 * All payloads are invented and dated on the far-future `2030-01-*` fixture
 * convention.
 */
class TrendsCachePeekTest {

    private val dao = FakeCacheDao()
    private val peek = TrendsCachePeek(cacheDao = dao, json = WellnessJson)

    private val widgetKey = TrendsRepository.sleepKey("widget")
    private val rangeKey = TrendsRepository.sleepKey("12w")

    // ---- key order ----------------------------------------------------------

    @Test
    @DisplayName("the first key wins, and the second is never even read")
    fun firstKeyServed() = runTest {
        store(widgetKey, sleepPayload(needMin = 505.0))
        store(rangeKey, sleepPayload(needMin = 999.0))

        val peeked = peek.sleep(listOf(widgetKey, rangeKey))

        assertEquals(505.0, peeked?.dto?.tonight?.needMin)
        assertEquals(listOf(widgetKey), dao.readKeys, "a hit on the first key must end the peek")
    }

    @Test
    @DisplayName("a missing first key falls through to the second, in order")
    fun secondKeyServedOnMiss() = runTest {
        // The widget placed before its worker's first run: only the app's own
        // copy exists, and it carries the identical range-independent `tonight`.
        store(rangeKey, sleepPayload(needMin = 505.0))

        val peeked = peek.sleep(listOf(widgetKey, rangeKey))

        assertEquals(505.0, peeked?.dto?.tonight?.needMin)
        assertEquals(listOf(widgetKey, rangeKey), dao.readKeys)
    }

    // ---- failure posture ----------------------------------------------------

    @Test
    @DisplayName("an undecodable copy falls through, keeps its row, and never writes")
    fun decodeFailureFallsThrough() = runTest {
        // A payload from a build that spoke a different shape: valid JSON, no
        // `days`, so the whole decode fails.
        store(widgetKey, """{"available":true}""")
        store(rangeKey, sleepPayload(needMin = 505.0))

        val peeked = peek.sleep(listOf(widgetKey, rangeKey))

        assertEquals(505.0, peeked?.dto?.tonight?.needMin)
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
        assertNull(peek.sleep(listOf(widgetKey, rangeKey)))
        assertEquals(listOf(widgetKey, rangeKey), dao.readKeys, "both were tried")

        assertNull(peek.sleep(emptyList()))
    }

    @Test
    @DisplayName("a DAO that throws is null overall, and ends the peek then and there")
    fun readFailureIsNull() = runTest {
        // The good copy under the second key is deliberately present: a read
        // failure is a fact about the database, not about the key, so spending
        // the same error again would only be a second way to fail.
        store(rangeKey, sleepPayload(needMin = 505.0))
        dao.readFailure = RuntimeException("database is not openable")

        assertNull(peek.sleep(listOf(widgetKey, rangeKey)))
        assertEquals(1, dao.readKeys.size, "the peek stops at the first failed read")
    }

    @Test
    @DisplayName("a cancelled render is not a failed one — CancellationException is rethrown")
    fun cancellationIsRethrown() = runTest {
        // The one throwable that must NOT be flattened into "nothing to draw":
        // swallowing it would report an answer for work that was abandoned, and
        // would break the structured-concurrency contract of whatever cancelled it.
        dao.readFailure = CancellationException("render cancelled")

        assertThrows<CancellationException> { peek.sleep(listOf(widgetKey)) }
    }

    // ---- the stamp ----------------------------------------------------------

    @Test
    @DisplayName("fetchedAt is the row's own stamp, passed through untouched")
    fun fetchedAtVerbatim() = runTest {
        // The freshness *rule* is the widget's (a 90-minute window); this class
        // only reports the age it found, and rewriting the stamp here would take
        // that decision away from the one place that can make it.
        store(widgetKey, sleepPayload(needMin = 505.0), fetchedAt = 1_893_500_000_000L)

        assertEquals(1_893_500_000_000L, peek.sleep(listOf(widgetKey))?.fetchedAt)
    }

    @Test
    @DisplayName("the payload decodes whole — this is the same DTO the card renders")
    fun payloadDecodesWhole() = runTest {
        store(widgetKey, sleepPayload(needMin = 505.0))

        val dto = peek.sleep(listOf(widgetKey))?.dto

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
        dao.rows[TrendsRepository.MODULE to key] =
            PayloadCacheEntity(TrendsRepository.MODULE, key, payload, fetchedAt)
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

    private class FakeCacheDao : PayloadCacheDao {
        val rows = mutableMapOf<Pair<String, String>, PayloadCacheEntity>()

        /** In order, so a test can assert what was asked for and what was not. */
        val readKeys = mutableListOf<String>()
        var readFailure: Throwable? = null
        var writes = 0
        var deletes = 0

        override suspend fun upsert(entry: PayloadCacheEntity) {
            writes += 1
            rows[entry.module to entry.key] = entry
        }

        override suspend fun find(module: String, key: String): PayloadCacheEntity? {
            readKeys += key
            readFailure?.let { throw it }
            return rows[module to key]
        }

        override suspend fun delete(module: String, key: String) {
            deletes += 1
            rows.remove(module to key)
        }

        override suspend fun clearModule(module: String) {
            deletes += 1
            rows.keys.removeAll { it.first == module }
        }
    }
}

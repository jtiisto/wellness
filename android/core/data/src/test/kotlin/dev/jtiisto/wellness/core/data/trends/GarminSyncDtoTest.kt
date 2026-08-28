package dev.jtiisto.wellness.core.data.trends

import dev.jtiisto.wellness.core.data.WellnessJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The `garmin` module's wire contract.
 *
 * Inline JSON rather than golden files, unlike its neighbour `TrendsDtoTest`:
 * these two payloads are four scalar keys each, and covering the omitted-key
 * matrix with fixtures would mean five files holding two lines apiece. The
 * goldens exist for payloads with *shape*; `TrendsApiTest`, in the same module,
 * already asserts against inline bodies for the same reason.
 *
 * The one thing worth more than the rest here: **every optional key is omitted
 * when absent, never null**, so each property's default has to be what its
 * absence means. A client that only ever saw the fully-populated form would not
 * notice the difference until the first sync of a fresh install.
 */
class GarminSyncDtoTest {

    private val json = WellnessJson

    private fun trigger(text: String) = json.decodeFromString(GarminSyncTrigger.serializer(), text)

    private fun status(text: String) = json.decodeFromString(GarminSyncStatus.serializer(), text)

    // ---- POST /sync --------------------------------------------------------

    @Test
    @DisplayName("all four trigger statuses decode, and only cooldown carries a countdown")
    fun triggerStatusesDecode() {
        assertEquals("started", trigger("""{"status":"started"}""").status)
        assertEquals("running", trigger("""{"status":"running"}""").status)
        assertEquals("unconfigured", trigger("""{"status":"unconfigured"}""").status)

        val cooldown = trigger("""{"status":"cooldown","retry_in_sec":412}""")
        assertEquals("cooldown", cooldown.status)
        assertEquals(412.0, cooldown.retryInSec)
    }

    @Test
    @DisplayName("an omitted retry_in_sec is null — absence is the value, and it is never sent as one")
    fun triggerOmitsRetryWhenNotCoolingDown() {
        assertNull(trigger("""{"status":"started"}""").retryInSec)
    }

    @Test
    @DisplayName("a fractional retry_in_sec decodes: it is a remainder off a clock, not a count")
    fun triggerRetryIsNotAnInteger() {
        // An Int property would fail the whole payload here, and the status —
        // the only field the client acts on — would be lost with it.
        assertEquals(412.5, trigger("""{"status":"cooldown","retry_in_sec":412.5}""").retryInSec)
    }

    // ---- GET /sync/status --------------------------------------------------

    @Test
    @DisplayName("a status with nothing but `running` decodes, all three optionals defaulting to null")
    fun statusWithNoHistoryDecodes() {
        val fresh = status("""{"running":false}""")

        assertFalse(fresh.running)
        assertNull(fresh.lastFinishedAt)
        assertNull(fresh.lastOutcome)
        assertNull(fresh.lastSyncedAt)
    }

    @Test
    @DisplayName("a fully populated status decodes every key, epoch millis included")
    fun fullStatusDecodes() {
        val done = status(
            """{"running":false,"last_finished_at":1893456000000,""" +
                """"last_outcome":"ok","last_synced_at":1893455000000}""",
        )

        assertFalse(done.running)
        assertEquals(1_893_456_000_000L, done.lastFinishedAt)
        assertEquals("ok", done.lastOutcome)
        assertEquals(1_893_455_000_000L, done.lastSyncedAt)
    }

    @Test
    @DisplayName("a failed outcome is a status word, not an error shape — the poll reads it and moves on")
    fun failedOutcomeDecodes() {
        val failed = status("""{"running":false,"last_outcome":"failed","last_finished_at":1893456000000}""")

        assertEquals("failed", failed.lastOutcome)
        assertNull(failed.lastSyncedAt)
    }

    @Test
    @DisplayName("an unknown key round-trips harmlessly — a server addition must not break a poll")
    fun unknownKeysAreIgnored() {
        val running = status("""{"running":true,"pid":4242}""")

        assertTrue(running.running)
    }
}

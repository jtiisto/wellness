package dev.jtiisto.wellness.core.data.network

import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.sync.DebugLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The device clock the server buckets the watch's days by. Two things have to
 * hold or the server's zone timeline is a fiction: the values must be the
 * phone's own, and they must be read at send time — a zone captured when the
 * client was built would keep reporting home from the other side of a flight,
 * and would keep reporting winter through the summer.
 *
 * The wall-clock instants below are in the past on purpose: a zone's offset on
 * a past date is a matter of record in the tz database, while one in the future
 * is a projection that a rule change could move under the test.
 */
class DeviceClockHeadersTest {

    private val debugLog = mockk<DebugLog>().also { log ->
        every { log.log(any(), any(), any()) } returns Unit
    }

    private val config = ServerConfig("https://server/wellness")

    private val requests = mutableListOf<HttpRequestData>()

    private fun client(
        zone: () -> ZoneId,
        now: (ZoneId) -> ZonedDateTime,
    ): HttpClient {
        val engine = MockEngine { request ->
            requests += request
            respond(
                content = "{}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        return buildHttpClient(engine, config, WellnessJson, debugLog, zone, now)
    }

    private fun at(month: Int, day: Int): (ZoneId) -> ZonedDateTime =
        { zone -> ZonedDateTime.of(2026, month, day, 12, 0, 0, 0, zone) }

    private fun zoneHeader(index: Int = 0) = requests[index].headers["X-Client-Zone"]

    private fun offsetHeader(index: Int = 0) = requests[index].headers["X-Client-Offset-Min"]

    @Test
    @DisplayName("every request carries the zone id and the offset in minutes")
    fun headersAreSentOnEveryRequest() = runTest {
        val client = client({ ZoneId.of("Europe/Helsinki") }, at(month = 7, day = 15))

        client.get(config.endpoint("api/journal/sync/status"))

        assertNotNull(zoneHeader(), "the server cannot bucket a day without the zone")
        assertNotNull(offsetHeader(), "the offset is the cross-check for an id the server does not know")
    }

    @Test
    @DisplayName("the offset is the zone's summer offset in summer, DST included")
    fun summerOffsetIsDstExact() = runTest {
        val client = client({ ZoneId.of("Europe/Helsinki") }, at(month = 7, day = 15))

        client.get(config.endpoint("api/journal/sync/status"))

        assertEquals("Europe/Helsinki", zoneHeader())
        assertEquals("180", offsetHeader())
    }

    @Test
    @DisplayName("the same zone in winter reports its standard offset")
    fun winterOffsetIsDstExact() = runTest {
        val client = client({ ZoneId.of("Europe/Helsinki") }, at(month = 1, day = 15))

        client.get(config.endpoint("api/journal/sync/status"))

        assertEquals("Europe/Helsinki", zoneHeader())
        assertEquals("120", offsetHeader())
    }

    @Test
    @DisplayName("a zone west of UTC keeps its sign")
    fun westOfUtcKeepsItsSign() = runTest {
        val client = client({ ZoneId.of("America/Los_Angeles") }, at(month = 7, day = 15))

        client.get(config.endpoint("api/journal/sync/status"))

        assertEquals("America/Los_Angeles", zoneHeader())
        assertEquals("-420", offsetHeader())
    }

    @Test
    @DisplayName("a half-hour zone is reported in minutes, not rounded to an hour")
    fun halfHourZoneKeepsItsMinutes() = runTest {
        val client = client({ ZoneId.of("Asia/Kolkata") }, at(month = 7, day = 15))

        client.get(config.endpoint("api/journal/sync/status"))

        assertEquals("Asia/Kolkata", zoneHeader())
        assertEquals("330", offsetHeader())
    }

    @Test
    @DisplayName("a quarter-hour zone survives too, so hour arithmetic cannot pass")
    fun quarterHourZoneKeepsItsMinutes() = runTest {
        val client = client({ ZoneId.of("Asia/Kathmandu") }, at(month = 7, day = 15))

        client.get(config.endpoint("api/journal/sync/status"))

        assertEquals("Asia/Kathmandu", zoneHeader())
        assertEquals("345", offsetHeader())
    }

    @Test
    @DisplayName("the headers are read per request, so a flight is reported by the next call")
    fun headersAreReEvaluatedPerRequest() = runTest {
        var here = ZoneId.of("Europe/Helsinki")
        val client = client({ here }, at(month = 7, day = 15))

        client.get(config.endpoint("api/journal/sync/status"))
        here = ZoneId.of("America/Los_Angeles")
        client.get(config.endpoint("api/journal/sync/status"))

        assertEquals("Europe/Helsinki", zoneHeader(0))
        assertEquals("180", offsetHeader(0))
        // Same client, same summer day: only the phone moved.
        assertEquals("America/Los_Angeles", zoneHeader(1))
        assertEquals("-420", offsetHeader(1))
    }

    @Test
    @DisplayName("with nothing injected the client reports the JVM's own clock")
    fun defaultsReportTheJvmClock() = runTest {
        val engine = MockEngine { request ->
            requests += request
            respond(
                content = "{}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = buildHttpClient(engine, config, WellnessJson, debugLog)

        val before = jvmOffsetMinutes()
        client.get(config.endpoint("api/journal/sync/status"))
        val after = jvmOffsetMinutes()

        assertEquals(ZoneId.systemDefault().id, zoneHeader())
        // Either value is correct; naming both is what keeps a DST transition
        // during the run from failing a test about the default path.
        assertTrue(
            offsetHeader() in setOf(before.toString(), after.toString()),
            "expected ${offsetHeader()} to be the JVM offset ($before or $after)",
        )
    }

    private fun jvmOffsetMinutes(): Int {
        val zone = ZoneId.systemDefault()
        return ZonedDateTime.now(zone).offset.totalSeconds / 60
    }
}

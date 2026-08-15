package dev.jtiisto.wellness.core.data.sync

import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.network.ServerConfig
import dev.jtiisto.wellness.core.data.network.buildHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The snackbar side of the rule [DebugLogPrivacyTest] guards for the dump.
 *
 * A snackbar is the wider disclosure of the two: the dump is shared only when
 * the user chooses to share it, while this appears unasked, over whatever tab
 * they are on and whoever is looking at the screen. Everything forbidden in a
 * dump is forbidden here — response bodies above all, because a FastAPI 422
 * echoes the input it rejected, and the input is the journal entry.
 *
 * The server address counts too. It is not health data, but it is the one
 * private-network hostname the app knows, and it has no business on a toast.
 *
 * These drive a **real** Ktor failure rather than a hand-built exception: the
 * leak being guarded is a property of the message Ktor assembles, so a stub
 * would prove nothing. Each test asserts that premise before asserting the fix.
 */
class SyncErrorSnackbarPrivacyTest {

    private val config = ServerConfig("https://$HOST:9443/wellness")

    /** A genuine `ResponseException`, message and all, for a body we chose. */
    private suspend fun responseFailure(status: HttpStatusCode, body: String): Throwable {
        val engine = MockEngine {
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = buildHttpClient(engine, config, WellnessJson, mockk(relaxed = true))
        return runCatching { client.get(config.endpoint("api/journal/sync/status")) }
            .exceptionOrNull()
            ?: error("expectSuccess should have turned $status into a failure")
    }

    @Test
    @DisplayName("a rejected upload's snackbar carries neither the echoed body nor the server address")
    fun serverErrorSnackbarCarriesNeitherBodyNorAddress() = runTest {
        val failure = responseFailure(
            HttpStatusCode.UnprocessableEntity,
            """{"detail":[{"loc":["body","value"],"msg":"invalid $FORBIDDEN_VALUE"}]}""",
        )
        val raw = failure.message.orEmpty()
        // The premise, and the whole reason this rule exists: Ktor really does
        // put both the URL and the response body in the message the old code
        // read.
        assertTrue(raw.contains(FORBIDDEN_VALUE), "premise: Ktor inlines the response body:\n$raw")
        assertTrue(raw.contains(HOST), "premise: Ktor inlines the request URL:\n$raw")

        val events = SyncErrorEvents()
        events.postServerError(failure)
        val shown = events.messages.first()

        assertFalse(shown.contains(FORBIDDEN_VALUE), "the rejected entry reached the snackbar: $shown")
        assertFalse(shown.contains(HOST), "the server address reached the snackbar: $shown")
        // What is left is still a diagnosis: which failure, and what the server
        // said about it in numbers.
        assertTrue(shown.startsWith("Sync Failed: "), shown)
        assertTrue(shown.contains("422"), "the status is the useful part and must survive: $shown")
    }

    @Test
    @DisplayName("no arm of postServerError reads Throwable.message — not even for a plain exception")
    fun aNonHttpFailureIsDescribedByTypeAlone() = runTest {
        val events = SyncErrorEvents()

        events.postServerError(IllegalStateException("row $FORBIDDEN_VALUE would not merge"))

        assertEquals("Sync Failed: IllegalStateException", events.messages.first())
    }

    private companion object {
        /** Stands in for whatever the server quoted back at us. */
        const val FORBIDDEN_VALUE = "FORBIDDEN-ENTRY-VALUE"

        /** Stands in for the user's private server, which the URL carries. */
        const val HOST = "forbidden-host.example"
    }
}

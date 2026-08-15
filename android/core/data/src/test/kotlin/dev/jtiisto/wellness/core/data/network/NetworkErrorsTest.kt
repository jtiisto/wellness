package dev.jtiisto.wellness.core.data.network

import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.statement.HttpResponse
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The silent-retry-vs-surface decision. A misclassification is not cosmetic:
 * calling a real server fault "network" hides it behind a status dot forever.
 */
class NetworkErrorsTest {

    @Test
    @DisplayName("transport failures classify as network, whatever the engine calls them")
    fun transportFailuresAreNetwork() {
        assertTrue(isNetworkError(IOException("closed")))
        assertTrue(isNetworkError(UnknownHostException("pop-os.tailexample.ts.net")))
        assertTrue(isNetworkError(SocketTimeoutException()))
        assertTrue(isNetworkError(HttpRequestTimeoutException("http://host/x", 30_000)))
    }

    @Test
    @DisplayName("a wrapped transport failure is still network")
    fun wrappedTransportFailureIsNetwork() {
        assertTrue(isNetworkError(IllegalStateException("engine failed", IOException("reset"))))
    }

    @Test
    @DisplayName("a server response and a decode failure are not network errors")
    fun serverFaultsAreNotNetwork() {
        val response = mockk<HttpResponse>(relaxed = true)
        every { response.toString() } returns "500"

        assertFalse(isNetworkError(ServerResponseException(response, "boom")))
        assertFalse(isNetworkError(SerializationException("unexpected token")))
        assertFalse(isNetworkError(IllegalStateException("plain bug")))
    }

    @Test
    @DisplayName("a self-referencing cause chain terminates instead of hanging")
    fun selfReferencingCauseTerminates() {
        val looping = object : RuntimeException("loop") {
            override val cause: Throwable get() = this
        }

        assertFalse(isNetworkError(looping))
    }
}

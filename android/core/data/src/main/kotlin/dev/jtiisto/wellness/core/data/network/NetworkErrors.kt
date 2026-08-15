package dev.jtiisto.wellness.core.data.network

import io.ktor.client.plugins.ResponseException
import java.io.IOException

/**
 * A failure described in terms that are safe to put in a shareable debug dump.
 *
 * **Never use `Throwable.message` for this.** Ktor builds a `ResponseException`
 * message as `"Bad response: … Text: \"<body>\""` — the response body, inline,
 * up to 32 KB of it. For a journal or coach sync that body is user data, and a
 * FastAPI 422 is the worst case of all: its `detail` echoes the *input* that
 * failed validation, so a rejected upload would put entry values straight into
 * the log the user is invited to share.
 *
 * The class name plus the status says everything a diagnosis actually needs.
 */
fun describeForLog(error: Throwable): String {
    val name = error::class.simpleName ?: "error"
    val status = when (error) {
        is ResponseException -> error.response.status.value
        is AnalysisHttpException -> error.status
        else -> null
    }
    return if (status == null) name else "$name(HTTP $status)"
}

/**
 * Whether a sync failure was the network rather than the server.
 *
 * The distinction drives the scheduler's silent-retry-vs-surface decision: a
 * dropped connection is already visible in the status dot and deserves no
 * toast, while a 500 is something the user should hear about.
 *
 * Classification is by exception *type*, never by message: Ktor's timeouts and
 * connect failures are all [IOException]s, whereas a non-2xx surfaces as a
 * `ResponseException`, which is not. Causes are walked because engine failures
 * routinely arrive wrapped.
 */
fun isNetworkError(error: Throwable): Boolean {
    var current: Throwable? = error
    while (current != null) {
        if (current is IOException) return true
        val cause = current.cause
        current = if (cause === current) null else cause
    }
    return false
}

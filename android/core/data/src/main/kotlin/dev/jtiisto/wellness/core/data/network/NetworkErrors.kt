package dev.jtiisto.wellness.core.data.network

import java.io.IOException

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

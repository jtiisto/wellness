package dev.jtiisto.wellness.core.data.network

import dev.jtiisto.wellness.core.data.db.ServerProfilesDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Which server this process talks to — decided once, at boot, before anything else runs. */
sealed interface ServerResolution {
    /** [nickname] is what the Tools tab shows; "Built-in" when no profile is active. */
    data class Resolved(val config: ServerConfig, val nickname: String) : ServerResolution

    /** The address book could not be trusted. No client, no schedulers, no writes. */
    data class Failed(val message: String) : ServerResolution
}

/**
 * Decides the server for this process, once, at startup.
 *
 * There are exactly four answers and three of them are not "use the built-in
 * server":
 *
 * | address book | answer |
 * |---|---|
 * | exactly one active row | that row's URL |
 * | no active row | the `BuildConfig` built-in |
 * | more than one active row | **fail closed** |
 * | the read itself failed | **fail closed** |
 *
 * The last two are the interesting ones, and falling back to the built-in
 * server for either would be actively dangerous rather than merely wrong. Room
 * still holds whatever the *previous* server sent — dirty rows included — and
 * those rows would be uploaded to whichever server the fallback picked. One
 * unreadable database would push server B's workout logs into server A. So a
 * confused address book stops the app at a recovery screen instead: no HTTP
 * client is built, no scheduler starts, and nothing can be written anywhere.
 *
 * The read is a suspend DAO call run under a bounded [runBlocking] on
 * [Dispatchers.IO]. Blocking the main thread at startup is the price of having
 * a decided answer before the first `get<ServerConfig>()`; the alternative —
 * `allowMainThreadQueries()` — would hand that permission to every query in the
 * app forever, to save one bounded wait at boot.
 */
class ServerBootstrap(
    private val dao: ServerProfilesDao,
    /** The compiled-in address, shown as the undeletable first row of the list. */
    val builtInUrl: String,
    private val timeoutMs: Long = RESOLVE_TIMEOUT_MS,
) {

    private val _state = MutableStateFlow<ServerResolution?>(null)

    /** Null until [resolveBlocking] has run. */
    val state: StateFlow<ServerResolution?> = _state.asStateFlow()

    /** Boot-time entry point. Also the recovery screen's retry. */
    fun resolveBlocking(): ServerResolution {
        val resolution = runBlocking {
            withTimeoutOrNull(timeoutMs) { withContext(Dispatchers.IO) { resolve() } }
        } ?: ServerResolution.Failed(TIMED_OUT)
        _state.value = resolution
        return resolution
    }

    suspend fun resolve(): ServerResolution = try {
        val active = dao.listActive()
        when {
            active.size > 1 -> ServerResolution.Failed(MULTIPLE_ACTIVE)
            active.size == 1 -> ServerResolution.Resolved(
                config = ServerConfig(active.first().url),
                nickname = active.first().nickname,
            )
            else -> ServerResolution.Resolved(ServerConfig(builtInUrl), BUILT_IN_NICKNAME)
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        ServerResolution.Failed(READ_FAILED)
    }

    /**
     * The resolved config, or a hard failure.
     *
     * Koin resolves [ServerConfig] through this, so an attempt to build the
     * HTTP client while the app is sitting on the recovery screen is a crash
     * rather than a silent fallback. Nothing on that screen touches it.
     */
    fun requireConfig(): ServerConfig = when (val resolution = _state.value) {
        is ServerResolution.Resolved -> resolution.config
        else -> error("the server was not resolved: $resolution")
    }

    companion object {
        const val BUILT_IN_NICKNAME = "Built-in"

        /** Generous: this is one indexed read of a table with a handful of rows. */
        const val RESOLVE_TIMEOUT_MS = 5_000L

        const val MULTIPLE_ACTIVE = "More than one server is marked active."
        const val READ_FAILED = "The saved server list could not be read."
        const val TIMED_OUT = "The saved server list did not respond."
    }
}

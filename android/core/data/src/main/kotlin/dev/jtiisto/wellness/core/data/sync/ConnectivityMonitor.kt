package dev.jtiisto.wellness.core.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the device has a *validated* internet connection.
 *
 * Validated matters: an unvalidated Wi-Fi association (captive portal, dead AP)
 * reports "connected" while every request times out, which would have the
 * scheduler burning retries.
 *
 * Networks are tracked individually, so losing one while another validated
 * network remains does not report offline.
 */
class ConnectivityMonitor(private val context: Context) {

    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val validated = mutableSetOf<Network>()

    /**
     * Guarded by the same monitor as [validated], not merely volatile: `start`
     * and `stop` are check-then-act on this field, and the app orchestrator's
     * lifecycle callbacks are not guaranteed to reach them from one thread.
     */
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Idempotent. Registers the callback, then seeds the current value. */
    fun start() {
        synchronized(validated) {
            if (callback != null) return
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return

            validated.clear()

            val observer = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    update { if (capabilities.isValidatedInternet()) validated += network else validated -= network }
                }

                override fun onLost(network: Network) {
                    update { validated -= network }
                }
            }
            manager.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                observer,
            )
            callback = observer

            // Seed AFTER registration, never before: a network alive at this
            // point is already covered by the callback (its onLost will
            // arrive), whereas one that died pre-registration would otherwise
            // latch in the set forever — the framework replays events only for
            // networks it still knows about. The lock is reentrant, so the
            // callback's own updates are ordered behind this one.
            update {
                val active = manager.activeNetwork
                if (active != null && manager.getNetworkCapabilities(active).isValidatedInternet()) {
                    validated += active
                }
            }
        }
    }

    /** Idempotent. Unregisters the callback; the last known value stays put. */
    fun stop() {
        synchronized(validated) {
            val observer = callback ?: return
            // Unregister first, and only then forget it. Clearing the field
            // ahead of a `getSystemService` that returns null would drop the
            // app's only reference to a callback the framework still holds —
            // an unremovable listener on a monitor that says it is stopped.
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
            manager.unregisterNetworkCallback(observer)
            callback = null
        }
    }

    private inline fun update(mutate: () -> Unit) {
        synchronized(validated) {
            mutate()
            _isOnline.value = validated.isNotEmpty()
        }
    }

    private fun NetworkCapabilities?.isValidatedInternet(): Boolean =
        this != null &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

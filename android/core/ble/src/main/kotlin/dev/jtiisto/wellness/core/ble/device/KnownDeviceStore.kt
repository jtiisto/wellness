package dev.jtiisto.wellness.core.ble.device

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A strap the user has successfully connected to at least once.
 *
 * [name] is non-null here, unlike [dev.jtiisto.wellness.core.ble.model.BleDevice.name]:
 * a device only becomes known through a connect the user initiated from a list
 * that showed them something, and an unnamed advertisement is remembered under
 * its address so the row is never blank.
 */
data class KnownDevice(
    val address: String,
    val name: String,
)

/**
 * Where the MAC→name map actually lives.
 *
 * An interface because the implementation is `SharedPreferences`, which cannot
 * run in a JVM unit test, and because *what the store does with the map* — the
 * ordering, the fallbacks, publishing the observable list — is the part worth
 * testing. See `PrefsKnownDeviceStorage` for the real one.
 */
interface KnownDeviceStorage {
    fun load(): Map<String, String>
    fun put(address: String, name: String)
    fun remove(address: String)
}

/**
 * The remembered straps, and the observable list the pairing UI renders.
 *
 * **No OS bonding.** A heart-rate strap is an open GATT peripheral; pairing it
 * at the platform level buys nothing and gives the user a second, confusing
 * place where the device can be forgotten. "Known" here means only "this app
 * has connected to it and will offer it again".
 *
 * Deliberately does **not** read [storage] on construction: this is a Koin
 * singleton, so the first `get()` could land on the main thread, and a
 * `SharedPreferences` load there is disk I/O in a frame. Callers [refresh]
 * from a coroutine when they need the list; [save] and [forget] republish on
 * their own, so a screen that only ever writes stays correct without one.
 *
 * @param state the Koin-owned flow the UI collects. Injected rather than owned
 *   so the singleton and the qualifier that keeps it distinct from the capture
 *   state both live in one place — see `bleModule`.
 */
class KnownDeviceStore(
    private val storage: KnownDeviceStorage,
    private val state: MutableStateFlow<List<KnownDevice>>,
) {

    val devices: StateFlow<List<KnownDevice>> = state.asStateFlow()

    /** Re-read the map and publish it. Safe to call repeatedly. */
    fun refresh() {
        state.value = storage.load()
            .map { (address, name) -> KnownDevice(address, name) }
            // Stable order, so a list that has not changed does not reshuffle
            // itself between two reads of an unordered map.
            .sortedWith(compareBy({ it.name.lowercase() }, { it.address }))
    }

    /**
     * Remember [address]. A blank or absent [name] falls back to the address,
     * so a row in the UI is never empty.
     */
    fun save(address: String, name: String?) {
        storage.put(address, name?.takeIf { it.isNotBlank() } ?: address)
        refresh()
    }

    fun forget(address: String) {
        storage.remove(address)
        refresh()
    }

    /** The remembered name for [address], or null when it is not known. */
    fun nameOf(address: String): String? = storage.load()[address]

    fun isKnown(address: String): Boolean = nameOf(address) != null

    /**
     * The strap the Start Workout sheet offers.
     *
     * v1 remembers a map but uses one device: the sheet only appears when there
     * is something to offer, and offering the first of a stable ordering is
     * both deterministic and, with a single strap, exactly right.
     */
    fun preferred(): KnownDevice? = storage.load()
        .map { (address, name) -> KnownDevice(address, name) }
        .minWithOrNull(compareBy({ it.name.lowercase() }, { it.address }))
}

package dev.jtiisto.wellness.core.ble.device

import android.content.Context
import android.content.SharedPreferences

/**
 * [KnownDeviceStorage] over `SharedPreferences`, one entry per strap.
 *
 * A prefixed key per device rather than one serialized blob: the map has at most
 * a couple of entries, and a prefix keeps a corrupt or unexpected value from
 * taking the whole list with it.
 *
 * Device-only glue, excluded from the coverage gate — the logic that reads this
 * map lives in [KnownDeviceStore] and is tested against a fake.
 */
class PrefsKnownDeviceStorage(context: Context) : KnownDeviceStorage {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): Map<String, String> = prefs.all.entries
        .filter { it.key.startsWith(KEY_PREFIX) }
        .mapNotNull { (key, value) ->
            val name = value as? String ?: return@mapNotNull null
            key.removePrefix(KEY_PREFIX) to name
        }
        .toMap()

    override fun put(address: String, name: String) {
        prefs.edit().putString(KEY_PREFIX + address, name).apply()
    }

    override fun remove(address: String) {
        prefs.edit().remove(KEY_PREFIX + address).apply()
    }

    private companion object {
        const val PREFS_NAME = "hr_known_devices"
        const val KEY_PREFIX = "device_"
    }
}

package dev.jtiisto.wellness.core.ble.device

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The map `PrefsKnownDeviceStorage` keeps, without the SharedPreferences. */
private class FakeKnownDeviceStorage(
    initial: Map<String, String> = emptyMap(),
) : KnownDeviceStorage {

    // Deliberately unordered-ish: a HashMap is what SharedPreferences.getAll()
    // hands back, and the store is what has to make the list deterministic.
    private val entries = HashMap(initial)

    override fun load(): Map<String, String> = HashMap(entries)

    override fun put(address: String, name: String) {
        entries[address] = name
    }

    override fun remove(address: String) {
        entries.remove(address)
    }
}

/**
 * The remembered straps.
 *
 * There is no OS bonding involved: "known" means only that this app connected
 * to the device once and will offer it again, so everything worth asserting is
 * about the map and the list the UI renders from it.
 */
class KnownDeviceStoreTest {

    private fun store(
        storage: KnownDeviceStorage = FakeKnownDeviceStorage(),
    ): Pair<KnownDeviceStore, MutableStateFlow<List<KnownDevice>>> {
        val state = MutableStateFlow(emptyList<KnownDevice>())
        return KnownDeviceStore(storage, state) to state
    }

    @Test
    @DisplayName("nothing is read until something asks — the constructor touches no storage")
    fun constructionIsCheap() {
        val (store, state) = store(FakeKnownDeviceStorage(mapOf("AA:BB" to "HRM-Pro")))

        // A Koin singleton's first get() can land on the main thread, and a
        // SharedPreferences load there is disk I/O in a frame.
        assertEquals(emptyList<KnownDevice>(), state.value)

        store.refresh()
        assertEquals(listOf(KnownDevice("AA:BB", "HRM-Pro")), state.value)
    }

    @Test
    @DisplayName("saving publishes the new list without a separate refresh")
    fun saveRepublishes() {
        val (store, state) = store()

        store.save("AA:BB", "HRM-Pro")

        assertEquals(listOf(KnownDevice("AA:BB", "HRM-Pro")), state.value)
        assertEquals(state.value, store.devices.value)
    }

    @Test
    @DisplayName("a nameless advertisement is remembered under its address")
    fun blankNamesFallBackToTheAddress() {
        val (store, _) = store()

        store.save("AA:BB", null)
        store.save("CC:DD", "   ")

        assertEquals("AA:BB", store.nameOf("AA:BB"))
        assertEquals("CC:DD", store.nameOf("CC:DD"))
    }

    @Test
    @DisplayName("forgetting removes it from storage and from the list")
    fun forgetRemoves() {
        val (store, state) = store()
        store.save("AA:BB", "HRM-Pro")

        store.forget("AA:BB")

        assertEquals(emptyList<KnownDevice>(), state.value)
        assertNull(store.nameOf("AA:BB"))
        assertFalse(store.isKnown("AA:BB"))
    }

    @Test
    @DisplayName("the list is ordered by name, so two reads never reshuffle it")
    fun listIsStablyOrdered() {
        val (store, state) = store(
            FakeKnownDeviceStorage(
                mapOf("CC:DD" to "polar h10", "AA:BB" to "HRM-Pro", "EE:FF" to "Garmin"),
            ),
        )

        store.refresh()

        assertEquals(
            listOf("Garmin", "HRM-Pro", "polar h10"),
            state.value.map { it.name },
        )
    }

    @Test
    @DisplayName("two straps with one name still order deterministically, by address")
    fun tiesBreakOnAddress() {
        val (store, state) = store(
            FakeKnownDeviceStorage(mapOf("CC:DD" to "HRM", "AA:BB" to "HRM")),
        )

        store.refresh()

        assertEquals(listOf("AA:BB", "CC:DD"), state.value.map { it.address })
    }

    @Test
    @DisplayName("the preferred strap is the first of that same ordering")
    fun preferredIsDeterministic() {
        val (store, _) = store(
            FakeKnownDeviceStorage(mapOf("CC:DD" to "Polar H10", "AA:BB" to "Garmin HRM")),
        )

        assertEquals(KnownDevice("AA:BB", "Garmin HRM"), store.preferred())
    }

    @Test
    @DisplayName("nothing known means nothing to offer")
    fun preferredIsNullWhenEmpty() {
        val (store, _) = store()

        assertNull(store.preferred())
        assertTrue(store.devices.value.isEmpty())
    }
}

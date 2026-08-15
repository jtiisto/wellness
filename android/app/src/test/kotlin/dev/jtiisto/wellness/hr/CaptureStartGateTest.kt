package dev.jtiisto.wellness.hr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The capture service's own preconditions.
 *
 * The UI is supposed to make all three true before the intent is ever sent, so
 * the interesting cases are the ones that reach the service without passing
 * through it: a `START_STICKY` restart after a process kill, and a permission
 * revoked while a session was running.
 */
class CaptureStartGateTest {

    @Test
    @DisplayName("everything in place permits the start")
    fun permitted() {
        assertEquals(
            CaptureRefusal.NONE,
            CaptureStartGate.evaluate("AA:BB", hasConnectPermission = true, serverResolved = true),
        )
    }

    @Test
    @DisplayName("no strap address, no capture — a blank one counts as none")
    fun missingDevice() {
        assertEquals(
            CaptureRefusal.MISSING_DEVICE,
            CaptureStartGate.evaluate(null, hasConnectPermission = true, serverResolved = true),
        )
        assertEquals(
            CaptureRefusal.MISSING_DEVICE,
            CaptureStartGate.evaluate("   ", hasConnectPermission = true, serverResolved = true),
        )
    }

    @Test
    @DisplayName("without BLUETOOTH_CONNECT the GATT connect would only throw")
    fun missingPermission() {
        assertEquals(
            CaptureRefusal.MISSING_BLUETOOTH_PERMISSION,
            CaptureStartGate.evaluate("AA:BB", hasConnectPermission = false, serverResolved = true),
        )
    }

    @Test
    @DisplayName("an unresolved server is refused before the wake lock is taken")
    fun unresolvedServer() {
        // The upload nudge resolves HrSyncStore, which resolves the server
        // config, which throws when nothing is resolved — so a service that
        // started here would die on its first buffer flush, holding a wake lock
        // and a foreground notification.
        assertEquals(
            CaptureRefusal.SERVER_UNRESOLVED,
            CaptureStartGate.evaluate("AA:BB", hasConnectPermission = true, serverResolved = false),
        )
    }

    @Test
    @DisplayName("the device check comes first, so a start with nothing at all names the device")
    fun refusalOrderIsDeterministic() {
        assertEquals(
            CaptureRefusal.MISSING_DEVICE,
            CaptureStartGate.evaluate(null, hasConnectPermission = false, serverResolved = false),
        )
        assertEquals(
            CaptureRefusal.MISSING_BLUETOOTH_PERMISSION,
            CaptureStartGate.evaluate("AA:BB", hasConnectPermission = false, serverResolved = false),
        )
    }

    @Test
    @DisplayName("a sticky restart goes through the same gate, and is not a way around it")
    fun resumeIsGatedLikeAStart() {
        // The address comes off the resumed session row rather than an Intent,
        // and nothing else about the decision changes. A restart arriving after
        // the user revoked the permission must not quietly resume recording.
        val fromTheRow = "AA:BB:CC:DD:EE:FF"

        assertEquals(
            CaptureRefusal.NONE,
            CaptureStartGate.evaluate(fromTheRow, hasConnectPermission = true, serverResolved = true),
        )
        assertEquals(
            CaptureRefusal.MISSING_BLUETOOTH_PERMISSION,
            CaptureStartGate.evaluate(fromTheRow, hasConnectPermission = false, serverResolved = true),
        )
        assertEquals(
            CaptureRefusal.SERVER_UNRESOLVED,
            CaptureStartGate.evaluate(fromTheRow, hasConnectPermission = true, serverResolved = false),
        )
    }

    @Test
    @DisplayName("a resumed row with no device is refused rather than started blind")
    fun resumeWithACorruptRowIsRefused() {
        assertEquals(
            CaptureRefusal.MISSING_DEVICE,
            CaptureStartGate.evaluate("", hasConnectPermission = true, serverResolved = true),
        )
    }

    @Test
    @DisplayName("every refusal has its own log line")
    fun everyRefusalIsDescribed() {
        val reasons = CaptureRefusal.entries.map(CaptureStartGate::reason)

        assertEquals(reasons.size, reasons.distinct().size)
        assertTrue(reasons.none { it.isBlank() })
    }
}

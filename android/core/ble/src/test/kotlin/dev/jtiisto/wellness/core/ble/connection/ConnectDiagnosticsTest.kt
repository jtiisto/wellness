package dev.jtiisto.wellness.core.ble.connection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * What a failed connect tells the user.
 *
 * The distinction being defended is the one the advertising probe exists for:
 * a strap that is silent (asleep, out of range, or connected elsewhere) needs
 * a different action from one that is advertising and refusing the connection.
 * Claiming either without having listened would send someone hunting a fault
 * that is not there, which is why the agnostic cases return null rather than
 * guessing.
 */
class ConnectDiagnosticsTest {

    @Test
    @DisplayName("an advertisement heard means the strap is there and the link failed")
    fun heardVerdict() {
        val verdict = ConnectDiagnostics.verdict(ProbeState.HEARD, rssi = -62)

        assertTrue(verdict!!.contains("is advertising"))
        assertTrue(verdict.contains("-62"))
    }

    @Test
    @DisplayName("listening and hearing nothing is the only thing that claims silence")
    fun listeningVerdict() {
        val verdict = ConnectDiagnostics.verdict(ProbeState.LISTENING, rssi = null)

        assertEquals("strap not advertising — asleep, out of range, or connected elsewhere", verdict)
    }

    @Test
    @DisplayName("the silent verdict names no cause the probe cannot see")
    fun listeningVerdictMakesNoAntPlusClaim() {
        // A silent probe cannot tell who holds the strap, and ANT+ reception
        // does not occupy a Bluetooth slot — the ported wording claimed both.
        val verdict = ConnectDiagnostics.verdict(ProbeState.LISTENING, rssi = null)!!

        assertFalse(verdict.contains("ANT", ignoreCase = true))
        assertFalse(verdict.contains("watch", ignoreCase = true))
        assertFalse(verdict.contains("likely", ignoreCase = true))
    }

    @Test
    @DisplayName("the heard verdict is unchanged by the 2026-10-17 amendment")
    fun heardVerdictPinned() {
        assertEquals(
            "strap is advertising, rssi=-62 — connection failed or rejected",
            ConnectDiagnostics.verdict(ProbeState.HEARD, rssi = -62),
        )
    }

    @Test
    @DisplayName("a drop past the gap threshold says the link dropped, and claims no failed attempt")
    fun dropDetail() {
        assertEquals("Strap link dropped — reconnecting", ConnectDiagnostics.dropDetail())
        assertFalse(ConnectDiagnostics.dropDetail().contains("failed"))
    }

    @Test
    @DisplayName("a probe that never listened says nothing at all")
    fun agnosticVerdicts() {
        assertNull(ConnectDiagnostics.verdict(ProbeState.INACTIVE, rssi = null))
        assertNull(ConnectDiagnostics.verdict(ProbeState.UNAVAILABLE, rssi = null))
        // Even holding a stale RSSI: the state, not the number, is the verdict.
        assertNull(ConnectDiagnostics.verdict(ProbeState.UNAVAILABLE, rssi = -40))
    }

    @Test
    @DisplayName("retry text carries the verdict in parentheses when there is one")
    fun retryDetailWithVerdict() {
        assertEquals(
            "Connect attempt 3 failed (strap asleep) — retrying",
            ConnectDiagnostics.retryDetail(3, "strap asleep"),
        )
    }

    @Test
    @DisplayName("no verdict leaves no empty parentheses behind")
    fun retryDetailWithoutVerdict() {
        assertEquals("Connect attempt 1 failed — retrying", ConnectDiagnostics.retryDetail(1, null))
    }

    @Test
    @DisplayName("the give-up text reports how many attempts were spent")
    fun giveUpDetail() {
        assertEquals(
            "Unable to connect after 15 attempts",
            ConnectDiagnostics.giveUpDetail(15, null),
        )
        assertEquals(
            "Unable to connect after 15 attempts (strap asleep)",
            ConnectDiagnostics.giveUpDetail(15, "strap asleep"),
        )
    }
}

package dev.jtiisto.wellness.core.ble.connection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * What a failed connect tells the user.
 *
 * The distinction being defended is the one the advertising probe exists for:
 * a strap that is silent (asleep, or held by a watch over ANT+) needs a
 * different action from one that is advertising and refusing the connection.
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

        assertTrue(verdict!!.contains("not advertising"))
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

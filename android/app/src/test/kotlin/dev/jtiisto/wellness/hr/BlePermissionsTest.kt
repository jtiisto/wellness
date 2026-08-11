package dev.jtiisto.wellness.hr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The app's first runtime-permission flow, as decisions.
 *
 * The one that matters is the difference between a denial that can be asked
 * about again and one that cannot: asking after a permanent denial shows no
 * dialog at all, which the user experiences as a button that does nothing.
 */
class BlePermissionsTest {

    @Test
    @DisplayName("scan and connect are requested as a pair")
    fun bothPermissionsAreRequired() {
        assertEquals(2, BlePermissions.REQUIRED.size)
        assertEquals(BlePermissions.REQUIRED.size, BlePermissions.REQUIRED.distinct().size)
        assertTrue(BlePermissions.REQUIRED.all { it.isNotBlank() })
    }

    @Test
    @DisplayName("granted means go")
    fun grantedProceeds() {
        assertEquals(PermissionStep.PROCEED, BlePermissions.stepFor(BlePermissionStatus.GRANTED))
    }

    @Test
    @DisplayName("never asked, and asked-and-refused-once, both ask")
    fun unknownAndDeniedRequest() {
        assertEquals(PermissionStep.REQUEST, BlePermissions.stepFor(BlePermissionStatus.UNKNOWN))
        assertEquals(PermissionStep.REQUEST, BlePermissions.stepFor(BlePermissionStatus.DENIED))
    }

    @Test
    @DisplayName("a permanent denial explains instead of asking into the void")
    fun blockedExplains() {
        assertEquals(PermissionStep.EXPLAIN, BlePermissions.stepFor(BlePermissionStatus.BLOCKED))
    }

    // ---- reading the answer out of the result map --------------------------

    @Test
    @DisplayName("both granted is the only grant; a half-granted result is a denial")
    fun bothOrNothing() {
        val (scan, connect) = BlePermissions.REQUIRED

        assertEquals(PermissionAnswer.GRANTED, BlePermissions.answerFrom(mapOf(scan to true, connect to true)))
        // A scan grant that cannot connect would fill the pairing list with
        // straps none of which could be connected to — worse than not scanning,
        // because it looks like it worked.
        assertEquals(PermissionAnswer.DENIED, BlePermissions.answerFrom(mapOf(scan to true, connect to false)))
        assertEquals(PermissionAnswer.DENIED, BlePermissions.answerFrom(mapOf(scan to false, connect to false)))
    }

    @Test
    @DisplayName("an empty result map is no answer at all, not a refusal")
    fun emptyResultIsNoAnswer() {
        // Android reports a cancelled permission interaction — dialog swiped
        // away, activity recreated under it — as an empty map.
        assertEquals(PermissionAnswer.NONE, BlePermissions.answerFrom(emptyMap()))
    }

    @Test
    @DisplayName("a result missing either permission is no answer either")
    fun partialResultIsNoAnswer() {
        val (scan, connect) = BlePermissions.REQUIRED

        assertEquals(PermissionAnswer.NONE, BlePermissions.answerFrom(mapOf(scan to true)))
        assertEquals(PermissionAnswer.NONE, BlePermissions.answerFrom(mapOf(connect to false)))
    }

    // ---- what an answer does to the state machine ----------------------------

    @Test
    @DisplayName("the answer maps onto the three outcomes, and only the last one is terminal")
    fun statusAfterResult() {
        assertEquals(
            BlePermissionStatus.GRANTED,
            BlePermissions.statusAfter(PermissionAnswer.GRANTED, canAskAgain = false),
        )
        assertEquals(
            BlePermissionStatus.GRANTED,
            BlePermissions.statusAfter(PermissionAnswer.GRANTED, canAskAgain = true),
        )
        assertEquals(
            BlePermissionStatus.DENIED,
            BlePermissions.statusAfter(PermissionAnswer.DENIED, canAskAgain = true),
        )
        assertEquals(
            BlePermissionStatus.BLOCKED,
            BlePermissions.statusAfter(PermissionAnswer.DENIED, canAskAgain = false),
        )
    }

    @Test
    @DisplayName("an interrupted request records nothing — it must never reach BLOCKED")
    fun noAnswerRecordsNothing() {
        // The bug this exists to pin: shouldShowRequestPermissionRationale is
        // false BEFORE the first dialog has ever been shown, so reading it on a
        // no-answer would classify an interrupted first tap as permanently
        // denied and send the user to Settings for a permission nobody asked
        // them for — with no way back, because the section would then refuse to
        // ask again.
        assertNull(BlePermissions.statusAfter(PermissionAnswer.NONE, canAskAgain = false))
        assertNull(BlePermissions.statusAfter(PermissionAnswer.NONE, canAskAgain = true))
    }

    @Test
    @DisplayName("only a refusal has anything to say, and the two refusals do not say the same thing")
    fun explanations() {
        assertNull(BlePermissions.explanation(BlePermissionStatus.GRANTED))
        // Nobody has been asked yet: there is nothing to explain about a dialog
        // that has not been shown.
        assertNull(BlePermissions.explanation(BlePermissionStatus.UNKNOWN))

        val denied = requireNotNull(BlePermissions.explanation(BlePermissionStatus.DENIED))
        val blocked = requireNotNull(BlePermissions.explanation(BlePermissionStatus.BLOCKED))
        assertTrue(denied != blocked)
        // The blocked one has to say where the switch is, because tapping again
        // will not produce a dialog.
        assertTrue(blocked.contains("Settings"))
    }
}

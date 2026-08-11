package dev.jtiisto.wellness.hr

import android.Manifest

/**
 * Where the app stands with the two Bluetooth permissions a strap needs.
 *
 * [UNKNOWN] and [DENIED] both lead to a request and are still kept apart: the
 * first has never been asked, and only the second has anything worth putting on
 * screen. [BLOCKED] is the one that changes the flow — the system will refuse
 * the dialog without showing it, so asking again is indistinguishable from doing
 * nothing and the user has to be told where the switch is instead.
 */
enum class BlePermissionStatus { UNKNOWN, GRANTED, DENIED, BLOCKED }

/**
 * What a permission request actually came back with.
 *
 * [NONE] is the one that matters and the one a two-state `granted: Boolean`
 * cannot express. A permission interaction can end without the system ever
 * deciding anything — the user swipes the dialog away, the activity is
 * recreated under it, the request is cancelled — and Android reports that as an
 * **empty result map**, indistinguishable from a refusal if all you look at is
 * "did everything come back true".
 */
enum class PermissionAnswer {

    /** Every required permission came back granted. */
    GRANTED,

    /** The system decided, and at least one required permission was refused. */
    DENIED,

    /** No decision was reached. Nothing is now known that was not known before. */
    NONE,
}

/** What a scan or connect tap must do before it can go ahead. */
enum class PermissionStep {
    /** Granted — run the action now. */
    PROCEED,

    /** Ask the system, and run the action if the answer is yes. */
    REQUEST,

    /** Asking is a no-op; say why instead. */
    EXPLAIN,
}

/**
 * The app's first runtime-permission flow, as decisions rather than callbacks.
 *
 * All of it is here rather than inside the composable that owns the launcher,
 * because the launcher is the one part that cannot be tested off a device and
 * the rules are the part that can be wrong: asking again after a permanent
 * denial looks to the user exactly like a button that does nothing.
 */
object BlePermissions {

    /**
     * Requested as a pair, always.
     *
     * A scan grant with no connect grant would let the pairing list fill up with
     * straps none of which could be connected to — a worse outcome than not
     * scanning, because it looks like it worked.
     */
    val REQUIRED: List<String> = listOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    fun stepFor(status: BlePermissionStatus): PermissionStep = when (status) {
        BlePermissionStatus.GRANTED -> PermissionStep.PROCEED
        BlePermissionStatus.UNKNOWN, BlePermissionStatus.DENIED -> PermissionStep.REQUEST
        BlePermissionStatus.BLOCKED -> PermissionStep.EXPLAIN
    }

    /**
     * What the system actually answered.
     *
     * A result that does not carry a verdict for **every** required permission
     * is [PermissionAnswer.NONE], not a denial. An empty map is the cancelled
     * interaction; a partial one is a shape the platform is not supposed to
     * produce, and guessing at the missing half is exactly the guess that would
     * strand the user.
     */
    fun answerFrom(results: Map<String, Boolean>): PermissionAnswer = when {
        REQUIRED.any { it !in results } -> PermissionAnswer.NONE
        // Both permissions, or none of the flow works — see [REQUIRED].
        REQUIRED.all { results[it] == true } -> PermissionAnswer.GRANTED
        else -> PermissionAnswer.DENIED
    }

    /**
     * The status a request result leaves behind, or **null when it leaves the
     * state exactly as it found it**.
     *
     * [canAskAgain] is `shouldShowRequestPermissionRationale`, and it is
     * consulted **only after a real denial** — which is the whole point of this
     * signature. That flag is also false *before* the first dialog has ever been
     * shown, so reading it on a [PermissionAnswer.NONE] would classify an
     * interrupted first tap as [BlePermissionStatus.BLOCKED] and send the user
     * to Settings to re-enable a permission they were never asked for, with no
     * way back: the section would refuse to ask again from then on.
     *
     * So a no-answer records nothing. The next tap asks again, which is the
     * correct reading of "the user dismissed the dialog".
     */
    fun statusAfter(answer: PermissionAnswer, canAskAgain: Boolean): BlePermissionStatus? = when (answer) {
        PermissionAnswer.GRANTED -> BlePermissionStatus.GRANTED
        PermissionAnswer.DENIED ->
            if (canAskAgain) BlePermissionStatus.DENIED else BlePermissionStatus.BLOCKED

        PermissionAnswer.NONE -> null
    }

    /** The inline text under the section, or null when there is nothing to say. */
    fun explanation(status: BlePermissionStatus): String? = when (status) {
        BlePermissionStatus.GRANTED, BlePermissionStatus.UNKNOWN -> null
        BlePermissionStatus.DENIED -> BlePermissionCopy.DENIED
        BlePermissionStatus.BLOCKED -> BlePermissionCopy.BLOCKED
    }
}

/**
 * What the user is told when the permission is not there.
 *
 * Two sentences that differ in exactly one way — whether tapping again will do
 * anything — because that is the only thing the user can act on.
 */
object BlePermissionCopy {

    const val DENIED =
        "Bluetooth permission is needed to find and connect to a heart-rate strap. " +
            "Tap Scan again to allow it."

    const val BLOCKED =
        "Bluetooth permission is turned off for this app. Enable Nearby devices in " +
            "Android Settings › Apps › Wellness › Permissions, then come back."
}

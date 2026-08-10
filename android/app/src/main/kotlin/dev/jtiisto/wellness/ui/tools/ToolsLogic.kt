package dev.jtiisto.wellness.ui.tools

import dev.jtiisto.wellness.core.data.db.ServerProfileEntity

/**
 * One row of the server list.
 *
 * The built-in server is a row like any other so the list reads as one list —
 * but it has no [id], which is what makes "you cannot delete this one" a
 * property of the data rather than a rule the UI has to remember.
 */
data class ServerProfileRow(
    val id: Long?,
    val nickname: String,
    val url: String,
    val isActive: Boolean,
) {
    val isBuiltIn: Boolean get() = id == null
}

/**
 * The Tools tab's decisions, as functions of state.
 *
 * Separated from the ViewModel because these are exactly the parts worth
 * pinning: which row is active is the difference between reconciling with the
 * right server and the wrong one, and the dialog copy is a promise about
 * destroying data that has to say what the code actually does.
 */
object ToolsLogic {

    /**
     * The list as rendered: the built-in server first, then saved profiles in
     * ascending id order.
     *
     * The built-in row is active precisely when no saved profile is — the same
     * rule boot resolution uses, deliberately duplicated nowhere else. More than
     * one active row cannot be rendered here at all, because boot would have
     * failed closed before this screen existed.
     */
    fun rows(profiles: List<ServerProfileEntity>, builtInUrl: String): List<ServerProfileRow> {
        val anyActive = profiles.any { it.isActive }
        return buildList {
            add(
                ServerProfileRow(
                    id = null,
                    nickname = ToolsCopy.BUILT_IN,
                    url = builtInUrl,
                    isActive = !anyActive,
                ),
            )
            profiles.sortedBy { it.id }.forEach { profile ->
                add(ServerProfileRow(profile.id, profile.nickname, profile.url, profile.isActive))
            }
        }
    }

    /** The nickname of whichever row is active — what the header shows. */
    fun activeNickname(profiles: List<ServerProfileEntity>): String =
        profiles.firstOrNull { it.isActive }?.nickname ?: ToolsCopy.BUILT_IN
}

/**
 * Every user-facing string on this tab.
 *
 * Gathered here because three of them describe irreversible data loss, and a
 * warning that has drifted from what the button does is worse than no warning.
 * The tests assert the text.
 */
object ToolsCopy {

    const val BUILT_IN = "Built-in"

    const val FORCE_SYNC = "Force sync"
    const val FORCE_SYNC_CONFIRM_TITLE = "Force sync?"
    const val EXPORT = "Export all data"
    const val SHARE_LOG = "Share log"

    const val EMPTY_PROFILES = "No saved servers."

    const val DELETE_TITLE = "Delete server?"
    const val SWITCH_TITLE = "Switch server?"
    const val DELETE_ACTION = "Delete"
    const val CANCEL_ACTION = "Cancel"
    const val SWITCH_ACTION = "Switch"
    const val CONTINUE_ACTION = "Continue"

    /**
     * The switch flow is the ONLY path that may change which server the app
     * talks to. A `rename` on the active row would have moved the URL that boot
     * resolution reads with no quiescence and no wipe, so the next launch would
     * upload the previous server's dirty rows to the new one.
     */
    const val ACTIVE_URL_LOCKED =
        "To change the active server's address, add it as a new profile and switch to it."

    const val EXPORT_FAILED = "Export failed"
    const val LOG_SHARE_FAILED = "Could not share the log"

    /**
     * Shown under the log. The 1 h retention window can straddle a switch, so a
     * dump shared afterwards may contain traffic from two different servers —
     * which is confusing rather than harmful, and only if nobody says so.
     */
    const val LOG_SPANS_SERVERS =
        "Entries may span more than one server if you switched within the last hour."

    fun deleteBody(nickname: String): String = "Remove \"$nickname\" from the list?"

    fun switchBody(nickname: String): String =
        "This wipes ALL local data on this device — including any unsynced changes — " +
            "and loads everything from \"$nickname\" instead. The app will close; reopen it to continue."

    /**
     * Deleting the profile the app is currently using is a switch *and* a
     * delete, so the one dialog has to disclose both. Splitting it into two
     * confirmations would have let someone accept the delete without ever being
     * told the data goes with it.
     */
    fun deleteActiveBody(nickname: String): String =
        "Delete \"$nickname\"? This also wipes all local data loaded from it and returns to " +
            "the built-in server. The app will close; reopen it to continue."
}

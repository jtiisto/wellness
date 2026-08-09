package dev.jtiisto.wellness.feature.analysis

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.ui.graphics.vector.ImageVector
import dev.jtiisto.wellness.core.data.analysis.ActiveReport
import dev.jtiisto.wellness.core.data.analysis.AnalysisQueryDto

/** What the "Try Again" button on a failed report is allowed to do. */
enum class TryAgainMode {
    /** The query is still registered and needs no input: run it. */
    RESUBMIT,

    /**
     * The query takes a location, so re-running it means going back to the card
     * and letting the user type one. It is never submitted on their behalf — the
     * PWA resubmitted location-less, silently producing a different report from
     * the one that failed.
     */
    NEEDS_LOCATION,

    /** The query is gone from the server, or the list never loaded. */
    UNAVAILABLE,
}

/**
 * The decisions the Analysis screens make that are worth testing without a
 * composition around them.
 */
object AnalysisUiLogic {

    fun tryAgainMode(queries: List<AnalysisQueryDto>, queryId: String): TryAgainMode {
        val query = queries.firstOrNull { it.id == queryId } ?: return TryAgainMode.UNAVAILABLE
        return if (query.acceptsLocation) TryAgainMode.NEEDS_LOCATION else TryAgainMode.RESUBMIT
    }

    /**
     * Whether a history row may be deleted.
     *
     * The server refuses with a 409 for exactly `pending` and `running`, so those
     * are exactly the rows that hide the control. A status neither side
     * recognises is *deletable* server-side, and hiding the ✕ for it would leave
     * a row nobody can ever remove.
     */
    fun canDelete(wireStatus: String): Boolean =
        wireStatus != "pending" && wireStatus != "running"

    /** The raw wire status, in the taxonomy dialect. Unknown values show as they came. */
    fun statusLabel(wireStatus: String): String = wireStatus.uppercase()

    /** A stub knows its label from the card that submitted it; a stub without one falls back. */
    fun progressLabel(active: ActiveReport?): String =
        active?.queryLabel?.takeIf { it.isNotBlank() } ?: "Running query…"

    /** When a report finished, or when it started if it never did. */
    fun reportTimestamp(completedAt: String?, createdAt: String): String =
        completedAt?.takeIf { it.isNotBlank() } ?: createdAt

    /**
     * The glyph for a query card.
     *
     * The names are the server's (`analysis_queries.py`), and an unknown or
     * missing one falls back to a neutral document — a user query registered
     * without an icon still has to render.
     */
    fun queryIcon(name: String?): ImageVector = when (name) {
        "dumbbell" -> Icons.Filled.FitnessCenter
        "zap" -> Icons.Filled.Bolt
        "calendar" -> Icons.Filled.CalendarMonth
        "heart-pulse" -> Icons.Filled.MonitorHeart
        "trending-up" -> Icons.Filled.TrendingUp
        else -> Icons.Filled.Description
    }
}

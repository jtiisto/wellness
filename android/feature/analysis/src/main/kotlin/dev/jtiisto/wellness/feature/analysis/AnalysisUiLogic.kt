package dev.jtiisto.wellness.feature.analysis

import dev.jtiisto.wellness.core.data.analysis.ActiveReport
import dev.jtiisto.wellness.core.data.analysis.AnalysisQueryDto
import dev.jtiisto.wellness.core.data.analysis.AnalysisView
import dev.jtiisto.wellness.feature.analysis.markdown.ReportBlock

/** What the "Try Again" button on a failed report is allowed to do. */
enum class TryAgainMode {
    /** The query is still registered and needs no input: run it. */
    RESUBMIT,

    /**
     * The query takes a location, so re-running it means going back to the row
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

    /** A stub knows its label from the row that submitted it; a stub without one falls back. */
    fun progressLabel(active: ActiveReport?): String =
        active?.queryLabel?.takeIf { it.isNotBlank() } ?: "Running query…"

    /** When a report finished, or when it started if it never did. */
    fun reportTimestamp(completedAt: String?, createdAt: String): String =
        completedAt?.takeIf { it.isNotBlank() } ?: createdAt

    /**
     * The header's eyebrow: what the tab in force is currently about.
     *
     * Not the module's own name — the display line under it already says
     * `ANALYSIS`, and an eyebrow repeating it would be the page reading its own
     * title back. It carries the count the view is standing on instead, which is
     * the journal header's rule (a derived mono line, never a decoration).
     *
     * Returned in its written case; every caps role in the system uppercases at
     * render, so there is one casing convention rather than two.
     */
    fun headerEyebrow(
        view: AnalysisView,
        queryCount: Int,
        historyCount: Int,
        queriesError: String?,
    ): String = when (view) {
        AnalysisView.PROGRESS -> "running"
        AnalysisView.REPORT -> "report"
        AnalysisView.HISTORY -> countPhrase(historyCount, "kept", "no reports")
        AnalysisView.QUERIES ->
            if (queriesError != null) "unavailable" else countPhrase(queryCount, "queries", "no queries")
    }

    /** The `QUERIES` head's qualifier; absent rather than `0 AVAILABLE` when there are none. */
    fun queriesSub(count: Int): String? = if (count == 0) null else "$count available"

    /** The `PAST REPORTS` head's qualifier, on the same rule. */
    fun historySub(count: Int): String? = if (count == 0) null else "$count kept"

    /**
     * The `RUNNING` head's qualifier: the query's own name.
     *
     * Null rather than [progressLabel]'s fallback — the head already says
     * `RUNNING`, so a stub with no label of its own leaves the slot empty
     * instead of qualifying "running" with "running query".
     */
    fun progressSub(active: ActiveReport?): String? =
        active?.queryLabel?.takeIf { it.isNotBlank() }

    private fun countPhrase(count: Int, plural: String, empty: String): String =
        if (count == 0) empty else "$count $plural"

    /**
     * A history row's verdict, as a shape rather than as a colour.
     *
     * The three outcomes a reader acts on are "it is there", "it broke" and "it
     * is still going" — so the wire's open vocabulary collapses onto three
     * marks, and a status neither side recognises reads as in-flight rather than
     * as a failure it might not be.
     */
    fun historyMark(wireStatus: String): HistoryMark = when (wireStatus) {
        "completed" -> HistoryMark.DONE
        "failed" -> HistoryMark.FAILED
        else -> HistoryMark.PENDING
    }

    /**
     * What the mark is announced as.
     *
     * The two terminal marks are spoken as the words they mean; an in-flight row
     * is spoken as the status itself, because `PENDING`, `RUNNING` and a status
     * this client has never seen are three different things to a reader waiting
     * on one of them.
     */
    fun historyMarkDescription(wireStatus: String): String = when (historyMark(wireStatus)) {
        HistoryMark.DONE -> "Completed"
        HistoryMark.FAILED -> "Failed"
        HistoryMark.PENDING -> statusLabel(wireStatus)
    }

    /**
     * The heading level a report's own top section sits at.
     *
     * A model writes its own outline: one report opens at `#`, the next at `##`,
     * and both mean "this is a section of the report". Ranking by absolute level
     * would set the second one's sections in the third-tier face for no reason a
     * reader can see, so the document's shallowest heading defines the top and
     * everything else is measured from it. A report with no headings answers 1,
     * which costs nothing — there is nothing to rank.
     */
    fun topHeadingLevel(blocks: List<ReportBlock>): Int = headingLevels(blocks).minOrNull() ?: 1

    /**
     * How far a heading sits below the report's top level: 0 renders display
     * caps over a rule, 1 body semibold, 2 and deeper body medium.
     */
    fun headingRank(level: Int, topLevel: Int): Int =
        (level - topLevel).coerceIn(0, DEEPEST_HEADING_RANK)

    private fun headingLevels(blocks: List<ReportBlock>): List<Int> = blocks.flatMap { block ->
        when (block) {
            is ReportBlock.Heading -> listOf(block.level)
            is ReportBlock.Quote -> headingLevels(block.children)
            is ReportBlock.BulletList -> block.items.flatMap(::headingLevels)
            is ReportBlock.OrderedList -> block.items.flatMap(::headingLevels)
            else -> emptyList()
        }
    }

    private const val DEEPEST_HEADING_RANK = 2
}

/** The three shapes a history row can wear. See [AnalysisUiLogic.historyMark]. */
enum class HistoryMark {
    /** A report that finished: the filled ink dot. */
    DONE,

    /** A report that failed: the open ink dot, with the system's mono bang beside it. */
    FAILED,

    /** Anything still on its way: the faint open dot — no verdict has been reached. */
    PENDING,
}

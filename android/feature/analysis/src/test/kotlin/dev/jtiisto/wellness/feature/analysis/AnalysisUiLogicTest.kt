package dev.jtiisto.wellness.feature.analysis

import dev.jtiisto.wellness.core.data.analysis.ActiveReport
import dev.jtiisto.wellness.core.data.analysis.AnalysisQueryDto
import dev.jtiisto.wellness.core.data.analysis.AnalysisView
import dev.jtiisto.wellness.core.data.analysis.ReportDetailDto
import dev.jtiisto.wellness.feature.analysis.markdown.ReportBlock
import dev.jtiisto.wellness.feature.analysis.markdown.ReportInline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The small decisions the Analysis screens make, without a composition around them. */
class AnalysisUiLogicTest {

    private fun query(id: String, acceptsLocation: Boolean = false) =
        AnalysisQueryDto(id, "Label", "Description", acceptsLocation = acceptsLocation)

    @Test
    @DisplayName("Try Again resubmits a plain query, opens a location one, and disables a vanished one")
    fun tryAgainModes() {
        val queries = listOf(query("fixture-a"), query("fixture-b", acceptsLocation = true))

        assertEquals(TryAgainMode.RESUBMIT, AnalysisUiLogic.tryAgainMode(queries, "fixture-a"))
        assertEquals(TryAgainMode.NEEDS_LOCATION, AnalysisUiLogic.tryAgainMode(queries, "fixture-b"))
        assertEquals(TryAgainMode.UNAVAILABLE, AnalysisUiLogic.tryAgainMode(queries, "fixture-gone"))
        assertEquals(
            TryAgainMode.UNAVAILABLE,
            AnalysisUiLogic.tryAgainMode(emptyList(), "fixture-a"),
            "queries that never loaded cannot be re-run either",
        )
    }

    @Test
    @DisplayName("delete is hidden for exactly the two statuses the server refuses")
    fun deletability() {
        assertFalse(AnalysisUiLogic.canDelete("pending"))
        assertFalse(AnalysisUiLogic.canDelete("running"))
        assertTrue(AnalysisUiLogic.canDelete("completed"))
        assertTrue(AnalysisUiLogic.canDelete("failed"))
        assertTrue(
            AnalysisUiLogic.canDelete("queued"),
            "an unrecognised status is deletable server-side; hiding the control would strand the row",
        )
    }

    @Test
    @DisplayName("the status shows as it came off the wire, in the taxonomy dialect")
    fun statusLabels() {
        assertEquals("COMPLETED", AnalysisUiLogic.statusLabel("completed"))
        assertEquals("QUEUED", AnalysisUiLogic.statusLabel("queued"))
    }

    @Test
    @DisplayName("the progress label prefers the query's own, and falls back when a stub has none")
    fun progressLabels() {
        val stub = ActiveReport.Stub(45, "fixture-a", "Fixture Weekly Review")
        assertEquals("Fixture Weekly Review", AnalysisUiLogic.progressLabel(stub))

        assertEquals("Running query…", AnalysisUiLogic.progressLabel(ActiveReport.Stub(45, "fixture-a", "")))
        assertEquals("Running query…", AnalysisUiLogic.progressLabel(null))

        val loaded = ActiveReport.Loaded(
            ReportDetailDto(7, "fixture-a", "Fixture Post-Workout", "running", null, CREATED, null, null),
        )
        assertEquals("Fixture Post-Workout", AnalysisUiLogic.progressLabel(loaded))
    }

    @Test
    @DisplayName("a report is stamped with when it finished, or when it started if it never did")
    fun reportTimestampPrefersCompletion() {
        assertEquals("done", AnalysisUiLogic.reportTimestamp("done", "started"))
        assertEquals("started", AnalysisUiLogic.reportTimestamp(null, "started"))
        assertEquals("started", AnalysisUiLogic.reportTimestamp("  ", "started"))
    }

    @Test
    @DisplayName("the eyebrow carries the count the view stands on, never the module's own name")
    fun headerEyebrows() {
        assertEquals(
            "4 queries",
            AnalysisUiLogic.headerEyebrow(AnalysisView.QUERIES, 4, 12, null),
        )
        assertEquals(
            "12 kept",
            AnalysisUiLogic.headerEyebrow(AnalysisView.HISTORY, 4, 12, null),
        )
        assertEquals("running", AnalysisUiLogic.headerEyebrow(AnalysisView.PROGRESS, 4, 12, null))
        assertEquals("report", AnalysisUiLogic.headerEyebrow(AnalysisView.REPORT, 4, 12, null))

        // The two empties read as sentences rather than as "0 QUERIES", and a
        // module the server has switched off says so instead of counting zero.
        assertEquals("no queries", AnalysisUiLogic.headerEyebrow(AnalysisView.QUERIES, 0, 0, null))
        assertEquals("no reports", AnalysisUiLogic.headerEyebrow(AnalysisView.HISTORY, 0, 0, null))
        assertEquals(
            "unavailable",
            AnalysisUiLogic.headerEyebrow(AnalysisView.QUERIES, 0, 3, "Analysis is disabled on the server"),
        )
    }

    @Test
    @DisplayName("a section head's qualifier disappears rather than reading zero")
    fun sectionSubs() {
        assertEquals("4 available", AnalysisUiLogic.queriesSub(4))
        assertEquals("12 kept", AnalysisUiLogic.historySub(12))
        assertNull(AnalysisUiLogic.queriesSub(0))
        assertNull(AnalysisUiLogic.historySub(0))

        // The running head takes the query's own name, and takes nothing at all
        // rather than qualifying "running" with the progress fallback.
        assertEquals(
            "Fixture Weekly Review",
            AnalysisUiLogic.progressSub(ActiveReport.Stub(45, "fixture-a", "Fixture Weekly Review")),
        )
        assertNull(AnalysisUiLogic.progressSub(ActiveReport.Stub(45, "fixture-a", "")))
        assertNull(AnalysisUiLogic.progressSub(null))
    }

    @Test
    @DisplayName("history judges in three shapes, and an unknown status is in flight rather than failed")
    fun historyMarks() {
        assertEquals(HistoryMark.DONE, AnalysisUiLogic.historyMark("completed"))
        assertEquals(HistoryMark.FAILED, AnalysisUiLogic.historyMark("failed"))
        assertEquals(HistoryMark.PENDING, AnalysisUiLogic.historyMark("pending"))
        assertEquals(HistoryMark.PENDING, AnalysisUiLogic.historyMark("running"))
        assertEquals(
            HistoryMark.PENDING,
            AnalysisUiLogic.historyMark("queued"),
            "a status this client has never seen is not a failure",
        )
    }

    @Test
    @DisplayName("each mark is spoken: the two verdicts as words, an in-flight row as its own status")
    fun historyMarksSpeak() {
        assertEquals("Completed", AnalysisUiLogic.historyMarkDescription("completed"))
        assertEquals("Failed", AnalysisUiLogic.historyMarkDescription("failed"))
        assertEquals("RUNNING", AnalysisUiLogic.historyMarkDescription("running"))
        assertEquals("QUEUED", AnalysisUiLogic.historyMarkDescription("queued"))
    }

    @Test
    @DisplayName("the report's own shallowest heading is its top level, wherever it is nested")
    fun topHeadingLevels() {
        val opensAtOne = listOf(heading(1), heading(2), heading(3))
        assertEquals(1, AnalysisUiLogic.topHeadingLevel(opensAtOne))

        // The common model output: no `#` at all, sections at `##`. Those are
        // the report's top sections and must set like them.
        val opensAtTwo = listOf(ReportBlock.Paragraph(emptyList()), heading(2), heading(4))
        assertEquals(2, AnalysisUiLogic.topHeadingLevel(opensAtTwo))

        val nested = listOf(
            ReportBlock.Quote(listOf(heading(3))),
            ReportBlock.BulletList(listOf(listOf(heading(2)))),
        )
        assertEquals(2, AnalysisUiLogic.topHeadingLevel(nested), "a nested heading still counts")

        assertEquals(
            1,
            AnalysisUiLogic.topHeadingLevel(listOf(ReportBlock.Paragraph(emptyList()))),
            "a report with no headings has nothing to rank",
        )
    }

    @Test
    @DisplayName("headings rank relative to that top, and everything deeper than two shares a face")
    fun headingRanks() {
        assertEquals(0, AnalysisUiLogic.headingRank(level = 2, topLevel = 2))
        assertEquals(1, AnalysisUiLogic.headingRank(level = 3, topLevel = 2))
        assertEquals(2, AnalysisUiLogic.headingRank(level = 4, topLevel = 2))
        assertEquals(
            2,
            AnalysisUiLogic.headingRank(level = 6, topLevel = 2),
            "the ramp bottoms out; an H6 is not a fourth kind of heading",
        )
        assertEquals(
            0,
            AnalysisUiLogic.headingRank(level = 1, topLevel = 2),
            "nothing can rank above the top, even if a caller passes a stale one",
        )
    }

    private fun heading(level: Int) =
        ReportBlock.Heading(level, listOf(ReportInline.Text("Fixture heading")))

    private companion object {
        const val CREATED = "2031-03-04T09:15:00.000000Z"
    }
}

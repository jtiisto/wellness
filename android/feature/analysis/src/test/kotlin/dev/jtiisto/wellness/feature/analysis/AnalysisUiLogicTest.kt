package dev.jtiisto.wellness.feature.analysis

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.TrendingUp
import dev.jtiisto.wellness.core.data.analysis.ActiveReport
import dev.jtiisto.wellness.core.data.analysis.AnalysisQueryDto
import dev.jtiisto.wellness.core.data.analysis.ReportDetailDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    @DisplayName("every server icon name maps, and anything else falls back to a document")
    fun iconMapping() {
        assertEquals(Icons.Filled.FitnessCenter, AnalysisUiLogic.queryIcon("dumbbell"))
        assertEquals(Icons.Filled.Bolt, AnalysisUiLogic.queryIcon("zap"))
        assertEquals(Icons.Filled.CalendarMonth, AnalysisUiLogic.queryIcon("calendar"))
        assertEquals(Icons.Filled.MonitorHeart, AnalysisUiLogic.queryIcon("heart-pulse"))
        assertEquals(Icons.Filled.TrendingUp, AnalysisUiLogic.queryIcon("trending-up"))
        assertEquals(Icons.Filled.Description, AnalysisUiLogic.queryIcon("document"))
        assertEquals(
            Icons.Filled.Description,
            AnalysisUiLogic.queryIcon(null),
            "a user query registered without an icon still has to render",
        )
        assertEquals(Icons.Filled.Description, AnalysisUiLogic.queryIcon("no-such-glyph"))
    }

    private companion object {
        const val CREATED = "2031-03-04T09:15:00.000000Z"
    }
}

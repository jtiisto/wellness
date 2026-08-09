package dev.jtiisto.wellness.core.data.analysis

import dev.jtiisto.wellness.core.data.WellnessJson
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The Analysis wire contract, against the fixtures in `testdata/golden/analysis/`.
 *
 * One asymmetry runs through all of it and is the reason these fixtures exist:
 * `/queries` **omits** the optional keys it has no value for, while every report
 * shape sends **JSON null** — those rows come straight off `SELECT *`. A DTO
 * that assumed either convention held everywhere would decode half this module.
 */
class AnalysisDtoTest {

    private val json = WellnessJson

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/golden/analysis/$name")) {
            "missing golden fixture golden/analysis/$name"
        }.use { it.readBytes().decodeToString() }

    private fun <T> decode(name: String, deserializer: DeserializationStrategy<T>): T =
        json.decodeFromString(deserializer, fixture(name))

    // ---- queries -----------------------------------------------------------

    @Test
    @DisplayName("the query list decodes with icon and accepts_location omitted, not null")
    fun queriesDecode() {
        val queries = decode("queries.json", ListSerializer(AnalysisQueryDto.serializer()))

        assertEquals(3, queries.size)
        assertEquals(
            listOf("fixture-post-workout", "fixture-weekly-review", "fixture-bare"),
            queries.map { it.id },
        )

        val withIcon = queries[0]
        assertEquals("dumbbell", withIcon.icon)
        assertFalse(withIcon.acceptsLocation, "the key is absent, so the default must be false")

        val withLocation = queries[1]
        assertEquals("calendar", withLocation.icon)
        assertTrue(withLocation.acceptsLocation)

        val bare = queries[2]
        assertNull(bare.icon)
        assertFalse(bare.acceptsLocation)
        assertEquals("Fixture Bare Query", bare.label)
        assertEquals("Fixture query registered without an icon or a location.", bare.description)
    }

    @Test
    @DisplayName("an empty query list decodes to an empty list, not a failure")
    fun emptyQueriesDecode() {
        assertTrue(decode("empty-list.json", ListSerializer(AnalysisQueryDto.serializer())).isEmpty())
    }

    // ---- report detail -----------------------------------------------------

    @Test
    @DisplayName("a pending report decodes with every optional field explicitly null")
    fun pendingReportDecodes() {
        val report = decode("report-pending.json", ReportDetailDto.serializer())

        assertEquals(42L, report.id)
        assertEquals("fixture-post-workout", report.queryId)
        assertEquals("Fixture Post-Workout", report.queryLabel)
        assertEquals("pending", report.status)
        assertNull(report.responseMarkdown)
        assertNull(report.completedAt)
        assertNull(report.errorMessage)
        assertEquals("2031-03-04T10:00:00.000001Z", report.createdAt)
        assertEquals(ReportStatus.PENDING, report.reportStatus)
        assertFalse(report.reportStatus.isTerminal)
    }

    @Test
    @DisplayName("a completed report decodes, and the two unmodeled columns are dropped silently")
    fun completedReportDecodes() {
        val report = decode("report-completed.json", ReportDetailDto.serializer())

        assertEquals(41L, report.id)
        assertEquals(ReportStatus.COMPLETED, report.reportStatus)
        assertTrue(report.reportStatus.isTerminal)
        assertEquals("2031-03-04T09:18:42.654321Z", report.completedAt)
        assertNull(report.errorMessage)

        val markdown = requireNotNull(report.responseMarkdown)
        assertTrue(markdown.startsWith("# Fixture Weekly Review"), markdown.take(40))
        assertTrue(markdown.contains("<img src=x onerror="), "the raw-HTML vector must survive decoding")
        // prompt_sent and cli_metadata are in the payload and are deliberately
        // not properties; ignoreUnknownKeys is what makes that safe.
        assertTrue(fixture("report-completed.json").contains("\"prompt_sent\""))
        assertTrue(fixture("report-completed.json").contains("\"cli_metadata\""))
    }

    @Test
    @DisplayName("a failed report carries its error message")
    fun failedReportDecodes() {
        val report = decode("report-failed.json", ReportDetailDto.serializer())

        assertEquals(ReportStatus.FAILED, report.reportStatus)
        assertTrue(report.reportStatus.isTerminal)
        assertEquals("Server restarted during execution", report.errorMessage)
        assertNull(report.responseMarkdown)
    }

    @Test
    @DisplayName("a status this build has never heard of decodes to UNKNOWN and stays non-terminal")
    fun unknownStatusDecodes() {
        val report = decode("report-unknown-status.json", ReportDetailDto.serializer())

        assertEquals("queued", report.status)
        assertEquals(ReportStatus.UNKNOWN, report.reportStatus)
        assertFalse(
            report.reportStatus.isTerminal,
            "an unrecognised status must not be treated as finished — the report may still be running",
        )
    }

    // ---- lists -------------------------------------------------------------

    @Test
    @DisplayName("the history projection decodes newest-first with a null completed_at on the live row")
    fun historyDecodes() {
        val history = decode("reports-history.json", ListSerializer(ReportSummaryDto.serializer()))

        assertEquals(listOf(44L, 43L, 41L), history.map { it.id })
        assertNull(history[0].completedAt)
        assertEquals(ReportStatus.RUNNING, history[0].reportStatus)
        assertEquals("2031-03-04T11:32:05.000000Z", history[1].completedAt)
        assertEquals("Fixture Weekly Review", history[2].queryLabel)
        assertEquals("fixture-weekly-review", history[2].queryId)
    }

    @Test
    @DisplayName("an empty history decodes to an empty list")
    fun emptyHistoryDecodes() {
        assertTrue(decode("empty-list.json", ListSerializer(ReportSummaryDto.serializer())).isEmpty())
    }

    @Test
    @DisplayName("the pending endpoint returns full rows, not the six-column projection")
    fun pendingListDecodes() {
        val pending = decode("pending.json", ListSerializer(ReportDetailDto.serializer()))

        assertEquals(1, pending.size)
        assertEquals(44L, pending[0].id)
        assertEquals(ReportStatus.RUNNING, pending[0].reportStatus)
        assertNull(pending[0].responseMarkdown)
    }

    @Test
    @DisplayName("the submit envelope is two fields")
    fun submitResponseDecodes() {
        val response = decode("submit-response.json", SubmitResponseDto.serializer())

        assertEquals(45L, response.id)
        assertEquals("pending", response.status)
    }

    // ---- status mapping ----------------------------------------------------

    @Test
    @DisplayName("the wire value is 'completed'; 'complete' is not a status and must not be terminal")
    fun statusMapping() {
        assertEquals(ReportStatus.PENDING, ReportStatus.from("pending"))
        assertEquals(ReportStatus.RUNNING, ReportStatus.from("running"))
        assertEquals(ReportStatus.COMPLETED, ReportStatus.from("completed"))
        assertEquals(ReportStatus.FAILED, ReportStatus.from("failed"))
        assertEquals(ReportStatus.UNKNOWN, ReportStatus.from("complete"))
        assertEquals(ReportStatus.UNKNOWN, ReportStatus.from("COMPLETED"))
        assertEquals(ReportStatus.UNKNOWN, ReportStatus.from(null))
        assertEquals(ReportStatus.UNKNOWN, ReportStatus.from(""))

        assertTrue(ReportStatus.COMPLETED.isTerminal)
        assertTrue(ReportStatus.FAILED.isTerminal)
        assertFalse(ReportStatus.PENDING.isTerminal)
        assertFalse(ReportStatus.RUNNING.isTerminal)
        assertFalse(ReportStatus.UNKNOWN.isTerminal)
    }
}

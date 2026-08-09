package dev.jtiisto.wellness.core.data.analysis

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One runnable query, as `GET /queries` projects it.
 *
 * This is the **only** Analysis payload that follows the project's
 * omitted-optional convention: the server builds each entry with a dict
 * comprehension that adds `icon` and `accepts_location` only when they are set
 * (`analysis_queries.py:list_queries`). Every other shape here is a raw sqlite
 * row and sends JSON nulls instead — see [ReportSummaryDto].
 */
@Serializable
data class AnalysisQueryDto(
    val id: String,
    val label: String,
    val description: String,
    /** Absent, never null. Names a glyph in the client's icon table. */
    val icon: String? = null,
    /** Absent unless literally `true`. */
    @SerialName("accepts_location") val acceptsLocation: Boolean = false,
)

/**
 * A history row: the six-column projection `GET /reports` returns.
 *
 * `completed_at` is **JSON null** until the report reaches a terminal status —
 * these come straight off `SELECT *`, so absent-vs-null is not a distinction the
 * server makes here.
 */
@Serializable
data class ReportSummaryDto(
    val id: Long,
    @SerialName("query_id") val queryId: String,
    @SerialName("query_label") val queryLabel: String,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("completed_at") val completedAt: String? = null,
)

/**
 * A full report row: `GET /reports/{id}` and every element of
 * `GET /reports/pending`.
 *
 * `prompt_sent` and `cli_metadata` are deliberately unmodeled — the prompt is
 * large and of no use on the client, and the metadata shape is a server debug
 * aid. `ignoreUnknownKeys` drops them on decode while the cached raw body keeps
 * them, so nothing is lost that a later version might want.
 */
@Serializable
data class ReportDetailDto(
    val id: Long,
    @SerialName("query_id") val queryId: String,
    @SerialName("query_label") val queryLabel: String,
    val status: String,
    @SerialName("response_markdown") val responseMarkdown: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("error_message") val errorMessage: String? = null,
)

/** The 201 envelope from `POST /reports`. */
@Serializable
data class SubmitResponseDto(
    val id: Long,
    val status: String,
)

/**
 * The four statuses the server writes, plus everything else.
 *
 * The wire value is `"completed"`, never `"complete"` — the PWA's own
 * terminal check spells it out twice and getting it wrong would poll a finished
 * report forever.
 *
 * [UNKNOWN] is **not terminal**: an out-of-repo `user_queries.py` can introduce
 * a status this build has never heard of, and treating it as finished would
 * strand a running report. It is instead the one non-terminal status with a
 * ceiling on how long the poll will wait for it (see `AnalysisStore`).
 */
enum class ReportStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    UNKNOWN,
    ;

    /** Whether the server will ever change this status again. */
    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED

    companion object {
        fun from(wire: String?): ReportStatus = when (wire) {
            "pending" -> PENDING
            "running" -> RUNNING
            "completed" -> COMPLETED
            "failed" -> FAILED
            else -> UNKNOWN
        }
    }
}

/** The status of a full row, already classified. */
val ReportDetailDto.reportStatus: ReportStatus
    get() = ReportStatus.from(status)

/** The status of a history row, already classified. */
val ReportSummaryDto.reportStatus: ReportStatus
    get() = ReportStatus.from(status)

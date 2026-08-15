package dev.jtiisto.wellness.core.data.hr

import dev.jtiisto.wellness.core.data.db.HrSampleEntity
import dev.jtiisto.wellness.core.data.db.HrSessionEntity
import dev.jtiisto.wellness.core.data.db.SetEventEntity
import dev.jtiisto.wellness.core.data.network.DateString
import kotlinx.serialization.Serializable

/**
 * The HR wire protocol, `specs/hr-protocol.md` (authoritative home: the server
 * repo's `docs/ARCHITECTURE.md`, "HR: Idempotent Batch Ingestion").
 *
 * **Not a `@SerialName` in the file.** Keys are camelCase on both sides —
 * request and response, envelope and row — because this protocol was authored
 * by this client and the server side generates its aliases from the same
 * spelling. Coach's snake_case rows are a preserved PWA-era blob format, not a
 * precedent; the property names below *are* the wire names, and renaming one
 * renames the field.
 *
 * Optional fields are omitted, never null: the shared
 * [dev.jtiisto.wellness.core.data.WellnessJson] has `explicitNulls` and
 * `encodeDefaults` off, so a null optional and a value equal to its documented
 * default both disappear from the payload. That is the contract — an explicit
 * null is outside it on both sides, and the server answers inconsistently to
 * one by design.
 *
 * Every millisecond field is a **data value**, not a sync watermark. The
 * opaque-timestamp rule governs server-issued stamps and this protocol has
 * none: nothing flows back but counts.
 */

/**
 * One RR interval. The key the server ignores duplicates on is
 * `(deviceId, timestampMs, seq)`.
 *
 * **There is deliberately no `sensorType`.** The wire rule is "omitted ⇒
 * `garmin_hrm`", that is the only value v1 can produce, and the local schema
 * has no column for it — so a client-side field could only ever serialize the
 * default the server already assumes, or omit itself. The server's column
 * exists for a future source; adding it here belongs to whichever release
 * introduces one.
 *
 * [isGapBefore] is false for almost every row and omitted when it is. [seq]
 * disambiguates beats anchored to the same millisecond without editing their
 * timestamps, which is what the analysis measures.
 */
@Serializable
data class SampleDto(
    val deviceId: String,
    val timestampMs: Long,
    val seq: Int,
    val heartRateBpm: Int,
    val rrIntervalMs: Int,
    val isGapBefore: Boolean = false,
    val sessionId: String,
)

/**
 * One completion toggle, idempotent on [eventId].
 *
 * A toggle carries at most one of [setNum] (per-set ticks) and [itemKey]
 * (checklist items); neither present is an exercise-level toggle, which is how
 * cardio completion arrives. [action] is `check` | `uncheck` — an uncheck is
 * undo-as-data and nothing is ever deleted. [sessionId] is absent when no
 * capture was running, which is most events.
 */
@Serializable
data class SetEventDto(
    val eventId: String,
    val date: DateString,
    val exerciseKey: String,
    val setNum: Int? = null,
    val itemKey: String? = null,
    val action: String,
    val clientTimestampMs: Long,
    val sessionId: String? = null,
)

/**
 * One capture session. The endpoint is a **full-row upsert** on [sessionId], so
 * an optional this stops sending clears its stored column rather than lingering
 * from an earlier upload — the stored row stays a faithful copy of this one
 * rather than the union of every upload ever sent.
 *
 * [endedAtMs] absent means the session is still open.
 */
@Serializable
data class SessionDto(
    val sessionId: String,
    val deviceId: String,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val workoutDate: DateString? = null,
    val workoutSessionId: Long? = null,
)

/** `POST /api/hr/samples/batch`. */
@Serializable
data class SamplesBatchRequest(val clientId: String, val samples: List<SampleDto>)

/** `POST /api/hr/set-events/batch` — the array is `events`, not `setEvents`. */
@Serializable
data class SetEventsBatchRequest(val clientId: String, val events: List<SetEventDto>)

/** `POST /api/hr/sessions/batch`. */
@Serializable
data class SessionsBatchRequest(val clientId: String, val sessions: List<SessionDto>)

/**
 * What the two `INSERT OR IGNORE` endpoints answer.
 *
 * The counts come from the connection's change counter, so
 * `accepted + duplicates == totalReceived` holds by construction — it is a
 * reconciliation promise for a client that never saw the response to an earlier
 * flush, not something to gate marking rows on. Marking is driven by the 2xx.
 *
 * **No defaults**: a field absent from this body is not a protocol state, it is
 * a body we do not understand, and acting on a partially-read response is worse
 * than re-uploading. The ingest is idempotent, so a re-upload costs nothing.
 */
@Serializable
data class IngestResponseDto(val accepted: Int, val duplicates: Int, val totalReceived: Int)

/**
 * What the sessions upsert answers. [upserted] counts **distinct** sessions
 * written — a batch repeating a `sessionId` collapses to its last occurrence —
 * while [totalReceived] counts rows sent, so the two differ legitimately.
 */
@Serializable
data class UpsertResponseDto(val upserted: Int, val totalReceived: Int)

/** `GET /api/hr/status` — the reachability probe, three row counts. */
@Serializable
data class StatusResponseDto(
    val status: String,
    val samplesCount: Int,
    val setEventsCount: Int,
    val sessionsCount: Int,
)

/**
 * Row → wire, for each of the three tables.
 *
 * The local sync columns (`isSynced`, `syncedAt`, `isQuarantined`) have no wire
 * counterpart and stop here: they are this client's bookkeeping about what it
 * has sent, and the server keeps its own.
 */
internal fun HrSampleEntity.toDto(): SampleDto = SampleDto(
    deviceId = deviceId,
    timestampMs = timestampMs,
    seq = seq,
    heartRateBpm = heartRateBpm,
    rrIntervalMs = rrIntervalMs,
    isGapBefore = isGapBefore,
    sessionId = sessionId,
)

internal fun SetEventEntity.toDto(): SetEventDto = SetEventDto(
    eventId = eventId,
    date = date,
    exerciseKey = exerciseKey,
    setNum = setNum,
    itemKey = itemKey,
    action = action,
    clientTimestampMs = clientTimestampMs,
    sessionId = sessionId,
)

internal fun HrSessionEntity.toDto(): SessionDto = SessionDto(
    sessionId = sessionId,
    deviceId = deviceId,
    startedAtMs = startedAtMs,
    endedAtMs = endedAtMs,
    workoutDate = workoutDate,
    workoutSessionId = workoutSessionId,
)

package dev.jtiisto.wellness.core.data.db

import dev.jtiisto.wellness.core.data.network.DateString

/**
 * In-memory stand-ins for the three heart-rate DAOs.
 *
 * The composed `@Transaction` methods are deliberately *not* overridden — those
 * are the real code, and running them against these maps is what gives the
 * upload store a headless test rig at all.
 *
 * The overridden `@Query` methods are a transcription of the SQL beside them,
 * and `HrDaoTest` in `androidTest` asserts the same behaviours against the real
 * schema. That pairing is the only thing keeping the two honest: a fake that
 * quietly disagrees with its query would make every store test above it
 * meaningless.
 */
internal open class FakeHrSessionDao : HrSessionDao() {

    val sessions = linkedMapOf<String, HrSessionEntity>()

    override suspend fun find(sessionId: String): HrSessionEntity? = sessions[sessionId]

    override suspend fun needsUpload(): List<HrSessionEntity> =
        sessions.values.filter { !it.isSynced && !it.isQuarantined }.sortedBy { it.startedAtMs }

    override suspend fun newestOpen(): HrSessionEntity? =
        sessions.values.filter { it.endedAtMs == null }.maxByOrNull { it.startedAtMs }

    override suspend fun listAll(): List<HrSessionEntity> = sessions.values.sortedBy { it.startedAtMs }

    override suspend fun countPending(): Int = sessions.values.count { !it.isSynced && !it.isQuarantined }

    override suspend fun countQuarantined(): Int = sessions.values.count { it.isQuarantined }

    override suspend fun upsertRow(row: HrSessionEntity) {
        sessions[row.sessionId] = row
    }

    /** Guarded exactly as the SQL is: a verdict for an older row simply misses. */
    override suspend fun markSynced(sessionId: String, generation: Long) {
        sessions[sessionId]
            ?.takeIf { it.dirtyGeneration == generation }
            ?.let { sessions[sessionId] = it.copy(isSynced = true) }
    }

    override suspend fun markQuarantined(sessionId: String, generation: Long) {
        sessions[sessionId]
            ?.takeIf { it.dirtyGeneration == generation }
            ?.let { sessions[sessionId] = it.copy(isQuarantined = true) }
    }

    override suspend fun delete(sessionId: String) {
        sessions.remove(sessionId)
    }

    /** `endedAtMs IS NULL` guard and rows-affected, exactly as the statement has them. */
    override suspend fun anchorWorkout(
        sessionId: String,
        workoutDate: DateString,
        workoutSessionId: Long?,
    ): Int {
        val row = sessions[sessionId]?.takeIf { it.endedAtMs == null } ?: return 0
        sessions[sessionId] = row.copy(
            workoutDate = workoutDate,
            workoutSessionId = workoutSessionId,
            isSynced = false,
            isQuarantined = false,
            dirtyGeneration = row.dirtyGeneration + 1,
        )
        return 1
    }

    override suspend fun closeSession(sessionId: String, endedAtMs: Long): Int {
        val row = sessions[sessionId]?.takeIf { it.endedAtMs == null } ?: return 0
        sessions[sessionId] = row.copy(
            endedAtMs = endedAtMs,
            isSynced = false,
            isQuarantined = false,
            dirtyGeneration = row.dirtyGeneration + 1,
        )
        return 1
    }
}

internal open class FakeHrSampleDao : HrSampleDao() {

    val samples = linkedMapOf<HrSampleKey, HrSampleEntity>()

    private var nextRowId = 1L

    /** `-1` for a row the composite key already held, as `INSERT OR IGNORE` reports. */
    override suspend fun insertAll(rows: List<HrSampleEntity>): List<Long> = rows.map { row ->
        if (samples.putIfAbsent(row.key(), row) == null) nextRowId++ else -1L
    }

    override suspend fun pendingUpload(limit: Int): List<HrSampleEntity> = samples.values
        .filter { !it.isSynced && !it.isQuarantined }
        .sortedWith(compareBy({ it.timestampMs }, { it.seq }))
        .take(limit)

    override suspend fun listAll(): List<HrSampleEntity> =
        samples.values.sortedWith(compareBy({ it.timestampMs }, { it.seq }))

    override suspend fun countPending(): Int = samples.values.count { !it.isSynced && !it.isQuarantined }

    override suspend fun countQuarantined(): Int = samples.values.count { it.isQuarantined }

    override suspend fun summaries(): List<HrSampleSummary> = samples.values
        .groupBy { it.sessionId }
        .map { (sessionId, rows) ->
            HrSampleSummary(
                sessionId = sessionId,
                total = rows.size,
                pending = rows.count { !it.isSynced && !it.isQuarantined },
                quarantined = rows.count { it.isQuarantined },
                firstTimestampMs = rows.minOf { it.timestampMs },
                lastTimestampMs = rows.maxOf { it.timestampMs },
            )
        }
        .sortedBy { it.firstTimestampMs }

    override suspend fun markSyncedRow(deviceId: String, timestampMs: Long, seq: Int, syncedAt: Long) {
        val key = HrSampleKey(deviceId, timestampMs, seq)
        samples[key]?.let { samples[key] = it.copy(isSynced = true, syncedAt = syncedAt) }
    }

    override suspend fun markQuarantinedRow(deviceId: String, timestampMs: Long, seq: Int) {
        val key = HrSampleKey(deviceId, timestampMs, seq)
        samples[key]?.let { samples[key] = it.copy(isQuarantined = true) }
    }

    override suspend fun pruneSynced(cutoffMs: Long): Int {
        val doomed = samples.values.filter { it.isSynced && it.timestampMs < cutoffMs }.map { it.key() }
        doomed.forEach { samples.remove(it) }
        return doomed.size
    }
}

internal open class FakeSetEventDao : SetEventDao() {

    /** Insertion-ordered, which is what stands in for `rowid` in the tie-break. */
    val events = linkedMapOf<String, SetEventEntity>()

    /** Mirrors the plain `@Insert`: a reused id aborts rather than overwriting. */
    override suspend fun insert(event: SetEventEntity) {
        check(events.putIfAbsent(event.eventId, event) == null) { "duplicate eventId ${event.eventId}" }
    }

    override suspend fun pendingUpload(limit: Int): List<SetEventEntity> = events.values
        .filter { !it.isSynced && !it.isQuarantined }
        .sortedBy { it.clientTimestampMs }
        .take(limit)

    override suspend fun listAll(): List<SetEventEntity> = events.values.sortedBy { it.clientTimestampMs }

    override suspend fun countPending(): Int = events.values.count { !it.isSynced && !it.isQuarantined }

    override suspend fun countQuarantined(): Int = events.values.count { it.isQuarantined }

    override suspend fun markSynced(eventIds: List<String>) {
        for (id in eventIds) events[id]?.let { events[id] = it.copy(isSynced = true) }
    }

    override suspend fun markQuarantined(eventIds: List<String>) {
        for (id in eventIds) events[id]?.let { events[id] = it.copy(isQuarantined = true) }
    }

    override suspend fun pruneSynced(cutoffMs: Long): Int {
        val doomed = events.values.filter { it.isSynced && it.clientTimestampMs < cutoffMs }.map { it.eventId }
        doomed.forEach { events.remove(it) }
        return doomed.size
    }
}

package dev.jtiisto.wellness.core.ble.quality

import dev.jtiisto.wellness.core.ble.model.HeartRateSample
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Rolling live estimate of RR signal quality for DFA α1, from the beat stream
 * the capture is recording. Not a full analysis — a fast, honest read using the
 * offline module's gates (≥95% RR coverage, ≤5% artifacts, no gap).
 *
 * ⚠ PARITY: the thresholds and rules below (coverage band, artifact ceiling,
 * ectopic threshold, omission factor, longest-contiguous-run coverage, gap
 * veto) MUST match the offline analysis in the wellness server repo
 * (`~/dev/health/wellness`): `src/hr_analysis/quality.py` and
 * `src/hr_analysis/pipeline.py` `_window_alpha1`. "GOOD" here promises the
 * server will trust the window — if the two drift, that promise breaks. No
 * shared test can enforce cross-language parity; what exists on each side is a
 * tripwire that fails loudly when a value is edited —
 * `test/hr/analysis/test_parity_constants.py` there,
 * `SignalQualityTrackerTest` here. Change one side and you MUST change the
 * other. See `specs/coach-heart-rate.md`.
 *
 * One deliberate, documented approximation: the ectopic median is trailing here
 * vs centered offline (a live stream has no future beats) — see MEDIAN_WINDOW.
 * It only affects near-threshold beats mid-transition.
 *
 * @param clock elapsed-realtime source in ms, injected for testability
 */
class SignalQualityTracker(
    private val clock: () -> Long,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val minMeasureMs: Long = DEFAULT_MIN_MEASURE_MS,
    private val minBeats: Int = DEFAULT_MIN_BEATS,
    private val goodCoverage: Double = 0.95,      // == quality.py MIN_TRUSTED_COVERAGE
    private val goodCoverageMax: Double = 1.05,   // == quality.py MAX_TRUSTED_COVERAGE
    private val goodArtifact: Double = 0.05,      // == quality.py MAX_TRUSTED_ARTIFACT
    private val fairCoverage: Double = 0.85,
    private val fairCoverageMax: Double = 1.15,
    private val fairArtifact: Double = 0.15,
    private val ectopicThreshold: Double = 0.20,  // == quality.py ECTOPIC_THRESHOLD
) {
    companion object {
        const val DEFAULT_WINDOW_MS = 120_000L   // matches the DFA 2-min window (pipeline.py WINDOW_MS)
        const val DEFAULT_MIN_MEASURE_MS = 20_000L
        const val DEFAULT_MIN_BEATS = 20
        // Offline quality.py uses a 5-beat window for the ectopic median; match
        // the size. The one unavoidable difference: offline centers it (i-2..i+2)
        // because it has the whole recording; live can only look BACKWARD
        // without adding latency, so this is a trailing 5-beat median. Values
        // agree in steady state and differ only mid-transition near the 5%
        // ceiling — an accepted live approximation (see class PARITY note).
        private const val MEDIAN_WINDOW = 5
        private const val OMISSION_FACTOR = 1.5  // == quality.py OMISSION_FACTOR
    }

    // breaksBefore marks a discontinuity (no-RR / gap / omission) — coverage is
    // measured over the LONGEST clean run, matching the offline analysis.
    // coverageRrMs is the ectopic-CORRECTED interval used for coverage time.
    private data class Event(
        val timeMs: Long,
        val coverageRrMs: Int,
        val validRr: Boolean,
        val breaksBefore: Boolean,
        val isGap: Boolean,
        val isArtifact: Boolean,
    )

    private val events = ArrayDeque<Event>()
    private val recentRr = ArrayDeque<Int>()
    private var firstMs = Long.MIN_VALUE
    private var lastEventMs = Long.MIN_VALUE
    private var hadSignal = false

    fun add(sample: HeartRateSample) {
        val now = clock()
        if (firstMs == Long.MIN_VALUE) firstMs = now

        val rrs = sample.rrIntervalsMs
        if (rrs.isEmpty()) {
            addEvent(Event(now, 0, validRr = false, breaksBefore = true, isGap = sample.isGapBefore, isArtifact = true))
        } else {
            rrs.forEachIndexed { index, rr ->
                // Bundled RRs share one receipt time; anchor each beat backward
                // by the RRs that follow it (same as IntervalBuffer), so the
                // reconstructed spacing is one RR apart and doesn't read as a
                // string of omissions
                val remainingMs = (index + 1 until rrs.size)
                    .sumOf { rrs[it].toLong().coerceAtLeast(0L) }
                val eventTime = now - remainingMs
                val gapHere = sample.isGapBefore && index == 0
                if (rr <= 0) {
                    addEvent(Event(eventTime, 0, validRr = false, breaksBefore = true, isGap = gapHere, isArtifact = true))
                } else {
                    val ectopic = isEctopic(rr)
                    // A reconstructed gap much larger than the RR means a beat
                    // was omitted (offline: timestamp delta > 1.5*rr), even
                    // without an isGapBefore flag — break the run there too
                    val omission = lastEventMs != Long.MIN_VALUE &&
                        (eventTime - lastEventMs) > OMISSION_FACTOR * rr
                    // Correct an ectopic to the recent median before it counts
                    // toward covered time, so a spurious long RR can't inflate it
                    val coverageRr = if (ectopic) (recentMedian() ?: rr) else rr
                    recentRr.addLast(rr)
                    if (recentRr.size > MEDIAN_WINDOW) recentRr.removeFirst()
                    addEvent(
                        Event(
                            timeMs = eventTime,
                            coverageRrMs = coverageRr,
                            validRr = true,
                            breaksBefore = gapHere || omission,
                            isGap = gapHere,
                            isArtifact = ectopic || gapHere || omission,
                        )
                    )
                }
            }
        }
        trim(now)
    }

    fun quality(): SignalQuality {
        val now = clock()
        trim(now)
        val elapsed = if (firstMs == Long.MIN_VALUE) 0 else now - firstMs
        if (events.size < minBeats || elapsed < minMeasureMs) {
            // Distinguish "still collecting first data" from "had a stream that
            // went silent" — a prolonged outage that trims the window empty is
            // POOR, not MEASURING
            return SignalQuality(if (hadSignal) SignalQualityLevel.POOR else SignalQualityLevel.MEASURING)
        }
        hadSignal = true

        // Covered time of the longest contiguous (break-free) run. The first
        // valid beat of each run is excluded — its interval ends AT the run
        // start, spanning time before it (offline _covered_ms does the same).
        var best = 0L
        var current = 0L
        var inRun = false
        for (e in events) {
            if (e.breaksBefore) {
                if (current > best) best = current
                current = 0
                inRun = false
            }
            if (e.validRr) {
                if (inRun) current += e.coverageRrMs else inRun = true
            }
        }
        if (current > best) best = current

        val span = (now - events.first().timeMs).coerceIn(1, windowMs)
        val coverage = best.toDouble() / span
        val artifactFrac = events.count { it.isArtifact }.toDouble() / events.size
        // Any gap anywhere in the window vetoes trust for the whole window
        val gapInWindow = events.any { it.isGap }

        val level = when {
            gapInWindow -> SignalQualityLevel.POOR
            coverage in goodCoverage..goodCoverageMax && artifactFrac <= goodArtifact ->
                SignalQualityLevel.GOOD
            coverage in fairCoverage..fairCoverageMax && artifactFrac <= fairArtifact ->
                SignalQualityLevel.FAIR
            else -> SignalQualityLevel.POOR
        }
        val coveragePercent = (coverage * 100).roundToInt().coerceIn(0, 100)
        return SignalQuality(level, coveragePercent)
    }

    fun reset() {
        events.clear()
        recentRr.clear()
        firstMs = Long.MIN_VALUE
        lastEventMs = Long.MIN_VALUE
        hadSignal = false
    }

    private fun addEvent(event: Event) {
        events.addLast(event)
        lastEventMs = event.timeMs
    }

    private fun recentMedian(): Int? {
        if (recentRr.isEmpty()) return null
        val sorted = recentRr.sorted()
        return sorted[sorted.size / 2]
    }

    private fun isEctopic(rr: Int): Boolean {
        if (recentRr.size < 3) return false
        val median = recentMedian() ?: return false
        if (median <= 0) return false
        return abs(rr - median).toDouble() / median > ectopicThreshold
    }

    private fun trim(now: Long) {
        while (events.isNotEmpty() && events.first().timeMs < now - windowMs) {
            events.removeFirst()
        }
    }
}

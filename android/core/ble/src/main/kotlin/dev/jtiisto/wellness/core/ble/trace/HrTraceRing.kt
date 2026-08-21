package dev.jtiisto.wellness.core.ble.trace

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * How many samples the rolling window keeps: about thirty seconds of trace at
 * the density a strap actually notifies at, plus margin.
 *
 * The guide draws a ~30 s window, and a strap reports roughly once per beat —
 * one to two notifications a second at the rates this is used at. Sixty-four is
 * that span with room for a fast heart, and it is the *whole* memory: the window
 * is a display, and a sample older than it can never be drawn again. The record
 * of the capture is `hr_samples`, not this.
 */
const val TRACE_WINDOW_CAPACITY = 64

/**
 * One point on the live heart-rate trace: what the strap said, and when it said
 * it.
 *
 * [timestampMs] is epoch milliseconds, and doing arithmetic on it is legal here
 * for the reason `HrCaptureStore` records of its own clock — these are **data
 * values**, not server-issued sync watermarks, so the opaque-timestamp rule has
 * nothing to reach. It is the phone's wall clock at the moment the notification
 * was delivered, which is the only time reference the strap gives us.
 *
 * **One point per notification, not per RR interval.** A notification reports a
 * single BPM however many intervals ride with it, so splitting it would draw a
 * flat step across timestamps
 * [dev.jtiisto.wellness.core.ble.buffer.IntervalBuffer] computed to place *beats
 * in the record* — more points saying the same thing, at instants nothing
 * measured a rate for.
 */
data class TraceSample(val timestampMs: Long, val bpm: Int)

/**
 * The rolling window of recent heart rate the guidance display draws.
 *
 * ## Why this is not part of [dev.jtiisto.wellness.core.ble.capture.HrCaptureState]
 *
 * That type is **scalar and conflatable by contract**: it carries the current
 * BPM, and it is published through a `StateFlow` precisely because a screen only
 * ever wants the newest value. A trace wants every value, and folding a history
 * into a conflating flow would drop beats between two collector resumptions with
 * nothing to say they were dropped. So this is published beside it, from its own
 * singleton, and the two are collected independently.
 *
 * ## Why a conflating flow is nevertheless right *here*
 *
 * Each value is the whole window rather than one arrival: cumulative, so a value
 * the collector never observes is contained in the one it observes next. The
 * property that survives conflation is the one the display needs — *no sample
 * still inside the window is ever missed* — and a collector far enough behind to
 * lose one was going to be drawing a stale window regardless.
 *
 * ## Thread-safety
 *
 * Samples are recorded from the capture service's collector (on
 * `Dispatchers.Default`) and the window is read from the UI. The mechanism is
 * one atomic compare-and-set over an **immutable list**: [record] never blocks,
 * never suspends and never throws, and a reader holds a complete window that
 * cannot change underneath it. No lock, and nothing for a snapshot to copy.
 *
 * That "never suspends, never throws" is a requirement rather than a nicety. The
 * feed sits in the same collector as `IntervalBuffer.add`, one statement away
 * from the `HrCaptureStore` actor and the flush it can trigger; a display that
 * could suspend there would delay persistence, and one that could throw would be
 * read as a failed flush and retried forever.
 *
 * This class holds no capture state and knows nothing about sessions, gaps or
 * geometry. Discontinuities are visible in the timestamps — the consumer decides
 * what silence looks like.
 */
class HrTraceRing(private val capacity: Int = TRACE_WINDOW_CAPACITY) {

    init {
        require(capacity >= 1) { "a trace window keeps at least one sample, not $capacity" }
    }

    private val _samples = MutableStateFlow<List<TraceSample>>(emptyList())

    private val generation = AtomicLong(0)

    /**
     * The window, oldest first. Empty until a capture delivers its first
     * notification, and empty again after [beginSession].
     */
    val samples: StateFlow<List<TraceSample>> = _samples.asStateFlow()

    /**
     * A capture session's licence to write into the window. Obtained from
     * [beginSession] and superseded by the next call to it: a stale recorder's
     * [record] is a silent no-op.
     *
     * The fence exists because teardown cancels the sample collector without
     * joining it — deliberately, since the teardown path runs in `finally`
     * blocks that must never block or throw. A cancelled collector can
     * therefore still be executing its non-suspending tail while the *next*
     * capture is starting, and an unfenced write there would land one stale
     * sample in the new session's cleared window. Same rule as the connection
     * handles in the stream-liveness contract: identity decides, not timing.
     */
    inner class Recorder internal constructor(private val licence: Long) {

        /**
         * Add the newest reading, dropping the oldest once the window is full —
         * unless a newer session owns the window, in which case do nothing.
         *
         * Stored verbatim: nothing is sorted, deduplicated or range-checked. Two
         * notifications can share a millisecond and a strap can report a rate the
         * body never had, and both are things the display should show rather than
         * things this should quietly repair.
         *
         * The licence check sits INSIDE the update lambda on purpose: if this
         * write races [beginSession]'s clear and loses the compare-and-set, the
         * retry re-runs the lambda, re-reads the generation, and backs off —
         * the check-then-act window a pre-CAS check would leave is exactly the
         * race being fenced.
         */
        fun record(timestampMs: Long, bpm: Int) {
            val sample = TraceSample(timestampMs, bpm)
            _samples.update { window ->
                if (generation.get() != licence) window
                else (window + sample).takeLast(capacity)
            }
        }
    }

    /**
     * Open a new session's window: supersede every earlier [Recorder], forget
     * everything, and hand back the licence the new capture records under.
     *
     * Called when a capture begins rather than when one ends, and the ordering is
     * the reason: a collector cancelled at teardown can still have a sample in
     * flight, so a clear on the way out can be undone by it. The clear on the way
     * in happens before the new collector is wired, and the generation bump —
     * which comes FIRST — makes the old collector's tail write a no-op rather
     * than a resurrection. Nothing draws the window while no capture is running,
     * so the interval between two sessions is not visible anyway.
     */
    fun beginSession(): Recorder {
        val licence = generation.incrementAndGet()
        _samples.value = emptyList()
        return Recorder(licence)
    }
}

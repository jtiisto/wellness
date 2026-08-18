package dev.jtiisto.wellness.feature.coach

import dev.jtiisto.wellness.core.data.coach.HookAction
import dev.jtiisto.wellness.core.data.coach.HookButtonState
import dev.jtiisto.wellness.core.data.coach.WorkoutStatusDto
import dev.jtiisto.wellness.core.data.coach.statusToState
import dev.jtiisto.wellness.core.data.network.CoachApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Whether the server has each workout hook configured at all. */
data class HookAvailability(val start: Boolean = false, val end: Boolean = false)

/**
 * The Start/End hook machine's whole state for one session.
 *
 * The PWA keeps this in `WorkoutView`'s component state; here it is explicit so
 * the entry gate — the rule that decides whether a workout can be logged at all
 * — is testable without a browser.
 *
 * [dataExists] is the holder's own view of "the log has exercise content", kept
 * only for the fired→locked promotion. The **gate** is asked with the caller's
 * value instead (see [startGateSatisfied]): the log and this state arrive on two
 * flows, and a one-emission lag must never lock a user out mid-workout.
 *
 * [inFlight] is bookkeeping, not display state: a POST or DELETE that is still
 * running owns its action's state, and the pending re-check must not overwrite
 * it with a server view that predates the request.
 */
data class WorkoutHooksState(
    val sessionId: Long? = null,
    val statusLoaded: Boolean = false,
    val statusFetchFailed: Boolean = false,
    val actions: HookAvailability = HookAvailability(),
    val startState: HookButtonState = HookButtonState.DEFAULT,
    /**
     * The server's `fired_at` for the start hook, carried verbatim.
     *
     * Read by the header eyebrow's `STARTED <time>` caption and by nothing else.
     * It stays an opaque string here — the display-only parse, and the reasoning
     * for why the protocol rule survives it, live in `CoachNotation`'s
     * `wallClock`.
     *
     * Null whenever the time is not the server's to give: before any status has
     * been read, on an optimistic FIRED from our own POST (the server has a time,
     * we have not fetched it back), and on a start that was undone.
     */
    val startFiredAt: String? = null,
    val endState: HookButtonState = HookButtonState.DEFAULT,
    val dataExists: Boolean = false,
    val inFlight: Set<HookAction> = emptySet(),
) {
    fun buttonState(action: HookAction): HookButtonState =
        if (action == HookAction.START) startState else endState

    /** True while any button is waiting on something — ours or the server's. */
    val anyPending: Boolean
        get() = startState == HookButtonState.PENDING || endState == HookButtonState.PENDING

    /**
     * Whether the collapsible controls appear at all.
     *
     * Availability is reset the moment a new session's fetch begins, so this can
     * never expose the previous session's buttons while the new status is still
     * loading.
     */
    fun showControls(isEditable: Boolean): Boolean =
        isEditable && statusLoaded && (actions.start || actions.end)

    /**
     * The four escapes from the pre-workout gate, verbatim from the PWA:
     *
     * 1. no Start action is configured — there is nothing to forget;
     * 2. Start was clicked, whatever came of it (success, failure, or pending);
     * 3. exercise data already exists — crash recovery, or a workout in progress;
     * 4. the status fetch failed — offline, and nobody gets locked out for it.
     *
     * The consequence of (4) is that the pre-workout hook does not fire when
     * offline. That is the right trade: the hook captures live pre-workout stats
     * that need the server anyway.
     */
    fun startGateSatisfied(dataExists: Boolean): Boolean =
        !actions.start ||
            startState != HookButtonState.DEFAULT ||
            dataExists ||
            statusFetchFailed

    /** Entry is editable only on today's date AND once the gate is open. */
    fun effectiveEditable(isEditable: Boolean, dataExists: Boolean): Boolean =
        isEditable && startGateSatisfied(dataExists)

    /** A press does something only from these two states. */
    fun canFire(action: HookAction): Boolean =
        buttonState(action) == HookButtonState.DEFAULT || buttonState(action) == HookButtonState.FAILED

    /**
     * Undo visibility is deliberately asymmetric. A fired Start can be taken
     * back until real data exists (after which it is LOCKED); a failed Start has
     * nothing to undo, because the gate is already open and the button offers a
     * retry instead. End has no such lock, and a failed End is worth clearing.
     */
    fun canUndo(action: HookAction): Boolean = when (action) {
        HookAction.START -> startState == HookButtonState.FIRED
        HookAction.END -> endState == HookButtonState.FIRED || endState == HookButtonState.FAILED
    }
}

/**
 * The workout Start/End hooks for the session currently on screen.
 *
 * One status fetch per (session, editable) pair, exactly as the PWA does — with
 * one approved addition: while a button sits PENDING (the server recorded the
 * hook but the script has not exited yet), the status is re-checked on a bounded
 * schedule instead of being left stuck on "Working…" forever. The re-check never
 * runs when nothing is pending, and it stops the moment the state resolves or
 * the screen moves to another session.
 *
 * Every network callback re-checks [WorkoutHooksState.sessionId] before writing:
 * a response that arrives after the user has moved on belongs to nobody.
 */
class WorkoutHooks(
    private val api: CoachApi,
    private val scope: CoroutineScope,
    private val recheckIntervalMs: Long = RECHECK_INTERVAL_MS,
    private val hookDeadlineMs: Long = HOOK_DEADLINE_MS,
) {
    private val _state = MutableStateFlow(WorkoutHooksState())
    val state: StateFlow<WorkoutHooksState> = _state.asStateFlow()

    private var fetchJob: Job? = null
    private var recheckJob: Job? = null

    /**
     * Point the machine at a session, refetching when it or the editability
     * changes.
     *
     * A past or future day, or a rest day with no session, short-circuits: no
     * request is made and the state settles as "loaded, nothing available",
     * which leaves the gate open (escape 1) rather than locking a read-only day.
     */
    fun onSession(sessionId: Long?, isEditable: Boolean) {
        fetchJob?.cancel()
        recheckJob?.cancel()

        // Everything resets, `dataExists` included. Stale availability would
        // expose the previous session's controls; a stale `dataExists` is worse
        // still, because promotion is one-way — a FIRED status arriving for the
        // new session before its log has published would lock the Undo away on
        // the strength of the OLD day's sets. The log collector republishes the
        // truth for this date immediately afterwards.
        _state.update { current ->
            current.copy(
                sessionId = sessionId,
                statusLoaded = sessionId == null || !isEditable,
                statusFetchFailed = false,
                actions = HookAvailability(),
                startState = HookButtonState.DEFAULT,
                startFiredAt = null,
                endState = HookButtonState.DEFAULT,
                dataExists = false,
                inFlight = emptySet(),
            )
        }
        if (sessionId == null || !isEditable) return

        fetchJob = scope.launch {
            refreshStatus(sessionId, initial = true)
            startRecheckIfPending(sessionId)
        }
    }

    /**
     * The log gained or lost exercise content.
     *
     * This is the reactive half of the fired→locked promotion: a Start fired
     * before anything was logged keeps its Undo until the first set lands.
     */
    fun onDataExists(exists: Boolean) {
        _state.update { it.copy(dataExists = exists).promoted() }
    }

    /**
     * Fire a hook, optimistically.
     *
     * The PENDING transition happens here rather than inside the coroutine, so
     * the button reads "Working…" on the same frame as the tap instead of
     * whenever the dispatcher gets round to it.
     */
    fun fire(action: HookAction) {
        val sessionId = _state.value.sessionId ?: return
        beginRequest(action)
        scope.launch {
            val fired = attempt { api.fireWorkoutHook(sessionId, action) }
            finishRequest(sessionId, action) {
                // A 2xx means the server accepted and spawned the script. The
                // promotion inside `update` resolves straight to LOCKED when data
                // already exists, so a mid-workout Start never flashes an Undo
                // the user could hit by accident.
                if (fired.isSuccess) HookButtonState.FIRED else HookButtonState.FAILED
            }
        }
    }

    /**
     * Take a fired hook back.
     *
     * A failed DELETE re-reads the status rather than guessing, so the button
     * ends up showing what the server actually holds. If that read fails too,
     * DEFAULT is the safe landing: it offers the action again, and firing twice
     * is idempotent server-side (the insert upserts).
     */
    fun undo(action: HookAction) {
        val sessionId = _state.value.sessionId ?: return
        beginRequest(action)
        scope.launch {
            val undone = attempt { api.undoWorkoutHook(sessionId, action) }
            if (undone.isSuccess) {
                finishRequest(sessionId, action) { HookButtonState.DEFAULT }
                return@launch
            }
            // The recovered state and its time come from the same read: adopting
            // one without the other would caption the eyebrow with a different
            // attempt than the button is showing.
            val recoveredResult = attempt { api.workoutStatus(sessionId) }.getOrNull()?.forAction(action)
            finishRequest(sessionId, action, firedAt = recoveredResult?.firedAt) {
                recoveredResult?.let { statusToState(it) } ?: HookButtonState.DEFAULT
            }
            startRecheckIfPending(sessionId)
        }
    }

    // ---- status reads ---------------------------------------------------------

    /**
     * Read the server's status.
     *
     * [initial] separates the two callers. The one-shot fetch owns the whole
     * state and reports its own failure as [WorkoutHooksState.statusFetchFailed]
     * — that is what opens the gate offline. The bounded re-check is the
     * opposite: it only resolves buttons that are still PENDING, leaves
     * everything else alone, and treats a failed poll as a blip worth ignoring.
     * Letting it clear availability would make the controls vanish mid-workout
     * over one dropped packet.
     */
    private suspend fun refreshStatus(sessionId: Long, initial: Boolean) {
        val result = attempt { api.workoutStatus(sessionId) }
        if (_state.value.sessionId != sessionId) return

        val status = result.getOrNull()
        if (status == null) {
            if (!initial) return
            _state.update {
                it.copy(
                    actions = HookAvailability(),
                    statusFetchFailed = true,
                    statusLoaded = true,
                ).promoted()
            }
            return
        }

        _state.update { current ->
            current.copy(
                actions = HookAvailability(status.actionsAvailable.start, status.actionsAvailable.end),
                startState = current.adopt(HookAction.START, statusToState(status.start), initial),
                // The time travels with the state it belongs to: adopting the
                // server's view of Start without its `fired_at` would caption the
                // eyebrow from an earlier read of a different run.
                startFiredAt = if (current.adopts(HookAction.START, initial)) {
                    status.start?.firedAt
                } else {
                    current.startFiredAt
                },
                endState = current.adopt(HookAction.END, statusToState(status.end), initial),
                statusFetchFailed = false,
                statusLoaded = true,
            ).promoted()
        }
    }

    /**
     * Whether a status read owns this action's state. The one-shot fetch owns all
     * of it; the re-check only resolves a button that is still PENDING and is not
     * waiting on a request of our own.
     */
    private fun WorkoutHooksState.adopts(action: HookAction, initial: Boolean): Boolean =
        initial || (buttonState(action) == HookButtonState.PENDING && action !in inFlight)

    /** Which state to take on, per [adopts]: the server's, or the one we hold. */
    private fun WorkoutHooksState.adopt(
        action: HookAction,
        serverState: HookButtonState,
        initial: Boolean,
    ): HookButtonState = if (adopts(action, initial)) serverState else buttonState(action)

    /**
     * Deviation 10: bounded re-checking of a pending hook.
     *
     * The server kills a hook at 120 s, so the state cannot stay pending
     * indefinitely — which makes a fixed schedule (every 15 s up to the
     * deadline, plus one read after it to pick up the timeout's exit code) both
     * sufficient and self-terminating. It is not a poll loop: it exists only
     * while something is pending on this session.
     */
    private fun startRecheckIfPending(sessionId: Long) {
        if (!_state.value.anyPending) return
        if (recheckJob?.isActive == true) return
        recheckJob = scope.launch {
            var elapsed = 0L
            while (true) {
                delay(recheckIntervalMs)
                elapsed += recheckIntervalMs
                val current = _state.value
                if (current.sessionId != sessionId || !current.anyPending) return@launch
                refreshStatus(sessionId, initial = false)
                if (elapsed >= hookDeadlineMs + recheckIntervalMs) return@launch
            }
        }
    }

    // ---- request bookkeeping ----------------------------------------------------

    private fun beginRequest(action: HookAction) {
        _state.update {
            it.copy(inFlight = it.inFlight + action).withActionState(action, HookButtonState.PENDING)
        }
    }

    private fun finishRequest(
        sessionId: Long,
        action: HookAction,
        firedAt: String? = null,
        outcome: () -> HookButtonState,
    ) {
        _state.update { current ->
            if (current.sessionId != sessionId) return@update current
            current.copy(inFlight = current.inFlight - action).withActionState(action, outcome(), firedAt)
        }
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Result.failure(error)
    }

    companion object {
        /** How often a pending hook is re-read. */
        const val RECHECK_INTERVAL_MS = 15_000L

        /** `HOOK_TIMEOUT_SECONDS` in `src/modules/coach.py` — keep the two in step. */
        const val HOOK_DEADLINE_MS = 120_000L
    }
}

/**
 * Set one action's button state, promoting a fired Start over data into LOCKED
 * in the same emission.
 *
 * Doing it in one step is the point: two updates would render a frame where the
 * Undo button exists on a workout that is already under way.
 *
 * [firedAt] is the server time that arrived WITH [next], and it defaults to
 * null because every locally-initiated transition has none: a retried Start
 * must not caption the eyebrow with the failed attempt's time, and an
 * optimistic FIRED has a time the server holds but we have not read. Only the
 * undo-failure recovery — the one caller whose state comes from a fresh status
 * read — passes one, so state and time can never describe different attempts.
 */
private fun WorkoutHooksState.withActionState(
    action: HookAction,
    next: HookButtonState,
    firedAt: String? = null,
): WorkoutHooksState = when (action) {
    HookAction.START -> copy(
        startState = next,
        startFiredAt = if (next == HookButtonState.DEFAULT) null else firedAt,
    )

    HookAction.END -> copy(endState = next)
}.promoted()

/** A fired Start with logged data behind it can no longer be undone. */
private fun WorkoutHooksState.promoted(): WorkoutHooksState =
    if (startState == HookButtonState.FIRED && dataExists) {
        copy(startState = HookButtonState.LOCKED)
    } else {
        this
    }

private fun WorkoutStatusDto.forAction(action: HookAction) =
    if (action == HookAction.START) start else end

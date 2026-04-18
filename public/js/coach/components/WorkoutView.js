/**
 * Workout View Component - Main exercise list
 *
 * Exercise entry gating:
 *   When workout actions (Start/End) are configured, exercises are read-only
 *   until the user taps "Start Workout" at least once. This ensures pre-workout
 *   hooks (e.g., Garmin stats capture) aren't forgotten.
 *
 *   The gate unlocks when ANY of these conditions is true:
 *     1. The user has clicked Start (any outcome — success, failure, or pending)
 *     2. Exercise data already exists in the log (crash recovery / returning to
 *        a workout already in progress)
 *     3. The status fetch failed (offline fallback — never lock the user out)
 *     4. No workout actions are configured (behaves like before)
 *
 *   This means the pre-workout hook won't fire when offline, which is the right
 *   trade-off: the hook captures live pre-workout stats, which aren't available
 *   without server connectivity anyway.
 */
import { h } from 'preact';
import { useState, useEffect } from 'preact/hooks';
import htm from 'htm';

import { BlockView } from './BlockView.js';
import { SessionFeedback } from './SessionFeedback.js';
import { getToday, parseLocalDate } from '../../shared/utils.js';

const html = htm.bind(h);

const API_BASE = '/wellness/api/coach';

// Workout action button states
const STATE_DEFAULT = 'default';
const STATE_PENDING = 'pending';
const STATE_FIRED = 'fired';
const STATE_FAILED = 'failed';
const STATE_LOCKED = 'locked';

/**
 * Check whether the log contains any exercise data (beyond session_feedback).
 * Used for two purposes:
 *   1. Lock the Start Workout undo once real data is entered
 *   2. Unlock exercise entry on crash recovery (data exists → gate satisfied)
 */
function hasExerciseData(log) {
    if (!log) return false;
    return Object.keys(log).some(key => {
        if (key === 'session_feedback' || key.startsWith('_')) return false;
        const entry = log[key];
        return entry.completed ||
               entry.sets?.length > 0 ||
               entry.completed_items?.length > 0 ||
               entry.duration_min != null;
    });
}

/**
 * Derive button state from a workout status result (start/end phase data).
 */
function statusToState(result) {
    if (!result) return STATE_DEFAULT;
    if (result.exit_code === null || result.exit_code === undefined) return STATE_PENDING;
    return result.exit_code === 0 ? STATE_FIRED : STATE_FAILED;
}

export function WorkoutView({ date, plan, log, isEditable = true }) {
    if (!plan) {
        return html`
            <div class="empty-state">
                <div class="empty-state-icon">📋</div>
                <p class="empty-state-text">No workout scheduled for this day</p>
            </div>
        `;
    }

    const blocks = plan.blocks || [];

    const today = getToday();
    const isFutureDate = date > today;

    // Collapsible header state.
    // `hasAutoExpanded` tracks whether the "gate active → auto-expand header"
    // effect has fired for this session, so the user's manual collapse is not
    // overridden once it's happened. Both reset when sessionId changes.
    const [expanded, setExpanded] = useState(false);
    const [hasAutoExpanded, setHasAutoExpanded] = useState(false);

    // Workout action availability & button states
    const [actionsAvailable, setActionsAvailable] = useState({ start: false, end: false });
    const [startState, setStartState] = useState(STATE_DEFAULT);
    const [endState, setEndState] = useState(STATE_DEFAULT);
    const [statusLoaded, setStatusLoaded] = useState(false);
    const [statusFetchFailed, setStatusFetchFailed] = useState(false);

    const sessionId = plan.session_id;
    const showControls = isEditable && statusLoaded && (actionsAvailable.start || actionsAvailable.end);
    const exerciseDataExists = hasExerciseData(log);

    // Exercise entry gating: when Start Workout is available, exercises are
    // read-only until the user taps Start at least once. Unlocks on any of:
    //   - Start was clicked (any outcome: success, failure, pending)
    //   - Exercise data already exists (crash recovery)
    //   - Status fetch failed (offline fallback)
    //   - No start action configured (no gate needed)
    const workoutStarted = startState !== STATE_DEFAULT;
    const startGateSatisfied = !actionsAvailable.start  // no start action configured
        || workoutStarted                               // user clicked Start
        || exerciseDataExists                           // crash recovery / mid-workout
        || statusFetchFailed;                           // offline fallback
    const effectiveEditable = isEditable && startGateSatisfied;

    // Fetch workout status on mount / when session changes.
    // On failure (offline), sets statusFetchFailed so the start gate falls back
    // to unlocked — the user is never locked out due to connectivity issues.
    useEffect(() => {
        if (!isEditable || !sessionId) {
            setActionsAvailable({ start: false, end: false });
            setStatusFetchFailed(false);
            setStatusLoaded(true);
            return;
        }

        let cancelled = false;

        async function fetchWorkoutStatus() {
            try {
                const res = await fetch(`${API_BASE}/workout/${sessionId}/status`);
                if (!res.ok) {
                    setActionsAvailable({ start: false, end: false });
                    setStatusFetchFailed(true);
                    setStatusLoaded(true);
                    return;
                }
                const data = await res.json();
                if (cancelled) return;

                setActionsAvailable(data.actions_available || { start: false, end: false });
                setStartState(statusToState(data.start));
                setEndState(statusToState(data.end));
                setStatusFetchFailed(false);
                setStatusLoaded(true);
            } catch {
                if (!cancelled) {
                    setActionsAvailable({ start: false, end: false });
                    setStatusFetchFailed(true);
                    setStatusLoaded(true);
                }
            }
        }

        setStatusLoaded(false);
        setStatusFetchFailed(false);
        // Reset collapsible state when switching sessions so each workout
        // starts with its own auto-expand decision.
        setExpanded(false);
        setHasAutoExpanded(false);
        fetchWorkoutStatus();

        return () => { cancelled = true; };
    }, [sessionId, isEditable]);

    // Auto-expand the header the first time the Start gate is active so the
    // user can see why exercise entry is locked. Fires once per session; the
    // user stays in control after manual toggling.
    useEffect(() => {
        if (!hasAutoExpanded && showControls && !startGateSatisfied) {
            setExpanded(true);
            setHasAutoExpanded(true);
        }
    }, [showControls, startGateSatisfied, hasAutoExpanded]);

    // Lock the start-workout undo once exercise data appears
    useEffect(() => {
        if (startState === STATE_FIRED && exerciseDataExists) {
            setStartState(STATE_LOCKED);
        }
    }, [exerciseDataExists, startState]);

    async function startOrEndWorkout(action) {
        const setState = action === 'start' ? setStartState : setEndState;
        setState(STATE_PENDING);

        try {
            const res = await fetch(`${API_BASE}/workout/${sessionId}/${action}`, {
                method: 'POST',
            });
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            setState(STATE_FIRED);
            if (action === 'start' && exerciseDataExists) {
                setState(STATE_LOCKED);
            }
        } catch {
            setState(STATE_FAILED);
        }
    }

    async function undoAction(action) {
        const setState = action === 'start' ? setStartState : setEndState;
        setState(STATE_PENDING);

        try {
            const res = await fetch(`${API_BASE}/workout/${sessionId}/${action}`, {
                method: 'DELETE',
            });
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            setState(STATE_DEFAULT);
        } catch {
            // Restore previous state on failure — re-fetch to be safe
            try {
                const res = await fetch(`${API_BASE}/workout/${sessionId}/status`);
                if (res.ok) {
                    const data = await res.json();
                    setState(statusToState(action === 'start' ? data.start : data.end));
                }
            } catch {
                setState(STATE_DEFAULT);
            }
        }
    }

    function handleToggle() {
        setExpanded(prev => !prev);
    }

    function renderActionButton(action, state) {
        const isStart = action === 'start';
        const label = isStart ? 'Start Workout' : 'End Workout';
        const isPending = state === STATE_PENDING;

        let stateClass = '--default';
        if (state === STATE_FIRED || state === STATE_LOCKED) stateClass = '--fired';
        else if (state === STATE_FAILED) stateClass = '--failed';

        const canFire = state === STATE_DEFAULT || state === STATE_FAILED;
        const canUndo = isStart
            ? state === STATE_FIRED
            : (state === STATE_FIRED || state === STATE_FAILED);

        return html`
            <div class="hook-btn-group">
                <button
                    class="hook-btn hook-btn${isStart ? '--start' : '--end'} hook-btn${stateClass}"
                    disabled=${isPending || state === STATE_LOCKED}
                    onClick=${() => canFire && startOrEndWorkout(action)}
                >
                    ${isPending ? 'Working...' : label}
                    ${state === STATE_LOCKED && ' (locked)'}
                </button>
                ${canUndo && html`
                    <button
                        class="hook-undo"
                        onClick=${() => undoAction(action)}
                    >Undo</button>
                `}
            </div>
        `;
    }

    // Banner content for non-editable views only (past/future).
    // Explains *why* the day is locked, not just that it is — future dates
    // point the user to when they can log, past dates clarify read-only.
    let readOnlyBanner = null;
    if (!isEditable) {
        if (isFutureDate) {
            const when = parseLocalDate(date).toLocaleDateString(undefined, {
                weekday: 'short', month: 'short', day: 'numeric'
            });
            readOnlyBanner = html`
                <div class="read-only-banner">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16" aria-hidden="true">
                        <circle cx="12" cy="12" r="10"></circle>
                        <polyline points="12 6 12 12 16 14"></polyline>
                    </svg>
                    <span>Scheduled workout — come back on ${when} to log it.</span>
                </div>
            `;
        } else {
            readOnlyBanner = html`
                <div class="read-only-banner">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16" aria-hidden="true">
                        <rect x="3" y="11" width="18" height="11" rx="2" ry="2"></rect>
                        <path d="M7 11V7a5 5 0 0 1 10 0v4"></path>
                    </svg>
                    <span>Past workout — read-only.</span>
                </div>
            `;
        }
    }

    return html`
        <div class="workout-view ${!effectiveEditable ? 'read-only' : ''}">
            ${readOnlyBanner}
            <div class="workout-header ${showControls ? 'workout-header--collapsible' : ''}">
                <div
                    class="workout-header-toggle"
                    onClick=${showControls ? handleToggle : undefined}
                    role=${showControls ? 'button' : undefined}
                    tabIndex=${showControls ? 0 : undefined}
                    onKeyDown=${showControls ? (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); handleToggle(); } } : undefined}
                >
                    <h2 class="workout-day-name">${plan.day_name || 'Workout'}</h2>
                    ${showControls && html`
                        <span class="workout-header-chevron ${expanded ? 'workout-header-chevron--open' : ''}">▼</span>
                    `}
                </div>
                <div class="workout-meta">
                    ${plan.location && html`
                        <span class="workout-meta-item">
                            <span class="icon">📍</span>
                            ${plan.location}
                        </span>
                    `}
                    ${plan.phase && html`
                        <span class="workout-meta-item">
                            <span class="icon">📊</span>
                            ${plan.phase}
                        </span>
                    `}
                </div>
                <div class="workout-header-body ${expanded ? 'workout-header-body--open' : ''}">
                    <div class="workout-header-body-inner">
                        ${showControls && html`
                            <div class="workout-controls">
                                ${actionsAvailable.start && renderActionButton('start', startState)}
                                ${actionsAvailable.end && renderActionButton('end', endState)}
                            </div>
                        `}
                    </div>
                </div>
            </div>

            <div class="blocks-list">
                ${blocks.map(block => html`
                    <${BlockView}
                        key=${block.block_index}
                        date=${date}
                        block=${block}
                        log=${log}
                        isEditable=${effectiveEditable}
                    />
                `)}
            </div>

            <${SessionFeedback}
                date=${date}
                feedback=${log?.session_feedback || {}}
                isEditable=${effectiveEditable}
            />
        </div>
    `;
}

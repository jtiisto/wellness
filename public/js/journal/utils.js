/**
 * Journal-specific utility functions
 */
import { formatDateLocal, parseLocalDate } from '../shared/utils.js';

// Every weekday (Sun..Sat, 0..6) — the "daily" schedule and the default when a
// tracker carries no schedule at all.
export const ALL_DAYS = [0, 1, 2, 3, 4, 5, 6];

// Far-past sentinel for the genesis schedule segment. Sorts below any real
// YYYY-MM-DD under plain string comparison, so the genesis segment covers all
// dates before the first schedule change. Never parsed as a Date.
export const SCHEDULE_GENESIS_DATE = '0000-01-01';

// Tracker polarity values. Stored-only for now — nothing reads it yet; the
// later journal entry-screen redesign is the intended consumer.
// Absent polarity is treated as unspecified/neutral.
export const POLARITY_VALUES = ['positive', 'negative', 'neutral'];

/**
 * Get the last N days including today
 * @param {number} n - Number of days
 * @returns {Array<{date: string, dayName: string, dayNum: number, isToday: boolean}>}
 */
export function getLastNDays(n = 7) {
    const days = [];
    const today = new Date();
    const dayNames = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

    for (let i = n - 1; i >= 0; i--) {
        const date = new Date(today);
        date.setDate(today.getDate() - i);

        days.push({
            date: formatDateLocal(date),
            dayName: dayNames[date.getDay()],
            dayNum: date.getDate(),
            isToday: i === 0,
            dayOfWeek: date.getDay()
        });
    }

    return days;
}

/**
 * Get the day of week for a date string
 * @param {string} dateStr - ISO date string
 * @returns {number} 0-6 (Sunday-Saturday)
 */
export function getDayOfWeek(dateStr) {
    return parseLocalDate(dateStr).getDay();
}

// Normalize a weekday list to sorted, de-duplicated integers in 0..6. Ignores
// anything out of range or non-integer, so a stray value can never widen or
// corrupt a schedule.
function normalizeDays(days) {
    if (!Array.isArray(days)) {
        return [];
    }
    const seen = new Set();
    for (const d of days) {
        const n = Number(d);
        if (Number.isInteger(n) && n >= 0 && n <= 6) {
            seen.add(n);
        }
    }
    return Array.from(seen).sort((a, b) => a - b);
}

function daysEqual(a, b) {
    if (a.length !== b.length) {
        return false;
    }
    return a.every((v, i) => v === b[i]);
}

/**
 * Select the effective-dated segment that applies on `dateStr` from a history
 * array of `{ effectiveFrom, ... }` objects: the segment with the greatest
 * `effectiveFrom <= dateStr`, falling back to the earliest when `dateStr`
 * precedes them all. `effectiveFrom` is compared as a plain YYYY-MM-DD string
 * (== chronological for zero-padded ISO dates; no Date, no timezone). Returns
 * null for an absent/empty history. Shared by schedule and target derivation.
 *
 * @param {Array<{effectiveFrom: string}>} history
 * @param {string} dateStr - Local YYYY-MM-DD date
 * @returns {Object|null} the chosen segment, or null
 */
export function selectSegmentForDate(history, dateStr) {
    if (!Array.isArray(history) || history.length === 0) {
        return null;
    }
    let chosen = null;
    for (const seg of history) {
        if (seg.effectiveFrom <= dateStr &&
            (chosen === null || seg.effectiveFrom > chosen.effectiveFrom)) {
            chosen = seg;
        }
    }
    if (chosen === null) {
        // dateStr precedes every segment — use the earliest.
        chosen = history.reduce(
            (a, b) => (b.effectiveFrom < a.effectiveFrom ? b : a));
    }
    return chosen;
}

/**
 * Resolve the set of weekdays a tracker is scheduled on, as of a given date.
 *
 * Priority (see docs/ARCHITECTURE.md "Tracker scheduling"):
 *   1. `scheduleHistory` (effective-dated segments) — see selectSegmentForDate.
 *   2. Legacy `frequency: 'weekly'` → just `weeklyDay`.
 *   3. Legacy `frequency: 'daily'`, or nothing at all → every day.
 *
 * @param {Object} tracker - Tracker config object
 * @param {string} dateStr - Local YYYY-MM-DD date
 * @returns {Set<number>} weekdays (0=Sun..6=Sat)
 */
export function getScheduleDaysForDate(tracker, dateStr) {
    const seg = selectSegmentForDate(tracker && tracker.scheduleHistory, dateStr);
    if (seg !== null) {
        return new Set(normalizeDays(seg.days));
    }

    if (tracker && tracker.frequency === 'weekly') {
        return new Set([tracker.weeklyDay]);
    }

    return new Set(ALL_DAYS);
}

/**
 * Resolve a tracker's value target as of a given date: the `target` object
 * (`{min?, max?}`) from the effective-dated `targetHistory` segment in effect on
 * `dateStr`, or null when there is no target (absent history, or a segment whose
 * `target` is null — a target removed effective-dated). See docs/ARCHITECTURE.md
 * "Tracker targets".
 *
 * @param {Object} tracker - Tracker config object
 * @param {string} dateStr - Local YYYY-MM-DD date
 * @returns {{min?: number, max?: number}|null}
 */
export function targetForDate(tracker, dateStr) {
    const seg = selectSegmentForDate(tracker && tracker.targetHistory, dateStr);
    return seg ? (seg.target ?? null) : null;
}

/**
 * Parse a target text input into a typed `{min?, max?}` object.
 *
 *   - "" / whitespace → no target (`{ target: null, error: null }`)
 *   - "150-170" → `{ min: 150, max: 170 }` (range)
 *   - "10" → polarity-defaulted: `{ max: 10 }` for negative, else `{ min: 10 }`
 *   - min > max, non-numeric, or negative input → `error` set, `target: null`
 *
 * @param {string} str
 * @param {string} polarity - 'positive' | 'negative' | 'neutral' | undefined
 * @returns {{target: ({min?: number, max?: number}|null), error: (string|null)}}
 */
export function parseTarget(str, polarity) {
    if (str == null) {
        return { target: null, error: null };
    }
    const s = String(str).trim();
    if (s === '') {
        return { target: null, error: null };
    }
    const range = s.match(/^(\d+(?:\.\d+)?)\s*-\s*(\d+(?:\.\d+)?)$/);
    if (range) {
        const min = Number(range[1]);
        const max = Number(range[2]);
        if (min > max) {
            return { target: null, error: 'Range start must be ≤ end' };
        }
        return { target: { min, max }, error: null };
    }
    const single = s.match(/^(\d+(?:\.\d+)?)$/);
    if (single) {
        const n = Number(single[1]);
        return {
            target: polarity === 'negative' ? { max: n } : { min: n },
            error: null,
        };
    }
    return { target: null, error: 'Enter a number (e.g. 10) or range (e.g. 150-170)' };
}

/**
 * Format a typed target for display, appending the unit when present:
 * "≥ 10 g", "≤ 2", "150–170 g", "8 g" (exact). Empty/absent target → "".
 * @param {{min?: number, max?: number}|null} target
 * @param {string} [unit]
 * @returns {string}
 */
export function formatTarget(target, unit) {
    if (!target || (target.min == null && target.max == null)) {
        return '';
    }
    const suffix = unit ? ` ${unit}` : '';
    const { min, max } = target;
    if (min != null && max != null) {
        return min === max ? `${min}${suffix}` : `${min}–${max}${suffix}`;
    }
    if (min != null) {
        return `≥ ${min}${suffix}`;
    }
    return `≤ ${max}${suffix}`;
}

/**
 * Per-day target judgment — a pure JS twin of `_target_status` in
 * mcp_servers/journal_mcp/adherence.py, kept faithful so the grid and the
 * MCP/coach never disagree. `target` is a non-null `{min?, max?}`. Returns
 * 'met' | 'partial' | 'missed'.
 *
 * The polarity no-entry gate is applied FIRST, before any bound check, for every
 * target kind: with no entry a negative-polarity tracker is 'met' (absence =
 * avoided) and anything else is 'missed'. An entry with a null value is 'missed'.
 * Bounds (entry + value present):
 *   - at-least ({min}): met if value >= min; partial if 0 < value < min; else missed
 *   - at-most ({max}): met if value <= max; over → missed (no partial)
 *   - range ({min,max}): met in-range; partial if value < min; over → missed
 *
 * @param {{min?: number, max?: number}} target
 * @param {number|null} value
 * @param {boolean} hasEntry
 * @param {string} [polarity] - 'positive' | 'negative' | 'neutral' | undefined
 * @returns {'met'|'partial'|'missed'}
 */
/**
 * A day's logged value as a number, or null when absent/non-numeric. Entries
 * share one `value` field across tracker types, so a tracker converted
 * from/to type 'note' can carry free-text values — a targeted comparison must
 * treat those as "no usable value" ('missed'), never as silently satisfying a
 * range (NaN comparisons are all false, which read as in-range). Twin of
 * adherence.py's _coerce_numeric — keep in lockstep.
 */
function coerceNumericValue(value) {
    if (typeof value === 'number') return Number.isFinite(value) ? value : null;
    if (typeof value === 'string' && value.trim() !== '') {
        const n = Number(value);
        return Number.isFinite(n) ? n : null;
    }
    return null;
}

export function targetStatus(target, value, hasEntry, polarity) {
    if (!hasEntry) {
        return polarity === 'negative' ? 'met' : 'missed';
    }
    value = coerceNumericValue(value);
    if (value == null) {
        return 'missed';
    }
    const min = target ? (target.min ?? null) : null;
    const max = target ? (target.max ?? null) : null;
    if (min != null && max != null) {
        if (value < min) return 'partial';
        if (value > max) return 'missed';
        return 'met';
    }
    if (min != null) {
        if (value >= min) return 'met';
        return value > 0 ? 'partial' : 'missed';
    }
    if (max != null) {
        return value <= max ? 'met' : 'missed';
    }
    return 'missed';
}

/**
 * Resolve a tracker's status on a given date from that day's log entry, using the
 * target in effect (targetForDate) and polarity — the single-day judgment the
 * grid and (later) the category summary roll up. Mirrors how adherence.py judges
 * a scheduled day:
 *   - Targeted: delegate to targetStatus (met/partial/missed).
 *   - Untargeted: strict checkbox parity — positive/neutral is 'met' iff the
 *     checkbox is set; negative is 'met' iff there is no entry (avoided). A value
 *     with no checkbox is NOT 'met' (no logged-counts special case).
 *
 * Pass the raw day-log record (`{completed?, value?}`) or null/undefined when
 * nothing is logged — NOT a `{}` fallback — so "no entry" stays distinguishable.
 *
 * @param {Object} tracker
 * @param {string} dateStr - Local YYYY-MM-DD
 * @param {Object|null|undefined} entry
 * @returns {{state: string, hasTarget: boolean, target: (object|null), value: (number|null), hasEntry: boolean, polarity: (string|undefined)}}
 */
export function dayStatus(tracker, dateStr, entry) {
    const target = targetForDate(tracker, dateStr);
    const hasTarget = target != null;
    const hasEntry = entry != null;
    const value = hasEntry ? (entry.value ?? null) : null;
    const completed = hasEntry && entry.completed === true;
    const polarity = tracker && tracker.polarity;
    let state;
    if (hasTarget) {
        state = targetStatus(target, value, hasEntry, polarity);
    } else if (polarity === 'negative') {
        state = hasEntry ? 'missed' : 'met';
    } else {
        state = completed ? 'met' : 'missed';
    }
    return { state, hasTarget, target, value, hasEntry, polarity };
}

/**
 * Build the inline target-progress display model for a quantifiable tracker row
 * from a dayStatus result + the tracker's unit. Presentation only — the
 * semantics live in dayStatus/targetStatus. Returns null when no target is in
 * effect. Framing per kind (see docs/ARCHITECTURE.md "Tracker targets"):
 *   - at-least ({min}): progress — "120 / ≥ 150 g" + a fill ratio (value/min).
 *   - at-most ({max}): headroom — "1 of ≤ 2 · 1 left" (no fill); over the ceiling
 *     reads as a calm warning ("over by N"), never an error.
 *   - range ({min,max}): membership — "160 in 150–170 g" (no fill).
 * `tone` ∈ 'met' | 'partial' | 'over' | 'neutral' drives the row color; a
 * negative tracker with no entry is 'met' ("avoided"), never failure.
 *
 * @param {ReturnType<typeof dayStatus>} ds
 * @param {string} [unit]
 * @returns {{text: string, tone: string, fillPct: (number|null)}|null}
 */
export function formatTargetProgress(ds, unit) {
    if (!ds || !ds.hasTarget || !ds.target) {
        return null;
    }
    const { target, value, hasEntry, state, polarity } = ds;
    const label = formatTarget(target, unit);
    const min = target.min ?? null;
    const max = target.max ?? null;
    const isRange = min != null && max != null;
    const isAtLeast = !isRange && min != null;
    const isAtMost = !isRange && max != null;

    if (!hasEntry) {
        if (polarity === 'negative') {
            return { text: `${label} · avoided`, tone: 'met', fillPct: null };
        }
        return { text: label, tone: 'neutral', fillPct: isAtLeast ? 0 : null };
    }

    // Math runs on the coerced numeric value (twin rule of targetStatus): an
    // entry whose value is absent or non-numeric has NO usable value — it must
    // render neutral, not 'met' (dayStatus scores it 'missed'; the row must
    // not contradict the day dot). The raw value is still what gets displayed.
    const num = coerceNumericValue(value);
    const shown = value == null ? '—' : String(value);

    if (isAtLeast) {
        const tone = state === 'met' ? 'met' : (state === 'partial' ? 'partial' : 'neutral');
        const fillPct = (num != null && min > 0)
            ? Math.max(0, Math.min(1, num / min)) * 100
            : 0;
        return { text: `${shown} / ${label}`, tone, fillPct };
    }
    if (isAtMost) {
        if (num == null) {
            return { text: `${shown} of ${label}`, tone: 'neutral', fillPct: null };
        }
        const over = num > max;
        const suffix = over ? ` · over by ${num - max}` : ` · ${max - num} left`;
        return { text: `${shown} of ${label}${suffix}`, tone: over ? 'over' : 'met', fillPct: null };
    }
    // range
    let tone = 'neutral';
    if (state === 'met') tone = 'met';
    else if (state === 'partial') tone = 'partial';
    else if (num != null && num > max) tone = 'over';
    return { text: `${shown} in ${label}`, tone, fillPct: null };
}

/**
 * Whether a tracker is expected on a given date — its schedule (as of that date)
 * includes that date's local weekday.
 * @param {Object} tracker - Tracker config object
 * @param {string} dateStr - Local YYYY-MM-DD date
 * @returns {boolean}
 */
export function isExpectedOn(tracker, dateStr) {
    return getScheduleDaysForDate(tracker, dateStr).has(getDayOfWeek(dateStr));
}

/**
 * Check if a tracker should appear on a given date.
 *
 * Visibility is "expected on this date" OR "already has a log entry that date":
 * an off-schedule day still shows the tracker when a record for it exists in
 * that day's log, so an exceptional entry (e.g. a weekday-only supplement taken
 * on a weekend) stays visible and editable. The predicate is presence of a
 * record for `tracker.id` — even `completed: false` — so unchecking an
 * off-schedule entry doesn't make the row vanish mid-edit. This visibility rule
 * is deliberately separate from any goal/completion semantics (see
 * docs/ARCHITECTURE.md "Tracker scheduling"). With `dayLog` omitted this reduces
 * to pure expectation.
 *
 * @param {Object} tracker - Tracker config object
 * @param {string} dateStr - Local YYYY-MM-DD date
 * @param {Object} [dayLog] - The day's log map ({ trackerId: entry }), e.g. dailyLogs[dateStr]
 * @returns {boolean}
 */
export function shouldShowTracker(tracker, dateStr, dayLog) {
    if (isExpectedOn(tracker, dateStr)) {
        return true;
    }
    return !!(dayLog && tracker &&
        Object.prototype.hasOwnProperty.call(dayLog, tracker.id));
}

/**
 * Compute the tracker's next `scheduleHistory` after the user picks a new set of
 * weekdays, following the apply-from-today write rules (see
 * docs/ARCHITECTURE.md "Tracker scheduling"):
 *
 *   - No-op: `newDays` equals the currently-effective days → `changed: false`
 *     and the caller must NOT write or mark the tracker dirty.
 *   - First edit (no `scheduleHistory` yet): materialize a genesis segment with
 *     the pre-edit schedule from `SCHEDULE_GENESIS_DATE`, then a segment with
 *     the new schedule effective `today` — the whole past keeps the old
 *     schedule, today-onward gets the new one.
 *   - Same-day re-edit (latest segment's `effectiveFrom === today`): replace
 *     that segment's days in place, keeping `effectiveFrom` strictly increasing.
 *   - Later change (latest `effectiveFrom < today`): append a new segment.
 *
 * Pure: `today` is passed in (local YYYY-MM-DD) rather than read from the clock.
 *
 * @param {Object} tracker - Current tracker config (may carry scheduleHistory or legacy fields)
 * @param {number[]} newDays - Chosen weekdays (0..6)
 * @param {string} today - Local YYYY-MM-DD (caller passes getToday())
 * @returns {{changed: boolean, scheduleHistory: Array<{effectiveFrom: string, days: number[]}>|undefined}}
 */
// Shared apply-from-today write rule for effective-dated segment histories
// (schedule + target). Given the value in effect today (`currentValue`) and the
// new value, returns `{ changed, history }`:
//   - no-op (values equal) → changed:false, history returned unchanged (same ref)
//   - first edit (no history) → genesis segment (old value) + today segment (new)
//   - same-day re-edit (latest effectiveFrom === today) → replace that segment
//   - later change → append a today segment
// `equals(a, b)` compares values; `makeSegment(effectiveFrom, value)` builds a
// segment of the appropriate shape.
function applySegmentEdit({ history, currentValue, newValue, today, equals, makeSegment }) {
    const hist = (Array.isArray(history) && history.length > 0) ? history : null;
    // Segments dated AFTER today (cross-device clock skew artifacts) would
    // silently override this edit the day they arrive — segment selection
    // picks the greatest effectiveFrom <= date. Any edit made today
    // supersedes them, INCLUDING a value-equal one (the user just confirmed
    // today's value; the pending future flip must not survive it).
    const hasFuture = hist !== null && hist.some(seg => seg.effectiveFrom > today);
    if (equals(newValue, currentValue) && !hasFuture) {
        return { changed: false, history };
    }
    if (hist === null) {
        return {
            changed: true,
            history: [
                makeSegment(SCHEDULE_GENESIS_DATE, currentValue),
                makeSegment(today, newValue),
            ],
        };
    }
    // Drop today's and future segments; append the new today segment. Covers
    // the same-day re-edit (replace) and the future-segment supersede in one
    // rule — segment selection is order-independent, so append position is fine.
    return {
        changed: true,
        history: [...hist.filter(seg => seg.effectiveFrom < today), makeSegment(today, newValue)],
    };
}

function targetsEqual(a, b) {
    const an = a || null;
    const bn = b || null;
    if (an === null && bn === null) {
        return true;
    }
    if (an === null || bn === null) {
        return false;
    }
    return (an.min ?? null) === (bn.min ?? null) && (an.max ?? null) === (bn.max ?? null);
}

export function computeScheduleHistoryUpdate(tracker, newDays, today) {
    const days = normalizeDays(newDays);
    const currentDays = normalizeDays(
        Array.from(getScheduleDaysForDate(tracker, today)));
    const res = applySegmentEdit({
        history: tracker && tracker.scheduleHistory,
        currentValue: currentDays,
        newValue: days,
        today,
        equals: daysEqual,
        makeSegment: (effectiveFrom, value) => ({ effectiveFrom, days: value }),
    });
    return { changed: res.changed, scheduleHistory: res.history };
}

/**
 * Compute the tracker's next `targetHistory` after the user sets a new typed
 * target, following the same apply-from-today write rules as
 * `computeScheduleHistoryUpdate` (no-op guard / genesis split / same-day replace
 * / append). `newTarget` is a `{min?, max?}` object or null (target cleared). A
 * cleared target is recorded as an effective-dated segment carrying
 * `target: null`, so past target-based adherence is preserved.
 *
 * @param {Object} tracker - Current tracker config (may carry targetHistory)
 * @param {{min?: number, max?: number}|null} newTarget
 * @param {string} today - Local YYYY-MM-DD (caller passes getToday())
 * @returns {{changed: boolean, targetHistory: Array<{effectiveFrom: string, target: object|null}>|undefined}}
 */
export function computeTargetHistoryUpdate(tracker, newTarget, today) {
    const res = applySegmentEdit({
        history: tracker && tracker.targetHistory,
        currentValue: targetForDate(tracker, today),
        newValue: newTarget || null,
        today,
        equals: targetsEqual,
        makeSegment: (effectiveFrom, value) => ({ effectiveFrom, target: value }),
    });
    return { changed: res.changed, targetHistory: res.history };
}

/**
 * Normalize a tracker's legacy `frequency` / `weeklyDay` into the canonical
 * `scheduleHistory` form, idempotently. Returns the SAME reference when there is
 * nothing to change, so callers can mark a tracker dirty only on a real change
 * (and converge after one upload).
 *
 *   - legacy `frequency: 'weekly'` (with no existing scheduleHistory) → a single
 *     genesis segment carrying `[weeklyDay]`
 *   - `frequency: 'daily'` / absent → no scheduleHistory (absence == daily)
 *   - an existing `scheduleHistory` is preserved (already canonical)
 *   - `frequency` / `weeklyDay` are always stripped
 *
 * The derivation (getScheduleDaysForDate / shouldShowTracker) still honors the
 * legacy fields, so an un-normalized tracker keeps working until it converges.
 *
 * @param {Object} tracker
 * @returns {Object} the normalized tracker, or `tracker` unchanged
 */
export function normalizeTrackerSchedule(tracker) {
    if (!tracker || !('frequency' in tracker || 'weeklyDay' in tracker)) {
        return tracker;
    }
    const next = { ...tracker };
    const hasSchedule = Array.isArray(next.scheduleHistory) && next.scheduleHistory.length > 0;
    const wasWeekly = next.frequency === 'weekly';
    const weeklyDay = Number(next.weeklyDay);
    delete next.frequency;
    delete next.weeklyDay;
    if (!hasSchedule && wasWeekly &&
        Number.isInteger(weeklyDay) && weeklyDay >= 0 && weeklyDay <= 6) {
        next.scheduleHistory = [{ effectiveFrom: SCHEDULE_GENESIS_DATE, days: [weeklyDay] }];
    }
    return next;
}

/**
 * Map the config form's chosen weekdays + polarity to the tracker fields to
 * persist. Thin wrapper over the write helper:
 *
 *   - Schedule. A new tracker left at Daily writes no `scheduleHistory` (keeps
 *     the common case clean); a narrower new tracker gets a single genesis
 *     segment (a tracker created today has no prior history). Editing an
 *     existing tracker delegates to `computeScheduleHistoryUpdate`, so an
 *     unchanged day-set writes nothing.
 *   - Empty selection coerces to Daily.
 *   - Polarity. A valid value is written; selecting "unspecified" on a tracker
 *     that had one clears it (`undefined`, dropped from meta_json on upload).
 *   - Target (quantifiable only). Pass a typed `{min?,max?}` object or null; a
 *     new tracker with a target gets a single genesis segment, editing delegates
 *     to `computeTargetHistoryUpdate` (clearing writes a `target:null` segment).
 *     Pass `target: undefined` (the default) to leave `targetHistory` untouched
 *     (non-quantifiable types).
 *
 * Returns a partial patch to merge into the tracker; keys are present only when
 * they should be written.
 *
 * @param {Object|null} existingTracker - The tracker being edited, or null for a new one
 * @param {{days: number[], polarity: string, target?: object|null}} form
 * @param {string} today - Local YYYY-MM-DD (caller passes getToday())
 * @returns {{scheduleHistory?: Array, polarity?: string|undefined, targetHistory?: Array}}
 */
export function buildTrackerSaveFields(existingTracker, { days, polarity, target }, today) {
    const fields = {};

    const chosen = normalizeDays(
        (Array.isArray(days) && days.length > 0) ? days : ALL_DAYS);
    if (!existingTracker) {
        if (!daysEqual(chosen, ALL_DAYS)) {
            fields.scheduleHistory = [{ effectiveFrom: SCHEDULE_GENESIS_DATE, days: chosen }];
        }
    } else {
        const res = computeScheduleHistoryUpdate(existingTracker, chosen, today);
        if (res.changed) {
            fields.scheduleHistory = res.scheduleHistory;
        }
    }

    if (POLARITY_VALUES.includes(polarity)) {
        fields.polarity = polarity;
    } else if (existingTracker && existingTracker.polarity !== undefined) {
        fields.polarity = undefined; // explicit clear back to unspecified
    }

    // `undefined` = not applicable (leave targetHistory alone); null = no target.
    if (target !== undefined) {
        if (!existingTracker) {
            if (target !== null) {
                fields.targetHistory = [{ effectiveFrom: SCHEDULE_GENESIS_DATE, target }];
            }
        } else {
            const res = computeTargetHistoryUpdate(existingTracker, target, today);
            if (res.changed) {
                fields.targetHistory = res.targetHistory;
            }
        }
    }

    return fields;
}

/**
 * Inverse of `parseTarget` for seeding the config text input: render a typed
 * target back to a raw, re-parseable string ("10", "150-170"). Uses the range
 * form for both range and exact ({min,max}) so a save without edits round-trips
 * to the same target under the no-op guard; a bare min/max renders as the number
 * (round-trips as long as the tracker's polarity is unchanged). Empty → "".
 * @param {{min?: number, max?: number}|null} target
 * @returns {string}
 */
export function formatTargetInput(target) {
    if (!target || (target.min == null && target.max == null)) {
        return '';
    }
    const { min, max } = target;
    if (min != null && max != null) {
        return `${min}-${max}`;
    }
    return String(min != null ? min : max);
}

/**
 * Human-readable summary of a weekday set for the config list: "Daily",
 * "Mon–Fri", or a slash-joined short-name list ("Mon/Wed/Fri", "Sun/Sat").
 * @param {Set<number>|number[]} daysInput
 * @returns {string}
 */
export function formatScheduleSummary(daysInput) {
    const days = normalizeDays(
        Array.isArray(daysInput) ? daysInput : Array.from(daysInput || []));
    if (days.length === 0 || daysEqual(days, ALL_DAYS)) {
        return 'Daily';
    }
    if (daysEqual(days, [1, 2, 3, 4, 5])) {
        return 'Mon–Fri';
    }
    const names = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
    return days.map(d => names[d]).join('/');
}

/**
 * Check if date is within the last N days
 * @param {string} dateStr - ISO date string
 * @param {number} days - Number of days
 * @returns {boolean}
 */
/**
 * The oldest local date the journal store still holds logs for — the twin of
 * `pruneOldLogs`/`isWithinLastNDays` (days back from today, midnight-local).
 * Dot rows and other lookbacks must not judge days older than this: their
 * logs are pruned locally, so absence there means "unknown", not "missed".
 */
export function localDataWindowStart(days = 7) {
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const cutoff = new Date(today);
    cutoff.setDate(today.getDate() - days);
    return formatDateLocal(cutoff);
}

export function isWithinLastNDays(dateStr, days = 7) {
    const date = parseLocalDate(dateStr);
    const today = new Date();
    today.setHours(0, 0, 0, 0);

    const cutoff = new Date(today);
    cutoff.setDate(today.getDate() - days);

    return date >= cutoff;
}

/**
 * Roll up a category's trackers into an "on track" summary for the selected date,
 * behind the collapsed-category badge. Only trackers *expected* on the date
 * (isExpectedOn) are considered, so an off-schedule day is never a miss.
 *
 * A tracker is **actionable** when there is a goal to be "on track" against: a
 * non-neutral polarity (a habit to build or avoid) OR a target in effect that day
 * (value-vs-goal). Untargeted *neutral* trackers are **observations** (e.g. a
 * "Headache" log) — there is nothing to be on-track about, so judging them by
 * checkbox is noise: they are EXCLUDED from the on-track counters and their
 * entries are tallied separately as `observed`.
 *
 *   - `actionable` — expected actionable trackers (the on-track denominator).
 *   - `onTrack` / `partial` / `notYet` — those bucketed by single-day dayStatus
 *     (sum to `actionable`).
 *   - `observed` — expected untargeted-neutral trackers that have an entry that
 *     day (activity, not a goal).
 *
 * Pure — pass the day's log map ({ id: entry }).
 *
 * @param {Array} trackers - the category's trackers (the visible list is fine;
 *   the isExpectedOn gate re-selects the expected subset)
 * @param {string} dateStr - Local YYYY-MM-DD
 * @param {Object} [dayLog] - { trackerId: entry } for that date
 * @returns {{actionable: number, onTrack: number, partial: number, notYet: number, observed: number}}
 */
export function categorySummary(trackers, dateStr, dayLog) {
    let actionable = 0;
    let onTrack = 0;
    let partial = 0;
    let notYet = 0;
    let observed = 0;
    for (const t of (trackers || [])) {
        if (!isExpectedOn(t, dateStr)) {
            continue;
        }
        const ds = dayStatus(t, dateStr, dayLog ? dayLog[t.id] : undefined);
        const isActionable = (t && t.polarity && t.polarity !== 'neutral') || ds.hasTarget;
        if (isActionable) {
            actionable += 1;
            if (ds.state === 'met') onTrack += 1;
            else if (ds.state === 'partial') partial += 1;
            else notYet += 1;
        } else if (ds.hasEntry) {
            observed += 1;
        }
    }
    return { actionable, onTrack, partial, notYet, observed };
}

/**
 * Format a categorySummary into the collapsed-header badge model, or null when
 * there is nothing worth saying. Three cases:
 *   - actionable > 0 → the on-track fraction ("N of M on track", or "All on
 *     track" in met tone when all are met). Observation activity is intentionally
 *     dropped here to keep a mixed category's badge compact.
 *   - actionable == 0 && observed > 0 → denominator-free "K logged" (neutral
 *     tone) — pure observations have no expectation, so no "N of M" and no "All".
 *   - otherwise → null (suppressed; "0 logged" or an empty day is just noise).
 * `tone` ∈ 'met' | 'neutral' drives color.
 *
 * @param {ReturnType<typeof categorySummary>} summary
 * @returns {{text: string, tone: string}|null}
 */
export function formatCategorySummary(summary) {
    if (!summary) {
        return null;
    }
    const { actionable, onTrack, observed } = summary;
    if (actionable > 0) {
        const allMet = onTrack === actionable;
        return {
            text: allMet ? 'All on track' : `${onTrack} of ${actionable} on track`,
            tone: allMet ? 'met' : 'neutral',
        };
    }
    if (observed > 0) {
        return { text: `${observed} logged`, tone: 'neutral' };
    }
    return null;
}

/**
 * The last `n` local calendar days ending on `endDateStr` (oldest → newest), each
 * with its single-day state for `tracker` — the "recent texture" dot row. A day is:
 *   - 'off' — the tracker is not expected that day (off-schedule ≠ missed);
 *   - for an **actionable** tracker (non-neutral polarity or a target in effect),
 *     the `dayStatus` state ('met' | 'partial' | 'missed');
 *   - for an untargeted **neutral** tracker (an observation), 'noted' (an entry
 *     exists) or 'quiet' (none) — logged-vs-quiet, not a met/missed judgment.
 * The observation re-framing lives here, in the presentation-window helper;
 * `dayStatus` / `targetStatus` (the MCP parity pins) are untouched. Purely the
 * single-day predicate repeated: no streaks, no rates, no clamping.
 *
 * @param {Object} tracker
 * @param {string} endDateStr - Local YYYY-MM-DD of the newest day (usually today)
 * @param {Object} logs - dailyLogs map: { 'YYYY-MM-DD': { trackerId: entry } }
 * @param {number} [n=7] - window length in days
 * @returns {Array<{date: string, state: string}>} oldest → newest
 */
export function recentDayStates(tracker, endDateStr, logs, n = 7, earliestKnownDate = null) {
    const end = parseLocalDate(endDateStr);
    const out = [];
    for (let i = n - 1; i >= 0; i--) {
        const d = new Date(end);
        d.setDate(end.getDate() - i);
        const dateStr = formatDateLocal(d);
        let state;
        if (earliestKnownDate && dateStr < earliestKnownDate) {
            // Before the local data window (logs pruned / never synced): the
            // day's truth is unknown — mute it like an off-schedule day
            // instead of fabricating 'missed'/'quiet' from absent data.
            state = 'off';
        } else if (!isExpectedOn(tracker, dateStr)) {
            state = 'off';
        } else {
            const entry = (logs && logs[dateStr]) ? (logs[dateStr][tracker.id] ?? null) : null;
            const ds = dayStatus(tracker, dateStr, entry);
            const isActionable = (tracker && tracker.polarity && tracker.polarity !== 'neutral') || ds.hasTarget;
            if (isActionable) {
                state = ds.state; // met | partial | missed
            } else {
                state = ds.hasEntry ? 'noted' : 'quiet';
            }
        }
        out.push({ date: dateStr, state });
    }
    return out;
}

/**
 * Group trackers by category, sorted alphabetically
 * @param {Array} trackers - Array of tracker configs
 * @returns {Object} Object with category names as keys
 */
export function groupByCategory(trackers) {
    const grouped = {};

    trackers.forEach(tracker => {
        const category = tracker.category || 'Uncategorized';
        if (!grouped[category]) {
            grouped[category] = [];
        }
        grouped[category].push(tracker);
    });

    // Sort trackers within each category alphabetically
    Object.keys(grouped).forEach(category => {
        grouped[category].sort((a, b) => a.name.localeCompare(b.name));
    });

    // Return sorted categories
    const sortedCategories = Object.keys(grouped).sort();
    const result = {};
    sortedCategories.forEach(cat => {
        result[cat] = grouped[cat];
    });

    return result;
}

/**
 * Get unique categories from trackers
 * @param {Array} trackers - Array of tracker configs
 * @returns {Array<string>} Sorted array of unique categories
 */
export function getCategories(trackers) {
    const categories = new Set();
    trackers.forEach(t => {
        if (t.category) {
            categories.add(t.category);
        }
    });
    return Array.from(categories).sort();
}

"""Schedule-adherence computation — SHARED domain layer.

Canonical home of the adherence math (moved from
mcp_servers/journal_mcp/adherence.py, which re-exports for its import
surface). Consumers: the Journal MCP's get_schedule_adherence tool and the
Trends module's weekly adherence ribbons / streaks / day-status rollups.

Pure, dependency-free helpers (no DB, no FastMCP) so the adherence math is unit
testable in isolation. Reads the canonical schedule (`scheduleHistory` segments
in `trackers.schedule_json`), `polarity`, and the typed value target
(`targetHistory` segments in `trackers.target_json`); see docs/ARCHITECTURE.md
"Tracker scheduling" and "Tracker targets".

Date safety: weekday is derived from a real `YYYY-MM-DD` via
`date.fromisoformat(...).isoweekday()` — a plain `date` carries no timezone, so
the weekday is the same in any process TZ. Every segment's `effectiveFrom` is
compared as a **string only** and is NEVER date-parsed: the genesis sentinel
`'0000-01-01'` would raise `ValueError` under `date.fromisoformat` (year 0 is out
of range).
"""
import json
from datetime import date, timedelta

_ALL_DAYS = frozenset(range(7))  # 0=Sun .. 6=Sat (matches the client's getDay())


def _load_json_list(raw):
    """Parse a JSON string to a list, or None (absent/malformed/not a list)."""
    if not raw:
        return None
    try:
        parsed = json.loads(raw)
    except (ValueError, TypeError):
        return None
    return parsed if isinstance(parsed, list) else None


def _normalize_days(days):
    """Coerce a segment's `days` to a set of ints in 0..6, dropping anything
    out of range or non-integer (bools included)."""
    out = set()
    if isinstance(days, list):
        for x in days:
            if isinstance(x, bool):
                continue
            if isinstance(x, int) and 0 <= x <= 6:
                out.add(x)
    return out


def _select_segment_for_date(history, date_str):
    """The effective-dated segment applying on `date_str`: the one with the
    greatest `effectiveFrom <= date_str`, falling back to the earliest when the
    date precedes them all. None for an absent/empty history. `effectiveFrom` is
    compared as a STRING only — never date-parsed (year-0 genesis sentinel).
    Shared by schedule and target derivation.
    """
    if not history:
        return None
    chosen = None
    for seg in history:
        if not isinstance(seg, dict):
            continue
        ef = seg.get("effectiveFrom")
        if ef is not None and ef <= date_str and (
                chosen is None or ef > chosen["effectiveFrom"]):
            chosen = seg
    if chosen is None:
        candidates = [
            s for s in history
            if isinstance(s, dict) and s.get("effectiveFrom") is not None
        ]
        if not candidates:
            return None
        chosen = min(candidates, key=lambda s: s["effectiveFrom"])
    return chosen


def _segment_days_for_date(schedule, date_str):
    """Weekdays a tracker is scheduled on for `date_str` (0=Sun..6=Sat); an
    absent/empty schedule means daily."""
    seg = _select_segment_for_date(schedule, date_str)
    if seg is None:
        return _ALL_DAYS
    return _normalize_days(seg.get("days"))


def _legacy_weekly_days(meta_json):
    """Legacy pre-scheduleHistory shape: meta_json `{"frequency": "weekly",
    "weeklyDay": N}` → frozenset({N}), else None.

    Mirrors the client twin (`getScheduleDaysForDate` fallback +
    `normalizeTrackerSchedule` in public/js/journal/utils.js): a tracker the
    PWA has not loaded since the schedule migration has schedule_json NULL but
    may still be weekly via these meta fields — judging it as daily here made
    a perfectly-adhered weekly habit read ~87% missed. An invalid weeklyDay
    means daily (the normalize rule), so return None for it.
    """
    if not meta_json:
        return None
    try:
        meta = json.loads(meta_json)
    except (ValueError, TypeError):
        return None
    if not isinstance(meta, dict) or meta.get("frequency") != "weekly":
        return None
    day = meta.get("weeklyDay")
    if isinstance(day, bool) or not isinstance(day, int) or not 0 <= day <= 6:
        return None
    return frozenset({day})


def _coerce_numeric(value):
    """A day's logged value as a float, or None when absent/non-numeric.

    Entries share one `value` column across tracker types, so a tracker
    converted from (or to) type 'note' can carry free-text values; a targeted
    comparison must treat those as "no usable value" (→ 'missed'), never
    raise. Booleans are explicitly not values."""
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, str):
        try:
            return float(value)
        except ValueError:
            return None
    return None


def _target_for_date(target_history, date_str):
    """The typed target (`{min?, max?}`) in effect on `date_str`, or None when
    there is no target — an absent history or a `target: null` segment (a target
    removed effective-dated)."""
    seg = _select_segment_for_date(target_history, date_str)
    if seg is None:
        return None
    target = seg.get("target")
    return target if isinstance(target, dict) else None


def _target_status(target, value, has_entry, polarity):
    """Whether a scheduled day meets its in-effect target: 'met' | 'partial' |
    'missed'.

    No-entry rule (user decision): a **negative**-polarity tracker with no entry
    counts as **met** (absence = successfully avoided); positive/neutral with no
    entry is **missed** (never partial). With an entry present, the value is
    tested against the bounds:
      - at-least (min): met if value >= min; partial if 0 < value < min; else missed
      - at-most (max): met if value <= max; over → missed
      - range (min,max): met if min <= value <= max; partial if value < min; over → missed
    An entry with a null value is `missed` (can't confirm the target was met).
    """
    if not has_entry:
        return "met" if polarity == "negative" else "missed"
    value = _coerce_numeric(value)
    if value is None:
        return "missed"
    min_v = target.get("min")
    max_v = target.get("max")
    if min_v is not None and max_v is not None:
        if value < min_v:
            return "partial"
        if value > max_v:
            return "missed"
        return "met"
    if min_v is not None:
        if value >= min_v:
            return "met"
        return "partial" if value > 0 else "missed"
    if max_v is not None:
        return "met" if value <= max_v else "missed"
    return "missed"


def _rate(numerator, denominator):
    """Rounded ratio, or None when there is no denominator (never divide by
    zero — a window can legitimately have zero scheduled days)."""
    if denominator == 0:
        return None
    return round(numerator / denominator, 3)


def _metric_kind(polarity):
    if polarity == "positive":
        return "adherence"
    if polarity == "negative":
        return "avoidance"
    return "coverage"  # neutral / unspecified


def compute_adherence(schedule_json, polarity, tracker_type, entries,
                      window_start, window_end, target_json=None, values=None,
                      meta_json=None):
    """Adherence metrics for one tracker over the inclusive window
    [`window_start`, `window_end`] (real `YYYY-MM-DD` strings).

    `entries` maps a date string to that day's `completed` value (1/0/None);
    `values` (optional) maps a date string to the day's numeric `value`, needed
    only when the tracker has a target. `meta_json` (optional) supplies the
    legacy frequency/weeklyDay fallback for trackers whose schedule_json has
    not been normalized yet (see _legacy_weekly_days).

    Without a target, "done" is `completed == 1` and the output is unchanged from
    the pre-target tool. With a target in effect on a day (`targetHistory`),
    "done" for that day is instead whether the value satisfies the target (see
    `_target_status`, including the per-polarity no-entry rule) — this fixes the
    accumulator undercount (value logging never sets the checkbox).

    `target` (echoed as of `window_end`), `target_met_days`,
    `target_partial_days` (targeted-day-only breakdown), and `blended_met_days`
    (the rate numerator below — the count a per-day consumer should display as
    "met") are added only when the tracker has a `targetHistory`. Per polarity
    when a target is present, the rate
    numerator is a per-date **blended** met count: positive →
    `adherence_rate = blended_met / scheduled_days`; negative → `avoidance_rate =
    blended_met / scheduled_days`; neutral → `coverage_rate` is unchanged (logged
    / scheduled). On days before a target took effect, the rate falls back to that
    day's untargeted criterion (positive → completed; negative → no entry
    avoided), so `blended_met == target_met_days` for a fully-targeted window. All
    rates are None when `scheduled_days == 0`.
    """
    schedule = _load_json_list(schedule_json)
    target_history = _load_json_list(target_json)
    has_target = target_history is not None
    # Un-normalized legacy weekly tracker: no scheduleHistory yet, weekly via
    # meta fields — must match the client twin, not default to daily.
    legacy_days = _legacy_weekly_days(meta_json) if not schedule else None

    scheduled_days = logged_days = done_days = off_schedule_entries = 0
    target_met_days = target_partial_days = rate_met_days = 0
    day = date.fromisoformat(window_start)
    end = date.fromisoformat(window_end)
    while day <= end:
        date_str = day.isoformat()
        weekday = day.isoweekday() % 7  # Mon=1..Sun=7 -> Sun=0..Sat=6
        has_entry = date_str in entries
        day_set = (legacy_days if legacy_days is not None
                   else _segment_days_for_date(schedule, date_str))
        if weekday in day_set:
            scheduled_days += 1
            if has_entry:
                logged_days += 1
            day_completed = has_entry and entries[date_str] == 1
            target = _target_for_date(target_history, date_str) if has_target else None
            if target is not None:
                status = _target_status(
                    target, values.get(date_str) if values else None,
                    has_entry, polarity)
                if status == "met":
                    done_days += 1
                    target_met_days += 1
                    rate_met_days += 1
                elif status == "partial":
                    target_partial_days += 1
            else:
                # No target in effect on D (untargeted tracker, or a day before a
                # target took effect). "done" is the checkbox as before; for a
                # targeted tracker the blended rate falls back to this day's
                # untargeted per-polarity criterion.
                if day_completed:
                    done_days += 1
                if has_target:
                    if polarity == "positive" and day_completed:
                        rate_met_days += 1
                    elif polarity == "negative" and not has_entry:
                        rate_met_days += 1
        elif has_entry:
            off_schedule_entries += 1
        day += timedelta(days=1)

    metric_kind = _metric_kind(polarity)
    result = {
        "polarity": polarity,
        "metric_kind": metric_kind,
        "window": {"start": window_start, "end": window_end},
        "scheduled_days": scheduled_days,
        "logged_days": logged_days,
        "done_days": done_days,
        "missed_days": scheduled_days - logged_days,
        "off_schedule_entries": off_schedule_entries,
        "coverage_rate": _rate(logged_days, scheduled_days),
    }
    if has_target:
        result["target"] = _target_for_date(target_history, window_end)
        result["target_met_days"] = target_met_days
        result["target_partial_days"] = target_partial_days
        result["blended_met_days"] = rate_met_days
    if metric_kind == "adherence":
        result["adherence_rate"] = _rate(
            rate_met_days if has_target else done_days, scheduled_days)
    elif metric_kind == "avoidance":
        result["avoidance_rate"] = _rate(
            rate_met_days if has_target else (scheduled_days - logged_days),
            scheduled_days)
    return result


# ==================== Day-level + streak + band helpers (Trends) ====================
#
# These reuse the module-private helpers above WITHOUT touching
# compute_adherence — the safety-reviewed windowed rollup stays as-is; weekly
# buckets are produced by calling it once per week window.


def _day_status_parsed(schedule, legacy_days, polarity, entries, values,
                       target_history, date_str):
    """Single-day judgment over pre-parsed inputs. 'off' = not scheduled that
    day (includes pause segments — an empty day-set schedules nothing)."""
    weekday = date.fromisoformat(date_str).isoweekday() % 7
    day_set = (legacy_days if legacy_days is not None
               else _segment_days_for_date(schedule, date_str))
    if weekday not in day_set:
        return "off"
    has_entry = date_str in entries
    target = _target_for_date(target_history, date_str)
    if target is not None:
        return _target_status(
            target, values.get(date_str) if values else None, has_entry, polarity)
    if polarity == "negative":
        return "missed" if has_entry else "met"
    return "met" if has_entry and entries[date_str] == 1 else "missed"


def day_status(schedule_json, polarity, entries, values, target_json, meta_json,
               date_str):
    """'met' | 'partial' | 'missed' | 'off' for one day — the same per-day
    criteria compute_adherence rolls up (target in effect → value vs target
    with the per-polarity no-entry rule; untargeted positive/neutral → the
    checkbox; untargeted negative → absence = met)."""
    schedule = _load_json_list(schedule_json)
    legacy_days = _legacy_weekly_days(meta_json) if not schedule else None
    return _day_status_parsed(
        schedule, legacy_days, polarity, entries, values,
        _load_json_list(target_json), date_str)


def compute_streaks(schedule_json, polarity, entries, values, target_json,
                    meta_json, *, first_date, today):
    """{'current': int, 'best': int} consecutive-met streaks over
    [first_date, today], counting SCHEDULED days only: 'off' days (including
    pause windows) are transparent — skipped, never breaking a run. A
    scheduled 'partial'/'missed' day resets. TODAY, if scheduled but not (yet)
    met, does NOT break the current streak — the day isn't over."""
    if not first_date:
        return {"current": 0, "best": 0}
    schedule = _load_json_list(schedule_json)
    legacy_days = _legacy_weekly_days(meta_json) if not schedule else None
    target_history = _load_json_list(target_json)

    def status(date_str):
        return _day_status_parsed(
            schedule, legacy_days, polarity, entries, values,
            target_history, date_str)

    best = run = 0
    day = date.fromisoformat(first_date)
    end = date.fromisoformat(today)
    while day <= end:
        s = status(day.isoformat())
        if s == "met":
            run += 1
            best = max(best, run)
        elif s != "off" and not (day == end):
            run = 0
        elif s != "off" and day == end:
            # Today, scheduled, not met yet: neither counts nor breaks.
            pass
        day += timedelta(days=1)

    # `run` now holds the streak ending at the last decided scheduled day.
    return {"current": run, "best": best}


def target_band_segments(target_json, start, end):
    """Stepped-band source: the effective-dated targetHistory clipped to
    [start, end] → [{'start', 'end', 'min', 'max'}] with INCLUSIVE date-string
    bounds. Windows where the target is None (removed / never set) emit no
    segment — the band has a gap. Boundary dates inside the window are real
    dates (only the genesis sentinel is un-parseable, and it is never > start),
    so the day-before arithmetic below is safe."""
    history = _load_json_list(target_json)
    if not history:
        return []
    bounds = sorted({start} | {
        seg["effectiveFrom"] for seg in history
        if isinstance(seg, dict) and seg.get("effectiveFrom")
        and start < seg["effectiveFrom"] <= end
    })
    segments = []
    for i, b in enumerate(bounds):
        if i + 1 < len(bounds):
            nxt = date.fromisoformat(bounds[i + 1])
            seg_end = (nxt - timedelta(days=1)).isoformat()
        else:
            seg_end = end
        if seg_end < b:
            continue
        target = _target_for_date(history, b)
        if target is None:
            continue
        segments.append({"start": b, "end": seg_end,
                         "min": target.get("min"), "max": target.get("max")})
    return segments

"""Schedule-adherence computation for the Journal MCP.

Pure, dependency-free helpers (no DB, no FastMCP) so the adherence math is unit
testable in isolation. Reads the canonical schedule (the `scheduleHistory`
segments stored as JSON in `trackers.schedule_json`) plus `polarity`; see
docs/ARCHITECTURE.md "Tracker scheduling".

Date safety: weekday is derived from a real `YYYY-MM-DD` via
`date.fromisoformat(...).isoweekday()` — a plain `date` carries no timezone, so
the weekday is the same in any process TZ. The schedule's `effectiveFrom` values
are compared as **strings only** and are NEVER date-parsed: the genesis sentinel
`'0000-01-01'` would raise `ValueError` under `date.fromisoformat` (year 0 is out
of range).
"""
import json
from datetime import date, timedelta

_ALL_DAYS = frozenset(range(7))  # 0=Sun .. 6=Sat (matches the client's getDay())


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


def _segment_days_for_date(schedule, date_str):
    """Weekdays a tracker is scheduled on for `date_str` (0=Sun..6=Sat).

    Picks the segment with the greatest `effectiveFrom <= date_str`, falling back
    to the earliest segment when `date_str` precedes them all; an absent/empty
    schedule means daily. `effectiveFrom` is compared as a string — never
    date-parsed (see module docstring re: the year-0 genesis sentinel).
    """
    if not schedule:
        return _ALL_DAYS
    chosen = None
    for seg in schedule:
        if not isinstance(seg, dict):
            continue
        ef = seg.get("effectiveFrom")
        if ef is not None and ef <= date_str and (
                chosen is None or ef > chosen["effectiveFrom"]):
            chosen = seg
    if chosen is None:
        candidates = [
            s for s in schedule
            if isinstance(s, dict) and s.get("effectiveFrom") is not None
        ]
        if not candidates:
            return _ALL_DAYS
        chosen = min(candidates, key=lambda s: s["effectiveFrom"])
    return _normalize_days(chosen.get("days"))


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
                      window_start, window_end):
    """Adherence metrics for one tracker over the inclusive window
    [`window_start`, `window_end`] (real `YYYY-MM-DD` strings).

    `entries` maps a date string to that day's `completed` value (1/0/None) for
    this tracker within the window. `tracker_type` is accepted for future
    per-type rules but is currently unused — `done` is uniformly
    `completed == 1`, with `logged` (any entry present) reported separately.

    Returns the metrics dict (the caller adds `tracker` / `tracker_id`). Every
    rate is `None` when `scheduled_days` is 0.
    """
    schedule = None
    if schedule_json:
        try:
            schedule = json.loads(schedule_json)
        except (ValueError, TypeError):
            schedule = None

    scheduled_days = logged_days = done_days = off_schedule_entries = 0
    day = date.fromisoformat(window_start)
    end = date.fromisoformat(window_end)
    while day <= end:
        date_str = day.isoformat()
        weekday = day.isoweekday() % 7  # Mon=1..Sun=7 -> Sun=0..Sat=6
        has_entry = date_str in entries
        if weekday in _segment_days_for_date(schedule, date_str):
            scheduled_days += 1
            if has_entry:
                logged_days += 1
                if entries[date_str] == 1:
                    done_days += 1
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
    if metric_kind == "adherence":
        result["adherence_rate"] = _rate(done_days, scheduled_days)
    elif metric_kind == "avoidance":
        result["avoidance_rate"] = _rate(
            scheduled_days - logged_days, scheduled_days)
    return result

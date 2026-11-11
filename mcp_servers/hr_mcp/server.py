"""HR MCP Server implementation.

Read-only MCP access to captured heart-rate sessions: listing, per-session
reports, aligned time series, and interval plan-vs-actual comparison. Migrated
from the pulse-bridge repo's `pulse_bridge_mcp`, repointed at this repo's
`hr.db`.

A capture is named by its session id and never by a clock: no tool accepts a
time window — the retrieval ruling `hr_analysis.db` records. Every per-session
tool takes that id; `get_latest_session_report` is the one selector, and it
picks the newest capture itself.

The docstrings below are the analysis LLM's whole instruction manual for this
surface, and each one is read on its own — a tool description is not a chapter
of a document the reader has in front of them. So the standing rules (the
two-case model, the prohibition on cross-checking watch times, and the
needs_exercise_key response every per-session tool can return) are repeated in
full on each of them rather than pointed at from one. The repetition is the
point; a cross-reference would be a rule the caller never sees.

The server never writes: it reads through `hr_analysis`, whose connections are
sqlite `mode=ro` (see `database.py`). The `hr` module's HTTP endpoints are the
only writer of that database.
"""

from pathlib import Path
from typing import Any, Optional

# Path resolution is the `hr` module's own (HR_DB_PATH env > data/hr.db), so a
# test harness or a second environment repoints server, CLI and MCP together.
# (src/ is placed on the path by hr_mcp/__init__.)
from config import get_module_db_path

try:
    from fastmcp import FastMCP
except ImportError:
    raise ImportError(
        "FastMCP is required for MCP server functionality. "
        "Install with: pip install fastmcp"
    )

from .config import MCPConfig
from .database import DatabaseManager
from . import tools


def create_mcp_server(config: Optional[MCPConfig] = None) -> FastMCP:
    """Create and configure the read-only HR MCP server."""
    if config is None:
        config = MCPConfig.from_db_path(Path(get_module_db_path("hr")))

    config.validate()
    db = DatabaseManager(config)
    mcp = FastMCP("HR Analysis")

    @mcp.tool()
    def list_sessions(limit: int | None = None) -> list[dict[str, Any]]:
        """WHEN TO USE: Find captured heart-rate sessions available for analysis.

        Start here: the per-session tools take a session_id from this listing
        (get_latest_session_report alone picks its own). No lookup by time: a
        capture is identified by its id; nothing in this server accepts a clock
        reading.

        THE TWO CASES. A ride is either owned end to end by this system (strap
        capture AND cardio guide, both keyed to one session id) or happened
        entirely on the watch, which this database knows nothing about.
        `guided` on each row says which, and decides how to analyse the session.

        NEVER cross-check these captures against a Garmin/watch activity. The
        watch typically starts on mounting the bike, START is pressed after
        clipping in, so the two differ by minutes as a matter of course. Normal,
        not a data problem, not worth mentioning in an answer.

        Result, newest first, per session: session_id, device_id, start/end epoch
        ms, duration_s, beat count, workout_date (the coach-side day it was
        captured for, when the session row carries one), and
        - guided: whether the cardio guide recorded a timeline for this capture.
          THE field to read first; it picks the analysis case (see
          get_session_report).
        - guided_exercises: only when guided. One entry per guided exercise:
          exercise_key, the anchor instant and its offset into the capture,
          extends tapped and time they added, segment count, the ride's total
          seconds. Two entries = the capture spans two rides; pass exercise_key
          to the other tools to say which one you mean.

        Args:
            limit: Max sessions to return (newest first)
        """
        return tools.list_sessions(db, limit=limit)

    @mcp.tool()
    def get_session_report(
        session_id: str,
        hrmax: int | None = None,
        exercise_key: str | None = None,
        include_windows: bool = False,
    ) -> dict[str, Any]:
        """WHEN TO USE: Analyze one captured heart-rate session.

        THE TWO CASES. A ride is either owned end to end by this system (strap
        capture AND cardio guide, both keyed to this session id) or happened
        entirely on the watch, which this database knows nothing about.
        `structure` on every result says which:
        - structure.guided = true: the guide recorded the timeline actually
          ridden; that recording IS the plan. Analysis window = the ride itself
          ([anchor, anchor + total)); `structure.segments` has per-segment
          metrics against each segment's OWN recorded band. Do not look for a
          plan elsewhere and do not supply one.
        - structure.guided = false: nothing recorded; the report is the whole
          capture with no structure. Have the planned interval structure and
          want it matched? Use get_vo2_summary.

        NEVER cross-check these times against a Garmin/watch activity. The watch
        typically starts on mounting the bike, START is pressed after clipping
        in, so the two differ by minutes as a matter of course. Normal, not a
        data problem, not worth mentioning in an answer.

        Result: duration-weighted HR (and zones with hrmax), RMSSD, DFA alpha1,
        signal-detected work/rest `bouts`, set-completion markers (set_events,
        offsets from the analysis-window start), a quality block, `structure`.

        For a guided session, `structure` also carries:
        - coverage: how much of the scheduled ride the beats reached. Early bail
          = missing_tail_sec and complete=false; say so, do not report the ride
          as if it ran to the end.
        - segments: per segment, recorded bounds and role (warmup / work /
          cooldown; ABSENCE IN THE RECORD MEANS WORK, and the easy steps between
          efforts are deliberately work), seconds in / below / above the band,
          fraction_in_band, time_to_band_s, average and peak HR, RR quality.
          These are the ride's bouts; the separate `bouts` array is the signal
          detector's independent reading of the same beats. Where they disagree,
          that is information.
        - work: the same numbers aggregated over work-role segments only (the
          ride as distinct from the preparation).

        Check quality first: rr_quality_insufficient = the RR signal never met the
        trust rule, so the HRV numbers are not usable.
        Averages are weighted by each beat's wall-clock duration, so they can
        differ by a beat or two from the phone app's workout-log average, which
        weights by beat.

        Args:
            session_id: Session to analyze (from list_sessions)
            hrmax: Max HR; enables the zone breakdown
            exercise_key: Which guided exercise, when the capture spans several.
                One: chosen automatically. Several and no key: does NOT guess;
                returns {needs_exercise_key: true, guided_exercises: [...]} and
                you call again with one of those keys.
            include_windows: Also return the per-window DFA/quality array (large;
                the quality block's trusted/total counts summarize it)
        """
        return tools.get_session_report(
            db,
            session_id=session_id,
            hrmax=hrmax,
            exercise_key=exercise_key,
            include_windows=include_windows,
        )

    @mcp.tool()
    def get_latest_session_report(
        hrmax: int | None = None,
        exercise_key: str | None = None,
        include_windows: bool = False,
    ) -> dict[str, Any]:
        """WHEN TO USE: Analyze the most recent capture without looking up its id.

        The one tool with no session_id; it selects the newest capture itself.
        Everything else is as get_session_report.

        THE TWO CASES. A ride is either owned end to end by this system (strap
        capture AND cardio guide, both keyed to one session id) or happened
        entirely on the watch, which this database knows nothing about.
        `structure` on the result says which:
        - structure.guided = true: the guide recorded the timeline actually
          ridden; that recording IS the plan. Analysis window = the ride itself;
          `structure.segments` has per-segment metrics against each segment's
          OWN recorded band. Do not look for a plan elsewhere and do not supply
          one.
        - structure.guided = false: nothing recorded; the report is the whole
          capture with no structure.

        NEVER cross-check these times against a Garmin/watch activity. The watch
        typically starts on mounting the bike, START is pressed after clipping
        in, so the two differ by minutes as a matter of course. Normal, not a
        data problem, not worth mentioning in an answer.

        Result: the same report shape as get_session_report, including
        `structure`; or the needs_exercise_key shape (see exercise_key) when the
        capture spans several guided exercises.

        Args:
            hrmax: Max HR; enables the zone breakdown
            exercise_key: Which guided exercise, when the capture spans several.
                One: chosen automatically. Several and no key: does NOT guess;
                returns {needs_exercise_key: true, guided_exercises: [...]} in
                place of a report, and you call again with one of those keys.
            include_windows: Also return the per-window DFA/quality array
        """
        return tools.get_latest_session_report(
            db, hrmax=hrmax, exercise_key=exercise_key,
            include_windows=include_windows,
        )

    @mcp.tool()
    def get_aligned_timeseries(
        session_id: str,
        resolution_s: int = 5,
        exercise_key: str | None = None,
        whole_capture: bool = False,
        include_quality: bool = False,
    ) -> dict[str, Any]:
        """WHEN TO USE: Get compact time buckets to read a session's shape yourself.

        Fallback for when the numbers need a second look: HR minute by minute,
        with the set-completion markers and the guide's own segment boundaries
        alongside.

        THE TWO CASES. A ride is either owned end to end by this system (strap
        capture AND cardio guide, both keyed to this session id) or happened
        entirely on the watch, which this database knows nothing about.
        `structure` on the result says which:
        - structure.guided = true: the guide recorded the timeline ridden. The
          buckets cover the ride itself; `structure.segments` gives each recorded
          segment's boundaries, role and band as offsets on THIS series' clock,
          so the plan lays over the curve directly.
        - structure.guided = false: nothing recorded; the buckets are the whole
          capture and there is no structure to draw.

        NEVER cross-check these times against a Garmin/watch activity. The watch
        typically starts on mounting the bike, START is pressed after clipping
        in, so the two differ by minutes as a matter of course. Normal, not a
        data problem, not worth mentioning in an answer.

        Result: set_events (offset-aligned completion markers), `structure` (as
        above), and one row per non-empty bucket: offset_s, mean/max HR, and
        gap=true only where the signal has a hole. Absolute time =
        analysis_window.start_ms + 1000 * offset_s.

        Args:
            session_id: Session to bucket (from list_sessions)
            resolution_s: Bucket width in seconds (minimum 1)
            exercise_key: Which guided exercise, when the capture spans several.
                One: chosen automatically. Several and no key: does NOT guess;
                returns {needs_exercise_key: true, guided_exercises: [...]} in
                place of the buckets, and you call again with one of those keys.
            whole_capture: Ignore the guided window; bucket every beat of the
                capture, including whatever preceded START and followed the end of
                the timeline. The one way to see outside a guided ride.
            include_quality: Full rows with RR coverage, artifact fraction, beat
                counts and per-row timestamps (roughly triples the size; an
                hour-long session in full form exceeds the tool result limit)
        """
        return tools.get_aligned_timeseries(
            db,
            session_id=session_id,
            resolution_s=resolution_s,
            exercise_key=exercise_key,
            whole_capture=whole_capture,
            include_quality=include_quality,
        )

    @mcp.tool()
    def get_vo2_summary(
        session_id: str,
        intent: dict[str, Any] | None = None,
        hrmax: int | None = None,
        exercise_key: str | None = None,
        resolution_s: int = 5,
    ) -> dict[str, Any]:
        """WHEN TO USE: Match an interval plan you know to an UNGUIDED capture.

        THE TWO CASES. A ride is either owned end to end by this system (strap
        capture AND cardio guide, both keyed to this session id) or happened
        entirely on the watch, which this database knows nothing about.
        This tool serves only one case:
        - structure.guided = false: nothing recorded; you supply the structure.
          This tool's case: `vo2` matches your intent against the beats.
        - structure.guided = true: the recorded timeline IS the plan; nothing to
          match. The result carries `structure` (the recorded reading, identical
          to get_session_report's) and NO `vo2` key. Any intent passed is
          ignored, announced as structure.supplied_intent_ignored rather than
          silently dropped.

        Read `structure.guided` on the listing before reaching for this tool.

        NEVER cross-check these times against a Garmin/watch activity. The watch
        typically starts on mounting the bike, START is pressed after clipping
        in, so the two differ by minutes as a matter of course. Normal, not a
        data problem, not worth mentioning in an answer.

        Result (unguided): `vo2` with expected vs detected work/rest bouts,
        per-bout peak HR and time at target, match-uncertainty flags. Expected
        bouts are laid out from the FIRST BEAT, so a capture that started well
        before the first effort matches poorly; fall back on the time series.
        Result (guided): the recorded `structure` instead; no matching at all.

        Args:
            session_id: Session to analyze (from list_sessions)
            intent: The planned structure: rounds, work_duration_s,
                rest_duration_s (all three or none), plus optional
                target_hr_min/target_hr_max. Omitted: the tool returns the time
                series alone, with intent_missing flagged.
            hrmax: Max HR; enables the zone breakdown
            exercise_key: Which guided exercise, when the capture spans several.
                One: chosen automatically. Several and no key: does NOT guess;
                returns {needs_exercise_key: true, guided_exercises: [...]} in
                place of a summary, and you call again with one of those keys.
            resolution_s: Bucket width for the fallback time series
        """
        return tools.get_vo2_summary(
            db,
            session_id=session_id,
            intent=intent,
            hrmax=hrmax,
            exercise_key=exercise_key,
            resolution_s=resolution_s,
        )

    return mcp


def main():
    """Main entry point for the HR MCP server."""
    try:
        mcp = create_mcp_server()
        mcp.run()
    except Exception as e:
        print(f"Failed to start MCP server: {e}")
        raise


if __name__ == "__main__":
    main()

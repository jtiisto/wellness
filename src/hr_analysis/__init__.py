"""Offline analysis of captured RR/HR sessions: DFA alpha1 threshold detection,
RMSSD, duration-weighted HR summary, and signal-based work/rest segmentation.

A session the cardio guide recorded a timeline for is additionally read against
that timeline — `guided` derives the ride's absolute schedule from the recorded
anchor, snapshot and extends, and measures each segment against the band it
asked for. Retrieval is by session id throughout; see `db` for the ruling.

A standalone CLI package (`python -m hr_analysis`), NOT part of the FastAPI
app: nothing under `src/modules/` or in `src/server.py` imports it, so numpy
never becomes a dependency of serving a request. It reads the `hr` module's
database read-only; see `db` for path resolution and `__main__` for the CLI.

Migrated from the pulse-bridge server's `analysis/` package (2026-08-10) and
repointed at `hr.db`. Behavioural deviations from that original are documented
where they occur: the column mapping and `seq` ordering in `db.load_beats`, and
the removal of the Polar timestamp-shift branch (this deployment is Garmin-only).
"""

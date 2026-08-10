"""Tests for report rendering: terminal summary, JSON, and optional PNG.

The PNG path is only checked for "does it produce a file / degrade gracefully"
— nothing here asserts on rendered pixels.
"""

import builtins
import json

import pytest

from hr_analysis import report as report_mod
from hr_analysis.pipeline import analyze
from hr_analysis.quality import Beat

from .conftest import requires_matplotlib


def _square_wave(rounds=4, work_hr=160, rest_hr=100, seconds=60, t0=1_893_456_000_000):
    """rounds x (work / rest) at a constant RR per segment."""
    beats = []
    start = t0
    for _ in range(rounds):
        for hr in (work_hr, rest_hr):
            rr = int(round(60000 / hr))
            for k in range(int(round(seconds * 1000 / rr))):
                beats.append(Beat(ts_ms=start + k * rr, rr_ms=rr, hr_bpm=hr, is_gap=False))
            start += seconds * 1000
    return beats


def _steady(beats_count=600, rr=700, hr=86, t0=1_893_456_000_000):
    return [Beat(ts_ms=t0 + i * rr, rr_ms=rr, hr_bpm=hr, is_gap=False)
            for i in range(beats_count)]


def _report(**overrides):
    """Minimal report dict shaped like analyze() output, for branch coverage."""
    base = {
        "span_s": 600.0,
        "beats": 850,
        "rr_coverage": 0.982,
        "gaps": 1,
        "artifact_frac_overall": 0.021,
        "hr": {"avg": 142.4, "max": 168, "min": 94, "zones": None},
        "rmssd_ms": 24.8,
        "alpha1": {
            "windows_total": 18,
            "windows_trusted": 12,
            "trusted_mean": 0.812,
            "trusted_min": 0.641,
            "trusted_windows_above_lt1": 3,
            "trusted_windows_above_lt2": 0,
            "verdict": "Trusted a1 mean 0.81 -> predominantly BELOW LT1 (aerobic).",
        },
        "bouts": [],
        "windows": [],
    }
    base.update(overrides)
    return base


# --- Terminal summary ---

def test_terminal_summary_reports_every_headline_metric():
    text = report_mod.terminal_summary("sess-abc", _report())
    assert text.splitlines()[0] == "Session sess-abc"
    assert "duration 10.0 min" in text
    assert "850 beats" in text
    assert "RR coverage 98%" in text
    assert "gaps: 1" in text
    assert "artifacts 2%" in text
    assert "HR  avg 142.4  max 168  min 94" in text
    assert "RMSSD 24.8 ms" in text
    assert "12/18 windows trusted" in text
    assert "above LT1: 3 win" in text
    assert "above LT2: 0 win" in text
    assert "VERDICT: Trusted a1 mean 0.81" in text


def test_terminal_summary_includes_trusted_mean_and_min():
    text = report_mod.terminal_summary("sess-abc", _report())
    assert "mean 0.812 min 0.641" in text


def test_terminal_summary_omits_mean_when_nothing_trusted():
    alpha1 = dict(_report()["alpha1"], windows_trusted=0, trusted_mean=None,
                  trusted_min=None, verdict="Cannot assess threshold")
    text = report_mod.terminal_summary("sess-abc", _report(alpha1=alpha1))
    assert "0/18 windows trusted" in text
    assert "mean" not in text
    assert "VERDICT: Cannot assess threshold" in text


def test_terminal_summary_renders_zone_line_when_zones_present():
    hr = {"avg": 142.4, "max": 168, "min": 94,
          "zones": {"Z1": 60.0, "Z2": 120.0, "Z3": 300.0, "Z4": 90.0, "Z5": 30.0}}
    text = report_mod.terminal_summary("sess-abc", _report(hr=hr))
    assert "zones Z1 1.0m  Z2 2.0m  Z3 5.0m  Z4 1.5m  Z5 0.5m" in text


def test_terminal_summary_omits_zone_line_without_hrmax():
    assert "zones" not in report_mod.terminal_summary("sess-abc", _report())


def test_terminal_summary_lists_bouts_with_available_extras():
    bouts = [
        {"kind": "work", "offset_s": 0.0, "duration_s": 240.0, "hr_avg": 164.2,
         "hr_peak": 172, "rmssd_ms": 6.4, "alpha1": 0.42},
        {"kind": "rest", "offset_s": 240.0, "duration_s": 180.0, "hr_avg": 112.8,
         "hr_peak": 141, "rmssd_ms": None, "alpha1": None},
    ]
    text = report_mod.terminal_summary("sess-abc", _report(bouts=bouts))
    assert "bouts detected: 2" in text
    assert "work @ 0.0m    240s  HR avg 164.2 peak 172  RMSSD 6.4ms  a1 0.42" in text
    # The rest bout has neither metric — no trailing extras
    assert "rest @ 4.0m    180s  HR avg 112.8 peak 141" in text
    assert "RMSSD None" not in text


def test_terminal_summary_handles_bout_without_metric_keys():
    # A bout dict missing the optional keys entirely must not raise
    bouts = [{"kind": "work", "offset_s": 0.0, "duration_s": 90.0,
              "hr_avg": 150.0, "hr_peak": 158}]
    text = report_mod.terminal_summary("sess-abc", _report(bouts=bouts))
    assert "bouts detected: 1" in text


def test_terminal_summary_says_so_when_no_bouts():
    text = report_mod.terminal_summary("sess-abc", _report())
    assert "bouts detected: none (HR too steady — continuous effort)" in text


def test_terminal_summary_of_a_real_pipeline_report():
    report = analyze(_square_wave(), hrmax=190)
    text = report_mod.terminal_summary("sess-live", report)
    assert text.startswith("Session sess-live")
    assert "zones" in text          # hrmax given
    assert "bouts detected: " in text
    assert "VERDICT: " in text


# --- JSON ---

def test_write_json_round_trips_the_report(tmp_path):
    report = _report()
    path = report_mod.write_json("sess-abc", report, str(tmp_path))
    assert path.name == "report_sess-abc.json"
    assert json.loads(path.read_text()) == {"session_id": "sess-abc", **report}


def test_write_json_creates_nested_output_dir(tmp_path):
    out = tmp_path / "reports" / "2026" / "08"
    path = report_mod.write_json("sess-abc", _report(), str(out))
    assert path.exists()
    assert path.parent == out


def test_write_json_overwrites_a_previous_run(tmp_path):
    report_mod.write_json("sess-abc", _report(beats=100), str(tmp_path))
    path = report_mod.write_json("sess-abc", _report(beats=200), str(tmp_path))
    assert json.loads(path.read_text())["beats"] == 200


def test_write_json_serializes_a_full_pipeline_report(tmp_path):
    # Guards against numpy scalars leaking out of the pipeline into json.dumps
    report = analyze(_square_wave(), hrmax=190)
    path = report_mod.write_json("sess-live", report, str(tmp_path))
    loaded = json.loads(path.read_text())
    assert loaded["session_id"] == "sess-live"
    assert loaded["beats"] == report["beats"]
    assert loaded["windows"] == report["windows"]
    assert loaded["hr"]["zones"] == report["hr"]["zones"]


# --- PNG (optional dependency) ---

def test_write_png_returns_none_when_matplotlib_missing(tmp_path, monkeypatch):
    real_import = builtins.__import__

    def no_matplotlib(name, *args, **kwargs):
        if name == "matplotlib" or name.startswith("matplotlib."):
            raise ImportError("No module named 'matplotlib'")
        return real_import(name, *args, **kwargs)

    monkeypatch.setattr(builtins, "__import__", no_matplotlib)
    assert report_mod.write_png("sess-abc", _report(), str(tmp_path)) is None
    assert list(tmp_path.iterdir()) == []


@requires_matplotlib
def test_write_png_renders_windows_and_work_bouts(tmp_path):
    beats = _square_wave()
    report = analyze(beats)
    assert report["windows"] and any(b["kind"] == "work" for b in report["bouts"])

    path = report_mod.write_png("sess-live", report, str(tmp_path), beats=beats)
    assert path is not None
    assert path.name == "report_sess-live.png"
    assert path.stat().st_size > 0


@requires_matplotlib
def test_write_png_falls_back_to_window_averages_without_beats(tmp_path):
    report = analyze(_steady())
    assert report["windows"]
    path = report_mod.write_png("sess-nobeats", report, str(tmp_path))
    assert path is not None and path.exists()


@requires_matplotlib
def test_write_png_handles_windows_with_no_alpha1(tmp_path):
    # Gap-riddled session: windows exist but none yields an a1 value to plot
    beats = [Beat(ts_ms=1_893_456_000_000 + i * 700, rr_ms=700, hr_bpm=86,
                  is_gap=(i % 3 == 0))
             for i in range(600)]
    report = analyze(beats)
    assert report["windows"] and all(w["alpha1"] is None for w in report["windows"])
    path = report_mod.write_png("sess-gappy", report, str(tmp_path), beats=beats)
    assert path is not None and path.exists()


@requires_matplotlib
def test_write_png_with_no_usable_hr_samples(tmp_path):
    # Every row is an HR-less artifact — the chart still renders, just empty
    beats = [Beat(ts_ms=1_893_456_000_000 + i * 700, rr_ms=700, hr_bpm=0, is_gap=False)
             for i in range(400)]
    report = analyze(beats)
    path = report_mod.write_png("sess-nohr", report, str(tmp_path), beats=beats)
    assert path is not None and path.exists()


@requires_matplotlib
def test_write_png_creates_nested_output_dir(tmp_path):
    out = tmp_path / "charts" / "nested"
    path = report_mod.write_png("sess-abc", _report(), str(out), beats=_steady(60))
    assert path is not None and path.parent == out

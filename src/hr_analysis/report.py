"""Render an analysis report as a terminal summary, JSON, and optional PNG."""

import json
from pathlib import Path

from . import hrv


def _structure_lines(structure):
    """The guided ride's own reading, when there is one.

    Every report says where its structure came from, because a report read back
    months later cannot tell a recorded timeline from a supplied one otherwise.
    An unguided session gets one line saying so; a guided one gets its segments
    with the share of each spent inside the band it asked for.
    """
    if not structure:
        return []
    if not structure.get("guided"):
        return ["  structure: none recorded (unguided capture)"]

    coverage = structure["coverage"]
    lines = [f"  guided: {structure['exercise_key']}  anchored {structure['anchor_ms']}"]
    total = structure["total_sec"]
    extension = structure["extension_sec"]
    lines.append(
        f"    timeline {'open-ended' if total is None else f'{total / 60:.1f} min'}"
        + (f" (+{extension / 60:.0f} min appended, {len(structure['extends'])} taps)"
           if extension else "")
        + (f"   {coverage['fraction']:.0%} covered" if coverage["fraction"] is not None else "")
        # Only a ride with a recorded length can end early. A segmentless one has
        # none — its planned duration lives in the coach plan, which hr.db does
        # not carry — so calling it incomplete would invent a target to have
        # missed.
        + ("" if coverage["scheduled_sec"] is None or coverage["complete"]
           else "   INCOMPLETE — capture ends early")
    )
    if structure["discarded_starts"]:
        lines.append(f"    {structure['discarded_starts']} earlier run(s) discarded"
                     " — the latest START wins")
    for seg in structure["segments"]:
        band = _band_text(seg)
        share = ("   -" if seg["fraction_in_band"] is None
                 else f"  {seg['fraction_in_band']:.0%} in band")
        lines.append(
            f"    {seg['index']}. {seg['role']:8s} {seg['duration_s'] / 60:5.1f}m "
            f"{band:11s} avg {seg['avg_hr']} peak {seg['peak_hr']}{share}"
            + (f"  (+{seg['extended_sec'] / 60:.0f}m)" if seg.get("extended_sec") else "")
        )
    work = structure["work"]
    lines.append(f"    work spans: avg {work['avg_hr']} peak {work['peak_hr']}"
                 + (f"  {work['fraction_in_band']:.0%} in band"
                    if work["fraction_in_band"] is not None else ""))
    return lines


def _band_text(segment):
    if "hr_min" in segment and "hr_max" in segment:
        return f"{segment['hr_min']}-{segment['hr_max']}"
    if "hr_min" in segment:
        return f">={segment['hr_min']}"
    if "hr_max" in segment:
        return f"<={segment['hr_max']}"
    return "no band"


def terminal_summary(session_id, report):
    lines = []
    a1 = report["alpha1"]
    hr = report["hr"]
    lines.append(f"Session {session_id}")
    lines.append(f"  duration {report['span_s'] / 60:.1f} min   {report['beats']} beats")
    lines.extend(_structure_lines(report.get("structure")))
    lines.append(f"  RR coverage {report['rr_coverage']:.0%}   gaps: {report['gaps']}"
                 f"   artifacts {report['artifact_frac_overall']:.0%}")
    lines.append(f"  HR  avg {hr['avg']}  max {hr['max']}  min {hr['min']}")
    if hr["zones"]:
        z = "  ".join(f"{k} {v / 60:.1f}m" for k, v in hr["zones"].items())
        lines.append(f"  zones {z}")
    lines.append(f"  RMSSD {report['rmssd_ms']} ms")
    lines.append(f"  DFA a1: {a1['windows_trusted']}/{a1['windows_total']} windows trusted"
                 + (f", mean {a1['trusted_mean']} min {a1['trusted_min']}"
                    if a1["trusted_mean"] is not None else ""))
    lines.append(f"          above LT1: {a1['trusted_windows_above_lt1']} win"
                 f"   above LT2: {a1['trusted_windows_above_lt2']} win")
    lines.append(f"  VERDICT: {a1['verdict']}")
    if report["bouts"]:
        lines.append(f"  bouts detected: {len(report['bouts'])}")
        for b in report["bouts"]:
            extra = ""
            if b.get("rmssd_ms") is not None:
                extra += f"  RMSSD {b['rmssd_ms']}ms"
            if b.get("alpha1") is not None:
                extra += f"  a1 {b['alpha1']}"
            lines.append(f"    {b['kind']:4s} @{b['offset_s'] / 60:4.1f}m"
                         f"  {b['duration_s']:5.0f}s  HR avg {b['hr_avg']} peak {b['hr_peak']}{extra}")
    else:
        lines.append("  bouts detected: none (HR too steady — continuous effort)")
    return "\n".join(lines)


def write_json(session_id, report, out_dir):
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    path = out / f"report_{session_id}.json"
    path.write_text(json.dumps({"session_id": session_id, **report}, indent=2))
    return path


def write_png(session_id, report, out_dir, beats=None):
    """HR + a1 traces with threshold lines and detected bouts.

    HR is drawn from the raw beats (so short sessions still get a chart);
    the a1 panel is populated only when 2-minute windows exist. Returns None
    only if matplotlib is genuinely unavailable.
    """
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        return None

    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    windows = report["windows"]
    bouts = report.get("bouts", [])

    fig, (ax_hr, ax_a1) = plt.subplots(2, 1, figsize=(11, 6), sharex=True)

    # HR from raw beats where available, else from window averages
    if beats:
        t0 = beats[0].ts_ms
        hr_x = [(b.ts_ms - t0) / 60000.0 for b in beats if b.hr_bpm > 0]
        hr_y = [b.hr_bpm for b in beats if b.hr_bpm > 0]
    else:
        hr_x = [w["offset_s"] / 60 for w in windows]
        hr_y = [w["hr_avg"] for w in windows]
    if hr_x:
        ax_hr.plot(hr_x, hr_y, color="#E94560", lw=1.2)
    ax_hr.set_ylabel("HR (bpm)")
    ax_hr.set_title(f"Session {session_id} — HR and DFA a1")
    ax_hr.grid(alpha=0.2)

    # Overlay detected work bouts as shaded spans
    for b in bouts:
        if b["kind"] == "work":
            start = b["offset_s"] / 60
            end = start + b["duration_s"] / 60
            ax_hr.axvspan(start, end, color="#FBBF24", alpha=0.12, zorder=0)

    if windows:
        offs = [w["offset_s"] / 60 for w in windows]
        a1s = [w["alpha1"] for w in windows]
        trusted = [w["trusted"] for w in windows]
        ax_a1.plot(offs, a1s, color="#4ADE80", lw=1.5, zorder=1)
        for o, a, t in zip(offs, a1s, trusted):
            if a is None:
                continue
            ax_a1.scatter([o], [a], s=18, zorder=2,
                          color="#4ADE80" if t else "#6A6A6A", edgecolors="none")
    else:
        ax_a1.text(0.5, 0.5, "session too short for 2-min DFA windows",
                   ha="center", va="center", transform=ax_a1.transAxes,
                   color="#6A6A6A", fontsize=10)
    ax_a1.axhline(hrv.ALPHA1_LT1, color="#FBBF24", ls="--", lw=1, label="LT1 (0.75)")
    ax_a1.axhline(hrv.ALPHA1_LT2, color="#EF4444", ls="--", lw=1, label="LT2 (0.50)")
    ax_a1.set_ylabel("DFA a1")
    ax_a1.set_xlabel("Time (min)")
    ax_a1.grid(alpha=0.2)
    ax_a1.legend(loc="upper right", fontsize=8)

    fig.tight_layout()
    path = out / f"report_{session_id}.png"
    fig.savefig(path, dpi=120)
    plt.close(fig)
    return path

#!/usr/bin/env python3
"""Provenance-based personal-data scan for a PUBLIC repo.

Why this exists
---------------
In July 2026 a scan found that `test/trends/test_health_endpoint.py` had been
built by pasting rows straight out of the real BodySpec and Quest databases:
two actual DEXA scans verbatim to six decimals, plus real lab draw dates and a
real out-of-range LDL. It shipped publicly in c18008e / 50e98c2.

The pre-existing guard was a *semantic* one — "does this read as first-person
medical narrative?" — and it explicitly waved through "a test fixture with
sample medical strings". A fixture pasted from a real DB passes that test: the
prose is innocent, only the PROVENANCE is not. Voice is the wrong signal.

So this checks provenance instead. It reads the real personal databases on this
machine, derives tokens distinctive enough that coincidence is implausible, and
fails if any of them appear in the repo.

Redaction contract
------------------
A finding NEVER prints the matched value — that would move personal data into
hook output, CI logs and terminal scrollback, which is the problem this guard
exists to prevent. Findings carry the token class, the source column, and a
short hash usable for allowlisting.

Deliberate gaps (a miss here is not a bug, it is the design)
-----------------------------------------------------------
* Values with fewer than 3 decimals (a body-fat "25.2", an LDL "152") are not
  matchable — they collide with ordinary numbers everywhere in a codebase. The
  date and identity classes are what cover the labs case.
* `timeseries` in the Garmin DB (445k sensor rows) is skipped for speed; nobody
  hand-pastes a sensor sample into a fixture.
* Free text must appear verbatim. A reworded or re-wrapped paste is a miss.
* Journal/coach entry DATES are not matchable either — the user logs most days,
  so nearly every date is "real" and the class would flag the whole test suite.

Usage
-----
    bin/scan_personal_data.py --staged        # added lines in the git index
    bin/scan_personal_data.py --all           # every tracked text file
    bin/scan_personal_data.py --rev <sha>     # the tree at a given commit

Exit codes: 0 clean (or no sources present) | 1 findings | 2 usage/IO error.
"""

import argparse
import hashlib
import math
import re
import sqlite3
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ALLOWLIST = ROOT / "bin" / "scan_personal_data.allow"
HOME = Path.home()

# Paths with no human-pasted content, where a match can only be a false
# positive. version.json is stamped with today's date by the pre-commit hook —
# commit on a DEXA scan day and the date class fires on a generated file.
EXCLUDED_PATHS = {
    "public/version.json",
    "bin/scan_personal_data.allow",   # hashes by construction
}

# A float literal only becomes distinctive at 3+ decimals: "12.345678" cannot
# be a coincidence, "12.34" can.
FLOAT_RE = re.compile(r"\d+\.\d{3,}")
DATE_RE = re.compile(r"\d{4}-\d{2}-\d{2}")

# Free text shorter than this collides with ordinary prose.
MIN_TEXT_LEN = 24
# Tracker names are short by nature; 4 is the floor that keeps "Zinc" in.
MIN_NAME_LEN = 4

# Classes, in report order.
FLOAT, DATE, IDENT, TEXT = "float", "date", "identity", "text"


# --------------------------------------------------------------------------
# Source databases
# --------------------------------------------------------------------------
# (label, path, [(sql, class, column-label)]). Every source is optional: a
# missing DB is skipped, so this runs on a fresh clone or another machine.
SOURCES = [
    (
        "bodyspec.db",
        HOME / ".bodyspecy" / "bodyspec.db",
        [
            ("SELECT scan_date FROM scans", DATE, "scans.scan_date"),
            ("SELECT lean_mass_kg, fat_mass_kg, total_mass_kg, "
             "total_body_fat_pct, vat_mass_kg, ag_ratio FROM scans",
             FLOAT, "scans.*_kg/pct/ratio"),
            ("SELECT bmd_g_cm2, t_score FROM scan_bone_density",
             FLOAT, "scan_bone_density.bmd/t_score"),
        ],
    ),
    (
        "questy.db",
        HOME / ".questy" / "questy.db",
        [
            ("SELECT report_date FROM reports", DATE, "reports.report_date"),
            ("SELECT patient_name, dob, specimen_id, requisition_id, "
             "lab_reference_id FROM reports", IDENT, "reports.identity"),
            ("SELECT content FROM notes", TEXT, "notes.content"),
        ],
    ),
    (
        "health.db",
        HOME / ".garmy" / "health.db",
        [
            # daily_health_metrics/body_composition only — see module docstring
            # on why `timeseries` is skipped.
            ("SELECT hrv_last_night_avg, hrv_baseline_low_upper, "
             "hrv_baseline_balanced_low, hrv_baseline_balanced_upper, "
             "sleep_duration_hours FROM daily_health_metrics",
             FLOAT, "daily_health_metrics.*"),
            ("SELECT weight_grams FROM body_composition",
             FLOAT, "body_composition.weight_grams"),
        ],
    ),
    (
        "journal.db",
        ROOT / "data" / "journal.db",
        [
            ("SELECT name FROM trackers WHERE COALESCE(deleted, 0) = 0",
             IDENT, "trackers.name"),
        ],
    ),
    (
        "coach.db",
        ROOT / "data" / "coach.db",
        [
            ("SELECT general_notes, pain_discomfort FROM workout_session_logs",
             TEXT, "workout_session_logs.notes"),
            ("SELECT user_note FROM exercise_logs", TEXT,
             "exercise_logs.user_note"),
        ],
    ),
]


def float_variants(value):
    """Every literal spelling of `value` a human could paste.

    The July 2026 leak pasted a 6-decimal TRUNCATION of a 9-decimal stored
    value (think 12.345678 for a stored 12.3456789), so matching the stored
    repr alone would have missed it. Emit both rounded and truncated forms at
    3..9 decimals.
    """
    out = set()
    try:
        v = float(value)
    except (TypeError, ValueError):
        return out
    if not math.isfinite(v) or v == 0:
        return out
    for nd in range(3, 10):
        out.add(f"{v:.{nd}f}")
        scale = 10 ** nd
        out.add(f"{math.trunc(v * scale) / scale:.{nd}f}")
    return out


def build_tokens(sources=None):
    """Collect tokens from whatever personal DBs exist. Returns
    (floats, dates, strings, present, missing) where each token maps to its
    "db table.column" origin.

    `sources` resolves at call time, not import time, so tests can substitute a
    synthetic source without the module-level default shadowing it."""
    sources = SOURCES if sources is None else sources
    floats, dates, strings = {}, {}, {}
    present, missing = [], []

    for label, path, queries in sources:
        if not Path(path).exists():
            missing.append(label)
            continue
        uri = f"file:{path}?mode=ro"
        try:
            conn = sqlite3.connect(uri, uri=True)
        except sqlite3.Error:
            missing.append(label)
            continue
        present.append(label)
        with conn:
            for sql, cls, column in queries:
                origin = f"{label} {column}"
                try:
                    rows = conn.execute(sql).fetchall()
                except sqlite3.Error:
                    # Schema drift in an external tool's DB must not break the
                    # commit path — the other classes still apply.
                    continue
                for row in rows:
                    for value in row:
                        if value is None:
                            continue
                        if cls == FLOAT:
                            for lit in float_variants(value):
                                floats.setdefault(lit, origin)
                        elif cls == DATE:
                            text = str(value).strip()[:10]
                            if DATE_RE.fullmatch(text):
                                dates.setdefault(text, origin)
                        else:
                            text = str(value).strip()
                            floor = (MIN_NAME_LEN if cls == IDENT
                                     else MIN_TEXT_LEN)
                            if len(text) >= floor:
                                strings.setdefault(
                                    text.lower(), (origin, cls))
        conn.close()
    return floats, dates, strings, present, missing


def token_hash(cls, token):
    """Stable short hash for allowlisting. Class-scoped so the same string in
    two classes stays distinguishable."""
    payload = f"{cls}|{token}".encode()
    return hashlib.sha256(payload).hexdigest()[:16]


def load_allowlist(path=ALLOWLIST):
    """Hashes of tokens judged safe. Hashes, not values — an allowlist of raw
    personal strings would itself be the leak."""
    allowed = set()
    if not Path(path).exists():
        return allowed
    for raw in Path(path).read_text().splitlines():
        line = raw.split("#", 1)[0].strip()
        if line:
            allowed.add(line.lower())
    return allowed


def scan_text(text, floats, dates, strings, allowed):
    """Find personal-data tokens in `text`. Returns [(line_no, cls, origin,
    hash)] — never the value."""
    findings = []
    lowered = text.lower()

    # Cheap file-level prefilter for the substring classes: only walk lines for
    # tokens that are present somewhere in the file at all.
    hits = [(tok, origin, cls) for tok, (origin, cls) in strings.items()
            if tok in lowered]

    for line_no, line in enumerate(text.splitlines(), start=1):
        for lit in FLOAT_RE.findall(line):
            origin = floats.get(lit)
            if origin and token_hash(FLOAT, lit) not in allowed:
                findings.append((line_no, FLOAT, origin, token_hash(FLOAT, lit)))
        for iso in DATE_RE.findall(line):
            origin = dates.get(iso)
            if origin and token_hash(DATE, iso) not in allowed:
                findings.append((line_no, DATE, origin, token_hash(DATE, iso)))
        if hits:
            line_lower = line.lower()
            for tok, origin, cls in hits:
                if tok in line_lower and token_hash(cls, tok) not in allowed:
                    findings.append((line_no, cls, origin, token_hash(cls, tok)))
    return findings


class _Git:
    __slots__ = ("stdout", "returncode")

    def __init__(self, stdout, returncode):
        self.stdout, self.returncode = stdout, returncode


def _git(*args):
    """Run git and decode leniently. Blobs in this repo include PNGs, and
    `git show <rev>:<png>` is not valid UTF-8 — strict decoding would abort the
    whole scan on the first icon."""
    proc = subprocess.run(["git", "-C", str(ROOT), *args],
                          capture_output=True, check=False)
    return _Git(proc.stdout.decode("utf-8", errors="replace"), proc.returncode)


def staged_files():
    out = _git("diff", "--cached", "--name-only", "--diff-filter=ACMR")
    return [p for p in out.stdout.splitlines() if p.strip()]


def staged_added_text(path):
    """Added lines of a staged file, with their post-image line numbers, glued
    back into a sparse text so scan_text's numbering stays meaningful."""
    out = _git("diff", "--cached", "-U0", "--", path)
    lines, line_no = {}, 0
    for raw in out.stdout.splitlines():
        if raw.startswith("@@"):
            m = re.search(r"\+(\d+)", raw)
            line_no = int(m.group(1)) if m else 0
        elif raw.startswith("+") and not raw.startswith("+++"):
            lines[line_no] = raw[1:]
            line_no += 1
    if not lines:
        return ""
    return "\n".join(lines.get(n, "") for n in range(1, max(lines) + 1))


def tracked_files(rev=None):
    out = (_git("ls-tree", "-r", "--name-only", rev) if rev
           else _git("ls-files"))
    return [p for p in out.stdout.splitlines() if p.strip()]


def read_blob(path, rev=None):
    if rev:
        out = _git("show", f"{rev}:{path}")
        return out.stdout if out.returncode == 0 else None
    full = ROOT / path
    if not full.exists() or full.is_dir():
        return None
    try:
        return full.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        return None  # binary or unreadable — nothing to paste-match


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Fail if repo content came from the real personal DBs.")
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--staged", action="store_true",
                       help="scan added lines in the git index (pre-commit)")
    group.add_argument("--all", action="store_true",
                       help="scan every tracked text file")
    group.add_argument("--rev", metavar="SHA",
                       help="scan the tracked tree at a given commit")
    args = parser.parse_args(argv)
    if not (args.staged or args.all or args.rev):
        args.staged = True

    floats, dates, strings, present, missing = build_tokens()
    if not present:
        print("personal-data scan: no source databases on this machine — "
              f"skipped (looked for: {', '.join(missing)})")
        return 0

    allowed = load_allowlist()
    if args.staged:
        targets = [(p, staged_added_text(p)) for p in staged_files()]
    else:
        rev = args.rev
        targets = [(p, read_blob(p, rev)) for p in tracked_files(rev)]

    findings = []
    for path, text in targets:
        if not text or path in EXCLUDED_PATHS:
            continue
        for line_no, cls, origin, digest in scan_text(
                text, floats, dates, strings, allowed):
            findings.append((path, line_no, cls, origin, digest))

    scope = "staged" if args.staged else (args.rev or "tracked tree")
    if not findings:
        print(f"personal-data scan: clean ({scope}; sources: "
              f"{', '.join(present)})")
        return 0

    print(f"\nPERSONAL DATA IN REPO CONTENT — {len(findings)} match(es) "
          f"in {scope}\n", file=sys.stderr)
    for path, line_no, cls, origin, digest in sorted(findings):
        print(f"  {path}:{line_no}  [{cls}] matches {origin}  ({digest})",
              file=sys.stderr)
    print("\nValues are redacted by design — this repo is PUBLIC and hook "
          "output is not.\nFix: invent the data instead of copying it.\n"
          "If a match is a genuine coincidence, add its hash with a "
          f"justification to\n  {ALLOWLIST.relative_to(ROOT)}\n", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())

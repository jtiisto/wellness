#!/usr/bin/env python3
"""Warn when the active Python environment has drifted from constraints.txt.

constraints.txt pins the stack the full suite last passed with. A venv installed
without `-c constraints.txt`, or upgraded by hand, can carry different versions
and fail (or hang) the gate for reasons unrelated to the change being committed.
The pre-commit hook runs this first so drift is named before any suite runs.

Warn-only by default (exit 0); `--strict` exits 1 on drift.
"""
import argparse
import importlib.metadata
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
CONSTRAINTS = ROOT / "constraints.txt"


def normalize(name):
    """PEP 503 name normalization: case and -_. runs are not significant."""
    return re.sub(r"[-_.]+", "-", name).lower()


def parse_constraints(text):
    """Return {normalized name: version} for every `name==version` line."""
    pins = {}
    for raw in text.splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        name, sep, version = line.partition("==")
        if sep and name.strip() and version.strip():
            pins[normalize(name.strip())] = version.strip()
    return pins


def installed_versions():
    """Return {normalized name: version} for the running interpreter."""
    return {
        normalize(dist.metadata["Name"]): dist.version
        for dist in importlib.metadata.distributions()
        if dist.metadata["Name"]
    }


def drift(pins, installed):
    """Return sorted (name, pinned, installed-or-None) for every mismatch."""
    return sorted(
        (name, version, installed.get(name))
        for name, version in pins.items()
        if installed.get(name) != version
    )


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--strict", action="store_true", help="exit 1 on drift")
    parser.add_argument("--constraints", type=pathlib.Path, default=CONSTRAINTS)
    args = parser.parse_args(argv)

    if not args.constraints.exists():
        print(f"check_constraints: {args.constraints} not found — skipped")
        return 0
    mismatches = drift(parse_constraints(args.constraints.read_text()), installed_versions())
    if not mismatches:
        return 0

    print(f"WARNING: this Python environment ({sys.prefix}) differs from constraints.txt:")
    for name, pinned, have in mismatches:
        print(f"  {name}: pinned {pinned}, installed {have or 'missing'}")
    print("  Reinstall: pip install -r requirements.txt -c constraints.txt")
    return 1 if args.strict else 0


if __name__ == "__main__":
    sys.exit(main())

"""Guard: keep production-deploy creep in check.

`bin/deploy.manifest` is the single source of truth for what `bin/deploy-prod.sh`
ships to production. This test fails if any **tracked** top-level entry or
**tracked** bin/ script is not classified in the manifest as either shipped or
explicitly excluded — so adding a new file forces a deliberate deploy decision
instead of silently shipping (or silently being left out).

Only git-tracked paths are checked, so the result is deterministic across
machines and clones (untracked/gitignored helper scripts aren't enforced — and
with the manifest allowlist they can't leak into prod regardless).
"""
import pathlib
import subprocess

import pytest

ROOT = pathlib.Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "bin" / "deploy.manifest"


def _parse_manifest():
    ship_top, ship_bin, exclude_top, exclude_bin = set(), set(), set(), set()
    for raw in MANIFEST.read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        assert len(parts) >= 2, f"Malformed manifest line: {raw!r}"
        action, target = parts[0], parts[1]
        if action in ("ship-dir", "ship-file"):
            ship_top.add(target)
        elif action == "ship-bin":
            ship_bin.add(target)
        elif action == "exclude":
            exclude_top.add(target)
        elif action == "exclude-bin":
            exclude_bin.add(target)
        else:
            pytest.fail(f"Unknown manifest action {action!r} in line: {raw!r}")
    ship_top.add("bin")  # bin/ ships selectively via the ship-bin entries
    return ship_top, ship_bin, exclude_top, exclude_bin


def _git(*args):
    return subprocess.check_output(["git", "-C", str(ROOT), *args], text=True).splitlines()


def _tracked_toplevel():
    return {line.split("/", 1)[0] for line in _git("ls-files") if line}


def _tracked_bin_scripts():
    out = _git("ls-files", "bin")
    names = set()
    for line in out:
        if line.startswith("bin/"):
            rest = line[len("bin/"):]
            if "/" not in rest:  # bin/ has no subdirs, but be defensive
                names.add(rest)
    return names


@pytest.mark.unit
def test_manifest_is_parseable_and_nonempty():
    ship_top, ship_bin, _, _ = _parse_manifest()
    assert ship_top and ship_bin, "manifest declares nothing to ship"


@pytest.mark.unit
def test_every_tracked_toplevel_entry_is_classified():
    ship_top, _, exclude_top, _ = _parse_manifest()
    classified = ship_top | exclude_top
    unclassified = sorted(_tracked_toplevel() - classified)
    assert not unclassified, (
        f"Top-level entries missing from bin/deploy.manifest: {unclassified}. "
        "Add a `ship-dir`/`ship-file` line (deploys to prod) or an `exclude` "
        "line (intentionally not deployed) for each."
    )


@pytest.mark.unit
def test_every_tracked_bin_script_is_classified():
    _, ship_bin, _, exclude_bin = _parse_manifest()
    classified = ship_bin | exclude_bin
    unclassified = sorted(_tracked_bin_scripts() - classified)
    assert not unclassified, (
        f"bin/ scripts missing from bin/deploy.manifest: {unclassified}. "
        "Add a `ship-bin` line (deploys to prod) or an `exclude-bin` line "
        "(dev-only) for each."
    )


@pytest.mark.unit
def test_no_entry_is_both_shipped_and_excluded():
    ship_top, ship_bin, exclude_top, exclude_bin = _parse_manifest()
    assert not (ship_top & exclude_top), f"both shipped and excluded: {ship_top & exclude_top}"
    assert not (ship_bin & exclude_bin), f"both shipped and excluded: {ship_bin & exclude_bin}"

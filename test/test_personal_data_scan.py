"""Tests for the provenance-based personal-data guard (bin/scan_personal_data.py).

Two jobs here. The unit tests pin the matching rules — especially the
truncation case, which is the one that actually happened: a 9-decimal stored
value was pasted truncated to 6, so matching the stored repr alone would
have missed the leak entirely. The guard test runs the real scanner over the
whole tracked tree, so the pre-push gate re-checks committed content, not just
whatever a given diff happens to touch.

Synchronous by construction — see project_e2e_test_patterns: an `async def`
test anywhere in this suite dies once pytest-playwright has been collected.
"""
import importlib.util
import pathlib
import sqlite3

import pytest

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCANNER = ROOT / "bin" / "scan_personal_data.py"


def _load_scanner():
    spec = importlib.util.spec_from_file_location("scan_personal_data", SCANNER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


scan = _load_scanner()


@pytest.mark.unit
class TestFloatVariants:
    # Illustrative values only — never a real stored value, which is the whole
    # point of the guard (a real one here would fail this file's own scan).
    def test_truncated_form_is_covered(self):
        # The July 2026 leak, exactly: stored full precision, pasted truncated.
        assert "12.345678" in scan.float_variants(12.3456789)

    def test_rounded_form_is_covered(self):
        assert "12.345679" in scan.float_variants(12.3456789)

    def test_three_decimal_floor(self):
        variants = scan.float_variants(1.234567)
        assert "1.235" in variants           # 3dp rounded
        assert all(len(v.split(".")[1]) >= 3 for v in variants)

    def test_low_precision_values_yield_no_short_literals(self):
        # 25.2 must not produce "25.2" — 1-2dp numbers collide with everything.
        assert "25.2" not in scan.float_variants(25.2)

    def test_zero_and_junk_are_ignored(self):
        assert scan.float_variants(0) == set()
        assert scan.float_variants(None) == set()
        assert scan.float_variants("not a number") == set()


@pytest.mark.unit
class TestHashingAndAllowlist:
    def test_hash_is_class_scoped(self):
        assert scan.token_hash("float", "1.234") != scan.token_hash("date", "1.234")

    def test_hash_is_stable_and_short(self):
        digest = scan.token_hash("identity", "example")
        assert digest == scan.token_hash("identity", "example")
        assert len(digest) == 16

    def test_allowlist_parses_hash_and_ignores_comments(self, tmp_path):
        path = tmp_path / "allow"
        path.write_text(
            "# a comment\n"
            "\n"
            "abc123def4567890  # justification\n"
            "FEDCBA9876543210\n"
        )
        assert scan.load_allowlist(path) == {"abc123def4567890", "fedcba9876543210"}

    def test_missing_allowlist_is_empty_not_an_error(self, tmp_path):
        assert scan.load_allowlist(tmp_path / "nope") == set()


@pytest.mark.unit
class TestScanText:
    # Every token here is INVENTED. Using a real one would make this file itself
    # a leak — and TestTrackedTreeIsClean would (correctly) fail on it.
    tokens = (
        {"12.345678": "example.db scans.lean"},           # floats
        {"2031-05-06": "example.db scans.scan_date"},     # dates
        {"moonleaf extract": ("example.db trackers.name", "identity"),
         "felt a sharp pinch in the left shoulder": ("example.db notes", "text")},
    )

    def _scan(self, text, allowed=frozenset()):
        return scan.scan_text(text, *self.tokens, allowed)

    def test_flags_pasted_float(self):
        found = self._scan("value = 12.345678\n")
        assert [(f[0], f[1]) for f in found] == [(1, "float")]

    def test_flags_event_date(self):
        found = self._scan("scan on 2031-05-06\n")
        assert [(f[0], f[1]) for f in found] == [(1, "date")]

    def test_flags_identity_case_insensitively(self):
        assert self._scan('name = "MOONLEAF EXTRACT"\n')[0][1] == "identity"

    def test_flags_verbatim_free_text(self):
        found = self._scan("note = 'Felt a sharp pinch in the left shoulder'\n")
        assert found[0][1] == "text"

    def test_ignores_low_precision_and_unrelated_values(self):
        assert self._scan("bf = 25.2\nweight = 85.4\nday = 2026-01-01\n") == []

    def test_line_numbers_are_reported(self):
        found = self._scan("a\nb\nvalue = 12.345678\n")
        assert found[0][0] == 3

    def test_allowlisted_token_is_silenced(self):
        allowed = {scan.token_hash("identity", "moonleaf extract")}
        assert self._scan("moonleaf extract\n", allowed) == []

    def test_finding_never_carries_the_value(self):
        # The redaction contract: hook output and CI logs must stay clean.
        for finding in self._scan("value = 12.345678\nmoonleaf extract\n"):
            assert "12.345678" not in str(finding)
            assert "moonleaf" not in str(finding).lower()


@pytest.mark.integration
class TestAgainstSyntheticSource:
    def test_end_to_end_flags_a_pasted_row(self, tmp_path, monkeypatch):
        db = tmp_path / "fake_personal.db"
        conn = sqlite3.connect(db)
        conn.execute("CREATE TABLE scans (scan_date DATE, lean_mass_kg REAL)")
        conn.execute("INSERT INTO scans VALUES ('2030-03-03', 61.987654321)")
        conn.commit()
        conn.close()

        monkeypatch.setattr(scan, "SOURCES", [
            ("fake.db", db, [
                ("SELECT scan_date FROM scans", scan.DATE, "scans.scan_date"),
                ("SELECT lean_mass_kg FROM scans", scan.FLOAT, "scans.lean"),
            ]),
        ])
        floats, dates, strings, present, missing = scan.build_tokens()
        assert present == ["fake.db"]

        # Truncated paste, the real-world shape of the mistake.
        findings = scan.scan_text("row = ('2030-03-03', 61.987654)\n",
                                  floats, dates, strings, set())
        assert {f[1] for f in findings} == {"date", "float"}

    def test_absent_source_is_skipped_not_fatal(self, tmp_path, monkeypatch):
        monkeypatch.setattr(scan, "SOURCES", [
            ("gone.db", tmp_path / "does-not-exist.db",
             [("SELECT 1", scan.FLOAT, "x")]),
        ])
        floats, dates, strings, present, missing = scan.build_tokens()
        assert (present, missing) == ([], ["gone.db"])

    def test_fixture_prefixed_tracker_names_are_not_tokens(self, tmp_path):
        # The golden generators create `fixture-` trackers on the dev server,
        # and data/journal.db IS that server's store — without the exclusion
        # the scan flags the very fixtures the generator wrote. Pins the REAL
        # query from SOURCES, not a monkeypatched copy, so the two can't drift.
        db = tmp_path / "journal.db"
        conn = sqlite3.connect(db)
        conn.execute("CREATE TABLE trackers (name TEXT, deleted INTEGER)")
        conn.executemany(
            "INSERT INTO trackers VALUES (?, 0)",
            [("fixture-Quantifiable",), ("moonleaf extract",)])
        conn.commit()
        conn.close()

        _label, _path, queries = next(
            s for s in scan.SOURCES if s[0] == "journal.db")
        sql = queries[0][0]
        rows = [r[0] for r in sqlite3.connect(db).execute(sql)]
        assert rows == ["moonleaf extract"]

    def test_schema_drift_does_not_break_the_scan(self, tmp_path, monkeypatch):
        db = tmp_path / "drifted.db"
        sqlite3.connect(db).close()          # exists, but has no tables
        monkeypatch.setattr(scan, "SOURCES", [
            ("drifted.db", db, [("SELECT gone FROM missing", scan.FLOAT, "x")]),
        ])
        floats, dates, strings, present, missing = scan.build_tokens()
        assert present == ["drifted.db"] and floats == {}


@pytest.mark.integration
class TestTrackedTreeIsClean:
    """The guard itself: no tracked file may contain personal-DB content.

    On a machine without the personal databases the scanner degrades to a skip
    and returns 0 — this test is then vacuous by design, not silently broken.
    """

    def test_tracked_tree_has_no_personal_data(self, capsys):
        rc = scan.main(["--all"])
        out = capsys.readouterr()
        assert rc == 0, (
            "Personal data found in tracked files — see the findings above. "
            "Fix the content; only allowlist genuine coincidences.\n"
            + out.err
        )
        _, _, _, present, _ = scan.build_tokens()
        if present:
            assert "clean" in out.out

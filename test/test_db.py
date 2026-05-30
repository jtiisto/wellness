"""Tests for shared database utilities (db.py)."""
from datetime import datetime, timezone
from pathlib import Path

import pytest
from src.modules.db import get_db, get_utc_now, utc_days_ago

_SRC_MODULES = Path(__file__).parent.parent / "src" / "modules"


def _parse_z(s: str) -> datetime:
    """Parse a Z-suffixed instant string back to an aware datetime."""
    return datetime.fromisoformat(s.replace("Z", "+00:00"))


class _FrozenDateTime(datetime):
    """datetime subclass whose now() is pinned, for byte-exact format checks."""
    _FIXED = datetime(2026, 5, 30, 12, 34, 56, 123456, tzinfo=timezone.utc)

    @classmethod
    def now(cls, tz=None):
        return cls._FIXED


class TestGetDbAutoRollback:
    """get_db should auto-rollback uncommitted changes on exception."""

    def test_rollback_on_exception(self, tmp_path):
        db_path = tmp_path / "test.db"
        # Create a table
        with get_db(db_path) as conn:
            conn.execute("CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT)")
            conn.commit()

        # Insert a row then raise — should be rolled back
        with pytest.raises(RuntimeError):
            with get_db(db_path) as conn:
                conn.execute("INSERT INTO items (name) VALUES ('should_not_persist')")
                raise RuntimeError("simulated failure")

        # Verify the row was not persisted
        with get_db(db_path) as conn:
            count = conn.execute("SELECT COUNT(*) FROM items").fetchone()[0]
            assert count == 0

    def test_commit_persists_on_success(self, tmp_path):
        db_path = tmp_path / "test.db"
        with get_db(db_path) as conn:
            conn.execute("CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT)")
            conn.commit()

        with get_db(db_path) as conn:
            conn.execute("INSERT INTO items (name) VALUES ('persisted')")
            conn.commit()

        with get_db(db_path) as conn:
            count = conn.execute("SELECT COUNT(*) FROM items").fetchone()[0]
            assert count == 1


class TestBusyTimeout:
    def test_busy_timeout_configured(self, tmp_path):
        """get_db connections should have busy_timeout set to 5000ms."""
        db_path = tmp_path / "test.db"
        with get_db(db_path) as conn:
            timeout = conn.execute("PRAGMA busy_timeout").fetchone()[0]
            assert timeout == 5000


class TestUtcDaysAgo:
    """R5: utc_days_ago is the helper for *instant* cutoffs (Z-suffixed)."""

    def test_returns_z_suffixed_instant(self):
        s = utc_days_ago(14)
        assert s.endswith("Z")
        assert "+00:00" not in s
        assert _parse_z(s).tzinfo is not None  # parseable as an aware instant

    def test_is_n_days_in_the_past(self):
        delta = _parse_z(get_utc_now()) - _parse_z(utc_days_ago(14))
        # ~14 days; allow a few seconds of wall-clock skew between the two calls
        assert abs(delta.total_seconds() - 14 * 86400) < 5

    def test_zero_days_is_now(self):
        delta = _parse_z(get_utc_now()) - _parse_z(utc_days_ago(0))
        assert abs(delta.total_seconds()) < 5


class TestInstantFormatContract:
    """R5: every producer of a stored/compared instant must emit the identical
    Z-suffixed, microsecond-precision format. The MCP servers keep their own
    get_utc_now copies (separate processes) — this pins them byte-identical to
    db.get_utc_now() so they can never drift (decision 2 in plans/ phase 2)."""

    def test_all_producers_byte_identical(self, monkeypatch):
        from src.modules import db as db_mod
        from coach_mcp import server as coach_server
        from coach_mcp import exercise_registry as coach_reg

        monkeypatch.setattr(db_mod, "datetime", _FrozenDateTime)
        monkeypatch.setattr(coach_server, "datetime", _FrozenDateTime)
        monkeypatch.setattr(coach_reg, "datetime", _FrozenDateTime)

        expected = "2026-05-30T12:34:56.123456Z"
        assert db_mod.get_utc_now() == expected
        assert coach_server.get_utc_now() == expected
        assert coach_reg._get_utc_now() == expected


class TestInstantFormattingRoutedThroughHelpers:
    """R5 lint guard: the modules that store/compare instants must route through
    db.get_utc_now / db.utc_days_ago, never inline `.isoformat()`. Date-only
    window cutoffs use strftime('%Y-%m-%d') (local calendar dates) and so never
    trip this — the absence of `.isoformat()` is the durable signal that no new
    instant-format drift (the +00:00 vs Z bug) has crept back in."""

    @pytest.mark.parametrize("module_file", ["coach.py", "journal.py"])
    def test_no_inline_isoformat(self, module_file):
        source = (_SRC_MODULES / module_file).read_text()
        assert ".isoformat()" not in source, (
            f"{module_file} formats an instant inline; route it through "
            "db.get_utc_now()/db.utc_days_ago() instead (R5)."
        )

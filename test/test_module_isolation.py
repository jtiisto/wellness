"""R2: two routers for the same module coexist in one process on isolated DBs.

The headline property the `_db_path` module-global made impossible. Each module's
`create_router(db_path)` now builds its own `DbAccessor` (or captures its own
db_path) and binds the handlers to it as closures, so a write driven through one
router's handler lands in *that* router's database — never in a sibling's.

The tell-tale: both routers are built in the same process, and the second is
built *after* the first. Under the old shared global, the second `create_router`
clobbered `_db_path`, so every handler (in both routers) wrote to the
last-initialized DB. These tests assert the write lands only in the first
router's DB — which can only hold if the handler captured its own accessor.
"""
import sqlite3

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from modules.db import get_db


def _mount(create_router, db_path) -> TestClient:
    app = FastAPI()
    app.include_router(create_router(db_path))
    return TestClient(app)


def _client_count(db_path, client_id: str) -> int:
    with get_db(db_path) as conn:
        return conn.execute(
            "SELECT COUNT(*) FROM clients WHERE id = ?", (client_id,)
        ).fetchone()[0]


@pytest.mark.unit
def test_create_router_returns_distinct_routers(tmp_path):
    """Each call yields a fresh router object (not a shared module-level one)."""
    from modules.journal import create_router

    a = create_router(tmp_path / "a.db")
    b = create_router(tmp_path / "b.db")
    assert a is not b


@pytest.mark.unit
def test_two_journal_routers_are_isolated(tmp_path):
    a_db, b_db = tmp_path / "ja.db", tmp_path / "jb.db"
    from modules.journal import create_router

    client_a = _mount(create_router, a_db)
    _mount(create_router, b_db)  # built AFTER A → would win a shared global

    # Register a client through A's handler only.
    assert client_a.post("/sync/register?client_id=only-in-a").status_code == 200

    # The write landed in A's DB; B's DB (initialized last) never saw it.
    assert _client_count(a_db, "only-in-a") == 1
    assert _client_count(b_db, "only-in-a") == 0


@pytest.mark.unit
def test_two_coach_routers_are_isolated(tmp_path):
    a_db, b_db = tmp_path / "ca.db", tmp_path / "cb.db"
    from modules.coach import create_router

    client_a = _mount(create_router, a_db)
    _mount(create_router, b_db)  # built AFTER A → would win a shared global

    assert client_a.post("/register?client_id=only-in-a").status_code == 200

    assert _client_count(a_db, "only-in-a") == 1
    assert _client_count(b_db, "only-in-a") == 0


@pytest.mark.unit
def test_two_analysis_routers_are_isolated(tmp_path):
    a_db, b_db = tmp_path / "aa.db", tmp_path / "ab.db"
    from modules.analysis import create_router
    from modules.analysis_db import create_report

    client_a = _mount(create_router, a_db)
    client_b = _mount(create_router, b_db)  # built AFTER A → would win a shared global

    # Seed a pending report into A's DB, then read each router's pending endpoint:
    # A's handler queries A's captured path and sees it; B's sees nothing.
    create_report(str(a_db), "q1", "Query One", "prompt")

    assert len(client_a.get("/reports/pending").json()) == 1
    assert client_b.get("/reports/pending").json() == []

"""Fixtures shared by the HR module's tests.

The batch endpoints answer with counts only, so every storage claim (column
values, omitted-field defaults, upsert overwrites) has to go around the API and
read the table directly — hence `hr_rows`.
"""
import json
import sqlite3
from pathlib import Path

import pytest

GOLDEN_DIR = Path(__file__).parent / "golden"


@pytest.fixture
def hr_rows(tmp_hr_db):
    """Read a table out of the module's temp DB as a list of plain dicts."""
    def _rows(table, order_by=None):
        conn = sqlite3.connect(tmp_hr_db)
        conn.row_factory = sqlite3.Row
        try:
            sql = f"SELECT * FROM {table}"
            if order_by:
                sql += f" ORDER BY {order_by}"
            return [dict(r) for r in conn.execute(sql)]
        finally:
            conn.close()
    return _rows


@pytest.fixture
def post_golden(client):
    """POST a golden fixture's RAW BYTES to an endpoint.

    Deliberately not `json=json.load(...)`: re-serializing through Python would
    prove only that our own encoder round-trips. Sending the checked-in bytes is
    what proves the protocol spec's literal example payload works against the
    real endpoint.
    """
    def _post(path, fixture_name):
        return client.post(
            path,
            content=(GOLDEN_DIR / fixture_name).read_bytes(),
            headers={"Content-Type": "application/json"},
        )
    return _post


@pytest.fixture
def golden_json():
    """Parse a golden fixture file."""
    return lambda name: json.loads((GOLDEN_DIR / name).read_text())

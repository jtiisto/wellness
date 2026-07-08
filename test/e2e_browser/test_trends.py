"""E2E tests for the Trends module: tab renders, exercise-picker flow, and
offline cache with the stale badge. Structural assertions only — chart
correctness lives in the endpoint + chart-logic suites (no visual asserts)."""

import sqlite3
from datetime import datetime, timedelta

import pytest
from pages.app_shell import AppShellPage

NOW = "2026-01-01T00:00:00Z"


@pytest.fixture
def trends_e2e_data(app_server):
    """Reset coach data and seed a small strength history directly (the sync
    API only writes today; trends needs a spread). Mirrors the session-scoped
    reset-before-seed convention."""
    from conftest import _reset_coach_data

    db_path = app_server["db_dir"] / "coach.db"
    conn = sqlite3.connect(db_path, timeout=10)
    conn.execute("PRAGMA foreign_keys = ON")
    _reset_coach_data(conn)
    cur = conn.cursor()

    cur.execute(
        "INSERT OR IGNORE INTO exercises (slug, name, category, created_at, source) "
        "VALUES ('bench_press', 'Bench Press', 'strength', ?, 'test')", (NOW,),
    )
    for n, (w, reps) in ((14, (85, 8)), (7, (90, 8)), (2, (95, 6))):
        d = (datetime.now() - timedelta(days=n)).strftime("%Y-%m-%d")
        cur.execute(
            "INSERT INTO workout_sessions (date, day_name, last_modified, modified_by) "
            "VALUES (?, 'Strength', ?, 'test')", (d, NOW),
        )
        session_id = cur.lastrowid
        cur.execute(
            "INSERT INTO session_blocks (session_id, position, block_type, title) "
            "VALUES (?, 0, 'strength', 'Main')", (session_id,),
        )
        block_id = cur.lastrowid
        cur.execute(
            "INSERT INTO planned_exercises (session_id, block_id, exercise_key, "
            "position, name, exercise_type, target_sets, canonical_slug) "
            "VALUES (?, ?, 'bench', 0, 'Bench Press', 'strength', 3, 'bench_press')",
            (session_id, block_id),
        )
        pe_id = cur.lastrowid
        cur.execute(
            "INSERT INTO workout_session_logs (session_id, date, last_modified, modified_by) "
            "VALUES (?, ?, ?, 'test')", (session_id, d, NOW),
        )
        log_id = cur.lastrowid
        cur.execute(
            "INSERT INTO exercise_logs (session_log_id, exercise_id, exercise_key, "
            "canonical_slug, last_modified) VALUES (?, ?, 'bench', 'bench_press', ?)",
            (log_id, pe_id, NOW),
        )
        el_id = cur.lastrowid
        cur.execute(
            "INSERT INTO set_logs (exercise_log_id, set_num, weight, reps, rpe, unit) "
            "VALUES (?, 1, ?, ?, 8.0, 'lbs')", (el_id, w, reps),
        )

    conn.commit()
    conn.close()
    return {"slug": "bench_press"}


@pytest.fixture
def trends_page(app_page, trends_e2e_data):
    shell = AppShellPage(app_page)
    shell.navigate_to("Trends")
    app_page.wait_for_selector(".trends", timeout=10000)
    return app_page


def test_trends_tab_renders(trends_page):
    """The tab mounts with its sub-tab bar; Overview (default) renders tiles
    or empty states without erroring."""
    page = trends_page
    assert page.locator(".trends-tabs .trends-tab").count() == 4
    page.wait_for_selector(".trends-main", timeout=5000)
    # Overview fetch settles into tiles (seeded strength → tonnage tile).
    page.wait_for_selector(".trends-tile", timeout=10000)
    assert page.locator(".trends-error").count() == 0


def test_exercise_picker_flow(trends_page):
    """Strength sub-tab: the seeded exercise appears in the picker and its
    progression SVG renders with session dots."""
    page = trends_page
    page.locator(".trends-tab", has_text="Strength").click()
    page.wait_for_selector(".trends-picker", timeout=10000)
    # The picker is a pill button opening the app bottom sheet (not the OS
    # select dialog); options are name-only — the slug suffix appears solely
    # for duplicate display names.
    page.locator(".trends-picker").click()
    page.wait_for_selector(".trends-picker-option", timeout=10000)
    options = page.locator(".trends-picker-option").all_text_contents()
    assert any("Bench Press" in o for o in options)
    assert not any("bench_press" in o for o in options)
    page.locator(".trends-picker-option", has_text="Bench Press").first.click()
    page.wait_for_selector(".trends-card svg.trends-chart", timeout=10000)
    assert page.locator(".trends-chart circle.trends-dot").count() >= 3


def test_trends_offline_cache_and_stale_badge(trends_page):
    """Data cached online serves offline with the stale badge visible."""
    page = trends_page
    # Load strength online (fills the cache), then go offline.
    page.locator(".trends-tab", has_text="Strength").click()
    page.wait_for_selector(".trends-card svg.trends-chart", timeout=10000)

    page.context.set_offline(True)
    try:
        page.evaluate("window.dispatchEvent(new Event('offline'))")
        # Navigate away and back — the re-fetch fails, cache serves, badge shows.
        page.locator(".trends-tab", has_text="Cardio").click()
        page.locator(".trends-tab", has_text="Strength").click()
        page.wait_for_selector(".trends-card svg.trends-chart", timeout=10000)
        page.wait_for_selector(".trends-stale-badge", timeout=10000)
    finally:
        page.context.set_offline(False)

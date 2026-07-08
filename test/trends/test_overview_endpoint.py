"""Integration tests for the trends overview endpoint + PR detection."""

import pytest

from modules.trends_queries import detect_prs


@pytest.mark.unit
class TestDetectPRs:
    def test_first_session_is_baseline_not_pr(self):
        sessions = [{"slug": "a", "date": "2026-01-01", "e1rm": 100.0}]
        assert detect_prs(sessions) == []

    def test_strict_improvement_is_pr(self):
        sessions = [
            {"slug": "a", "date": "2026-01-01", "e1rm": 100.0},
            {"slug": "a", "date": "2026-01-08", "e1rm": 100.0},  # equal: no PR
            {"slug": "a", "date": "2026-01-15", "e1rm": 104.0},  # PR
            {"slug": "a", "date": "2026-01-22", "e1rm": 102.0},  # regression: no PR
            {"slug": "a", "date": "2026-01-29", "e1rm": 105.0},  # PR
        ]
        assert [p["date"] for p in detect_prs(sessions)] == ["2026-01-15", "2026-01-29"]

    def test_slugs_tracked_independently(self):
        sessions = [
            {"slug": "a", "date": "2026-01-01", "e1rm": 100.0},
            {"slug": "b", "date": "2026-01-02", "e1rm": 50.0},
            {"slug": "b", "date": "2026-01-09", "e1rm": 55.0},
            {"slug": "a", "date": "2026-01-10", "e1rm": 90.0},
        ]
        prs = detect_prs(sessions)
        assert len(prs) == 1 and prs[0]["slug"] == "b"


@pytest.mark.integration
class TestOverviewEndpoint:
    def test_shape_with_data(self, client, strength_history, cardio_history, journal_history):
        resp = client.get("/api/trends/overview")
        assert resp.status_code == 200
        data = resp.json()

        assert set(data) == {"zone2", "tonnage", "adherence_focus", "prs"}
        assert len(data["zone2"]["sparkline"]) >= 3   # weeks up to the 8-wk window
        assert len(data["tonnage"]["sparkline"]) >= 3
        assert data["tonnage"]["this_week_kg"] >= 0

        # Focus rows: actionable only, weakest-first, ≤3, each with a 14-day ribbon.
        focus = data["adherence_focus"]
        assert len(focus) <= 3
        rates = [f["rate"] for f in focus]
        assert rates == sorted(rates)
        for f in focus:
            assert f["metric_kind"] in ("adherence", "avoidance")
            assert len(f["ribbon"]) == 14
            assert all(r["status"] in ("met", "partial", "missed", "off")
                       for r in f["ribbon"])
        # The half-paused tracker reports on its pre-pause days (rate 1.0 —
        # perfect before the pause) and its ribbon mutes the paused tail;
        # only a FULLY paused window (rate null) drops out of candidacy.
        stretch = next((f for f in focus if f["tracker_id"] == "t-stretch"), None)
        if stretch is not None:
            assert stretch["rate"] == 1.0
            assert all(r["status"] == "off" for r in stretch["ribbon"][-7:])

        # PRs: bench progressed within the window → at least one recent PR.
        assert data["prs"]["count_30d"] >= 1
        assert data["prs"]["latest"]["slug"] == "bench_press"

    def test_empty_dbs(self, client):
        data = client.get("/api/trends/overview").json()
        # The 8-week window is emitted as zero weeks (continuous sparkline).
        spark = data["zone2"]["sparkline"]
        assert len(spark) == 8
        assert all(w["planned_min"] == 0 and w["extra_min"] == 0 for w in spark)
        assert data["zone2"]["this_week_min"] == 0
        assert data["tonnage"]["this_week_kg"] == 0
        assert data["adherence_focus"] == []
        assert data["prs"] == {"count_30d": 0, "latest": None}


@pytest.mark.integration
class TestOverviewAssistedPRs:
    def test_less_assistance_is_a_pr(self, assisted_history, monkeypatch):
        # d2 dropped assistance 50 → 30 within the 30-day PR window: the
        # effective-load e1RM strictly increases, so it must count as a PR.
        monkeypatch.setenv("GARMIN_DB_PATH", str(assisted_history["garmin_path"]))
        import server as server_mod
        from fastapi.testclient import TestClient
        with TestClient(server_mod.create_app()) as c:
            data = c.get("/wellness/api/trends/overview").json()

        assert data["prs"]["count_30d"] >= 1
        assert data["prs"]["latest"]["slug"] == "assisted_pull_up"
        assert data["prs"]["latest"]["date"] == assisted_history["d2"].isoformat()

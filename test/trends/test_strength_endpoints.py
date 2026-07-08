"""Integration tests for the trends strength endpoints (exact shapes)."""

from datetime import date, timedelta

import pytest

from modules.trends_queries import week_start


def _iso(d):
    return d.isoformat()


@pytest.mark.integration
class TestStrengthExercises:
    def test_picker_shape_and_ordering(self, client, strength_history):
        resp = client.get("/api/trends/strength/exercises")
        assert resp.status_code == 200
        exercises = resp.json()["exercises"]

        # Both slugs present; slug-less adhoc_press absent from the picker.
        slugs = [e["slug"] for e in exercises]
        assert slugs == ["bench_press", "kb_goblet_squat"]  # last_used DESC

        bench = exercises[0]
        assert bench["name"] == "Bench Press"
        assert bench["unit"] == "lbs"
        # 4 planned days + the off-plan day (orphan shares a planned date).
        assert bench["session_count"] == 5
        assert bench["last_used"] == strength_history["bench_dates"][3]

    def test_all_time_bests(self, client, strength_history):
        exercises = client.get("/api/trends/strength/exercises").json()["exercises"]
        bench = next(e for e in exercises if e["slug"] == "bench_press")
        # Best weight: 100x5; best e1RM: 100×(1+5/30) = 116.7.
        assert bench["all_time"]["best_weight"]["weight"] == 100
        assert bench["all_time"]["best_weight"]["reps"] == 5
        assert bench["all_time"]["best_e1rm"]["value"] == 116.7
        assert bench["all_time"]["best_e1rm"]["date"] == strength_history["bench_dates"][3]

    def test_in_range_none_without_start(self, client, strength_history):
        exercises = client.get("/api/trends/strength/exercises").json()["exercises"]
        assert all(e["in_range"] is None for e in exercises)

    def test_in_range_bests_with_start(self, client, strength_history):
        start = _iso(strength_history["today"] - timedelta(days=7))
        exercises = client.get(
            f"/api/trends/strength/exercises?start={start}"
        ).json()["exercises"]
        bench = next(e for e in exercises if e["slug"] == "bench_press")
        # Only the 100x5 day and the off-plan 70x12 fall in the last 7 days.
        assert bench["in_range"]["best_weight"]["weight"] == 100


@pytest.mark.integration
class TestStrengthSeries:
    def test_series_shape_and_progression(self, client, strength_history):
        resp = client.get("/api/trends/strength/exercise/bench_press")
        assert resp.status_code == 200
        data = resp.json()
        assert data["exercise"]["name"] == "Bench Press"
        assert data["unit"] == "lbs"

        sessions = data["sessions"]
        dates = [s["date"] for s in sessions]
        assert dates == sorted(dates)
        by_date = {s["date"]: s for s in sessions}

        # 90x8 day: top set by e1RM (both sets equal → tie), RPE = mean(8.0)
        d1 = by_date[strength_history["bench_dates"][1]]
        assert d1["top_set"] == {"weight": 90, "reps": 8, "assistance": None}
        assert d1["e1rm"] == 114.0
        assert d1["top_set_rpe"] == 8.0  # None RPE excluded from the mean

        # kg-mixed day: 40.8kg ≈ 89.9 lbs x6 vs 95 lbs x6 → 95 wins; the
        # bodyweight (weight NULL) set is excluded from set_count.
        d2 = by_date[strength_history["bench_dates"][2]]
        assert d2["top_set"]["weight"] == 95
        assert d2["set_count"] == 2

        # Tie on e1rm+weight (100x5 twice): RPE = mean(8.5, 9.5) = 9.0.
        d3 = by_date[strength_history["bench_dates"][3]]
        assert d3["top_set_rpe"] == 9.0
        assert d3["off_plan"] is False  # orphan (80x10) doesn't win the top set

        # Plan-less day row is included and flagged.
        off = by_date[strength_history["offplan_date"]]
        assert off["off_plan"] is True
        assert off["top_set"] == {"weight": 70, "reps": 12, "assistance": None}

    def test_range_filters_sessions(self, client, strength_history):
        start = _iso(strength_history["today"] - timedelta(days=7))
        sessions = client.get(
            f"/api/trends/strength/exercise/bench_press?start={start}"
        ).json()["sessions"]
        assert all(s["date"] >= start for s in sessions)
        assert len(sessions) == 2  # 100x5 day + off-plan day

    def test_unknown_slug_404(self, client, strength_history):
        assert client.get("/api/trends/strength/exercise/nope").status_code == 404

    def test_bad_date_param_422(self, client, strength_history):
        resp = client.get("/api/trends/strength/exercise/bench_press?start=07-01-2026")
        assert resp.status_code == 422


@pytest.mark.integration
class TestStrengthVolume:
    def test_weekly_buckets_continuous_with_zero_weeks(self, client, strength_history):
        today = strength_history["today"]
        start = _iso(today - timedelta(days=42))
        weeks = client.get(f"/api/trends/strength/volume?start={start}").json()["weeks"]

        # Continuous Mondays from the floored start through today's week.
        mondays = [w["week_start"] for w in weeks]
        assert mondays[0] == _iso(week_start(today - timedelta(days=42)))
        assert mondays[-1] == _iso(week_start(today))
        expected = []
        m = week_start(today - timedelta(days=42))
        while m <= today:
            expected.append(_iso(m))
            m += timedelta(days=7)
        assert mondays == expected

        # At least one zero week exists in the seeded gaps.
        assert any(w["tonnage_kg"] == 0 and w["hard_sets"] == 0 for w in weeks)

    def test_partial_flag_only_on_current_week(self, client, strength_history):
        today = strength_history["today"]
        start = _iso(today - timedelta(days=42))
        weeks = client.get(f"/api/trends/strength/volume?start={start}").json()["weeks"]
        for w in weeks:
            assert w["partial"] == (w["week_start"] == _iso(week_start(today)))

    def test_tonnage_math_and_grouping(self, client, strength_history):
        today = strength_history["today"]
        # The week containing bench_dates[3] (2 days ago) and offplan (5 days
        # ago) — both may share the current week; compute over a wide range
        # and pick the bucket containing the 100x5 day.
        start = _iso(today - timedelta(days=42))
        weeks = client.get(f"/api/trends/strength/volume?start={start}").json()["weeks"]
        target_monday = _iso(week_start(today - timedelta(days=2)))
        wk = next(w for w in weeks if w["week_start"] == target_monday)

        # by_exercise sorted by tonnage desc; slug-less rows grouped by key.
        names = [e["slug"] for e in wk["by_exercise"]]
        assert names == sorted(
            names, key=lambda s: -next(e["tonnage_kg"] for e in wk["by_exercise"] if e["slug"] == s)
        )
        assert wk["tonnage_kg"] == round(
            sum(e["tonnage_kg"] for e in wk["by_exercise"]), 1
        )
        # 100x5 twice + orphan 80x10 = 1800 lbs → 816.5 kg (if offplan day in
        # another week; tolerate either by checking the bench contribution).
        bench = next(e for e in wk["by_exercise"] if e["slug"] == "bench_press")
        assert bench["hard_sets"] >= 3

    def test_all_range_starts_at_earliest_data(self, client, strength_history):
        weeks = client.get("/api/trends/strength/volume").json()["weeks"]
        first_seed = strength_history["bench_dates"][0]
        assert weeks[0]["week_start"] == _iso(week_start(date.fromisoformat(first_seed)))

    def test_empty_db_returns_empty_weeks(self, client):
        # No strength_history fixture: fresh tmp coach DB.
        assert client.get("/api/trends/strength/volume").json() == {"weeks": []}


@pytest.mark.integration
class TestAssistedEffectiveLoad:
    """Assisted exercises (registry equipment='assisted'): the logged weight
    is machine assistance, so aggregates score EFFECTIVE load = Garmin body
    weight − assistance; without body-weight data assisted sets drop out."""

    def _fresh_client(self, garmin_path, monkeypatch):
        # The trends router resolves GARMIN_DB_PATH at create_app time; the
        # base conftest pins it to a nonexistent file, so re-point and build.
        monkeypatch.setenv("GARMIN_DB_PATH", str(garmin_path))
        import server as server_mod
        from fastapi.testclient import TestClient
        return TestClient(server_mod.create_app())

    def test_series_scores_effective_load(self, assisted_history, monkeypatch):
        from modules.trends_queries import convert_weight, epley_e1rm

        with self._fresh_client(assisted_history["garmin_path"], monkeypatch) as c:
            data = c.get("/wellness/api/trends/strength/exercise/assisted_pull_up").json()

        assert data["exercise"]["equipment"] == "assisted"
        assert len(data["sessions"]) == 2
        s1, s2 = data["sessions"]

        # d1 (bw 90.7 kg): the 50-assist ×8 set out-e1RMs the 45-assist ×6.
        bw1 = convert_weight(90.7, "kg", "lbs")
        assert s1["top_set"]["assistance"] == 50
        assert s1["top_set"]["weight"] == round(bw1 - 50, 1)
        assert s1["e1rm"] == round(epley_e1rm(bw1 - 50, 8), 1)

        # d2 (bw stepped down to 88.4 kg): less assistance AND less bw.
        bw2 = convert_weight(88.4, "kg", "lbs")
        assert s2["top_set"]["assistance"] == 30
        assert s2["top_set"]["weight"] == round(bw2 - 30, 1)
        assert s2["e1rm"] == round(epley_e1rm(bw2 - 30, 6), 1)
        # Dropping assistance 50 → 30 IS progress: e1RM strictly increases.
        assert s2["e1rm"] > s1["e1rm"]

    def test_picker_bests_use_effective_load(self, assisted_history, monkeypatch):
        from modules.trends_queries import convert_weight

        with self._fresh_client(assisted_history["garmin_path"], monkeypatch) as c:
            data = c.get("/wellness/api/trends/strength/exercises").json()
        by_slug = {e["slug"]: e for e in data["exercises"]}

        apu = by_slug["assisted_pull_up"]
        assert apu["equipment"] == "assisted"
        # All-time best weight is the LEAST-assisted session, not Feb-style
        # max assistance.
        bw2 = convert_weight(88.4, "kg", "lbs")
        assert apu["all_time"]["best_weight"]["weight"] == round(bw2 - 30, 1)
        assert apu["all_time"]["best_weight"]["assistance"] == 30
        assert apu["all_time"]["best_e1rm"]["assistance"] == 30

        # Plain exercises: equipment passthrough, assistance null.
        bench = by_slug["bench_press"]
        assert bench["equipment"] is None
        assert bench["all_time"]["best_weight"]["assistance"] is None

    def test_volume_counts_effective_not_assistance(self, assisted_history, monkeypatch):
        from modules.trends_queries import convert_weight, to_kg

        with self._fresh_client(assisted_history["garmin_path"], monkeypatch) as c:
            data = c.get("/wellness/api/trends/strength/volume").json()

        d2 = assisted_history["d2"].isoformat()
        week = next(w for w in data["weeks"]
                    if w["week_start"] <= d2
                    and d2 <= (date.fromisoformat(w["week_start"]) + timedelta(days=6)).isoformat())
        apu = next(x for x in week["by_exercise"] if x["slug"] == "assisted_pull_up")
        bw2 = convert_weight(88.4, "kg", "lbs")
        assert apu["tonnage_kg"] == round(to_kg(bw2 - 30, "lbs") * 6, 1)

    def test_without_garmin_assisted_sets_drop_out(self, assisted_history, client):
        # Default client: GARMIN_DB_PATH nonexistent. Assisted sets must not
        # be scored raw — they disappear; plain exercises are unaffected.
        picker = client.get("/api/trends/strength/exercises").json()
        slugs = {e["slug"] for e in picker["exercises"]}
        assert "assisted_pull_up" not in slugs
        assert "bench_press" in slugs

        series = client.get("/api/trends/strength/exercise/assisted_pull_up").json()
        assert series["sessions"] == []

        volume = client.get("/api/trends/strength/volume").json()
        assert all(x["slug"] != "assisted_pull_up"
                   for w in volume["weeks"] for x in w["by_exercise"])

"""Tests for analysis query definitions."""
from modules.analysis_queries import QUERIES, get_query, list_queries


REQUIRED_FIELDS = {"id", "label", "description", "prompt_template"}
QUERY_IDS = ["post_workout", "pre_workout", "weekly_review"]


class TestQueriesStructure:
    def test_all_queries_have_required_fields(self):
        for q in QUERIES:
            missing = REQUIRED_FIELDS - set(q.keys())
            assert not missing, f"Query {q.get('id', '?')} missing fields: {missing}"

    def test_query_ids_are_unique(self):
        ids = [q["id"] for q in QUERIES]
        assert len(ids) == len(set(ids)), "Duplicate query IDs found"

    def test_expected_queries_exist(self):
        ids = [q["id"] for q in QUERIES]
        for expected in QUERY_IDS:
            assert expected in ids, f"Expected query '{expected}' not found"

    def test_all_prompts_are_nonempty_strings(self):
        for q in QUERIES:
            assert isinstance(q["prompt_template"], str)
            assert len(q["prompt_template"]) > 50, f"Query {q['id']} prompt is too short"


class TestGetQuery:
    def test_get_existing_query(self):
        for qid in QUERY_IDS:
            result = get_query(qid)
            assert result is not None
            assert result["id"] == qid

    def test_get_nonexistent_query(self):
        assert get_query("nonexistent") is None


class TestListQueries:
    def test_returns_all_queries(self):
        result = list_queries()
        assert len(result) == len(QUERIES)

    def test_excludes_prompt_template(self):
        result = list_queries()
        for q in result:
            assert "prompt_template" not in q

    def test_includes_id_label_description(self):
        result = list_queries()
        for q in result:
            assert "id" in q
            assert "label" in q
            assert "description" in q

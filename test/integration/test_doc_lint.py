"""Doc-lint: committed docs must not reference paths or protocols that no
longer exist.

Two renames/rewrites kept resurfacing in the docs long after the code moved on
(the mcp/ -> mcp_servers/ rename, and the last-write-wins -> server-token
arbitration sync rewrite). These greps make that class of drift a test failure
instead of a reader trap.
"""
import re
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
DOCS = [ROOT / "README.md", ROOT / "docs" / "ARCHITECTURE.md", ROOT / "docs" / "INSTALLATION.md"]


def _hits(pattern):
    hits = []
    for doc in DOCS:
        for i, line in enumerate(doc.read_text().splitlines(), 1):
            if re.search(pattern, line):
                hits.append(f"{doc.relative_to(ROOT)}:{i}: {line.strip()}")
    return hits


@pytest.mark.integration
class TestDocLint:
    def test_no_dead_mcp_dir_references(self):
        """mcp/ was renamed to mcp_servers/; docs must not point at the old path."""
        hits = _hits(r"wellness/mcp[\"/]|`mcp/`|\bcd mcp\b|├── mcp/")
        assert not hits, "docs reference the dead mcp/ path:\n" + "\n".join(hits)

    def test_no_dead_test_unit_dir_references(self):
        """There is no test/unit/ directory (top-level units are test/test_*.py)."""
        hits = _hits(r"test/unit/")
        assert not hits, "docs reference the nonexistent test/unit/:\n" + "\n".join(hits)

    def test_no_last_write_wins_claims(self):
        """Coach log sync is per-record server-token arbitration (R1/R3), not LWW."""
        hits = _hits(r"last-write-wins|last write wins")
        assert not hits, "docs still claim last-write-wins sync:\n" + "\n".join(hits)

    def test_no_client_timestamp_comparison_claims(self):
        """Force sync no longer reconciles by client/server timestamp comparison."""
        hits = _hits(r"reconciliation by timestamp comparison")
        assert not hits, "docs describe the removed timestamp-comparison force sync:\n" + "\n".join(hits)

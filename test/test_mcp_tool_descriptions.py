"""Nothing in an MCP tool docstring may be hidden from the client.

FastMCP (4.x, and the 3-series before it) builds a tool's client-visible
`description` from the docstring text before its first structured section, and
turns each `Args:` entry into that parameter's schema `description`. Anything
else, such as a `Returns:` or `Example:` section, is cut, and the model never
sees it. A `Word ...:` line directly followed by an indented line is a pattern
the parser can read as a section (it does once a recognised section such as
`Args:` is present), so the contract forbids it outright. A parenthesised
annotation after a parameter name (`limit (int, max 100): ...`) is dropped by
the parser, so the contract forbids that too. The HR docstrings are the
analysis model's whole manual, so a silent cut there is a real loss.

The contract, checked per tool through the public API on whatever FastMCP is
installed: the description is the whole docstring above a trailing `Args:`
section; `Args:` is last; every parameter is described, and its schema
description is its whole `Args:` entry text (whitespace-normalised). A failure
names the tool: put the text back above `Args:` or into the entry, and never
indent the line directly under a colon line. Flush-left `- ` bullets under it
are safe, and so are their indented continuation lines.
"""

import inspect
import re

import pytest

from coach_mcp.config import MCPConfig as CoachConfig
from coach_mcp.server import create_mcp_server as create_coach_server
from hr_mcp.config import MCPConfig as HrConfig
from hr_mcp.server import create_mcp_server as create_hr_server
from journal_mcp.config import MCPConfig as JournalConfig
from journal_mcp.server import create_mcp_server as create_journal_server
from mcp_tools import list_tools
from modules import coach, hr, journal
from modules.db import DbAccessor

EXPECTED_TOOLS = {
    "journal": [
        "explore_database_structure", "get_table_details", "execute_sql_query",
        "list_trackers", "get_entries", "get_journal_summary", "get_schedule_adherence",
    ],
    "coach": [
        "get_workout_plan", "set_workout_plan", "ingest_training_program",
        "delete_workout_plan", "move_workout_plan", "update_plan_metadata",
        "update_exercise", "add_exercise", "remove_exercise", "search_exercises",
        "get_exercise_history", "update_block", "add_block", "remove_block",
        "reorder_blocks", "get_workout_logs", "get_workout_summary",
        "list_scheduled_dates",
    ],
    "hr": [
        "list_sessions", "get_session_report", "get_latest_session_report",
        "get_aligned_timeseries", "get_vo2_summary",
    ],
}
EXPECTED_COUNTS = {"journal": 7, "coach": 18, "hr": 5}

_ARGS_HEADING = re.compile(r"^Args:$", re.MULTILINE)
_ARGS_ENTRY = re.compile(r"^(\w+)(\s*\([^)]*\))?:(.*)$")


@pytest.fixture(scope="module")
def listed_tools(tmp_path_factory):
    """`{server: {tool name: listed tool}}` for all three servers, each built on
    an initialised, empty synthetic database."""
    root = tmp_path_factory.mktemp("mcp_descriptions")
    paths = {}
    for name, module in (("journal", journal), ("coach", coach), ("hr", hr)):
        paths[name] = root / f"{name}.db"
        module.init_database(DbAccessor(paths[name]))
    servers = {
        "journal": create_journal_server(JournalConfig(db_path=paths["journal"])),
        "coach": create_coach_server(CoachConfig(db_path=paths["coach"])),
        "hr": create_hr_server(HrConfig.from_db_path(paths["hr"])),
    }
    return {name: {tool.name: tool for tool in list_tools(server)}
            for name, server in servers.items()}


def _split_docstring(doc):
    """Return (text above `Args:`, the `Args:` block's lines or None)."""
    headings = list(_ARGS_HEADING.finditer(doc))
    assert len(headings) <= 1, "more than one `Args:` heading"
    if not headings:
        return doc.strip(), None
    start = headings[0].start()
    return doc[:start].strip(), doc[headings[0].end():].split("\n")[1:]


def _args_entries(args_lines):
    """Return `[(name, annotation or None, text)]` for an `Args:` block: an entry
    is a line at the block's own indent, its text the rest of that line plus its
    more-indented continuation lines."""
    content = [line for line in args_lines if line.strip()]
    assert content, "empty `Args:` section"
    indent = min(len(line) - len(line.lstrip(" ")) for line in content)
    entries = []
    for line in content:
        if len(line) - len(line.lstrip(" ")) == indent:
            match = _ARGS_ENTRY.match(line.strip())
            assert match, f"not an `Args:` entry: {line.strip()!r}"
            entries.append([match.group(1), match.group(2), [match.group(3)]])
        else:
            assert entries, (
                f"continuation line with no entry above it: {line.strip()!r}")
            entries[-1][2].append(line)
    return [(name, annotation, _normalise(" ".join(text)))
            for name, annotation, text in entries]


def _normalise(text):
    return " ".join(text.split())


_CASES = [pytest.param(server, tool, id=f"{server}:{tool}")
          for server, tools in EXPECTED_TOOLS.items() for tool in tools]


@pytest.mark.integration
class TestToolDescriptions:
    @pytest.mark.parametrize("server", sorted(EXPECTED_COUNTS))
    def test_server_lists_the_expected_tools(self, listed_tools, server):
        """A server that lists nothing (or something new) cannot pass vacuously."""
        assert len(listed_tools[server]) == EXPECTED_COUNTS[server]
        assert set(listed_tools[server]) == set(EXPECTED_TOOLS[server])

    @pytest.mark.parametrize(("server", "name"), _CASES)
    def test_description_is_the_whole_docstring_above_args(self, listed_tools, server, name):
        tool = listed_tools[server][name]
        doc = inspect.getdoc(tool.fn)
        assert doc, "tool has no docstring"
        body, _ = _split_docstring(doc)
        assert tool.description == body, (
            "the client-visible description is not the docstring text above "
            "`Args:` — the parser cut a section out of it")

    @pytest.mark.parametrize(("server", "name"), _CASES)
    def test_args_is_last_and_names_real_parameters(self, listed_tools, server, name):
        tool = listed_tools[server][name]
        _, args_lines = _split_docstring(inspect.getdoc(tool.fn))
        properties = tool.parameters.get("properties", {})
        if args_lines is None:
            assert not properties, "a tool with parameters needs an `Args:` section"
            return
        content = [line for line in args_lines if line.strip()]
        assert content, "empty `Args:` section"
        assert all(line.startswith(" ") for line in content), (
            "text follows the `Args:` section: " +
            next(line for line in content if not line.startswith(" ")))
        names = [entry_name for entry_name, _, _ in _args_entries(args_lines)]
        assert len(names) == len(set(names)), f"duplicate `Args:` entries: {names}"
        assert set(names) <= set(properties), (
            f"`Args:` names no such parameter: {sorted(set(names) - set(properties))}")

    @pytest.mark.parametrize(("server", "name"), _CASES)
    def test_every_parameter_is_described(self, listed_tools, server, name):
        properties = listed_tools[server][name].parameters.get("properties", {})
        undescribed = [p for p, schema in properties.items()
                       if not (schema.get("description") or "").strip()]
        assert not undescribed, f"parameters without a description: {undescribed}"

    @pytest.mark.parametrize(("server", "name"), _CASES)
    def test_description_leads_with_when_to_use(self, listed_tools, server, name):
        assert listed_tools[server][name].description.startswith("WHEN TO USE:")

    @pytest.mark.parametrize(("server", "name"), _CASES)
    def test_args_entries_reach_the_client_whole(self, listed_tools, server, name):
        """Each entry's text is its parameter's schema description, nothing lost:
        the parser keeps continuation lines (dedented, so compared
        whitespace-normalised) and drops a `name (annotation):` annotation."""
        tool = listed_tools[server][name]
        _, args_lines = _split_docstring(inspect.getdoc(tool.fn))
        if args_lines is None:
            return
        properties = tool.parameters.get("properties", {})
        for param, annotation, text in _args_entries(args_lines):
            assert annotation is None, (
                f"`{param}{annotation}:` — the parser drops a parenthesised "
                "annotation after the parameter name; put it in the entry text")
            listed = _normalise(properties.get(param, {}).get("description") or "")
            assert listed == text, (
                f"`{param}`: the client gets {listed!r}, the docstring says {text!r}")

"""Nothing in an MCP tool docstring or server instructions may be hidden from
the client.

FastMCP (4.x, and the 3-series before it) builds a tool's client-visible
`description` from the docstring text before its first structured section, and
turns each `Args:` entry into that parameter's schema `description`. Anything
else, such as a `Returns:` or `Example:` section, is cut, and the model never
sees it. A `Word ...:` line directly followed by an indented line is a pattern
the parser can read as a section (it does once a recognised section such as
`Args:` is present), so the contract forbids it outright. A parenthesised
annotation after a parameter name (`limit (int, max 100): ...`) is dropped by
the parser, so the contract forbids that too. The tool descriptions and the
server instructions are the analysis model's whole manual, so a silent cut
there is a real loss.

The contract, checked per tool through the public API on whatever FastMCP is
installed: the description is the whole docstring above a trailing `Args:`
section; `Args:` is last; every parameter is described, and its schema
description is its whole `Args:` entry text (whitespace-normalised). A failure
names the tool: put the text back above `Args:` or into the entry, and never
indent the line directly under a colon line. Flush-left `- ` bullets under it
are safe, and so are their indented continuation lines.

The client cuts, too. Claude Code (the client these servers are written for)
loads tools through tool search: at session start the model gets only the tool
names and each server's `instructions`, and a tool's description arrives only
when that tool is loaded, one at a time. It cuts server instructions at 4,096
characters and a description loaded through tool search at 16,384; before
v2.1.295 it cut every description at 2,048. The model sees only the text before
the cut. So every server sends non-empty instructions of at most 4,096
characters (read through a real initialize handshake, as a client reads them),
and every description is at most 2,048 characters, the stricter cut. A
standing rule that holds for every tool on a server lives in full in its
instructions; a fact about one tool stays on that tool. The HR rules are pinned
both ways: the instructions carry the full two-case and never-cross-check
rules, and each HR description still carries its one-line form of each, since
a description is read on its own.
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
from mcp_tools import list_tools, resource_uris, server_instructions
from modules import coach, coach_plans, hr, journal
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

# Claude Code's cuts (see the module docstring).
MAX_INSTRUCTIONS_CHARS = 4096
MAX_DESCRIPTION_CHARS = 2048

# Short stable markers of the two HR standing rules. The instructions carry the
# full rules; every HR description carries a one-line form of each. Both sides
# are matched whitespace-normalised, so re-wrapping a line never trips them.
HR_RULES_IN_INSTRUCTIONS = [
    "THE TWO CASES",
    "owned end to end by this system",
    "happened entirely",
    "on the watch",
    "No third case",
    "do not supply one",
    "NEVER cross-check",
    "Garmin/watch",
    "mounting the bike",
    "clipping in",
    "differ by minutes",
    "not worth mentioning",
    "never by a clock",
    "needs_exercise_key",
    "whole_capture",
    "spans the whole workout",
]
HR_RULES_ON_EACH_TOOL = [
    "THE TWO CASES:",
    "owned end to end",
    "on the watch",
    "NEVER cross-check",
    "Garmin/watch",
    "not worth mentioning",
]

# `file://` resources each server's instructions name. Pinned, so the resource
# test cannot pass on instructions that name nothing.
RESOURCES_NAMED = {"journal": 1, "coach": 2, "hr": 0}
# Captures a trailing slash, never a sentence's final period.
_RESOURCE_URI = re.compile(r"file://[A-Za-z0-9_\-/]+")

_ARGS_HEADING = re.compile(r"^Args:$", re.MULTILINE)
_ARGS_ENTRY = re.compile(r"^(\w+)(\s*\([^)]*\))?:(.*)$")


@pytest.fixture(scope="module")
def servers(tmp_path_factory):
    """`{server: FastMCP server}` for all three servers, each built on an
    initialised, empty synthetic database."""
    root = tmp_path_factory.mktemp("mcp_descriptions")
    paths = {}
    for name, module in (("journal", journal), ("coach", coach), ("hr", hr)):
        paths[name] = root / f"{name}.db"
        module.init_database(DbAccessor(paths[name]))
    return {
        "journal": create_journal_server(JournalConfig(db_path=paths["journal"])),
        "coach": create_coach_server(CoachConfig(db_path=paths["coach"])),
        "hr": create_hr_server(HrConfig.from_db_path(paths["hr"])),
    }


@pytest.fixture(scope="module")
def listed_tools(servers):
    """`{server: {tool name: listed tool}}` for all three servers."""
    return {name: {tool.name: tool for tool in list_tools(server)}
            for name, server in servers.items()}


@pytest.fixture(scope="module")
def instructions(servers):
    """`{server: instructions text}` as each server's initialize handshake
    delivers it to a client."""
    return {name: server_instructions(server) for name, server in servers.items()}


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

    @pytest.mark.parametrize(("server", "name"), _CASES)
    def test_description_fits_the_client_cut(self, listed_tools, server, name):
        """Claude Code cut every description at 2,048 characters before v2.1.295;
        text past the cut never reaches the model."""
        length = len(listed_tools[server][name].description)
        assert length <= MAX_DESCRIPTION_CHARS, (
            f"description is {length} characters, over the client's "
            f"{MAX_DESCRIPTION_CHARS}-character cut: move a standing rule to the "
            "server's instructions or tighten the wording")

    def test_update_exercise_names_every_segment_key_and_role(self, listed_tools):
        """The `updates` text is the only coach docstring that lists segment
        keys; a key or role the server accepts but its `segments` entry omits is
        one the model never writes. Only the text from that entry on is
        searched, so a word elsewhere in `updates` cannot stand in for it."""
        listed = listed_tools["coach"]["update_exercise"]
        text = listed.parameters["properties"]["updates"].get("description") or ""
        marker = "`segments`:"
        assert marker in text, (
            f"update_exercise `updates` has no {marker!r} entry to check")
        segments = text[text.index(marker):]
        names = coach_plans.SEGMENT_KEYS + coach_plans.SEGMENT_ROLES
        missing = [n for n in names
                   if not re.search(rf"\b{re.escape(n)}\b", segments)]
        assert not missing, (
            f"update_exercise `updates` does not name segment key/role {missing}: "
            "add it to the `segments` entry")


_HR_TOOLS = [pytest.param(tool, id=f"hr:{tool}") for tool in EXPECTED_TOOLS["hr"]]


@pytest.mark.integration
class TestServerInstructions:
    @pytest.mark.parametrize("server", sorted(EXPECTED_COUNTS))
    def test_instructions_reach_the_client(self, instructions, server):
        """The model gets them at session start, before any tool description;
        a server without them starts the session as bare tool names."""
        text = instructions[server]
        assert text and text.strip(), "the initialize handshake carries no instructions"

    @pytest.mark.parametrize("server", sorted(EXPECTED_COUNTS))
    def test_instructions_fit_the_client_cut(self, instructions, server):
        length = len(instructions[server] or "")
        assert length <= MAX_INSTRUCTIONS_CHARS, (
            f"instructions are {length} characters, over the client's "
            f"{MAX_INSTRUCTIONS_CHARS}-character cut")

    @pytest.mark.parametrize("server", sorted(EXPECTED_COUNTS))
    def test_named_resources_are_written_as_listed(self, servers, instructions, server):
        """FastMCP lists `file://x` as `file://x/`, and Claude Code's resource
        reader rejects the bare form: a pointer must match the listing exactly."""
        named = _RESOURCE_URI.findall(instructions[server] or "")
        assert len(named) == RESOURCES_NAMED[server], (
            f"the instructions name {named}, expected {RESOURCES_NAMED[server]}")
        listed = resource_uris(servers[server])
        unlisted = [uri for uri in named if uri not in listed]
        assert not unlisted, (
            f"named but not listed verbatim: {unlisted}; the server lists {listed}")

    @pytest.mark.parametrize("marker", HR_RULES_IN_INSTRUCTIONS)
    def test_hr_instructions_carry_the_standing_rules(self, instructions, marker):
        assert marker in _normalise(instructions["hr"] or ""), (
            f"the HR instructions lost {marker!r}: they carry the full two-case "
            "and never-cross-check rules")

    @pytest.mark.parametrize("name", _HR_TOOLS)
    def test_each_hr_description_states_the_rules(self, listed_tools, name):
        """A description is read on its own, so each HR tool keeps a one-line
        form of both standing rules, not merely a pointer to the instructions."""
        description = _normalise(listed_tools["hr"][name].description)
        missing = [m for m in HR_RULES_ON_EACH_TOOL if m not in description]
        assert not missing, f"the one-line standing rules lost {missing}"

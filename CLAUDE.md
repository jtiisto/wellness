# Wellness

@docs/conventions/universal.md
@docs/conventions/web-service.md

Codex and other tools that don't follow `@` imports: read the two files above first;
they are the rules this file builds on. They are generated mirrors of the private
conventions repo (`docs/conventions/README.md`); never edit them here.

Monorepo: the FastAPI server + PWA live at the repo root; the native Android
client lives in `android/` (grafted 2026-08 with its full history — a former
sibling repo). A protocol change is ONE atomic commit across server, PWA, and
Android. `android/CLAUDE.md` carries the Android client; this file carries the
shared ground.

## Pointer map

| Area | Where |
|------|-------|
| Server + PWA | `src/`, `public/`, `mcp_servers/`, `test/` |
| Android client | `android/` — own CLAUDE.md, Gradle tree, `specs/`, `testdata/golden/` |
| Sync protocols & data models | `docs/ARCHITECTURE.md` — authoritative for BOTH clients ("the porting bible"); `android/specs/*` defer to it |
| Shared wire-contract fixtures | `test/hr/golden/` — ONE directory read by pytest AND the Android golden tests (staged onto the Gradle test classpath); never duplicate it |
| Visual design authority | `docs/conventions/logbook-design.md` — a generated mirror of the private conventions repo's Logbook module (`../conventions/bin/sync-conventions.sh`); never edit it here. The wellness-specific implementation spec is `android/specs/logbook-design-system.md` |
| Plan docs | `plans/` — gitignored, local-only, never committed (the repo is public). The Android plan lives at `plans/android-plan.md` |

## Shared ground (both clients — non-negotiable)

- Server timestamps are **opaque strings** compared lexically — never parsed as
  time in sync code (never `Instant.parse` on Android). Calendar dates are local
  `YYYY-MM-DD`; weekdays 0=Sun…6=Sat.
- Optional wire fields are **omitted, never null**; unknown fields round-trip
  verbatim on both clients (journal tracker `meta_json` passthrough).
- Fixture dates use the far-future `2030-01-*` convention so they can never
  collide with real event dates.
- **This repo is PUBLIC** (Forgejo origin, mirrored to GitHub). The
  personal-data scan (`bin/scan_personal_data.py`) runs on every commit and covers
  `android/**` staged content like everything else; the full-tree rescan runs in
  the pre-push gate.
- Hooks are path-scoped: android-only changes run the Gradle gates, server/PWA-only
  changes run the pytest gates (and the JS unit tests under Node.js), and a protocol
  commit touching both runs both — by design. The hooks call plain `python3`, so
  commit with the venv active.
- Coverage gate: `--cov-fail-under=88` (server/PWA). Python dependencies are pinned
  in `constraints.txt`; install with `pip install -r requirements.txt -c
  constraints.txt` and regenerate the pins only from a venv that passed the full
  suite (`docs/INSTALLATION.md`).
- Ports: 9000 production, 9001 `bin/server.sh --test`. Production runs on another
  host, deployed by the ops instance; this machine runs the test server only when a
  stack-wide change needs one.

## Documentation

This project has four documents that must stay in sync with the code:

| Document | Covers | Update when... |
|----------|--------|----------------|
| [README.md](README.md) | High-level module descriptions, quick start, project structure tree | Adding/removing modules, changing the project structure, or modifying server control |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Sync protocols, data models, design decisions, MCP behavior, testing patterns | Changing sync logic, adding/removing database tables, modifying MCP tools, or altering the analysis pipeline |
| [docs/INSTALLATION.md](docs/INSTALLATION.md) | Setup, deployment, Tailscale config, MCP server configuration | Changing dependencies, environment variables, or deployment steps |
| [android/CLAUDE.md](android/CLAUDE.md) | Android module structure, Kover gate, emulator/ADB workflow | Changing the Android build, test harness, or dev-environment workflow |

`docs/conventions/` is not one of the four: its files are generated mirrors of the private conventions repo. A shared-rule change is a commit there plus a sync here (see `docs/conventions/README.md`), and `test/test_conventions_mirror.py` enforces it.

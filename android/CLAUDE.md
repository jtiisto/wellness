# Wellness Native Android App

## Overview
Native Android client for the Wellness system (Journal, Coach, Trends, Analysis), working against the same FastAPI server APIs as the PWA at `~/dev/health/wellness`. Android client only — no server changes in this repo.

## Tech Stack
- Kotlin + Jetpack Compose, Material 3
- minSdk 35 (Pixel 10+)
- Ktor (HTTP), Koin (DI), kotlinx.serialization
- Single Room database with isDirty + dirtyGeneration flags
- MVI architecture with StateFlow
- Package: `dev.jtiisto.wellness`

## Module Structure
- `build-logic/` — convention plugins (`wellness.android.application|library|feature`); module config lives here, not copy-pasted
- `app/` — MainActivity, nav shell, Koin bootstrap, WorkManager init
- `core/data/` — Room, Ktor, DTOs, repositories, sync engine (headless-testable; no Compose deps)
- `core/ui/` — M3 theme + shared composables
- `feature/{journal,coach,trends,analysis}/` — ViewModels + screens only; features depend on `core/*`, never on each other; UI never touches DAOs

## Protocol Conventions (non-negotiable)
- Server timestamps are **opaque strings** compared lexically — never `Instant.parse` in sync code.
- Calendar dates are local `YYYY-MM-DD` strings; weekdays 0=Sun…6=Sat.
- Optional wire fields are **omitted, never null**; shared `Json { ignoreUnknownKeys; explicitNulls=false; encodeDefaults=false }`.
- Journal tracker unknown fields must round-trip verbatim (meta_json passthrough).
- Golden fixtures in `testdata/golden/` are **synthetic only** — never copied from live databases.

## Spec-Driven Development

All changes follow a spec-first workflow. No implementation begins without an agreed-upon spec.

### Process
1. **Define** — Before any code change, create or update a spec document in `specs/`. The spec is co-authored between the user and Claude through discussion.
2. **Agree** — The spec must be explicitly approved by the user before implementation starts.
3. **Implement** — Code is written to satisfy the spec. Reference the spec in commits.
4. **Evolve** — Specs are living documents. If implementation reveals the spec needs to change, update the spec first, get agreement, then continue.

### Spec Format
Each spec lives in `specs/` as a markdown file named after the feature or component (e.g., `specs/journal-sync.md`, `specs/core-network.md`).

A spec should include:
- **Goal** — What this achieves and why
- **API / Interface** — Public contracts (function signatures, data models, endpoints)
- **Behavior** — How it works, edge cases, error handling
- **Dependencies** — What it requires and what depends on it
- **Open Questions** — Unresolved decisions (tracked until resolved)

### Rules
- Never implement a feature without a spec. If no spec exists, create one first.
- If the user describes a change conversationally, capture it as a spec before coding.
- When a spec and the code disagree, the spec is the source of truth — update the code, or update the spec first if the code is right.
- Specs do not need to be exhaustive upfront. They grow as understanding grows.

## Build & Quality Harness
- Git hooks are tracked in `githooks/` (activate per-clone: `git config core.hooksPath githooks`). Pre-commit: change-scoped `./gradlew testDebugUnitTest`. Pre-push: full suite + `koverVerifyAggregated`; docs-only pushes skip; override with `WELLNESS_FULL_PUSH=1`.
- Commits/pushes go through `bin/git-commit-push.sh` (detached; see `~/dev/CLAUDE.md`).
- Room schemas are exported and committed under `core/data/schemas/`.
- Instrumented tests run only in emulator sessions (see ADB skills in `~/dev/native/CLAUDE.md`), never in hooks.
- When the emulator is unavailable, ship the debug APK for manual testing: `rclone copyto app/build/outputs/apk/debug/app-debug.apk "gdrive:Wellness/APKs/wellness-debug-<yyyymmdd-hhmm>.apk"` (plus a `wellness-debug-latest.apk` copy), then tell the user what to verify.

## Key Docs
- `plan.md` — Architecture plan and implementation phases (Phases 0–8)
- `~/dev/health/wellness/docs/ARCHITECTURE.md` — server/PWA sync protocols and data models (the porting bible)
- `~/dev/health/wellness/test/js/` — behavioral spec for all ported pure logic
- `docs/dev-environment-setup.md` — Linux build machine + Windows emulator setup
- `specs/` — Living spec documents (spec-driven development)

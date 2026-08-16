# Wellness Native Android App

## Overview
Native Android client for the Wellness system (Journal, Coach, Trends, Analysis), working against the same FastAPI server APIs as the PWA at this repo's root. Android client only — server changes happen at the root, and a protocol change lands as one atomic commit across server, PWA, and this tree (the point of the 2026-08 monorepo graft).

## Dev Environment
- **Build machine:** Linux server (where Claude Code runs, code lives, Gradle builds)
- **Emulator:** Windows laptop running Android Studio emulator
- **Connection:** ADB over TCP via Tailscale network (port 5555)
- **JDK:** OpenJDK 21
- **Android SDK:** `~/android-sdk`, API 35
- ADB skills for emulator interaction: `/adb-connect`, `/adb-deploy`, `/adb-logs`, `/adb-reconnect`, `/adb-status`, `/adb-clear` (local `.claude/skills/` at the repo root; machine-specific, untracked)
- `local.properties` (untracked) sets `sdk.dir`; `wellness.baseUrl` there overrides the compiled-in server URL — the tracked default is a placeholder, so dev builds MUST set it (see `local.properties.template`)

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
- Golden fixtures are **synthetic only** — never copied from live databases; fixture dates use the far-future `2030-01-*` convention. Coach/journal goldens live in `testdata/golden/`; the HR goldens are SHARED with the server at the repo root's `../test/hr/golden/` — one directory both suites read (a Sync task in `core/data` stages it onto the test classpath), so never create a second copy.

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
- Git hooks live at the REPO ROOT (`../githooks/`, path-scoped: android changes run the Gradle gates, server changes run pytest, both run both). This tree's own `githooks/` and `bin/git-commit-push.sh` retired at the graft — commits/pushes go through the root `bin/git-commit-push.sh` (detached; see `~/dev/CLAUDE.md`), and the root personal-data scan covers `android/**` staged content on every commit.
- All Gradle commands run from `android/` (this directory is the Gradle root).
- Room schemas are exported and committed under `core/data/schemas/`.
- Instrumented tests run only in emulator sessions (see the ADB skills above), never in hooks.
- **Kover gotcha**: BOTH filtered `--tests "*Foo*"` runs AND module-scoped invocations (`:core:ui:testDebugUnitTest`) poison kover artifacts — module-scoped runs write execution data WITHOUT the root report filters, so later aggregated runs count excluded packages. Symptoms: wildly wrong `koverVerifyAggregated` numbers, or an XML report disagreeing with the verify task in the same invocation. **The XML disagreement has a second, benign cause: `koverXmlReportAggregated` does NOT apply the `annotatedBy(@Composable)` filter, so its percentage counts every composable line — the verify task is the truth; read the real number by momentarily raising `minBound` to 100 and reading the violation message (restore it after).** **Including `build` in the same invocation ALSO yields unfiltered verify numbers** (measured on clean `git archive` trees, Phase 8: 70.23% with `build` in the graph vs 88.42% without, same tree) — the gate authority is the pre-push hook's own command, `./gradlew testDebugUnitTest koverVerifyAggregated`; run `./gradlew build assembleDebugAndroidTest` SEPARATELY for compile/lint/instrumented-compile. Recovery from real poisoning: deleting kover dirs alone is NOT enough — (a) the `*/build/kover` glob misses two-level modules (use `*/build/kover */*/build/kover`, same for the other globs), (b) up-to-date/build-cached test tasks never regenerate execution data, so also delete `*/build/tmp/koverCachedVerify*` and `*/build/test-results/testDebugUnitTest` at both depths, and (c) run with `--no-build-cache --rerun-tasks` — `--no-build-cache` alone still serves up-to-date poisoned artifacts. Also: Kover's `annotatedBy(@Composable)` filter cannot see capturing lambdas passed to non-inline composable wrappers — make layout-wrapper composables `inline` (as Compose's own Box/Row are) or they add counted-but-uncoverable lines.
- When the emulator is unavailable, ship the debug APK for manual testing (run from `android/`): `rclone copyto app/build/outputs/apk/debug/app-debug.apk "gdrive:Wellness/APKs/wellness-debug-<yyyymmdd-hhmm>.apk"` (plus a `wellness-debug-latest.apk` copy), then tell the user what to verify.

## Key Docs
- `plans/android-plan.md` (repo root; gitignored, local-only) — architecture plan and implementation phases (Phases 0–8), relocated from this tree's tracked `plan.md` at the graft
- `../docs/ARCHITECTURE.md` — server/PWA sync protocols and data models (the porting bible)
- `../test/js/` — behavioral spec for all ported pure logic
- `docs/dev-environment-setup.md` — Linux build machine + Windows emulator setup
- `specs/` — Living spec documents (spec-driven development)

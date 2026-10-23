# Wellness Native Android App

@../../conventions/universal.md
@../../conventions/android.md

Codex and other tools that don't follow `@` imports: read the two files above first;
they are the rules this file builds on.

## Overview
Native Android client for the Wellness system (Journal, Coach, Trends, Analysis), working against the same FastAPI server APIs as the PWA at this repo's root. Android client only — server changes happen at the root, and a protocol change lands as one atomic commit across server, PWA, and this tree (the point of the 2026-08 monorepo graft).

## Dev Environment
- `local.properties` (untracked) sets `sdk.dir`; `wellness.baseUrl` (debug/release) and `wellness.dev.baseUrl` (the `dev` build type) override the compiled-in server URLs — the tracked defaults are placeholders, so builds MUST set the key for the variant they build (see `local.properties.template`)
- Log tags for `/adb-logs`: `WellnessSync` (sync engine), `WellnessNet` (network/HTTP), `WellnessDB` (database).

## Tech Stack
- Kotlin + Jetpack Compose, Material 3
- minSdk 35 (Pixel 10+)
- Ktor (HTTP), Koin (DI), kotlinx.serialization
- Glance 1.2 (home-screen widget)
- Single Room database with isDirty + dirtyGeneration flags
- MVI architecture with StateFlow
- Package: `dev.jtiisto.wellness`

## Module Structure
- `build-logic/` — convention plugins `wellness.android.application|library|feature`
- `app/` — MainActivity, nav shell, Koin bootstrap, WorkManager init, home-screen widget (Glance; `widget/`, spec: `specs/widget.md`)
- `core/data/` — Room, Ktor, DTOs, repositories, sync engine (headless-testable; no Compose deps)
- `core/ui/` — M3 theme + shared composables
- `feature/{journal,coach,trends,analysis}/` — ViewModels + screens only; features depend on `core/*`, never on each other; UI never touches DAOs

## Protocol Conventions
The wire rules are "Shared ground" in the root `CLAUDE.md`. Here they mean:
- One shared `Json { ignoreUnknownKeys; explicitNulls=false; encodeDefaults=false }`.
- Coach/journal goldens live in `testdata/golden/`; the HR goldens are the root's `../test/hr/golden/`, staged onto the test classpath by a Sync task in `core/data` — never a second copy.

## Specs
Specs live in `specs/`, one markdown file per feature or component.

A spec includes:
- **Goal** — What this achieves and why
- **API / Interface** — Public contracts (function signatures, data models, endpoints)
- **Behavior** — How it works, edge cases, error handling
- **Dependencies** — What it requires and what depends on it
- **Open Questions** — Unresolved decisions (tracked until resolved)

## Build & Quality Harness
- Git hooks live at the REPO ROOT (`../githooks/`, path-scoped: android changes run the Gradle gates, server changes run pytest, both run both); commits and pushes run from the repo root, and the root personal-data scan covers `android/**` staged content on every commit.
- All Gradle commands run from `android/` (this directory is the Gradle root).
- Room schemas are exported and committed under `core/data/schemas/`.
- Kover gate: `minBound(85)` in `build.gradle.kts`.
- APK channel: `gdrive:Wellness/APKs/wellness-debug-<yyyymmdd-hhmm>.apk`, plus a `wellness-debug-latest.apk` copy.
- **The `dev` build type** (`:app` only — libraries keep debug/release and resolve through `matchingFallbacks`): `./gradlew assembleDev` → `app/build/outputs/apk/dev/app-dev.apk`, applicationId `dev.jtiisto.wellness.dev`, versionName `-dev`, "Wellness Dev" and a teal launcher icon. It **installs alongside** the daily driver and shares nothing with it — own Room database, DataStore, WorkManager queue, server address book, FileProvider authority — which is what makes it safe to device-test a UI round. It is born pointing at the test server via `wellness.dev.baseUrl` (see above), never `wellness.baseUrl`. No gate command changes: the variant is invisible to `testDebugUnitTest koverVerifyAggregated` and to the emulator `debug` workflow. Spec: `specs/dev-app-variant.md`.
- Dev APKs ride their own gdrive channel, never overwriting the prod one: `rclone copyto app/build/outputs/apk/dev/app-dev.apk "gdrive:Wellness/APKs/wellness-dev-<yyyymmdd-hhmm>.apk"` (plus a `wellness-dev-latest.apk` copy).

## Key Docs
- `plans/android-plan.md` (repo root; gitignored, local-only) — architecture plan and implementation phases (Phases 0–8), relocated from this tree's tracked `plan.md` at the graft
- `../docs/ARCHITECTURE.md` — server/PWA sync protocols and data models (the porting bible)
- `../test/js/` — behavioral spec for all ported pure logic
- `docs/dev-environment-setup.md` — Linux build machine + Windows emulator setup
- `specs/` — Living spec documents (spec-driven development)
- `../docs/conventions/logbook-design.md` — the Logbook design language's authority (generated mirror of the private conventions repo; never edit it here); `specs/logbook-design-system.md` is this app's implementation of it

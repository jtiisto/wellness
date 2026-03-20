# Wellness Native Android App

## Overview
Native Android client for the Wellness system (Journal, Coach, Analysis), working against the same FastAPI server APIs as the PWA.

## Tech Stack
- Kotlin + Jetpack Compose, Material 3
- minSdk 35 (Pixel 10+)
- Ktor (HTTP), Koin (DI), kotlinx.serialization
- Single Room database with isDirty flags
- MVI architecture with StateFlow
- Package: `dev.jtiisto.wellness`

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

## Project Structure
See `plan.md` for the full architecture plan, module structure, and implementation phases.

## Key Docs
- `plan.md` — Architecture plan and implementation phases
- `docs/dev-environment-setup.md` — Linux + Windows dev environment setup
- `docs/figma-design-workflow.md` — Figma + Google Stitch UI design guide
- `specs/` — Living spec documents (spec-driven development)

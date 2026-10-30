# docs/conventions — mirrored conventions modules

The shared conventions that govern this project live in a private sibling repo
(`../conventions` from this repo's root). This repo is public, so a clone of it
cannot follow a link into that repo. Every module this project depends on is
therefore **mirrored** here as a verbatim copy.

| File | What it is |
|---|---|
| `MODULES` | The modules to mirror, one path per line, relative to the conventions repo. The only hand-edited file here. |
| `MANIFEST` | Generated. Each mirrored file's sha256 and the conventions commit it was copied from. |
| `universal.md` | Generated. How we work: owner, plan/spec/build, agents, tests, version control, secrets. Imported by the root `CLAUDE.md`. |
| `web-service.md` | Generated. The FastAPI + PWA stack, serving and configuration rules. Imported by the root `CLAUDE.md`. |
| `android.md` | Generated. The native Android rules: coverage gate, devices, build box. Imported by `android/CLAUDE.md`. |
| `logbook-design.md` | Generated. The Logbook visual-design language: principles, tokens, type ramp, space and shape, shell, motion, launcher icon. |

**Never edit a mirrored file here.** To change a shared rule:

1. Edit the module in the conventions repo and commit it there.
2. From this repo's root, run `../conventions/bin/sync-conventions.sh`.
3. Commit the refreshed copy and `MANIFEST` here.

To mirror another module, add its path to `MODULES` and run the sync.

`test/test_conventions_mirror.py` enforces this. It fails when a copy no longer
matches `MANIFEST` (an edit made here), when the copy differs from the conventions
repo's HEAD (a stale mirror; skipped in a clone without the sibling repo), when
the Android palette's hex values disagree with the mirrored token table, and when
the Android Kover gate settings drift from `android.md` (configuration cache off in
`android/gradle.properties`, `--no-build-cache` on the pre-push gate line — via the
conventions repo's `bin/check-android-gate.sh`, with an inline fallback). The
pre-commit hook runs it whenever this directory or the palette is staged.

What is specific to wellness stays in its own specs: the Android implementation of
the design language is `android/specs/logbook-design-system.md`.

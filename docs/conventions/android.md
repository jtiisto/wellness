# Android apps

Native Android work: Kotlin, Jetpack Compose and Material 3, Gradle with convention
plugins, Koin for DI. Imported by every project that has an Android build.

## Build
- Module configuration lives in convention plugins under `build-logic/` and versions
  in `gradle/libs.versions.toml`; never copy-paste build config between modules.
- Gradle runs from the project's Gradle root. One Gradle invocation at a time on a
  build machine; `./gradlew --stop` when a session's builds are done. If the
  machine's memory guard kills background Gradle at daemon start-up, run the gate
  in the foreground.
- `local.properties` is untracked: `sdk.dir` plus the project's own keys, documented
  in a tracked `local.properties.template` or the project's `CLAUDE.md`.
- Run `./gradlew :app:lintVitalRelease` after any manifest or resource change; debug
  builds never run it and release builds fail on it.

## Coverage (Kover)
- Device-only glue (services, sinks, Android wrappers, DI modules, theme,
  `@Composable` functions, declared drawing-only packages) is excluded; everything
  else counts. Keep glue thin and decision-free so every decision is reachable by a
  JVM test with fakes and virtual time. Draw lambdas and `Modifier` extensions are
  not composables and do count: put drawing-only code in an excluded package and
  test the arithmetic it uses in a pure file.
- Kover's `annotatedBy(@Composable)` filter can't see capturing lambdas passed to
  non-inline composable wrappers; make layout-wrapper composables `inline`, as
  Compose's own `Box` and `Row` are.
- The gate is `minBound(N)` in the root `build.gradle.kts`; N is a project fact. The
  verify task is the truth. `koverXmlReportAggregated` ignores the `@Composable`
  filter and under-reports; read the true number by raising `minBound` to 100 for
  one run and reading the violation, then restore it.
- The gate's authority is the pre-push command, `./gradlew testDebugUnitTest
  koverVerifyAggregated --no-build-cache`. Run `build` and other tasks in a separate invocation:
  `build` in the same one yields unfiltered numbers (measured 70.23 % vs 88.42 % on
  the same tree).
- **The configuration cache stays off** (`org.gradle.configuration-cache=false` in
  `gradle.properties`). With it on, `koverVerifyAggregated` run by a daemon that
  reuses the entry without having configured the project in that process verifies
  **without the root filters**: the excluded glue and the `@Composable` lambdas
  count as uncovered and the gate fails in about a second with a wrong, lower
  number (share, 2026-10-25: 93.14 % instead of 99 %, the same number every time).
  It shows only when the verify has to recompute, i.e. after a build that executed
  tests, which is exactly the hooks' sequence (pre-commit runs the tests, pre-push
  verifies in a fresh daemon). Isolated with daemon restarts: a fresh daemon that
  reuses the entry fails, the same daemon after one `--no-configuration-cache`
  build passes, and with the cache off a fresh daemon passes. This was the
  "poisoning" earlier blamed on filtered and module-scoped runs; every recovery
  (`--rerun-tasks`, a stored entry) worked by configuring the project in-process.
  The execution data is not involved, but **the build cache is**:
  `koverCachedVerifyAggregated` is a cacheable task whose verdict file is keyed
  without the root filters, so a filter-less verdict once stored comes back from
  `~/.gradle/caches/build-cache-1` on every later run — after `clean`, and even
  with the configuration cache off (wellness, 2026-10-29: 83.19 % instead of a
  pass, three identical failures in about a second with 127 tasks `FROM-CACHE`;
  `--no-build-cache` alone made the same tree pass in 37 s). So the pre-push gate
  runs with `--no-build-cache`: the verdict is computed in the run that reports
  it, on tests that actually ran. If a gate ever fails in about a second at a
  number that repeats exactly, check these two settings first.
  `bin/check-android-gate.sh <project-root>` verifies both; a project's guard test
  runs it, and `ADOPTION.md` says which projects have.

## Devices
- **The usual path:** a headless emulator on the build machine for every device
  test, then a live test on the owner's phone by side-loading the APK (see "Without
  an emulator" below for the upload). The Windows laptop's Android Studio emulator,
  over Tailscale ADB, is an occasional fallback: use it only when a test really needs
  it, and ask the owner first, since it is often unavailable.
- ADB skills: `/adb-deploy`, `/adb-logs`, `/adb-status` and `/adb-clear` work on
  any connected emulator; `/adb-connect` and `/adb-reconnect` are for the laptop
  fallback only. They live in `skills/` here; `bin/install-skills.sh` links them
  into `~/.claude/skills/` once per machine. The laptop's address is a per-machine
  file, `~/.config/adb/laptop`, never in a repo. Each project's `CLAUDE.md` names its
  applicationId, its Gradle root and its log tags.
- **Install honestly:** `adb uninstall` then `adb install`; `install -r` can silently
  no-op and leave a stale APK (it once masked a working fix through several rounds).
  Compare the installed `base.apk` md5 with the file before trusting a result.
- Drive the UI with `uiautomator dump` (tap by content-desc); read crashes with
  `adb logcat -b crash`.
- Every control is **one** accessibility node carrying both `clickable="true"` and
  its `content-desc`; hidden controls stay reachable. One exception: the selected
  item of a tab group (`selectable` with `Role.Tab`) reports `selected="true"` and
  `clickable="false"`, which is Compose's own tab semantics and what TalkBack should
  announce; it is found by the same description and never needs driving (share M2,
  owner 2026-10-23). A described node with readable children gets its description
  moved to a fake child by Compose, so a control clears its children's semantics
  (`clearAndSetSemantics`) and describes its own node. A text field cannot (its
  editable text is what is read), so a text field's description sits on a fake
  child with the same bounds as the editable node; it is found and tapped by that
  description all the same (share M3, owner 2026-10-24).
- Headless emulators need KVM: the user must be in the `kvm` group (`sudo gpasswd -a
  $USER kvm`, once per machine; `emulator -accel-check` confirms). A shell started
  before that change boots through `sg kvm -c "…"`. Flags: `-no-window -no-audio
  -no-boot-anim -gpu swiftshader_indirect -no-snapshot`;
  let the guest settle about 60 s. Bracket `pgrep`/`pkill -f` patterns
  (`'[q]emu-system'`) or they match and kill the invoking shell.
- Instrumented tests run only in emulator sessions, never in hooks.
- **Nothing is done until it has run on a device.** Each project's smoke path runs on
  an emulator before a milestone is called done, and on the owner's phone before a
  tagged release. Every defect a device run finds becomes a fix with a regression
  test and a line in the smoke checklist stating the observation that proves it
  fixed.
- Without an emulator, ship the APK and tell the owner what to verify:
  `rclone copyto <apk> "gdrive:<App>/APKs/<app>-<variant>-<yyyymmdd-hhmm>.apk"`.
 

## Release signing
- One PKCS12 keystore per app at `~/.keystores/<app>-release.jks`, alias `<app>`,
  never committed, backed up off the machine. Losing it breaks in-place upgrades,
  and after Play App Signing enrolment it is the upload key.
- The build reads `<app>.keystore`, `<app>.keystorePassword`, `<app>.keyAlias` and
  `<app>.keyPassword` from `~/.gradle/gradle.properties`, per machine and outside
  every repo, so a fresh clone signs without a copy step. Missing keys leave the
  release build unsigned; it still builds.
- `~/.keystores/<app>.signing.properties` holds each app's four lines for setting up
  a machine.

# Spec: Parallel-Installable Dev App Variant

Status: **approved 2026-08-17 — implemented** (spec-first gate). Built and gate-verified
on the build machine; the on-device half of Verification (both installs coexisting, dev
syncing against the test server) is the Logbook round's Phase 5.

## Goal

A dev version of the app installable **alongside** the daily-driver app on the
phone, so UI rounds (first consumer: the Logbook re-theme,
[logbook-design-system.md](logbook-design-system.md)) can be device-tested
without touching the production install or its data.

Isolation comes from a distinct `applicationId`. `ServerBootstrap`'s own KDoc
documents why one install must never casually switch servers — Room still holds
the previous server's dirty rows, and an upload would push server B's logs into
server A. A parallel install sidesteps the whole class: own Room database, own
DataStore, own WorkManager queue, own server address book, own FileProvider
authority (the manifest already uses `${applicationId}.fileprovider`), own
notification channels. Nothing is shared with the prod install.

## Mechanism: a third build type, `dev`, on `:app` only

```kotlin
create("dev") {
    initWith(getByName("debug"))
    applicationIdSuffix = ".dev"       // dev.jtiisto.wellness.dev
    versionNameSuffix = "-dev"
    matchingFallbacks += "debug"       // libraries resolve their debug variant
    signingConfig = signingConfigs.getByName("debug")
    buildConfigField("String", "BUILD_STAMP", "\"${buildStamp(appVersionName, isRelease = false)}\"")
}
```

Why a build type and not product flavors: flavors would rename every variant
(`debug` → `prodDebug`/`devDebug`) across every module, breaking the documented
gate commands (`testDebugUnitTest koverVerifyAggregated`,
`build assembleDebugAndroidTest`), the pre-push hook, the Kover aggregation,
and the ADB emulator skills. A `:app`-only build type with `matchingFallbacks`
leaves **all of those untouched**: libraries keep two variants, the gate
command names don't change, the emulator workflow keeps installing `debug`.
The only new surface is `./gradlew assembleDev` →
`app/build/outputs/apk/dev/app-dev.apk`.

(The new build type's `:app` source set is `app/src/dev/`.)

## Server targeting (the actual prod-safety mechanism)

The compiled-in URL currently lives in `:core:data`'s BuildConfig
(`WELLNESS_BASE_URL` from `wellness.baseUrl` in `local.properties`). Since the
dev app consumes `:core:data`'s **debug** variant, a per-app-variant URL cannot
stay there. It moves to `:app`:

- `:app` gains per-build-type `buildConfigField("String", "WELLNESS_BASE_URL", …)`:
  - `debug`/`release`: `wellness.baseUrl` (unchanged semantics; tracked
    default stays a placeholder).
  - `dev`: **`wellness.dev.baseUrl`** — a new `local.properties` key pointing
    at the test server (`http://<tailnet-host>:9001/wellness`, served by
    `bin/server.sh --test` from this working tree); placeholder default, same
    rule as prod: a working build MUST set it. A missing file or key compiles
    the placeholder (a clean clone builds, reaches nothing); a key set to a
    blank value fails the build.
- `:core:data` drops its `WELLNESS_BASE_URL` BuildConfig field;
  `ServerBootstrap`'s `builtInUrl` is provided by `:app` at Koin startup (the
  app decides which server its process is born pointing at — architecturally
  where that decision belonged anyway).

The dev app's address book starts empty → resolves to its built-in = the test
server. Tools shows the nickname (`Built-in`) and `BUILD_STAMP` as today.
Cleartext HTTP inside the tailnet is already permitted app-wide
(`usesCleartextTraffic="true"`).

## Identity differentiation (never mistake the two on a phone)

- `app/src/dev/res/values/strings.xml`: `app_name` = **Wellness Dev**.
- `app/src/dev/res/` adaptive-icon override: same foreground, visibly
  different background color, so the launcher tells them apart at a glance.
- `versionNameSuffix "-dev"` shows in Tools next to the build stamp.

## Behavior

- No code paths branch on the variant beyond the compiled-in URL — dev is
  prod-identical logic against a different server and its own data.
- Both installs may coexist and run; the HR strap can only hold one BLE
  connection, so simultaneous capture from both apps is not supported
  (documented caveat, not enforced).
- APK delivery convention extends the existing one: `rclone copyto … 
  wellness-dev-<yyyymmdd-hhmm>.apk` + a `wellness-dev-latest.apk` copy —
  never overwriting the prod-channel `wellness-debug-*` names.

## Verification

- `./gradlew assembleDev` builds; APK installs on a device already holding the
  prod app; both launch, dev shows "Wellness Dev" + its icon + `-dev` stamp.
- Dev app syncs against the test server; prod install untouched (its data and
  server config unchanged after dev install/uninstall).
- Full gate stays green and its commands stay byte-identical:
  `./gradlew testDebugUnitTest koverVerifyAggregated` and
  `./gradlew build assembleDebugAndroidTest` (note: `build` now also
  assembles the dev variant of `:app` — slower, not different).
- Emulator debug workflow unaffected (`assembleDebug`, same appId as before).

## Dependencies

None new. Doc updates in the same change: `android/CLAUDE.md` (build harness:
the variant, the `wellness.dev.baseUrl` key, the APK naming),
`local.properties.template` (new key + example).

## Open Questions

None.

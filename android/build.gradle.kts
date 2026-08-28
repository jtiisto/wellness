plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kover)
}

// Merged coverage across all modules; gated by the repo root's
// githooks/pre-push via koverVerifyAggregated. Composables and framework glue are excluded so the
// metric tracks unit-testable logic (no Compose UI test rig in this project).
kover {
    merge {
        allProjects()
        createVariant("aggregated") {
            add("debug", optional = true)
        }
    }

    reports {
        filters {
            excludes {
                classes(
                    "*BuildConfig",
                    "*_Impl",
                    "*_Impl$*",
                    "*ComposableSingletons*",
                    "*ModuleKt",
                    "dev.jtiisto.wellness.MainActivity",
                    "dev.jtiisto.wellness.WellnessApplication",
                    // Heart-rate capture glue: a foreground service, a
                    // notification builder, a GATT wrapper, a BLE scanner and a
                    // SharedPreferences map. None of them can execute off a
                    // device, and the emulator has no Bluetooth either — they
                    // are verified on the physical device via the APK flow.
                    // Everything in them that could be *wrong* was deliberately
                    // moved out and is covered: CaptureStartGate,
                    // HrCaptureNotificationText, InactivityPolicy,
                    // ConnectDiagnostics, HrmAdvertisementFilter,
                    // KnownDeviceStore and the whole of HrCaptureStore.
                    "dev.jtiisto.wellness.hr.HrCaptureService",
                    "dev.jtiisto.wellness.hr.HrCaptureService$*",
                    "dev.jtiisto.wellness.hr.ServiceHrCaptureController",
                    "dev.jtiisto.wellness.hr.ServiceHrCaptureController$*",
                    "dev.jtiisto.wellness.ui.tools.StrapSectionKt",
                    "dev.jtiisto.wellness.hr.HrCaptureNotification",
                    "dev.jtiisto.wellness.hr.HrCaptureNotification$*",
                    "dev.jtiisto.wellness.core.ble.scanner.BleScanner",
                    "dev.jtiisto.wellness.core.ble.scanner.BleScanner$*",
                    "dev.jtiisto.wellness.core.ble.connection.GarminHrmConnection",
                    "dev.jtiisto.wellness.core.ble.connection.GarminHrmConnection$*",
                    "dev.jtiisto.wellness.core.ble.device.PrefsKnownDeviceStorage",
                    "dev.jtiisto.wellness.core.ble.device.PrefsKnownDeviceStorage$*",
                    // Home-screen widget glue: a Glance session, an AppWidget
                    // provider, a WorkManager job and a process-lifecycle
                    // observer. None of them can execute off a device — they
                    // need a GlanceId, an AppWidget host, WorkManager's runtime
                    // or a real process lifecycle — and they are verified on the
                    // emulator against specs/widget.md §Size buckets.
                    // The exclusion is the usual claim: these files hold no
                    // decisions. Every one they would have made lives in
                    // TodayWidgetLogic (keys, fetch window, the 90-minute
                    // freshness rule, shouldFetch, buckets, the tally fit rule,
                    // drawable and tint mapping), TodayWidgetPalette, or the two
                    // pre-resolution-safe peeks in :core:data — all counted.
                    // TodayWidgetContent needs no entry: it is composables only.
                    "dev.jtiisto.wellness.widget.TodayWidget",
                    "dev.jtiisto.wellness.widget.TodayWidget$*",
                    "dev.jtiisto.wellness.widget.TodayWidgetReceiver",
                    "dev.jtiisto.wellness.widget.TodayWidgetWorker",
                    "dev.jtiisto.wellness.widget.TodayWidgetWorker$*",
                    "dev.jtiisto.wellness.widget.WidgetBackgroundRefresh",
                    "dev.jtiisto.wellness.widget.WidgetBackgroundRefresh$*",
                )
                packages(
                    "dev.jtiisto.wellness.core.ui.theme",
                )
                annotatedBy("androidx.compose.runtime.Composable")
            }
        }
        variant("aggregated") {
            verify {
                rule {
                    // Raised 80 → 85 at the end of Phase 8, measured in one
                    // clean full invocation (see CLAUDE.md: filtered or
                    // module-scoped runs poison the execution data). Measure,
                    // then raise; never lower without a deliberate decision.
                    minBound(85)
                }
            }
        }
    }
}

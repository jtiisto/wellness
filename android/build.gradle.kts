plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kover)
}

// Merged coverage across all modules; gated by githooks/pre-push via
// koverVerifyAggregated. Composables and framework glue are excluded so the
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

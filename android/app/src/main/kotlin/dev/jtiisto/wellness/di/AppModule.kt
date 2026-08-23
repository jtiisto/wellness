package dev.jtiisto.wellness.di

import dev.jtiisto.wellness.BuildConfig
import dev.jtiisto.wellness.core.ble.capture.HrCaptureController
import dev.jtiisto.wellness.core.ble.capture.HrCaptureState
import dev.jtiisto.wellness.core.ble.di.HrCaptureStateQualifier
import dev.jtiisto.wellness.core.ble.scanner.BleScanner
import dev.jtiisto.wellness.core.data.di.AppScope
import dev.jtiisto.wellness.core.data.di.AppVersionName
import dev.jtiisto.wellness.core.data.network.JournalApi
import dev.jtiisto.wellness.hr.ServiceHrCaptureController
import dev.jtiisto.wellness.ui.tools.StrapViewModel
import dev.jtiisto.wellness.ui.tools.ToolsCopy
import dev.jtiisto.wellness.ui.tools.ToolsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** UI-layer wiring the app shell owns. Features bring their own modules. */
val appModule = module {
    // :core:data builds the export envelope but must not depend on :app to
    // learn the version it is stamping, so the value is handed down instead.
    single(AppVersionName) { BuildConfig.VERSION_NAME }

    // The capture service is an :app component, so this is the only module that
    // can bind the interface both it and :feature:coach start captures through.
    single<HrCaptureController> { ServiceHrCaptureController(androidContext(), errors = get()) }

    viewModel {
        ToolsViewModel(
            probeSyncStatus = { get<JournalApi>().syncStatus().lastModified },
            serverConfig = get(),
            bootstrap = get(),
            profilesDao = get(),
            forceSyncOrchestrator = get(),
            exporter = get(),
            sharedFiles = get(),
            switcher = get(),
            // The switch outlives this ViewModel by design: it quiesces, wipes
            // and then ends the process, none of which may stop because the
            // user navigated away from the tab that started it.
            appScope = get(AppScope),
            debugLog = get(),
            // The stamped asset BuildStampTask wrote at assemble time — read
            // once per ViewModel, formatted for the device's locale and zone.
            // runCatching: an APK genuinely missing its own asset should show
            // "Build unknown", not crash the Tools tab.
            buildStamp = ToolsCopy.buildLabel(
                raw = runCatching {
                    androidContext().assets.open("build_stamp.txt")
                        .bufferedReader().use { it.readText() }
                }.getOrNull(),
                locale = java.util.Locale.getDefault(),
                zone = java.time.ZoneId.systemDefault(),
            ),
        )
    }

    viewModel {
        StrapViewModel(
            knownDevices = get(),
            // The one function of the scanner this screen needs, handed over as
            // a lambda: the ViewModel has no other business with a BLE class it
            // could not construct in a unit test anyway.
            scan = get<BleScanner>()::scan,
            controller = get(),
            // Resolved as the mutable type it was registered under and handed
            // over as read-only — Koin keys on the declared class, and the
            // qualifier is what keeps it distinct from the known-device list,
            // which erases to the same one. See `bleModule`.
            captureState = get<MutableStateFlow<HrCaptureState>>(HrCaptureStateQualifier),
        )
    }
}

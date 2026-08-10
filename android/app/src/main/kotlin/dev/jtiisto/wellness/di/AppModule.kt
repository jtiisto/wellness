package dev.jtiisto.wellness.di

import dev.jtiisto.wellness.BuildConfig
import dev.jtiisto.wellness.core.data.di.AppVersionName
import dev.jtiisto.wellness.core.data.network.JournalApi
import dev.jtiisto.wellness.ui.tools.ToolsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** UI-layer wiring the app shell owns. Features bring their own modules. */
val appModule = module {
    // :core:data builds the export envelope but must not depend on :app to
    // learn the version it is stamping, so the value is handed down instead.
    single(AppVersionName) { BuildConfig.VERSION_NAME }

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
            debugLog = get(),
            buildStamp = BuildConfig.BUILD_STAMP,
        )
    }
}

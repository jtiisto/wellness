package dev.jtiisto.wellness

import android.app.Application
import dev.jtiisto.wellness.core.data.di.coreDataModule
import dev.jtiisto.wellness.core.data.sync.ConnectivityMonitor
import dev.jtiisto.wellness.core.data.sync.SyncOrchestrator
import dev.jtiisto.wellness.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class WellnessApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val koin = startKoin {
            androidLogger()
            androidContext(this@WellnessApplication)
            modules(coreDataModule, appModule)
        }.koin

        // Process-lifetime sync plumbing: connectivity first, so the
        // orchestrator's first foreground event already sees the right state.
        koin.get<ConnectivityMonitor>().start()
        koin.get<SyncOrchestrator>().start()
    }
}

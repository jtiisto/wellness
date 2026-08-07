package dev.jtiisto.wellness

import android.app.Application
import dev.jtiisto.wellness.core.data.di.CoachScheduler
import dev.jtiisto.wellness.core.data.di.JournalScheduler
import dev.jtiisto.wellness.core.data.di.coreDataModule
import dev.jtiisto.wellness.core.data.sync.ConnectivityMonitor
import dev.jtiisto.wellness.core.data.sync.SyncOrchestrator
import dev.jtiisto.wellness.core.data.sync.SyncScheduler
import dev.jtiisto.wellness.di.appModule
import dev.jtiisto.wellness.feature.journal.di.journalModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class WellnessApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val koin = startKoin {
            androidLogger()
            androidContext(this@WellnessApplication)
            modules(coreDataModule, appModule, journalModule)
        }.koin

        // Process-lifetime sync plumbing: connectivity first, so the
        // orchestrator's first foreground event already sees the right state,
        // then every module scheduler, then the orchestrator itself.
        koin.get<ConnectivityMonitor>().start()
        val orchestrator = koin.get<SyncOrchestrator>()
        orchestrator.register(koin.get<SyncScheduler>(JournalScheduler))
        orchestrator.register(koin.get<SyncScheduler>(CoachScheduler))
        orchestrator.start()
    }
}

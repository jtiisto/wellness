package dev.jtiisto.wellness

import android.app.Application
import dev.jtiisto.wellness.core.ble.di.bleModule
import dev.jtiisto.wellness.core.data.di.coreDataModule
import dev.jtiisto.wellness.di.appModule
import dev.jtiisto.wellness.feature.analysis.di.analysisModule
import dev.jtiisto.wellness.feature.coach.di.coachModule
import dev.jtiisto.wellness.feature.journal.di.journalModule
import dev.jtiisto.wellness.feature.trends.di.trendsModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class WellnessApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val koin = startKoin {
            androidLogger()
            androidContext(this@WellnessApplication)
            modules(
                coreDataModule,
                // After :core:data, which binds the sample sink and the BLE
                // diagnostics bridge this module resolves.
                bleModule,
                appModule,
                journalModule,
                coachModule,
                trendsModule,
                analysisModule,
            )
        }.koin

        // Resolves which server this process talks to and, only then, starts
        // anything that could talk to it. A failure leaves everything unstarted
        // and the UI on a recovery screen; see [bootWellness].
        bootWellness(koin)
    }
}

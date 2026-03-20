package dev.jtiisto.wellness

import android.app.Application
import dev.jtiisto.wellness.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class WellnessApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@WellnessApplication)
            modules(appModule)
        }
    }
}

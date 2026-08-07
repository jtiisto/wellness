package dev.jtiisto.wellness.core.data.di

import androidx.room.Room
import dev.jtiisto.wellness.core.data.BuildConfig
import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.db.WellnessDatabase
import dev.jtiisto.wellness.core.data.network.JournalApi
import dev.jtiisto.wellness.core.data.network.ServerConfig
import dev.jtiisto.wellness.core.data.network.buildHttpClient
import dev.jtiisto.wellness.core.data.sync.ConnectivityMonitor
import dev.jtiisto.wellness.core.data.sync.DebugLog
import dev.jtiisto.wellness.core.data.sync.SyncOrchestrator
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Scope that outlives every activity: sync work must survive backgrounding and
 * configuration changes, and is only ever torn down with the process.
 */
val AppScope = named("appScope")

private val DebugLogScope = named("debugLogScope")

val coreDataModule = module {
    single<CoroutineScope>(AppScope) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single<CoroutineScope>(DebugLogScope) { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    single<Json> { WellnessJson }
    single { ServerConfig(BuildConfig.WELLNESS_BASE_URL) }

    single {
        Room.databaseBuilder(androidContext(), WellnessDatabase::class.java, WellnessDatabase.NAME).build()
    }
    single { get<WellnessDatabase>().debugLogDao() }
    single { get<WellnessDatabase>().payloadCacheDao() }

    single { DebugLog(dao = get(), scope = get(DebugLogScope), json = get()) }
    single<HttpClient> { buildHttpClient(config = get(), json = get(), debugLog = get()) }
    single { JournalApi(client = get(), config = get()) }

    single { ConnectivityMonitor(androidContext()) }
    single { SyncOrchestrator(scope = get(AppScope), connectivity = get()) }
}

package dev.jtiisto.wellness.core.data.di

import androidx.room.Room
import dev.jtiisto.wellness.core.data.BuildConfig
import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.db.WELLNESS_MIGRATIONS
import dev.jtiisto.wellness.core.data.db.WellnessDatabase
import dev.jtiisto.wellness.core.data.journal.JournalSyncStore
import dev.jtiisto.wellness.core.data.journal.JournalUiPrefs
import dev.jtiisto.wellness.core.data.network.JournalApi
import dev.jtiisto.wellness.core.data.network.ServerConfig
import dev.jtiisto.wellness.core.data.network.buildHttpClient
import dev.jtiisto.wellness.core.data.network.isNetworkError
import dev.jtiisto.wellness.core.data.sync.ConnectivityMonitor
import dev.jtiisto.wellness.core.data.sync.DebugLog
import dev.jtiisto.wellness.core.data.sync.SyncOrchestrator
import dev.jtiisto.wellness.core.data.sync.SyncErrorEvents
import dev.jtiisto.wellness.core.data.sync.SyncScheduler
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

/** The journal module's [SyncScheduler]. One scheduler per module, app-lived. */
val JournalScheduler = named("journalScheduler")

private val DebugLogScope = named("debugLogScope")

val coreDataModule = module {
    single<CoroutineScope>(AppScope) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single<CoroutineScope>(DebugLogScope) { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    single<Json> { WellnessJson }
    single { ServerConfig(BuildConfig.WELLNESS_BASE_URL) }

    single {
        Room.databaseBuilder(androidContext(), WellnessDatabase::class.java, WellnessDatabase.NAME)
            .addMigrations(*WELLNESS_MIGRATIONS)
            .build()
    }
    single { get<WellnessDatabase>().debugLogDao() }
    single { get<WellnessDatabase>().payloadCacheDao() }
    single { get<WellnessDatabase>().journalDao() }

    single { DebugLog(dao = get(), scope = get(DebugLogScope), json = get()) }
    single<HttpClient> { buildHttpClient(config = get(), json = get(), debugLog = get()) }
    single { JournalApi(client = get(), config = get()) }

    single { ConnectivityMonitor(androidContext()) }
    single { SyncErrorEvents() }
    single { JournalUiPrefs(dao = get(), json = get()) }

    single {
        val connectivity = get<ConnectivityMonitor>()
        // The scheduler is resolved lazily, inside the lambda: it is built
        // around this store, so resolving it here would be a construction cycle.
        val koinScope = this
        JournalSyncStore(
            dao = get(),
            api = get(),
            isOnline = { connectivity.isOnline.value },
            json = get(),
            debugLog = get(),
            scheduleUpload = { koinScope.get<SyncScheduler>(JournalScheduler).scheduleUpload() },
        )
    }

    single<SyncScheduler>(JournalScheduler) {
        val connectivity = get<ConnectivityMonitor>()
        val store = get<JournalSyncStore>()
        // No pollCheckFn: the journal full-syncs on every poll tick, as the PWA
        // does. The delta with a `since` watermark is cheap enough not to need
        // a cheaper probe in front of it.
        val errors = get<SyncErrorEvents>()
        SyncScheduler(
            scope = get(AppScope),
            name = "journal",
            syncFn = store::triggerSync,
            isSyncing = { store.isSyncing },
            hasDirtyData = store::hasDirtyData,
            isOnline = { connectivity.isOnline.value },
            onServerError = errors::postServerError,
            isNetworkError = ::isNetworkError,
            debugLog = get(),
        )
    }

    single { SyncOrchestrator(scope = get(AppScope), connectivity = get()) }
}

package dev.jtiisto.wellness.core.data.di

import androidx.room.Room
import dev.jtiisto.wellness.core.data.BuildConfig
import dev.jtiisto.wellness.core.data.WellnessJson
import dev.jtiisto.wellness.core.data.analysis.AnalysisEvents
import dev.jtiisto.wellness.core.data.analysis.AnalysisRepository
import dev.jtiisto.wellness.core.data.analysis.AnalysisStore
import dev.jtiisto.wellness.core.data.coach.CoachSyncStore
import dev.jtiisto.wellness.core.data.db.WELLNESS_MIGRATIONS
import dev.jtiisto.wellness.core.data.db.WellnessDatabase
import dev.jtiisto.wellness.core.data.journal.JournalSyncStore
import dev.jtiisto.wellness.core.data.journal.JournalUiPrefs
import dev.jtiisto.wellness.core.data.network.AnalysisApi
import dev.jtiisto.wellness.core.data.network.CoachApi
import dev.jtiisto.wellness.core.data.network.JournalApi
import dev.jtiisto.wellness.core.data.network.ServerConfig
import dev.jtiisto.wellness.core.data.network.TrendsApi
import dev.jtiisto.wellness.core.data.network.buildHttpClient
import dev.jtiisto.wellness.core.data.network.isNetworkError
import dev.jtiisto.wellness.core.data.sync.ConnectivityMonitor
import dev.jtiisto.wellness.core.data.sync.DebugLog
import dev.jtiisto.wellness.core.data.sync.SyncOrchestrator
import dev.jtiisto.wellness.core.data.sync.SyncErrorEvents
import dev.jtiisto.wellness.core.data.sync.SyncScheduler
import dev.jtiisto.wellness.core.data.trends.TrendsPrefs
import dev.jtiisto.wellness.core.data.trends.TrendsRepository
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

/**
 * Scope that outlives every activity: sync work must survive backgrounding and
 * configuration changes, and is only ever torn down with the process.
 */
val AppScope = named("appScope")

/** The journal module's [SyncScheduler]. One scheduler per module, app-lived. */
val JournalScheduler = named("journalScheduler")

/** The coach module's [SyncScheduler]. */
val CoachScheduler = named("coachScheduler")

private val DebugLogScope = named("debugLogScope")

/**
 * The Analysis store's control context: exactly one thread, forever.
 *
 * Not a shared dispatcher and not `Dispatchers.Main` — the guarantee the store
 * needs is that no two of its mutations can interleave, and the only way to have
 * that without a lock around every write is a dispatcher that cannot run two
 * things at once.
 */
val AnalysisControlContext = named("analysisControlContext")

val coreDataModule = module {
    single<CoroutineScope>(AppScope) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single<CoroutineScope>(DebugLogScope) { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    single<CoroutineContext>(AnalysisControlContext) {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "analysis-control").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }

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
    single { get<WellnessDatabase>().coachDao() }
    single { get<WellnessDatabase>().trendsMetaDao() }

    single { DebugLog(dao = get(), scope = get(DebugLogScope), json = get()) }
    single<HttpClient> { buildHttpClient(config = get(), json = get(), debugLog = get()) }
    single { JournalApi(client = get(), config = get()) }
    single { CoachApi(client = get(), config = get()) }
    single { TrendsApi(client = get(), config = get()) }
    single { AnalysisApi(client = get(), config = get(), json = get()) }

    single { ConnectivityMonitor(androidContext()) }
    single { SyncErrorEvents() }
    single { JournalUiPrefs(dao = get(), json = get()) }
    single { TrendsPrefs(dao = get()) }
    single { TrendsRepository(api = get(), cacheDao = get(), debugLog = get(), json = get()) }

    single { AnalysisEvents() }
    single { AnalysisRepository(api = get(), cacheDao = get(), debugLog = get(), json = get()) }
    single {
        AnalysisStore(
            repository = get(),
            events = get(),
            debugLog = get(),
            scope = get(AppScope),
            controlContext = get(AnalysisControlContext),
        )
    }

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

    single {
        val connectivity = get<ConnectivityMonitor>()
        val koinScope = this
        CoachSyncStore(
            dao = get(),
            api = get(),
            isOnline = { connectivity.isOnline.value },
            json = get(),
            debugLog = get(),
            scheduleUpload = { koinScope.get<SyncScheduler>(CoachScheduler).scheduleUpload() },
        )
    }

    single<SyncScheduler>(CoachScheduler) {
        val connectivity = get<ConnectivityMonitor>()
        val store = get<CoachSyncStore>()
        val errors = get<SyncErrorEvents>()
        SyncScheduler(
            scope = get(AppScope),
            name = "coach",
            syncFn = store::triggerSync,
            isSyncing = { store.isSyncing },
            hasDirtyData = store::hasDirtyData,
            isOnline = { connectivity.isOnline.value },
            // Unlike the journal, coach has a cheap probe in front of the full
            // sync: `plans-version` is one timestamp, and a full coach pull is
            // not (plans are large and the window is 60 days).
            pollCheckFn = store::pollCheck,
            onServerError = errors::postServerError,
            isNetworkError = ::isNetworkError,
            debugLog = get(),
        )
    }

    single { SyncOrchestrator(scope = get(AppScope), connectivity = get()) }
}

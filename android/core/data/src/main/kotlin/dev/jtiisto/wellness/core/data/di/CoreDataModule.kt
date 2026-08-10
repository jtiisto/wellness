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
import dev.jtiisto.wellness.core.data.export.DataExporter
import dev.jtiisto.wellness.core.data.export.SharedFileStore
import dev.jtiisto.wellness.core.data.journal.JournalSyncStore
import dev.jtiisto.wellness.core.data.journal.JournalUiPrefs
import dev.jtiisto.wellness.core.data.network.AnalysisApi
import dev.jtiisto.wellness.core.data.network.CoachApi
import dev.jtiisto.wellness.core.data.network.JournalApi
import dev.jtiisto.wellness.core.data.network.ServerBootstrap
import dev.jtiisto.wellness.core.data.network.TrendsApi
import dev.jtiisto.wellness.core.data.network.buildHttpClient
import dev.jtiisto.wellness.core.data.network.isNetworkError
import dev.jtiisto.wellness.core.data.sync.ConnectivityMonitor
import dev.jtiisto.wellness.core.data.sync.DebugLog
import dev.jtiisto.wellness.core.data.sync.ForceSyncModule
import dev.jtiisto.wellness.core.data.sync.ForceSyncModuleResult
import dev.jtiisto.wellness.core.data.sync.ForceSyncOrchestrator
import dev.jtiisto.wellness.core.data.sync.ServerSessionGate
import dev.jtiisto.wellness.core.data.sync.ServerSwitcher
import dev.jtiisto.wellness.core.data.sync.SyncErrorEvents
import dev.jtiisto.wellness.core.data.sync.SyncFlushScheduler
import dev.jtiisto.wellness.core.data.sync.SyncFlushWorker
import dev.jtiisto.wellness.core.data.sync.SyncOrchestrator
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
import java.io.File
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

/** The app's `versionName`, supplied by `:app` — the export's `client` field. */
val AppVersionName = named("appVersionName")

val coreDataModule = module {
    single<CoroutineScope>(AppScope) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single<CoroutineScope>(DebugLogScope) { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    single<CoroutineContext>(AnalysisControlContext) {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "analysis-control").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }

    single<Json> { WellnessJson }

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
    single { get<WellnessDatabase>().serverProfilesDao() }
    single { get<WellnessDatabase>().serverSwitchDao() }
    single { get<WellnessDatabase>().exportDao() }

    single { ServerBootstrap(dao = get(), builtInUrl = BuildConfig.WELLNESS_BASE_URL) }
    // Resolved, never constructed from BuildConfig directly: which server this
    // process talks to is a boot decision, and asking for this before that
    // decision has been made is a bug worth crashing on.
    single { get<ServerBootstrap>().requireConfig() }

    // One instance for the process. Every network-to-Room writer holds this
    // same object, which is what makes closing it a global fence.
    single { ServerSessionGate() }
    single { SharedFileStore(root = File(androidContext().cacheDir, SharedFileStore.DIRECTORY)) }

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
    single { TrendsRepository(api = get(), cacheDao = get(), debugLog = get(), session = get(), json = get()) }
    single { DataExporter(dao = get(), clientVersion = get(AppVersionName)) }

    single { AnalysisEvents() }
    single { AnalysisRepository(api = get(), cacheDao = get(), debugLog = get(), session = get(), json = get()) }
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
            session = get(),
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
            session = get(),
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

    /**
     * The two modules as the force-sync orchestrator sees them: a force cycle,
     * a dirty probe, and the scheduler hooks the stores deliberately do not own.
     */
    single {
        val koinScope = this
        ForceSyncOrchestrator(
            coach = forceSyncModuleOf(
                store = get<CoachSyncStore>()::forceSync,
                dirty = get<CoachSyncStore>()::hasDirtyData,
                scheduler = { koinScope.get(CoachScheduler) },
            ),
            journal = forceSyncModuleOf(
                store = get<JournalSyncStore>()::forceSync,
                dirty = get<JournalSyncStore>()::hasDirtyData,
                scheduler = { koinScope.get(JournalScheduler) },
            ),
            isOnline = { get<ConnectivityMonitor>().isOnline.value },
        )
    }

    single {
        val context = androidContext()
        ServerSwitcher(
            gate = get(),
            schedulers = listOf(get(JournalScheduler), get(CoachScheduler)),
            drains = listOf(
                get<JournalSyncStore>()::awaitQuiescence,
                get<CoachSyncStore>()::awaitQuiescence,
            ),
            stopAnalysis = get<AnalysisStore>()::stopAndJoin,
            backgroundWork = SyncFlushWorker.canceller(context),
            dao = get(),
            sharedFiles = get(),
        )
    }

    single {
        val context = androidContext()
        val journal = get<JournalSyncStore>()
        val coach = get<CoachSyncStore>()
        val flush = SyncFlushScheduler(
            hasDirtyData = { journal.hasDirtyData() || coach.hasDirtyData() },
            enqueue = { SyncFlushWorker.enqueue(context) },
        )
        SyncOrchestrator(
            scope = get(AppScope),
            connectivity = get(),
            onBackground = flush::onBackgrounded,
        )
    }
}

/**
 * Adapt a store to [ForceSyncModule]. The scheduler is resolved lazily inside
 * the hooks for the same reason the stores' `scheduleUpload` is: each scheduler
 * is built around its store, so resolving one here would be a construction
 * cycle.
 */
private fun forceSyncModuleOf(
    store: suspend () -> ForceSyncModuleResult,
    dirty: suspend () -> Boolean,
    scheduler: () -> SyncScheduler,
): ForceSyncModule = object : ForceSyncModule {
    override suspend fun forceSync(): ForceSyncModuleResult = store()
    override suspend fun hasDirtyData(): Boolean = dirty()
    override fun resetRetry() = scheduler().resetRetry()
    override fun requestSync() = scheduler().requestSync()
}

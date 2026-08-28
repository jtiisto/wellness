package dev.jtiisto.wellness.feature.journal.di

import dev.jtiisto.wellness.core.data.di.JournalScheduler
import dev.jtiisto.wellness.core.data.sync.ConnectivityMonitor
import dev.jtiisto.wellness.feature.journal.JournalViewModel
import dev.jtiisto.wellness.feature.journal.TrackerFormViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** The Journal tab's ViewModels. Everything they depend on is app-lived already. */
val journalModule = module {
    viewModel {
        // Resolved once and closed over, as every scheduler in `coreDataModule`
        // does: the ViewModel wants the answer at the moment of the pull, not a
        // `get` call captured in a lambda that outlives this scope.
        val connectivity = get<ConnectivityMonitor>()
        JournalViewModel(
            store = get(),
            prefs = get(),
            scheduler = get(JournalScheduler),
            isOnline = { connectivity.isOnline.value },
            errors = get(),
        )
    }
    viewModel { TrackerFormViewModel(store = get()) }
}

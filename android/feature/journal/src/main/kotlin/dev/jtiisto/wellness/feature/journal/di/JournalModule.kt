package dev.jtiisto.wellness.feature.journal.di

import dev.jtiisto.wellness.core.data.di.JournalScheduler
import dev.jtiisto.wellness.feature.journal.JournalViewModel
import dev.jtiisto.wellness.feature.journal.TrackerFormViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** The Journal tab's ViewModels. Everything they depend on is app-lived already. */
val journalModule = module {
    viewModel { JournalViewModel(store = get(), prefs = get(), scheduler = get(JournalScheduler)) }
    viewModel { TrackerFormViewModel(store = get()) }
}

package dev.jtiisto.wellness.di

import dev.jtiisto.wellness.core.data.di.JournalScheduler
import dev.jtiisto.wellness.ui.journal.JournalDebugViewModel
import dev.jtiisto.wellness.ui.tools.ToolsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/** UI-layer wiring. Everything headless lives in `coreDataModule`. */
val appModule = module {
    viewModelOf(::ToolsViewModel)
    viewModel { JournalDebugViewModel(store = get(), scheduler = get(JournalScheduler)) }
}

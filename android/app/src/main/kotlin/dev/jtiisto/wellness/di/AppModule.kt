package dev.jtiisto.wellness.di

import dev.jtiisto.wellness.core.data.di.CoachScheduler
import dev.jtiisto.wellness.ui.coach.CoachDebugViewModel
import dev.jtiisto.wellness.ui.tools.ToolsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/** UI-layer wiring the app shell owns. Features bring their own modules. */
val appModule = module {
    viewModelOf(::ToolsViewModel)
    // The coach tab is a debug screen in the app module until Phase 5 gives
    // :feature:coach the real one.
    viewModel { CoachDebugViewModel(store = get(), scheduler = get(CoachScheduler)) }
}

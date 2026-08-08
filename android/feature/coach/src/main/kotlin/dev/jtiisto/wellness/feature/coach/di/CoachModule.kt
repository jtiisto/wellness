package dev.jtiisto.wellness.feature.coach.di

import dev.jtiisto.wellness.core.data.di.CoachScheduler
import dev.jtiisto.wellness.feature.coach.CoachViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** The Coach tab's ViewModel. Everything it depends on is app-lived already. */
val coachModule = module {
    viewModel { CoachViewModel(store = get(), scheduler = get(CoachScheduler), api = get()) }
}

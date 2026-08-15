package dev.jtiisto.wellness.feature.trends.di

import dev.jtiisto.wellness.feature.trends.CardioViewModel
import dev.jtiisto.wellness.feature.trends.HealthViewModel
import dev.jtiisto.wellness.feature.trends.JournalTrendsViewModel
import dev.jtiisto.wellness.feature.trends.OverviewViewModel
import dev.jtiisto.wellness.feature.trends.StrengthViewModel
import dev.jtiisto.wellness.feature.trends.TrendsShellViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** The Trends tab's ViewModels. The repository and prefs are app-lived already. */
val trendsModule = module {
    viewModel { TrendsShellViewModel(prefs = get()) }
    viewModel { OverviewViewModel(repository = get(), prefs = get(), debugLog = get()) }
    viewModel { StrengthViewModel(repository = get(), prefs = get(), debugLog = get()) }
    viewModel { CardioViewModel(repository = get(), prefs = get(), debugLog = get()) }
    viewModel { JournalTrendsViewModel(repository = get(), prefs = get(), debugLog = get()) }
    viewModel { HealthViewModel(repository = get(), prefs = get(), debugLog = get()) }
}

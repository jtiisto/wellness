package dev.jtiisto.wellness.feature.analysis.di

import dev.jtiisto.wellness.feature.analysis.AnalysisViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import java.time.ZoneId

/**
 * The Analysis tab's ViewModel. The store, its events and the repository are
 * app-lived already — the poll must survive this ViewModel, and so must the
 * events it raises, which is why the shell rather than this ViewModel collects
 * them.
 *
 * The zone is injected rather than read inside the formatter so a test can pin a
 * date across a DST boundary without touching the platform default.
 */
val analysisModule = module {
    viewModel {
        AnalysisViewModel(
            store = get(),
            zone = ZoneId.systemDefault(),
        )
    }
}

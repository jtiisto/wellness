package dev.jtiisto.wellness.feature.coach.di

import dev.jtiisto.wellness.core.ble.capture.HrCaptureState
import dev.jtiisto.wellness.core.ble.di.HrCaptureStateQualifier
import dev.jtiisto.wellness.core.data.di.CoachScheduler
import dev.jtiisto.wellness.feature.coach.CoachViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** The Coach tab's ViewModel. Everything it depends on is app-lived already. */
val coachModule = module {
    viewModel {
        CoachViewModel(
            store = get(),
            scheduler = get(CoachScheduler),
            api = get(),
            // Resolved as the mutable type it was registered under and taken
            // read-only: Koin keys on the declared class, and the qualifier is
            // what keeps this distinct from the known-device list, which erases
            // to the same key. See `bleModule`.
            captureState = get<MutableStateFlow<HrCaptureState>>(HrCaptureStateQualifier),
            knownStraps = get(),
            capture = get(),
            captureStore = get(),
            guideEvents = get(),
            beatReader = get(),
            // The app-lived ring the capture service feeds. Keyed on its own
            // class, not on the flow it publishes — see `bleModule`.
            traceRing = get(),
        )
    }
}

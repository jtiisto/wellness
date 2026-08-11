plugins {
    id("wellness.android.library")
}

dependencies {
    // Nothing from this repo: the module is a leaf, and the sink interface is
    // what keeps Room (and :core:data with it) on the other side.
    api(libs.kotlinx.coroutines.core)

    // Koin only for this module's own `bleModule`, the same way every other
    // module here owns its DI file. It is what lets the capture scope, the
    // service↔UI state flows and the scanner be singletons the app never
    // constructs by hand.
    implementation(libs.koin.android)
}

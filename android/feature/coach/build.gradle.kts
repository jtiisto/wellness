plugins {
    id("wellness.android.feature")
}

dependencies {
    // BackHandler and LocalActivity, both for the guidance overlay: it is a
    // surface the day puts up rather than a nav destination, so the system back
    // gesture has to be handled here, and FLAG_KEEP_SCREEN_ON needs the window
    // the Activity owns.
    implementation(libs.androidx.activity.compose)
}

plugins {
    id("wellness.android.feature")
}

dependencies {
    // The config screen is a real destination inside the Journal tab, so the
    // feature owns a nested NavHost (and with it, system-back support).
    implementation(libs.androidx.navigation.compose)
}

plugins {
    id("wellness.android.application")
}

android {
    namespace = "dev.jtiisto.wellness"

    defaultConfig {
        applicationId = "dev.jtiisto.wellness"
        versionCode = 1
        versionName = "0.2.0"
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:ui"))
    implementation(project(":feature:journal"))
    implementation(project(":feature:coach"))
    implementation(project(":feature:trends"))
    implementation(project(":feature:analysis"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
}

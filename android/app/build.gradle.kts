plugins {
    id("wellness.android.application")
}

val appVersionName = "0.2.0"

android {
    namespace = "dev.jtiisto.wellness"

    buildFeatures {
        // The Tools tab's build row reads BUILD_STAMP from here. The convention
        // plugin leaves this off by default; :app opts in because it is the one
        // module with a screen to show it on.
        buildConfig = true
    }

    defaultConfig {
        applicationId = "dev.jtiisto.wellness"
        versionCode = 1
        versionName = appVersionName
    }

    buildTypes {
        debug {
            buildConfigField("String", "BUILD_STAMP", "\"${buildStamp(appVersionName, isRelease = false)}\"")
        }
        release {
            buildConfigField("String", "BUILD_STAMP", "\"${buildStamp(appVersionName, isRelease = true)}\"")
        }
    }
}

dependencies {
    // Declared even though :core:data exposes it transitively: the capture
    // service is here, and it uses the BLE module directly.
    implementation(project(":core:ble"))
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
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
}

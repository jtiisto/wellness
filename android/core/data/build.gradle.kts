import java.util.Properties

plugins {
    id("wellness.android.library")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Server base URL: `wellness.baseUrl` in local.properties overrides the
// tailscale-serve endpoint. Read through a Provider so the configuration cache
// treats local.properties as a tracked input.
val defaultBaseUrl = "https://pop-os.tailexample.ts.net:9443/wellness"
val wellnessBaseUrl: String = providers
    .fileContents(layout.settingsDirectory.file("local.properties"))
    .asText
    .map { text ->
        Properties().apply { load(text.reader()) }.getProperty("wellness.baseUrl") ?: defaultBaseUrl
    }
    .getOrElse(defaultBaseUrl)

android {
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        buildConfigField("String", "WELLNESS_BASE_URL", "\"$wellnessBaseUrl\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets {
        // Golden server payloads live at the repo root, shared across modules;
        // putting them on the unit-test classpath is what lets the contract
        // tests load them as `/golden/<module>/<name>.json`.
        getByName("test") {
            resources.directories.add(layout.settingsDirectory.dir("testdata").asFile.path)
        }
        // MigrationTestHelper reads the exported schemas from the test APK's
        // assets — that is what makes runMigrationsAndValidate an assertion.
        getByName("androidTest") {
            assets.directories.add(layout.projectDirectory.dir("schemas").asFile.path)
        }
    }
}

dependencies {
    // The one edge between the two core modules, and it points this way on
    // purpose: :core:ble declares the sample sink, this module implements it
    // over Room. `api` because HrCaptureStore's own signature is written in
    // :core:ble types, so every consumer of this module already needs them.
    api(project(":core:ble"))

    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    api(libs.room.runtime)
    ksp(libs.room.compiler)

    api(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)

    implementation(libs.koin.android)
    implementation(libs.androidx.lifecycle.process)
    // WorkManager: the one-shot flush enqueued when the app backgrounds with
    // dirty rows (plan §3). Its startup Initializer is why this waited until
    // there was a Worker for it to initialize.
    implementation(libs.androidx.work.runtime)

    testImplementation(libs.ktor.client.mock)

    // Instrumented DAO/migration tests: run in emulator sessions, never hooks.
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.room.testing)
}

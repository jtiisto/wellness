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
}

dependencies {
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
    // WorkManager arrives with Phase 2's background flush — declaring it now
    // would already run its Initializer at app startup.

    testImplementation(libs.ktor.client.mock)

    // Instrumented DAO/migration tests: run in emulator sessions, never hooks.
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.room.testing)
}

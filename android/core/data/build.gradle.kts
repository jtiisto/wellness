plugins {
    id("wellness.android.library")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets {
        // Golden payloads land on the unit-test classpath as
        // `/golden/<module>/<name>.json`. Two roots feed it: the android tree's
        // own goldens (coach/journal — generated against the dev server), and
        // the staged copy of the SHARED HR contract fixtures (see the Sync
        // task below) — one repo-root directory both test suites read, so the
        // server and client halves of the wire contract cannot drift.
        getByName("test") {
            resources.directories.add(layout.settingsDirectory.dir("testdata").asFile.path)
            resources.directories.add(
                layout.buildDirectory.dir("sharedGoldens").get().asFile.path
            )
        }
        // MigrationTestHelper reads the exported schemas from the test APK's
        // assets — that is what makes runMigrationsAndValidate an assertion.
        getByName("androidTest") {
            assets.directories.add(layout.projectDirectory.dir("schemas").asFile.path)
        }
    }
}

// The HR wire-contract fixtures are SHARED with the server's pytest suite:
// one directory at the repo root, read byte-for-byte by both sides (this
// replaced the two-copy "regenerate both in one change set" pact when the
// repos merged). Staged under golden/hr/ so the classpath keeps the
// `/golden/<module>/` shape the contract tests load.
val stageSharedHrGoldens by tasks.registering(Sync::class) {
    from(layout.settingsDirectory.dir("../test/hr/golden"))
    exclude("README.md")
    into(layout.buildDirectory.dir("sharedGoldens/golden/hr"))
}
tasks.matching { it.name.endsWith("UnitTestJavaRes") }.configureEach {
    dependsOn(stageSharedHrGoldens)
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

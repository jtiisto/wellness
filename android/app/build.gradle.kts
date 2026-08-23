import java.util.Properties

plugins {
    id("wellness.android.application")
}

val appVersionName = "0.2.0"

// Which server a build is born pointing at. Read through a Provider so the
// configuration cache treats local.properties as a tracked input; the tracked
// defaults are placeholders, so a real build MUST set the key for its variant.
val localProperties = providers
    .fileContents(layout.settingsDirectory.file("local.properties"))
    .asText
    .map { text -> Properties().apply { load(text.reader()) } }

// A missing file or key compiles in the placeholder: a clean public clone must
// still build, it just reaches no server. A key someone SET but left blank is
// always a mistake, so that one fails the build instead of shipping an APK
// that dials "".
fun localBaseUrl(key: String, default: String): String {
    val value = localProperties.map { it.getProperty(key) ?: default }.getOrElse(default).trim()
    require(value.isNotEmpty()) {
        "local.properties sets $key to a blank value — set a real URL or drop the line to use the placeholder default"
    }
    return value
}

val prodBaseUrl = localBaseUrl("wellness.baseUrl", "https://pop-os.tailexample.ts.net:9443/wellness")
// The dev build type's own key: it targets the disposable test server, and
// sharing `wellness.baseUrl` would make one install's server change move both.
val devBaseUrl = localBaseUrl("wellness.dev.baseUrl", "http://pop-os.tailexample.ts.net:9001/wellness")

android {
    namespace = "dev.jtiisto.wellness"

    buildFeatures {
        // WELLNESS_BASE_URL lives here. The convention plugin leaves this off
        // by default; :app opts in because it is the one module born knowing a
        // server. (The Tools row's build stamp is a generated asset now, not a
        // BuildConfig field — see the androidComponents block at the bottom.)
        buildConfig = true
    }

    defaultConfig {
        applicationId = "dev.jtiisto.wellness"
        versionCode = 1
        versionName = appVersionName
    }

    buildTypes {
        debug {
            buildConfigField("String", "WELLNESS_BASE_URL", "\"$prodBaseUrl\"")
        }
        release {
            buildConfigField("String", "WELLNESS_BASE_URL", "\"$prodBaseUrl\"")
        }
        // Installs alongside the daily driver: the suffixed applicationId is
        // what gives it its own Room database, DataStore, WorkManager queue,
        // server address book and FileProvider authority, so device-testing a
        // UI round cannot touch the production install or its data.
        // See specs/dev-app-variant.md.
        create("dev") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            // Only :app has this build type; the libraries keep their two, and
            // this is what points the dev variant at their debug ones.
            matchingFallbacks += "debug"
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("String", "WELLNESS_BASE_URL", "\"$devBaseUrl\"")
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

// The Tools row's build stamp: an execution-time generated asset per variant,
// not a BuildConfig field — see BuildStampTask for why (configuration-cache
// preservation; GitStamp's commit anchoring retired 2026-08-22, user ruling:
// the whole fleet runs debug/dev, which the old design left identityless).
androidComponents {
    onVariants { variant ->
        val stamp = tasks.register(
            "generate${variant.name.replaceFirstChar { it.uppercase() }}BuildStamp",
            BuildStampTask::class.java,
        )
        variant.sources.assets?.addGeneratedSourceDirectory(stamp, BuildStampTask::outputDir)
    }
}

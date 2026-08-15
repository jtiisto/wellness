import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Android library with built-in Kotlin (AGP 9 — no kotlin-android plugin),
 * JUnit-platform unit tests, and the shared test dependencies.
 * jvmTarget follows compileOptions.targetCompatibility (21).
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            extensions.configure<LibraryExtension> {
                namespace = derivedNamespace
                compileSdk = COMPILE_SDK
                defaultConfig.minSdk = MIN_SDK
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_21
                    targetCompatibility = JavaVersion.VERSION_21
                }
                testOptions.unitTests.isReturnDefaultValues = true
            }
            configureUnitTests()
        }
    }
}

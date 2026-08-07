import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project

/**
 * Feature modules (journal/coach/trends/analysis): library + Compose +
 * the layer dependencies every feature gets (:core:data, :core:ui, ViewModel,
 * Koin). Features never depend on each other.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("wellness.android.library")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            extensions.configure<LibraryExtension> {
                buildFeatures.compose = true
            }
            addComposeDependencies()
            dependencies {
                "implementation"(project(":core:data"))
                "implementation"(project(":core:ui"))
                "implementation"(libs.findLibrary("androidx-lifecycle-runtime-compose").get())
                "implementation"(libs.findLibrary("androidx-lifecycle-viewmodel-compose").get())
                "implementation"(libs.findLibrary("koin-android").get())
                "implementation"(libs.findLibrary("koin-androidx-compose").get())
            }
        }
    }
}

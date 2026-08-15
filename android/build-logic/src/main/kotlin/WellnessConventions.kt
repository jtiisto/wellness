import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

internal const val COMPILE_SDK = 37
internal const val MIN_SDK = 35
internal const val TARGET_SDK = 36
internal const val BASE_PACKAGE = "dev.jtiisto.wellness"

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/** Namespace derived from the Gradle path: ":core:data" -> "dev.jtiisto.wellness.core.data". */
internal val Project.derivedNamespace: String
    get() = BASE_PACKAGE + path.replace(":", ".")

/** JUnit platform for unit tests plus the shared test dependencies every module gets. */
internal fun Project.configureUnitTests() {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
    dependencies {
        "testImplementation"(libs.findLibrary("junit-jupiter").get())
        "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
        "testImplementation"(libs.findLibrary("mockk").get())
        "testImplementation"(libs.findLibrary("turbine").get())
        "testImplementation"(libs.findLibrary("kotlinx-coroutines-test").get())
    }
}

/** Compose runtime dependencies for modules that render UI (app + features). */
internal fun Project.addComposeDependencies() {
    dependencies {
        "implementation"(platform(libs.findLibrary("compose-bom").get()))
        "implementation"(libs.findLibrary("compose-ui").get())
        "implementation"(libs.findLibrary("compose-material3").get())
        "implementation"(libs.findLibrary("compose-ui-tooling-preview").get())
        "debugImplementation"(libs.findLibrary("compose-ui-tooling").get())
    }
}

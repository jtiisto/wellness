plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kover)
}

// Merged coverage across all modules; gated by githooks/pre-push via
// koverVerifyAggregated. Composables and framework glue are excluded so the
// metric tracks unit-testable logic (no Compose UI test rig in this project).
kover {
    merge {
        allProjects()
        createVariant("aggregated") {
            add("debug", optional = true)
        }
    }

    reports {
        filters {
            excludes {
                classes(
                    "*BuildConfig",
                    "*_Impl",
                    "*_Impl$*",
                    "*ComposableSingletons*",
                    "*ModuleKt",
                    "dev.jtiisto.wellness.MainActivity",
                    "dev.jtiisto.wellness.WellnessApplication",
                )
                packages(
                    "dev.jtiisto.wellness.core.ui.theme",
                )
                annotatedBy("androidx.compose.runtime.Composable")
            }
        }
        variant("aggregated") {
            verify {
                rule {
                    // Raised 80 → 85 at the end of Phase 8, measured in one
                    // clean full invocation (see CLAUDE.md: filtered or
                    // module-scoped runs poison the execution data). Measure,
                    // then raise; never lower without a deliberate decision.
                    minBound(85)
                }
            }
        }
    }
}

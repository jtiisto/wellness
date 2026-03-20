pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolution {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "wellness"

include(":app")
include(":core:common")
include(":core:database")
include(":core:network")
include(":core:sync")
include(":core:ui")
include(":feature:journal")
include(":feature:coach")
include(":feature:analysis")

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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")   // PebbleKit Android 2
    }
}

rootProject.name = "MirrorMsg"
include(":app")

// The Feedback screen, in test (debug) builds only: app/build.gradle.kts
// takes :feedback as debugImplementation, so release builds have none of it.
// :feedback wraps the shared Feedback tab from mirrorapps-kit, a git
// submodule at kit/ (the module is kit/kit/).
include(":feedback")
include(":kit")
project(":kit").projectDir = file("kit/kit")

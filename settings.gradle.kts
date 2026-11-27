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
    plugins {
        id("com.android.application") version "8.7.3"
        id("org.jetbrains.kotlin.android") version "2.0.21"
        id("org.jetbrains.kotlin.jvm") version "2.0.21"
        id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Optional mirror for environments where Maven Central is rate limited.
        if (providers.gradleProperty("usePluginPortalMirror").orNull == "true") gradlePluginPortal()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}
rootProject.name = "realtime-captions-android"
include(":core")
// JVM harness that runs the sherpa-onnx engine on wav files and writes docs/benchmark.md.
include(":desktop")
// The Android app needs the Android SDK. Set -PcoreOnly=true to build/test only the pure-Kotlin core.
if (providers.gradleProperty("coreOnly").orNull != "true") {
    include(":app")
}

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // Central first: mirrors below are last-resort fallbacks and some
        // (e.g. aliyun) occasionally return 5xx, which must not break CI.
        mavenCentral()
        // Fallback for networks where dl.google.com is unavailable. Keep the
        // mirror scoped to Google's Android artifacts only. The Room Gradle
        // plugin (androidx.room) resolves here when dl.google.com 404s.
        maven("https://maven.aliyun.com/repository/google") {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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
        maven("https://maven.aliyun.com/repository/google") {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // org.fossify:mmslib used to come from here; it is now vendored in
        // app/libs/mmslib-1.0.0.aar because JitPack outages were failing CI
        // resolution. Nothing else in the build depends on JitPack.
    }
}

rootProject.name = "Messages"
include(":app")

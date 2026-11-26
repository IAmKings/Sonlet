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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // ReleaseKite 通过 JitPack 分发(应用内更新),其余依赖仍只走 google/mavenCentral
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.IAmKings.*") }
        }
    }
}

rootProject.name = "Sonlet"
include(":app")

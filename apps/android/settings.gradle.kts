pluginManagement {
    includeBuild("../../tooling/gradle/build-logic")
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
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../../tooling/gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "vazie-keep-android"
include(":app", ":core-bridge", ":autofill-host")

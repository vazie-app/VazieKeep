pluginManagement {
    includeBuild("../../tooling/gradle/build-logic")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        google()
    }
    versionCatalogs {
        create("libs") {
            from(files("../../tooling/gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "vazie-keep-desktop"
include(":app", ":core-bridge")

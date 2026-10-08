pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "videobridge"

include(":app-phone", ":app-tv")
include(
    ":core:model",
    ":core:common",
    ":core:network",
    ":core:database",
    ":core:datastore",
    ":core:data",
    ":core:player",
    ":core:designsystem",
    ":core:tv-designsystem",
    ":core:testing",
)
include(":feature:auth", ":feature:library")

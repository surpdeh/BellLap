pluginManagement {
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

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "BellLap"

include(
    ":app",
    ":core:model",
    ":core:statemachine",
    ":core:replay",
    ":core:service",
    ":core:log",
    ":audio:source",
    ":audio:bell",
    ":audio:android",
    ":vision:core",
    ":vision:flag",
    ":vision:finishline",
    ":capture",
    ":review",
    ":bugreport",
    ":testing:fixtures",
    ":testing:harness",
)

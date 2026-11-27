pluginManagement {
    repositories {
        // mitv4a: dropped Aliyun mirrors - they intermittently return HTTP 502
        // in GitHub Actions, and Gradle treats 5xx as a hard failure. Official
        // sources are stable on CI runners.
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

rootProject.name = "FakeAtv"
include(":app")

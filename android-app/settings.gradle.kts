pluginManagement {
    repositories {
        google()
        // Use repo1 directly instead of repo.maven.apache.org (403 workaround)
        maven {
            url = uri("https://repo1.maven.org/maven2")
            content { includeGroupByRegex(".*") }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Use repo1 directly instead of repo.maven.apache.org (403 workaround)
        maven {
            url = uri("https://repo1.maven.org/maven2")
            content { includeGroupByRegex(".*") }
        }
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Zapret2"
include(":app")

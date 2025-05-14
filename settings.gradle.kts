rootProject.name = "Tammy"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenLocal()
        google()
        mavenCentral()
        maven("https://gitlab.com/api/v4/projects/68438621/packages/maven") // c2x Conventions
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://gitlab.com/api/v4/projects/26519650/packages/maven") // trixnity
        maven("https://gitlab.com/api/v4/projects/47538655/packages/maven") // trixnity-messenger
        maven("https://gitlab.com/api/v4/projects/58749664/packages/maven") // sysnotify
        maven("https://gitlab.com/api/v4/projects/65998892/packages/maven") // androidx
        mavenLocal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

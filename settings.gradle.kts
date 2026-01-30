rootProject.name = "telecrypt-messenger"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        mavenLocal()
        maven("https://gitlab.com/api/v4/projects/68438621/packages/maven") // c2x Conventions
        maven("https://gitlab.com/api/v4/projects/75787729/packages/maven") // compose multiplatform a11y
        google()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        maven("https://gitlab.com/api/v4/projects/68438621/packages/maven") // c2x Conventions
        maven("https://gitlab.com/api/v4/projects/26519650/packages/maven") // trixnity
        maven("https://gitlab.com/api/v4/projects/47538655/packages/maven") // trixnity-messenger
        maven("https://gitlab.com/api/v4/projects/58749664/packages/maven") // sysnotify
        maven("https://gitlab.com/api/v4/projects/75787860/packages/maven") // compose multiplatform core a11y
        maven("https://gitlab.com/api/v4/projects/75787729/packages/maven") // compose multiplatform a11y
        maven("https://gitlab.com/api/v4/projects/38224197/packages/maven") // webviewko for web view window for calls
        google()
    }
}

plugins {
    id("de.connect2x.conventions.c2x-settings-plugin") version "20260129.102940"
}
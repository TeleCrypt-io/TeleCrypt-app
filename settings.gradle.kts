rootProject.name = "telecrypt messenger"

buildCache {
    val buildCacheUrl = System.getenv("GRADLE_BUILD_CACHE_URL")
    local {
        isEnabled = buildCacheUrl == null
        directory = File(rootDir, ".gradle").resolve("build-cache")
    }
    remote<HttpBuildCache> {
        isEnabled = buildCacheUrl != null
        if (buildCacheUrl != null) {
            url = uri(buildCacheUrl)
            isPush = true
            credentials {
                username = System.getenv("GRADLE_BUILD_CACHE_USERNAME")
                password = System.getenv("GRADLE_BUILD_CACHE_PASSWORD")
            }
        }
    }
}

pluginManagement {
    repositories {
        val dependencyCacheUrl = System.getenv("GRADLE_DEPENDENCY_CACHE_URL")
        if (dependencyCacheUrl != null)
            maven {
                url = uri(dependencyCacheUrl)
                authentication {
                    credentials {
                        username = System.getenv("GRADLE_DEPENDENCY_CACHE_USERNAME")
                        password = System.getenv("GRADLE_DEPENDENCY_CACHE_PASSWORD")
                    }
                }
            }
        gradlePluginPortal()
        mavenCentral()
        mavenLocal()
        maven("https://gitlab.com/api/v4/projects/68438621/packages/maven") // c2x Conventions
        google()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        val dependencyCacheUrl = System.getenv("GRADLE_DEPENDENCY_CACHE_URL")
        if (dependencyCacheUrl != null)
            maven {
                url = uri(dependencyCacheUrl)
                authentication {
                    credentials {
                        username = System.getenv("GRADLE_DEPENDENCY_CACHE_USERNAME")
                        password = System.getenv("GRADLE_DEPENDENCY_CACHE_PASSWORD")
                    }
                }
            }
        mavenCentral()
        mavenLocal()
        maven("https://gitlab.com/api/v4/projects/68438621/packages/maven") // c2x Conventions
        maven("https://gitlab.com/api/v4/projects/26519650/packages/maven") // trixnity
        maven("https://gitlab.com/api/v4/projects/47538655/packages/maven") // trixnity-messenger
        maven("https://gitlab.com/api/v4/projects/58749664/packages/maven") // sysnotify
        maven("https://gitlab.com/api/v4/projects/65998892/packages/maven") // androidx
        maven("https://gitlab.com/api/v4/projects/65231927/packages/maven") // kmp-jni
        maven("https://oss.sonatype.org/content/repositories/snapshots")
        google()
    }

    versionCatalogs {
        create("sharedLibs") {
            from("de.connect2x.conventions:c2x-shared-catalog:20250903.084655")
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

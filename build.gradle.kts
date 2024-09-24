import com.mikepenz.aboutlibraries.plugin.AboutLibrariesTask
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.cli.common.isWindows
import org.jetbrains.kotlin.gradle.internal.ensureParentDirsCreated
import org.jetbrains.kotlin.incremental.createDirectory

plugins {
    // FIXME version file
    kotlin("multiplatform") version "2.0.10"
    id("com.android.application") version "8.5.2"
    id("com.google.gms.google-services") version "4.4.2"
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.aboutlibraries.plugin)
    alias(libs.plugins.download.plugin)
}

repositories {
    google()
    mavenCentral()
    maven("https://gitlab.com/api/v4/projects/26519650/packages/maven") // trixnity
    maven("https://gitlab.com/api/v4/projects/47538655/packages/maven") // trixnity-messenger
    maven("https://gitlab.com/api/v4/projects/58749664/packages/maven") // sysnotify
    mavenLocal()
}

val version = libs.versions.tammy.get()
val appName = libs.versions.appName.get()
val appNameCleaned = appName.replace("[-.\\s]".toRegex(), "").lowercase()

enum class BuildFlavor { PROD, DEV }

val buildFlavor = BuildFlavor.valueOf(System.getenv("BUILD_FLAVOR") ?: if (isCI) "PROD" else "DEV")

val licensesDir = layout.buildDirectory.dir("generated").get().dir("aboutLibraries").asFile
val licenses by tasks.registering(AboutLibrariesTask::class) {
    resultDirectory = licensesDir
    dependsOn("collectDependencies")
}

val buildConfigGenerator by tasks.registering {
    val licencesFile = licensesDir.resolve("aboutlibraries.json")
    val generatedSrc = layout.buildDirectory.dir("generated-src/kotlin/")
    inputs.file(licencesFile)
    val outputFile = generatedSrc.get()
        .dir("de/connect2x/$appNameCleaned")
        .file("BuildConfig.kt")
    outputFile.asFile.ensureParentDirsCreated()
    doLast {
        val licencesString = licencesFile.readText()
        val quotes = "\"\"\""
        val buildConfigString =
            """
            package de.connect2x.$appNameCleaned
            
            object BuildConfig {
                const val version = "$version"
                val flavor = Flavor.valueOf("$buildFlavor")
                const val appName = "$appName"
                const val appNameCleaned = "$appNameCleaned"
                val licenses = $quotes$licencesString$quotes
            }
            
            enum class Flavor { PROD, DEV }
        """.trimIndent()
        outputFile.asFile.writeText(buildConfigString)
    }
    outputs.dirs(generatedSrc)
    dependsOn(licenses)
}


kotlin {
    val kotlinJvmTarget = libs.versions.jvmTarget.get()
    androidTarget()
    jvmToolchain(JavaLanguageVersion.of(kotlinJvmTarget).asInt())
    jvm("desktop") {
        compilations.all {
            kotlinOptions.jvmTarget = kotlinJvmTarget
        }
    }
    js("web", IR) {
        browser {
            runTask {
                mainOutputFileName = "$appNameCleaned.js"
            }
            webpackTask {
                mainOutputFileName = "$appNameCleaned.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlin.RequiresOptIn")
        }
        commonMain {
            dependencies {
                implementation(libs.trixnity.messenger)
                implementation(libs.messenger.compose.view)
                implementation(compose.components.resources)
            }
            kotlin.srcDir(buildConfigGenerator.map { it.outputs })
        }
        val desktopMain by getting {
            dependencies {
                // this is needed to create lock files working on all machines
                if (System.getProperty("bundleAll") == "true") {
                    implementation(compose.desktop.linux_x64)
                    implementation(compose.desktop.linux_arm64)
                    implementation(compose.desktop.windows_x64)
                    implementation(compose.desktop.macos_x64)
                    implementation(compose.desktop.macos_arm64)
                } else {
                    implementation(compose.desktop.currentOs)
                }
                implementation(libs.logback.classic)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
        androidMain {
            dependencies {
                implementation(compose.uiTooling)
                implementation(libs.bundles.android.common)
                implementation(libs.slf4j.api)
                implementation(project.dependencies.platform(libs.firebase.bom))
                implementation(libs.firebase.messaging.ktx)
            }
        }
        val webMain by getting {
            dependencies {
                implementation(npm("copy-webpack-plugin", libs.versions.copyWebpackPlugin.get()))
            }
        }
    }
}

composeCompiler {
    enableStrongSkippingMode = true
}

compose {
    desktop {
        application {
            mainClass = "de.connect2x.$appNameCleaned.desktop.MainKt"
            jvmArgs(
//            "-Dapple.awt.application.appearance=system",
                "-Xmx1G",
            )

            buildTypes.release.proguard {
                isEnabled = false
            }
            nativeDistributions {
                modules("java.net.http", "java.sql", "java.naming")
                targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
                appResourcesRootDir.set(layout.buildDirectory) // @see https://github.com/JetBrains/compose-jb/tree/master/tutorials/Native_distributions_and_local_execution#jvm-resource-loading
                packageName = appNameCleaned
                packageVersion = version

                windows {
                    menu = true
                    iconFile.set(project.file("src/desktopMain/resources/logo.ico"))
                }
                macOS {
                    dockName = appName
                    iconFile.set(project.file("src/desktopMain/resources/logo.icns"))
                }
            }
        }
    }
}

android {
    namespace = "de.connect2x.$appNameCleaned"
    buildFeatures {
        compose = true
    }
    compileSdk = libs.versions.androidCompileSDK.get().toInt()

    defaultConfig {
        minSdk = libs.versions.androidMinimalSDK.get().toInt()
        targetSdk = libs.versions.androidTargetSDK.get().toInt()
        versionCode = libs.versions.tammyVersionCode.get().toInt()
        versionName = version
        applicationId = "de.connect2x.${appNameCleaned}"
        setProperty("archivesBaseName", "${appNameCleaned}-${version}")
        resValue("string", "app_name", appName)
        resValue("string", "scheme", appNameCleaned)
    }

    signingConfigs {
        create("release") {
            if (isCI) {
                storeFile = System.getenv("ANDROID_RELEASE_STORE_FILE")?.let { file(it) }
                storePassword = System.getenv("ANDROID_RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_RELEASE_KEY_ALIAS") ?: "upload"
                keyPassword = System.getenv("ANDROID_RELEASE_KEY_PASSWORD")
            } else {
                storeFile = projectDir.resolve("debug.keystore")
                storePassword = "android"
                keyAlias = "android"
                keyPassword = "android"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false // FIXME
            isShrinkResources = false // FIXME
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
        }
    }
    packaging {
        resources {
            excludes += "META-INF/versions/9/previous-compilation-data.bin"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    flavorDimensions += "version"
    productFlavors {
        create(buildFlavor.name) {
            when (buildFlavor) {
                BuildFlavor.PROD -> {}
                BuildFlavor.DEV -> {
                    dimension = "version"
                    applicationIdSuffix = ".dev"
                    versionNameSuffix = "-DEV"
                }
            }
        }
    }
}

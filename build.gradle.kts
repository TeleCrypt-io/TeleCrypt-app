import com.mikepenz.aboutlibraries.plugin.AboutLibrariesTask
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.cli.common.isWindows
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

val generatedSrc = layout.buildDirectory.dir("generated-src/kotlin/")

enum class BuildFlavor { PROD, DEV }

val buildFlavor = BuildFlavor.valueOf(System.getenv("BUILD_FLAVOR") ?: if (isCI) "PROD" else "DEV")

val buildConfigGenerator by tasks.registering(Sync::class) {
    from(
        resources.text.fromString(
            """
            package de.connect2x.$appNameCleaned
            
            object BuildConfig {
                const val version = "$version"
                val flavor = Flavor.valueOf("$buildFlavor")
                val appName = "$appName"
                val appNameCleaned = "$appNameCleaned"
            }
            
            enum class Flavor { PROD, DEV }
        """.trimIndent()
        )
    ) {
        rename { "BuildConfig.kt" }
        into("de/connect2x/$appNameCleaned")
    }
    into(generatedSrc)
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
            kotlin.srcDir(buildConfigGenerator.map { it.destinationDir })
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

// aboutlibraries.json ########################################
val licenses by tasks.registering(AboutLibrariesTask::class) {
    resultDirectory = layout.projectDirectory.dir("src").dir("commonMain").dir("composeResources").dir("files").asFile
    dependsOn("collectDependencies")
}

tasks.findByName("copyNonXmlValueResourcesForCommonMain")
    ?.dependsOn(licenses)

// MSIX ########################################### TODO move to open source plugin

val appDescription = "Matrix Client"
val appPackage = "de.connect2x.$appNameCleaned"
val msixFileName = "$appName-${version}.msix"
val publisherName = "connect2x GmbH"
val publisherCN = "CN=connect2x GmbH, O=connect2x GmbH, L=Dippoldiswalde, S=Saxony, C=DE"

val urlSchema = appNameCleaned
val logoFileName = "logo.png"
val logo44FileName = "logo_44.png"
val logo155FileName = "logo_155.png"

val distributionDir: Provider<Directory> =
    compose.desktop.nativeApplication.distributions.outputBaseDir
        .map { it.dir("main").dir("app") }
val msixBuildDir: File = layout.buildDirectory.dir("msix").get().asFile.apply { createDirectory() }

val createMsixManifest by tasks.registering {
    group = "release"
    doLast {
        distributionDir.get().dir(appName).file("AppxManifest.xml").asFile.apply {
            createNewFile()
            writeText(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <Package
                  xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10"
                  xmlns:uap="http://schemas.microsoft.com/appx/manifest/uap/windows10"
                  xmlns:desktop4="http://schemas.microsoft.com/appx/manifest/desktop/windows10/4"
                  xmlns:uap10="http://schemas.microsoft.com/appx/manifest/uap/windows10/10"
                  xmlns:rescap="http://schemas.microsoft.com/appx/manifest/foundation/windows10/restrictedcapabilities"
                  IgnorableNamespaces="uap10 rescap">
                  <Identity Name="$appPackage" Publisher="$publisherCN" Version="$version" ProcessorArchitecture="x64" />
                  <Properties>
                    <DisplayName>$appName</DisplayName>
                    <PublisherDisplayName>$publisherName</PublisherDisplayName>
                    <Description>$appDescription</Description>
                    <Logo>$logoFileName</Logo>
                    <uap10:PackageIntegrity>
                      <uap10:Content Enforcement="on" />
                    </uap10:PackageIntegrity>
                  </Properties>
                  <Resources>
                    <Resource Language="de-de" />
                  </Resources>
                  <Dependencies>
                    <TargetDeviceFamily Name="Windows.Desktop" MinVersion="10.0.17763.0" MaxVersionTested="10.0.22000.1" />
                  </Dependencies>
                  <Capabilities>
                    <rescap:Capability Name="runFullTrust" />
                  </Capabilities>
                  <Applications>
                    <Application
                      Id="$appPackage"
                      Executable="$appName.exe"
                      EntryPoint="Windows.FullTrustApplication">
                      <uap:VisualElements DisplayName="$appName" Description="$appDescription"	Square150x150Logo="$logo155FileName"
                         Square44x44Logo="$logo44FileName" BackgroundColor="white" />
                      <Extensions>
                        <uap:Extension Category="windows.protocol">
                          <uap:Protocol Name="$urlSchema" />
                        </uap:Extension>
                      </Extensions>
                    </Application>
                  </Applications>
                </Package>
                """.trimIndent()
            )
        }
    }
    dependsOn(tasks.getByName("createDistributable"))
    onlyIf { isWindows }
}

val createMsixAppinstaller by tasks.registering {
    group = "release"
    doLast {
        val msixBaseUrl = requireNotNull(System.getenv("MSIX_BASE_URL"))
        val appinstallerFileName = "$appName.appinstaller"
        msixBuildDir.resolve(appinstallerFileName).apply {
            createNewFile()
            writeText(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <AppInstaller
                    xmlns="http://schemas.microsoft.com/appx/appinstaller/2018"
                    Version="$version"
                    Uri="$msixBaseUrl/$appinstallerFileName">
                    <MainPackage
                        Name="$appPackage"
                        Publisher="$publisherCN"
                        Version="$version"
                        ProcessorArchitecture="x64"
                        Uri="$msixBaseUrl/$msixFileName" />
                    <UpdateSettings>
                        <OnLaunch 
                            HoursBetweenUpdateChecks="12"
                            UpdateBlocksActivation="true"
                            ShowPrompt="true" />
                        <ForceUpdateFromAnyVersion>false</ForceUpdateFromAnyVersion>
                        <AutomaticBackgroundTask />
                    </UpdateSettings>
                </AppInstaller>
                """.trimIndent()
            )
        }
    }
    onlyIf { isWindows && isCI }
}

val copyMsixLogos by tasks.registering(Copy::class) {
    group = "release"
    from(projectDir.resolve("src").resolve("desktopMain").resolve("resources")) {
        include(logoFileName, logo44FileName, logo155FileName)
    }
    into(distributionDir.get().dir(appName).asFile)
    dependsOn(tasks.getByName("createDistributable"))
    onlyIf { isWindows }
}

val msixPack by tasks.registering(Exec::class) {
    group = "release"
    workingDir(msixBuildDir)
    executable = "makeappx.exe"
    args(
        "pack",
        "/o", // always overwrite destination
        "/d", distributionDir.get().dir(appName).asFile.absolutePath, // source
        "/p", msixFileName, // destination
    )
    dependsOn(tasks.getByName("createDistributable"), createMsixManifest, copyMsixLogos)
    onlyIf { isWindows }
}

val msixSign by tasks.registering(Exec::class) {
    group = "release"
    workingDir(msixBuildDir)
    executable = "signtool.exe"
    args(
        "sign",
        "/debug",
        "/fd", "sha256", // signature digest algorithm
        "/tr", System.getenv("CODE_SIGNING_TIMESTAMP_SERVER") ?: "", // timestamp server
        "/td", "sha256", // timestamp digest algorithm
        "/sha1", System.getenv("CODE_SIGNING_THUMBPRINT") ?: "", // key selection
        msixFileName
    )
    dependsOn(msixPack)
    onlyIf { isWindows }
}

val packageMsix by tasks.registering {
    group = "release"
    dependsOn(msixPack, msixSign, createMsixAppinstaller)
    onlyIf { isWindows }
}

import com.mikepenz.aboutlibraries.plugin.AboutLibrariesTask
import org.gradle.nativeplatform.platform.internal.DefaultOperatingSystem
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.internal.ensureParentDirsCreated
import org.jetbrains.kotlin.gradle.targets.js.dsl.ExperimentalDistributionDsl
import org.jetbrains.kotlin.incremental.createDirectory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.google.services)
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

val rawAppVersion = libs.versions.appVersion.get()
val appVersion = withVersionSuffix(rawAppVersion)
val appReleasedVersion = libs.versions.appReleasedVersion.get()
val appName = "Tammy"
val appNameCleaned = appName.replace("[-.\\s]".toRegex(), "").lowercase()

group = "de.connect2x"
version = appVersion

val distributionDir: Provider<Directory> =
    compose.desktop.nativeApplication.distributions.outputBaseDir.map { it.dir("main-release") }
val appDistributionDir: Provider<Directory> = distributionDir.map { it.dir("app") }

val os: DefaultOperatingSystem =
    org.gradle.nativeplatform.platform.internal.DefaultNativePlatform.getCurrentOperatingSystem()

enum class BuildFlavor { PROD, DEV }

val buildFlavor = BuildFlavor.valueOf(System.getenv("TAMMY_BUILD_FLAVOR") ?: if (isCI) "PROD" else "DEV")

val licensesDir = layout.buildDirectory.dir("generated").get().dir("aboutLibraries").asFile
val licenses by tasks.registering(AboutLibrariesTask::class) {
    resultDirectory = licensesDir
    dependsOn("collectDependencies")
}

val buildConfigGenerator by tasks.registering {
    val licencesFile = licensesDir.resolve("aboutlibraries.json")
    val generatedSrc = layout.buildDirectory.dir("generated-src/kotlin/")
    inputs.file(licencesFile)
    doLast {
        val outputFile = generatedSrc.get()
            .dir("de/connect2x/$appNameCleaned")
            .file("BuildConfig.kt")
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
        outputFile.asFile.apply {
            ensureParentDirsCreated()
            createNewFile()
            writeText(buildConfigString)
        }
    }
    outputs.dirs(generatedSrc)
    dependsOn(licenses)
}

tasks.named("prepareKotlinIdeaImport") {
    val prepareKotlinIdeaImport = this
    kotlin.sourceSets.all {
        prepareKotlinIdeaImport.dependsOn(kotlin)
    }
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
            @OptIn(ExperimentalDistributionDsl::class)
            distribution {
                outputDirectory.set(distributionDir.map { it.dir("web") })
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
            jvmArgs("-Xmx2G")

            buildTypes.release.proguard {
                isEnabled = false // TODO
            }
            nativeDistributions {
                modules("java.net.http", "java.sql", "java.naming")
                targetFormats(
                    TargetFormat.Exe,
                    TargetFormat.Msi,
                    TargetFormat.Dmg,
                    TargetFormat.Pkg,
                    TargetFormat.Deb,
                    TargetFormat.Rpm,
                )
                appResourcesRootDir.set(layout.buildDirectory) // @see https://github.com/JetBrains/compose-jb/tree/master/tutorials/Native_distributions_and_local_execution#jvm-resource-loading
                packageName = appName
                packageVersion = rawAppVersion
                licenseFile.set(project.file("LICENSE"))

                windows {
                    menu = true
                    iconFile.set(project.file("src/desktopMain/resources/logo.ico"))
                    upgradeUuid = "8d41e87a-4f88-41a3-bad9-9d4e8279b7e9"
                }
                macOS {
                    iconFile.set(project.file("src/desktopMain/resources/logo.icns"))
                    infoPlist {
                        extraKeysRawXml = """
                            <key>CFBundleURLTypes</key>
                              <array>
                                <dict>
                                  <key>CFBundleURLName</key>
                                  <string>$appName</string>
                                  <key>CFBundleURLSchemes</key>
                                  <array>
                                    <string>$appNameCleaned</string>
                                  </array>
                                </dict>
                              </array>
                        """.trimIndent()
                    }
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
        versionCode = libs.versions.appVersionCode.get().toInt()
        versionName = appVersion
        applicationId = "de.connect2x.${appNameCleaned}"
        setProperty("archivesBaseName", "${appName}-${appVersion}")
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
            isMinifyEnabled = false // TODO
            isShrinkResources = false // TODO
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

    when (buildFlavor) {
        BuildFlavor.PROD -> {}
        BuildFlavor.DEV -> {
            flavorDimensions += "version"
            productFlavors {
                create(buildFlavor.name) {
                    dimension = "version"
                    applicationIdSuffix = ".dev"
                    versionNameSuffix = "-DEV"
                }
            }
        }
    }
}

class Distribution(
    val type: String,
    val platform: String,
    val tasks: List<String>,
    val originalFileName: String = "$appName-$rawAppVersion.$type",
) {
    val fileName = "$appName-$platform-$appVersion.$type"
    val fileNameReleased = "$appName-$platform-$appReleasedVersion.$type"
    val fileNameWithoutVersion = "$appName-$platform.$type"
}

val distributions = listOf(
    Distribution("aab", "Android", listOf("bundleRelease"), "$appName-$rawAppVersion-release.aab"),
    Distribution("deb", "Linux", listOf("packageReleaseDeb"), "${appNameCleaned}_${rawAppVersion}_amd64.deb "),
    Distribution("rpm", "Linux", listOf("packageReleaseRpm"), "$appNameCleaned-$rawAppVersion-1.x86_64.rpm"),
    Distribution("zip", "Linux", listOf("createReleaseDistributable")),
    Distribution("dmg", "Mac", listOf("packageReleaseDmg")),
    Distribution("pkg", "Mac", listOf("packageReleasePkg")),
    Distribution("zip", "Mac", listOf("createReleaseDistributable")),
    Distribution("exe", "Windows", listOf("packageReleaseExe")),
    Distribution("msi", "Windows", listOf("packageReleaseMsi")),
    Distribution("msix", "Windows", listOf("packageReleaseMsix", "notarizeReleaseMsix")),
    Distribution("zip", "Windows", listOf("createReleaseDistributable")),
    Distribution("zip", "Web", listOf("webBrowserDistribution")),
)

// #####################################################################################################################
// mxix
// #####################################################################################################################

val appDescription = "Matrix Messenger Client"
val appPackage = "de.connect2x.timmy"
val misxDistribution = distributions.first { it.type == "msix" && it.platform == "Windows" }
val publisherName = "connect2x GmbH"
val publisherCN = "CN=connect2x GmbH, O=connect2x GmbH, L=Dippoldiswalde, S=Saxony, C=DE"

val logoFileName = "logo.png"
val logo44FileName = "logo_44.png"
val logo155FileName = "logo_155.png"

fun String.toMsix() = split(".").map { it.toInt() }.let { (major, minor, patch) -> "$major.0.$minor.$patch" }

val msixDistributionDir: Provider<Directory> =
    distributionDir.map { it.dir("msix").also { it.asFile.createDirectory() } }

val createMsixManifest by tasks.registering {
    doLast {
        appDistributionDir.get().dir(appName).file("AppxManifest.xml").asFile.apply {
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
                  <Identity Name="$appPackage" Publisher="$publisherCN" Version="${rawAppVersion.toMsix()}" ProcessorArchitecture="x64" />
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
                          <uap:Protocol Name="$appNameCleaned" />
                        </uap:Extension>
                      </Extensions>
                    </Application>
                  </Applications>
                </Package>
                """.trimIndent()
            )
        }
    }
    dependsOn("createReleaseDistributable")
    onlyIf { os.isWindows }
}

val copyMsixLogos by tasks.registering(Copy::class) {
    from(projectDir.resolve("src").resolve("desktopMain").resolve("resources")) {
        include(logoFileName, logo44FileName, logo155FileName)
    }
    into(appDistributionDir.get().dir(appName).asFile)
    dependsOn("createReleaseDistributable")
    onlyIf { os.isWindows }
}

val packageReleaseMsix by tasks.registering(Exec::class) {
    group = "compose desktop"
    workingDir(msixDistributionDir)
    executable = "makeappx.exe"
    args(
        "pack",
        "/o", // always overwrite destination
        "/d", appDistributionDir.get().dir(appName).asFile.absolutePath, // source
        "/p", misxDistribution.originalFileName, // destination
    )
    dependsOn("createReleaseDistributable", createMsixManifest, copyMsixLogos)
    onlyIf { os.isWindows }
}

val notarizeReleaseMsix by tasks.registering(Exec::class) {
    group = "compose desktop"
    workingDir(msixDistributionDir)
    executable = "signtool.exe"
    args(
        "sign",
        "/debug",
        "/fd", "sha256", // signature digest algorithm
        "/tr", System.getenv("MSIX_CODE_SIGNING_TIMESTAMP_SERVER") ?: "", // timestamp server
        "/td", "sha256", // timestamp digest algorithm
        "/sha1", System.getenv("MSIX_CODE_SIGNING_THUMBPRINT") ?: "", // key selection
        misxDistribution.originalFileName
    )
    dependsOn(packageReleaseMsix)
    onlyIf { os.isWindows && isRelease }
}

// #####################################################################################################################
// upload to package registry
// #####################################################################################################################

val projectUrl = "${System.getenv("CI_API_V4_URL")}/projects/${System.getenv("CI_PROJECT_ID")}"
fun packageRegistryUrl(packageName: String, version: String, fileName: String) =
    "$projectUrl/packages/generic/$packageName/$version/$fileName"

fun uploadToPackageRegistry(filePath: Path, packageName: String, fileName: String) {
    val httpClient = HttpClient.newHttpClient()
    val request = HttpRequest.newBuilder()
        .uri(URI.create(packageRegistryUrl(packageName, appVersion, fileName)))
        .header("Content-Type", "application/octet-stream")
        .headers("JOB-TOKEN", System.getenv("CI_JOB_TOKEN"))
        .PUT(HttpRequest.BodyPublishers.ofFile(filePath))
        .build()
    httpClient.send(request, HttpResponse.BodyHandlers.ofString())
}

fun uploadDistributableToPackageRegistry(distribution: Distribution) {
    uploadToPackageRegistry(
        distributionDir.get().file("${distribution.type}/${distribution.originalFileName}").asFile.toPath(),
        distribution.fileNameWithoutVersion,
        distribution.fileName
    )
}

val platformName: String = when {
    os.isLinux -> "Linux"
    os.isMacOsX -> "Mac"
    os.isWindows -> "Windows"
    else -> os.displayName
}
val platformZipDistribution = distributions.first { it.type == "zip" && it.platform == platformName }
val zipDistributionDir = distributionDir.map { it.dir("zip").also { it.asFile.createDirectory() } }

val packageReleasePlatformZip by tasks.creating(Zip::class) {
    group = "compose desktop"
    from(appDistributionDir)

    archiveFileName = platformZipDistribution.originalFileName
    destinationDirectory = zipDistributionDir
    dependsOn(platformZipDistribution.tasks, copyMsixLogos) // copyMsixLogos because of implicit dependency
}

val uploadPlatformZipDistributable by tasks.registering {
    group = "release"
    doLast {
        uploadDistributableToPackageRegistry(platformZipDistribution)
    }
    dependsOn(packageReleasePlatformZip)
}

val uploadAndroidDistributable by tasks.registering {
    group = "release"
    val thisDistribution = distributions.first { it.type == "aab" && it.platform == "Android" }
    doLast {
        uploadDistributableToPackageRegistry(thisDistribution)
    }
    dependsOn(thisDistribution.tasks)
}

val uploadLinuxDistributable by tasks.registering {
    group = "release"
    val thisDistributions = distributions.filter { it.platform == "Linux" && it.type != "zip" }
    doLast {
        thisDistributions.forEach {
            uploadDistributableToPackageRegistry(it)
        }
    }
    dependsOn(
        thisDistributions.flatMap { it.tasks.toList() }
    )
    onlyIf { os.isLinux }
}

val webZipDistribution = distributions.first { it.type == "zip" && it.platform == "Web" }

val packageReleaseWebZip by tasks.creating(Zip::class) {
    group = "compose desktop"
    from(distributionDir.map { it.dir("web") })
    archiveFileName = webZipDistribution.originalFileName
    destinationDirectory = zipDistributionDir
    dependsOn(webZipDistribution.tasks)
}

val uploadWebZipDistributable by tasks.registering {
    group = "release"
    doLast {
        uploadDistributableToPackageRegistry(webZipDistribution)
    }
    dependsOn(packageReleaseWebZip)
}

val uploadMacDistributable by tasks.registering {
    group = "release"
    val thisDistributions = distributions.filter { it.platform == "Mac" && it.type != "zip" }
    doLast {
        thisDistributions.forEach {
            uploadDistributableToPackageRegistry(it)
        }
    }
    dependsOn(
        thisDistributions.flatMap { it.tasks.toList() }
    )
    onlyIf { os.isMacOsX }
}

val uploadWindowsDistributable by tasks.registering {
    group = "release"
    val thisDistributions = distributions.filter { it.platform == "Windows" && it.type != "zip" }
    doLast {
        thisDistributions.forEach {
            uploadDistributableToPackageRegistry(it)
        }
    }
    dependsOn(
        thisDistributions.flatMap { it.tasks.toList() }
    )
    onlyIf { os.isWindows }
}

// #####################################################################################################################
// release
// #####################################################################################################################

val publicDir = provider { layout.projectDirectory.asFile.resolve("public").also { it.createDirectory() } }

fun getReleasedFileUrl(distribution: Distribution) =
    "https://gitlab.com/connect2x/tammy/-/releases/$appReleasedVersion/downloads/${distribution.fileNameReleased}"

val createGitLabPagesRedirects by tasks.registering {
    doLast {
        fun redirect(distribution: Distribution) =
            "/${distribution.fileNameWithoutVersion} ${
                packageRegistryUrl(
                    distribution.fileNameWithoutVersion,
                    appReleasedVersion,
                    distribution.fileNameReleased
                )
            } 302"

        publicDir.get()
            .resolve("_redirects")
            .apply {
                createNewFile()
                writeText(distributions.joinToString("\r\n") { redirect(it) })
            }
    }
}

val createGitLabPagesMsixAppinstaller by tasks.registering {
    doLast {
        val msixBaseUrl = "https://tammy.connect2x.de"
        val appinstallerFileName = "$appName.appinstaller"
        val uri = getReleasedFileUrl(Distribution("msix", "Windows", listOf()))
        publicDir.get()
            .resolve(appinstallerFileName)
            .apply {
                createNewFile()
                writeText(
                    """
                        <?xml version="1.0" encoding="utf-8"?>
                        <AppInstaller
                            xmlns="http://schemas.microsoft.com/appx/appinstaller/2018"
                            Version="${appReleasedVersion.toMsix()}"
                            Uri="$msixBaseUrl/$appinstallerFileName">
                            <MainPackage
                                Name="$appPackage"
                                Publisher="$publisherCN"
                                Version="${appReleasedVersion.toMsix()}"
                                ProcessorArchitecture="x64"
                                Uri="$uri" />
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
}

val createGitLabPagesFiles by tasks.registering {
    group = "release"
    dependsOn(createGitLabPagesRedirects, createGitLabPagesMsixAppinstaller)
}

val createGitLabRelease by tasks.registering {
    group = "release"
    doLast {
        fun assetsLinkJson(distribution: Distribution) =
            """
                {
                    "name": "${distribution.fileName}",
                    "url": "${
                packageRegistryUrl(
                    distribution.fileNameWithoutVersion,
                    appReleasedVersion,
                    distribution.fileNameReleased
                )
            }"
                }
            """.trimIndent()


        val httpClient = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$projectUrl/releases"))
            .header("Content-Type", "application/json")
            .headers("JOB-TOKEN", System.getenv("CI_JOB_TOKEN"))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """
                        {
                            "name": "$appVersion",
                            "tag_name": "v$appVersion",
                            "assets": {
                                "links": [
                                    ${distributions.joinToString(",") { assetsLinkJson(it) }}
                                ]
                            }
                        }
                    """.trimIndent()
                )
            )
            .build()
        httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }
}
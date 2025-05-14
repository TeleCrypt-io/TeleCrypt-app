import org.gradle.nativeplatform.platform.internal.DefaultArchitecture
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform
import org.gradle.nativeplatform.platform.internal.DefaultOperatingSystem
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.internal.ensureParentDirsCreated
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree
import org.jetbrains.kotlin.gradle.targets.js.dsl.ExperimentalDistributionDsl
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import de.connect2x.conventions.registerMultiplatformLicensesTasks
import org.gradle.internal.extensions.stdlib.capitalized

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.google.services)
    alias(libs.plugins.aboutlibraries.plugin)
    alias(libs.plugins.download.plugin)
    alias(libs.plugins.c2xConventions)
    de.connect2x.tammy.plugins.flatpak
}

val appVersion = libs.versions.appVersion.get()
val appPublishedVersion = libs.versions.appPublishedVersion.get()
if (isRelease)
    require(appVersion == appPublishedVersion) {
        "when creating a release, the appVersion ($appVersion) must the same as the appPublishedVersion($appPublishedVersion)"
    }
val appSuffixedVersion = withVersionSuffix(appVersion)
val appName = "Tammy"
val appId = "de.connect2x.tammy"
val privacyInfo = File("website/content/privacy.de-DE.md").readText().substringAfterMarkdownFrontMatter()
val imprint = File("website/content/imprint.de-DE.md").readText().substringAfterMarkdownFrontMatter()

group = "de.connect2x"
version = appSuffixedVersion

val distributionDir: Provider<Directory> =
    compose.desktop.nativeApplication.distributions.outputBaseDir.map { it.dir("main-release") }
val appDistributionDir: Provider<Directory> = distributionDir.map { it.dir("app") }

val os: DefaultOperatingSystem = DefaultNativePlatform.getCurrentOperatingSystem()
val arch: DefaultArchitecture = DefaultArchitecture(DefaultNativePlatform.getCurrentArchitecture().name)

enum class BuildFlavor { PROD, DEV }

val buildFlavor =
    BuildFlavor.valueOf(System.getenv("TAMMY_BUILD_FLAVOR") ?: if (isCI) "PROD" else "DEV")

registerMultiplatformLicensesTasks { licenseTask, target, variant ->
    // TODO: move this into c2x-conventions eventually
    val targetName = target.targetName
    val buildConfigTask =
        tasks.register("generateBuildConfig${targetName.capitalized()}${variant.capitalized()}") {
            dependsOn(licenseTask)
            group = "build config"
            val generatedSrc =
                layout.buildDirectory.dir("generatedSrc/${targetName}Main/kotlin")
            doLast {
                val outputFile = generatedSrc.get()
                    .dir(appId.replace(".", "/"))
                    .file("BuildConfig.kt")
                val quotes = "\"\"\""
                val licencesString = licenseTask.get().outputFile.get().asFile.readText()
                    .replace("$", "\${'$'}")
                    .replace(quotes, "")

                val buildConfigString =
                    """
            package $appId

            actual val BuildConfig: CommonBuildConfig = object : CommonBuildConfig {
                override val version: String = "$version"
                override val flavor: Flavor = Flavor.valueOf("$buildFlavor")
                override val appName: String = "$appName"
                override val appId: String = "$appId"
                override val licenses: String = $quotes$licencesString$quotes
                override val privacyInfo: String = $quotes$privacyInfo$quotes
                override val imprint: String = $quotes$imprint$quotes
            }
        """.trimIndent()
                outputFile.asFile.apply {
                    ensureParentDirsCreated()
                    createNewFile()
                    writeText(buildConfigString)
                }
            }
            outputs.dirs(generatedSrc)
        }
    kotlin.sourceSets.named("${targetName}Main") {
        kotlin.srcDir(buildConfigTask.map { it.outputs })
    }
}

kotlin {
    val kotlinJvmTarget = libs.versions.jvmTarget.get()
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        instrumentedTestVariant.sourceSetTree.set(KotlinSourceSetTree.test)
    }
    jvmToolchain(JavaLanguageVersion.of(kotlinJvmTarget).asInt())
    jvm("desktop") {
        compilations.all {
            kotlinOptions.jvmTarget = kotlinJvmTarget
        }
    }
    js("web", IR) {
        browser {
            runTask {
                mainOutputFileName = "$appId.js"
            }
            webpackTask {
                mainOutputFileName = "$appId.js"
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
            //kotlin.srcDir(buildConfigGenerator.map { it.outputs })
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
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                implementation(compose.uiTest)
                implementation(libs.okio.fakefilesystem)
            }
        }
    }
}

dependencies {
    androidTestImplementation(libs.screengrab)
    androidTestImplementation(libs.androidx.ui.test.junit4.android)
    debugImplementation(libs.androidx.ui.test.manifest)
}

composeCompiler {
    enableStrongSkippingMode = true
}

compose {
    desktop {
        application {
            mainClass = "$appId.desktop.MainKt"
            jvmArgs("-Xmx2G")

            buildTypes.release.proguard {
                isEnabled = false // TODO
            }
            nativeDistributions {
                modules("java.net.http", "java.sql", "java.naming", "jdk.accessibility")
                targetFormats(
                    // TargetFormat.Exe, // no deeplink support
                    // TargetFormat.Msi, // no deeplink support
                    TargetFormat.Dmg,
                    // TargetFormat.Pkg, // signing problems
                    // TargetFormat.Deb, // no deeplink support
                    // TargetFormat.Rpm, // no deeplink support
                )
                packageName = appName
                packageVersion = appVersion

                linux {
                    iconFile.set(project.file("src/desktopMain/resources/logo.png"))
                    modules("jdk.security.auth")
                }
                windows {
                    menu = true
                    iconFile.set(project.file("src/desktopMain/resources/logo.ico"))
                    upgradeUuid = "8D41E87A-4F88-41A3-BAD9-9D4E8279B7E9"
                }
                macOS {
                    val appleKeychainFile = file("apple_keychain.keychain")
                    if (appleKeychainFile.exists()) {
                        bundleID = appId
                        signing {
                            sign = true
                            keychain = "apple_keychain.keychain"
                            identity = "connect2x GmbH"
                        }
                        notarization {
                            teamID = System.getenv("APPLE_TEAM_ID")
                            appleID = System.getenv("APPLE_ID")
                            password = System.getenv("APPLE_NOTARIZATION_PASSWORD")
                        }
                    }
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
                                    <string>$appId</string>
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
    namespace = appId
    buildFeatures {
        compose = true
    }
    compileSdk = libs.versions.androidCompileSDK.get().toInt()

    defaultConfig {
        minSdk = libs.versions.androidMinimalSDK.get().toInt()
        targetSdk = libs.versions.androidTargetSDK.get().toInt()
        versionCode = System.getenv("CI_PIPELINE_IID")?.toInt() ?: 1
        versionName = appSuffixedVersion
        applicationId = appId
        setProperty("archivesBaseName", appName)
        resValue("string", "app_name", appName)
        resValue("string", "scheme", appId)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystoreFile = file("android_keystore.jks")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
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

val gitLabProjectUrl =
    "${System.getenv("CI_API_V4_URL")}/projects/${System.getenv("CI_PROJECT_ID")}"

data class Distribution(
    val type: String,
    val platform: String,
    val architecture: String,
    val tasks: List<String>,
    val originalFileName: String = "$appName-$appVersion.$type",
) {
    val fileName = "$appName-$platform-$architecture-$appPublishedVersion.$type"

    fun packageRegistryUrl(published: Boolean) =
        "$gitLabProjectUrl/packages/generic/$appName-$platform-$architecture.$type/" +
                "${if (published) appPublishedVersion else appSuffixedVersion}/" +
                if (published) fileName else "$appName-$platform-$architecture-$appSuffixedVersion.$type"
}

val distributions = listOf(
    Distribution(
        "aab", "Android", "universal",
        listOf("bundleRelease"),
        "$appName-release.aab"
    ),
    Distribution(
        "apk", "Android", "universal",
        listOf("assembleRelease"),
        "$appName-release.apk"
    ),
    Distribution(
        "zip", "Linux", "x64",
        listOf("packageReleasePlatformZip")
    ),
    Distribution(
        "dmg", "MacOS", "x64",
        listOf("packageReleaseDmg", "notarizeReleaseDmg")
    ),
    Distribution(
        "zip", "MacOS", "x64",
        listOf("packageReleasePlatformZip")
    ),
    Distribution(
        "dmg", "MacOS", "arm64",
        listOf("packageReleaseDmg", "notarizeReleaseDmg")
    ),
    Distribution(
        "zip", "MacOS", "arm64",
        listOf("packageReleasePlatformZip")
    ),
    Distribution(
        "msix", "Windows", "x64",
        listOf("packageReleaseMsix", "notarizeReleaseMsix")
    ),
    Distribution(
        "zip", "Windows", "x64",
        listOf("packageReleasePlatformZip")
    ),
    Distribution(
        "zip", "Web", "universal",
        listOf("packageReleaseWebZip")
    ),
    Distribution(
        "flatpak", "Linux", "x64",
        listOf("packageReleaseFlatpakBundle"),
    ),
    Distribution(
        "flatpak-sources.zip", "Linux", "x64",
        listOf("packageReleaseFlatpakSources"),
    )
)

// #####################################################################################################################
// mxix
// #####################################################################################################################

val appDescription = "Matrix Messenger Client"
val misxDistribution = distributions.first { it.type == "msix" && it.platform == "Windows" }
val publisherName = "connect2x GmbH"
val publisherCN = "CN=connect2x GmbH, O=connect2x GmbH, L=Dippoldiswalde, S=Saxony, C=DE"

val logoFileName = "logo.png"
val logo44FileName = "logo_44.png"
val logo155FileName = "logo_155.png"

fun String.toMsix() =
    substringBefore("-").split(".").map { it.toInt() }
        .let { (major, minor, patch) -> "$major.0.$minor.$patch" }

val msixDistributionDir: Provider<Directory> =
    distributionDir.map { it.dir("msix").also { it.asFile.mkdirs() } }

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
                  <Identity Name="$appId" Publisher="$publisherCN" Version="${appVersion.toMsix()}" ProcessorArchitecture="x64" />
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
                      Id="$appId"
                      Executable="$appName.exe"
                      EntryPoint="Windows.FullTrustApplication">
                      <uap:VisualElements DisplayName="$appName" Description="$appDescription"	Square150x150Logo="$logo155FileName"
                         Square44x44Logo="$logo44FileName" BackgroundColor="white" />
                      <Extensions>
                        <uap:Extension Category="windows.protocol">
                          <uap:Protocol Name="$appId" />
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
        "/td", "sha256" // timestamp digest algorithm
    )
    System.getenv("WINDOWS_CODE_SIGNING_TIMESTAMP_SERVER")
        ?.let { args("/tr", it) } // timestamp server
    System.getenv("WINDOWS_CODE_SIGNING_THUMBPRINT")
        ?.let { args("/sha1", it) } // key selection
    args(misxDistribution.originalFileName)
    dependsOn(packageReleaseMsix)
    onlyIf { os.isWindows && isRelease }
}

// #####################################################################################################################
// flatpak
// #####################################################################################################################

flatpak {
    applicationId = appId
    applicationName = appName

    flatpakRemoteName = "flathub"
    flatpakRemoteLocation = "https://dl.flathub.org/repo/flathub.flatpakrepo"
    // This is explicitly not in the build directory as this would bloat the cache by about 3GB without
    // actually being able to reuse anything.
    flatpakCacheDirectory = layout.projectDirectory.dir(".flatpak-cache")
    flatpakDependencies = mapOf(
        "org.freedesktop.Platform/x86_64/24.08" to "4032cef5b0b698b46df684fc68527252cb7350f39bd90b25bb0c79766911de23",
        "org.freedesktop.Platform.GL.default/x86_64/24.08" to "0afe3466c4abe98c6d77d6740f69fa18c9940e851176d3114903777f91469455",
        "org.freedesktop.Platform.GL.default/x86_64/24.08extra" to "10a5153b2b4aee357d43cecf7987ee972acd2d3d282b0f2e317c9caa4fd27c8c",
        "org.freedesktop.Platform.openh264/x86_64/2.4.1" to "50b21d3fc221e861a8573f642bd3e582453b68c8be9962614461ee4d1b0ea38e",
        "org.freedesktop.Sdk/x86_64/24.08" to "c452562271263c5cf32cec693f20a99f77c0771d9a13f607991984e927f0a602"
    )

    desktopTemplate = file("flatpak/app.desktop.tmpl")
    manifestTemplate = file("flatpak/manifest.json.tmpl")
    metainfoTemplate = file("flatpak/metainfo.xml.tmpl")
    iconsDirectory = layout.projectDirectory.dir("flatpak/icons")

    appDistributionDirectory = provider {
        tasks
            .named<AbstractJPackageTask>("createReleaseDistributable")
            .flatMap { it.destinationDir.dir(appName) }
    }.flatMap { it }

    developerName = publisherName
    publishedVersion = appVersion
    homepage = "https://tammy.connect2x.de"
}

val flatpakBundleDistribution =
    distributions.first { it.type == "flatpak" && it.platform == "Linux" }
val packageReleaseFlatpakBundle by tasks.registering {
    group = "compose desktop"

    inputs.file(flatpak.bundleFile)
    outputs.file(distributionDir.map { it.file("${flatpakBundleDistribution.type}/${flatpakBundleDistribution.originalFileName}") })

    doLast {
        val bundle = inputs.files.singleFile
        val target = outputs.files.singleFile

        bundle.copyTo(target, overwrite = true)
    }
}

// The point of this is that one can just import them in the de.connect2x.yml manifest when publishing to flathub
// The archive contains the structure with all files needed for the flatpak, e.g. metainfo, icons, desktop entry, etc. and can just be
// This can be built without any flatpak tooling
val flatpakSourcesDistribution =
    distributions.first { it.type == "flatpak-sources.zip" && it.platform == "Linux" }
val packageReleaseFlatpakSources by tasks.registering {
    group = "compose desktop"

    inputs.file(flatpak.sourcesZip)
    outputs.file(distributionDir.map { it.file("${flatpakSourcesDistribution.type}/${flatpakSourcesDistribution.originalFileName}") })

    doLast {
        val bundle = inputs.files.singleFile
        val target = outputs.files.singleFile

        bundle.copyTo(target, overwrite = true)
    }
}


// #####################################################################################################################
// upload to package registry
// #####################################################################################################################

fun uploadToPackageRegistry(filePath: Path, distribution: Distribution) {
    val httpClient = HttpClient.newHttpClient()
    val request = HttpRequest.newBuilder()
        .uri(URI.create(distribution.packageRegistryUrl(false)))
        .header("Content-Type", "application/octet-stream")
        .headers("JOB-TOKEN", System.getenv("CI_JOB_TOKEN"))
        .PUT(HttpRequest.BodyPublishers.ofFile(filePath))
        .build()
    httpClient.send(request, HttpResponse.BodyHandlers.ofString())
}

fun uploadDistributableToPackageRegistry(distribution: Distribution) {
    uploadToPackageRegistry(
        distributionDir.get()
            .file("${distribution.type}/${distribution.originalFileName}").asFile.toPath(),
        distribution,
    )
}

val platformName: String = when {
    os.isLinux -> "Linux"
    os.isMacOsX -> "MacOS"
    os.isWindows -> "Windows"
    else -> throw IllegalStateException("${os.name} is not supported")
}
val architectureName: String = when {
    arch.isAmd64 -> "x64"
    arch.isArm64 -> "arm64"
    else -> throw IllegalStateException("${arch.name} is not supported")
}

val platformZipDistribution =
    distributions.first { it.type == "zip" && it.platform == platformName && it.architecture == architectureName }
val zipDistributionDir = distributionDir.map { it.dir("zip").also { it.asFile.mkdirs() } }

val packageReleasePlatformZip by tasks.creating(Zip::class) {
    group = "compose desktop"
    from(appDistributionDir)

    archiveFileName = platformZipDistribution.originalFileName
    destinationDirectory = zipDistributionDir
    dependsOn.addAll(
        listOf(
            "createReleaseDistributable",
            copyMsixLogos
        )
    )// copyMsixLogos because of implicit dependency
}

val webZipDistribution = distributions.first { it.type == "zip" && it.platform == "Web" }

val packageReleaseWebZip by tasks.creating(Zip::class) {
    group = "compose desktop"
    from(distributionDir.map { it.dir("web") })
    archiveFileName = webZipDistribution.originalFileName
    destinationDirectory = zipDistributionDir
    dependsOn.add("webBrowserDistribution")
}

val uploadWebZipDistributable by tasks.registering {
    group = "release"
    doLast {
        uploadDistributableToPackageRegistry(webZipDistribution)
    }
    dependsOn.addAll(webZipDistribution.tasks)
}

val uploadPlatformDistributable by tasks.registering {
    group = "release"
    val thisDistributions =
        distributions.filter { it.platform == platformName && it.architecture == architectureName }
    doLast {
        thisDistributions.forEach {
            uploadDistributableToPackageRegistry(it)
        }
    }
    dependsOn.addAll(thisDistributions.flatMap { it.tasks.toList() })
}

val uploadAndroidDistributable by tasks.registering {
    group = "release"
    val aabDistribution = distributions.first { it.type == "aab" && it.platform == "Android" }
    val apkDistribution = distributions.first { it.type == "apk" && it.platform == "Android" }
    doLast {
        uploadToPackageRegistry(
            layout.buildDirectory.get()
                .file("outputs/bundle/release/${aabDistribution.originalFileName}").asFile.toPath(),
            aabDistribution
        )
        uploadToPackageRegistry(
            layout.buildDirectory.get()
                .file("outputs/apk/release/${apkDistribution.originalFileName}").asFile.toPath(),
            apkDistribution
        )
    }
    dependsOn.addAll(aabDistribution.tasks)
    dependsOn.addAll(apkDistribution.tasks)
}

// #####################################################################################################################
// release
// #####################################################################################################################

val createWebsiteDownloadLinks by tasks.registering {
    doLast {
        fun links(distribution: Distribution) =
            "$appName${distribution.platform}${distribution.architecture}${distribution.type}: " +
                    distribution.packageRegistryUrl(true)

        layout.projectDirectory.asFile
            .resolve("website")
            .resolve("config")
            .resolve("_default").also { it.mkdirs() }
            .resolve("params.yaml")
            .apply {
                createNewFile()
                writeText("downloads:\r\n  " + distributions.joinToString("\r\n  ") { links(it) })
            }
    }
}

fun createWebsiteMsixAppinstaller(architecture: String) {
    val websiteBaseUrl = "https://tammy.connect2x.de"
    val appinstallerFileName = "$appName-Windows-$architecture.appinstaller"
    val msixDistribution =
        distributions.first { it.platform == "Windows" && it.type == "msix" && it.architecture == architecture }
    val uri = msixDistribution.packageRegistryUrl(true)
    layout.projectDirectory.asFile
        .resolve("website")
        .resolve("static").also { it.mkdirs() }
        .resolve(appinstallerFileName)
        .apply {
            createNewFile()
            writeText(
                """
                        <?xml version="1.0" encoding="utf-8"?>
                        <AppInstaller
                            xmlns="http://schemas.microsoft.com/appx/appinstaller/2018"
                            Version="${appPublishedVersion.toMsix()}"
                            Uri="$websiteBaseUrl/$appinstallerFileName">
                            <MainPackage
                                Name="$appId"
                                Publisher="$publisherCN"
                                Version="${appPublishedVersion.toMsix()}"
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

val createWebsiteMsixX64Appinstaller by tasks.registering {
    doLast {
        createWebsiteMsixAppinstaller("x64")
    }
}

val createWebsiteFastlaneMetadata by tasks.registering(Copy::class) {
    from(layout.projectDirectory.dir("fastlane").dir("metadata"))
    into(
        layout.projectDirectory.asFile
            .resolve("website")
            .resolve("static").also { it.mkdirs() }
            .resolve("fastlane").resolve("metadata").also { it.mkdirs() }
    )
}

val prepareWebsite by tasks.registering {
    group = "release"
    dependsOn(
        createWebsiteDownloadLinks,
        createWebsiteMsixX64Appinstaller,
        createWebsiteFastlaneMetadata
    )
}

val createGitLabRelease by tasks.registering {
    group = "release"
    doLast {
        fun assetsLinkJson(distribution: Distribution) =
            """
                {
                    "name": "${distribution.fileName}",
                    "url": "${distribution.packageRegistryUrl(true)}"
                }
            """.trimIndent()


        val httpClient = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$gitLabProjectUrl/releases"))
            .header("Content-Type", "application/json")
            .headers("JOB-TOKEN", System.getenv("CI_JOB_TOKEN"))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    """
                        {
                            "name": "$appPublishedVersion",
                            "tag_name": "v$appPublishedVersion",
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

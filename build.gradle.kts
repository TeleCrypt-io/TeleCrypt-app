import de.connect2x.conventions.*
import org.gradle.internal.extensions.stdlib.capitalized
import org.gradle.nativeplatform.platform.internal.DefaultArchitecture
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform
import org.gradle.nativeplatform.platform.internal.DefaultOperatingSystem
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.jetbrains.kotlin.gradle.internal.ensureParentDirsCreated
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path

plugins {
    alias(sharedLibs.plugins.kotlin.multiplatform)
    alias(sharedLibs.plugins.android.application)
    alias(sharedLibs.plugins.compose.multiplatform)
    alias(sharedLibs.plugins.compose.compiler)
    alias(sharedLibs.plugins.aboutLibraries.plugin)
    alias(libs.plugins.download.plugin)
    alias(sharedLibs.plugins.c2xConventions)
    alias(sharedLibs.plugins.google.services)
    alias(sharedLibs.plugins.kotlinx.kover)
    de.connect2x.tammy.plugins.flatpak
}

configureJava(sharedLibs.versions.targetJvm)

val appVersion = libs.versions.appVersion.get()
val appPublishedVersion = libs.versions.appPublishedVersion.get()
if (CI.isRelease)
    require(appVersion == appPublishedVersion) {
        "when creating a release, the appVersion ($appVersion) must the same as the appPublishedVersion($appPublishedVersion)"
    }
val appSuffixedVersion = withVersionSuffix(libs.versions.appVersion)
val appName = "TeleCrypt Messenger"
val appId = "io.telecrypt.app"
val appHomepage = "https://telecrypt.io"

fun String.substringAfterMarkdownFrontMatter(): String {
    return this.substringAfter("---").substringAfter("---")
}

val privacyInfo =
    File("website/content/privacy.de-DE.md").readText().substringAfterMarkdownFrontMatter()
val imprint =
    File("website/content/imprint.de-DE.md").readText().substringAfterMarkdownFrontMatter()

group = "de.connect2x"
version = appSuffixedVersion

val distributionDir: Provider<Directory> =
    compose.desktop.nativeApplication.distributions.outputBaseDir.map { it.dir("main-release") }

val webDistributionDir: Provider<Directory> = project.layout.buildDirectory.dir("dist")
val appDistributionDir: Provider<Directory> = distributionDir.map { it.dir("app") }

val os: DefaultOperatingSystem = DefaultNativePlatform.getCurrentOperatingSystem()
val arch: DefaultArchitecture =
    DefaultArchitecture(DefaultNativePlatform.getCurrentArchitecture().name)

enum class BuildFlavor { PROD, DEV }

val buildFlavor =
    BuildFlavor.valueOf(System.getenv("TAMMY_BUILD_FLAVOR") ?: if (CI.isCI) "PROD" else "DEV")

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
                override val oAuth2ClientUrl: String = "$appHomepage"
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
    defaultCompilerOptions()
    withAndroid(minSdk = libs.versions.androidMinSdk)
    withJvm()
    withWeb {
        withBrowser {
            commonWebpackConfig {
                showProgress = true
            }
            runTask {
                mainOutputFileName.set("$appId.js")
            }
            webpackTask {
                mainOutputFileName.set("$appId.js")
            }
        }
        binaries.executable()
        useEsModules()
    }
    withIos {
        binaries.framework {
            export(sharedLibs.essenty.lifecycle)
            export(libs.trixnity.messenger.compose.view)
            baseName = "TeleCryptUI"
            isStatic = true
        }
    }
    applyDefaultHierarchyTemplate()
    sourceSets {
        all {
            languageSettings.optIn("kotlin.RequiresOptIn")
        }
        commonMain {
            dependencies {
                api(libs.trixnity.messenger.compose.view)
                implementation(libs.trixnity.messenger)
                implementation(libs.trixnity.messenger.compose.view.typography.nunito)
                implementation(compose.components.resources)
                implementation(sharedLibs.lognity.core)
                implementation(sharedLibs.lognity.config)
                implementation(sharedLibs.lognity.core.config)
            }
            //kotlin.srcDir(buildConfigGenerator.map { it.outputs })
        }
        jvmMain {
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
                implementation(sharedLibs.kotlinx.coroutines.swing)
            }
        }
        iosMain {
            dependencies {
                api(sharedLibs.essenty.lifecycle) // Needed for export as iOS framework
                implementation(libs.trixnity.messenger.notification.apns)
            }
        }
        androidMain {
            dependencies {
                implementation(compose.uiTooling)
                implementation(sharedLibs.androidx.appcompat)
                implementation(sharedLibs.androidx.work.runtime.ktx)
                implementation(sharedLibs.androidx.lifecycle.livedata.ktx)
                implementation(sharedLibs.androidx.activity.compose)
                implementation(libs.trixnity.messenger.notification.unifiedpush)
            }
        }

        webMain {
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
    androidTestImplementation(sharedLibs.compose.ui.test.junit4.android)
    debugImplementation(sharedLibs.compose.ui.test.android.manifest)
}

val distributionJavaHome = runCatching {
    javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    }.get().metadata.installationPath.asFile.absolutePath
}.getOrDefault(System.getenv("JAVA_HOME") ?: "/usr/lib/jvm/java-21-openjdk-amd64")

compose {
    desktop {
        application {
            mainClass = "$appId.Main"
            javaHome = distributionJavaHome
            jvmArgs("-Xmx2G")

            buildTypes.release.proguard {
                isEnabled.set(false) // TODO
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
                    iconFile.set(project.file("src/jvmMain/resources/logo.png"))
                    modules("jdk.security.auth")
                }
                windows {
                    menu = true
                    iconFile.set(project.file("src/jvmMain/resources/logo.ico"))
                    upgradeUuid = "8D41E87A-4F88-41A3-BAD9-9D4E8279B7E9"
                }
                macOS {
                    val appleKeychainFile = file("apple_keychain.keychain")
                    if (appleKeychainFile.exists()) {
                        bundleID = appId
                        signing {
                            sign.set(true)
                            keychain.set("apple_keychain.keychain")
                            identity.set("connect2x GmbH")
                        }
                        notarization {
                            teamID.set(System.getenv("APPLE_TEAM_ID"))
                            appleID.set(System.getenv("APPLE_ID"))
                            password.set(System.getenv("APPLE_NOTARIZATION_PASSWORD"))
                        }
                    }
                    iconFile.set(project.file("src/jvmMain/resources/logo.icns"))
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
    productFlavors {
        flavorDimensions.add("version")

        // This flavor requires the Google Play Services to be available on the target device. Apps built with this
        // configuration can't be published in F-Droid.
        register("googlePlay") {
            dimension = "version"
        }

        // This flavor removes the requirement of Google Play Services to be available on the target device. Apps built
        // with this configuration are intented to be published on F-Droid.
        register("libre") {
            dimension = "version"
        }
    }
    defaultConfig {
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toInt()
            ?: System.getenv("CI_PIPELINE_IID")?.toInt()
            ?: 1
        versionName = appSuffixedVersion
        applicationId = appId

        resValue("string", "app_name", appName)
        resValue("string", "scheme", appId)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    base {
        archivesName.set(appName)
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
}

// This MUST be loaded after the Android extension code because "googlePlayImplementation" is available after the
// Android code was loaded. This inserts the FCM notification provider of Trixnity Messenger into the Google Play
// product flavor source set.
dependencies {
    "googlePlayImplementation"(libs.trixnity.messenger.notification.fcm)
    "googlePlayImplementation"(sharedLibs.firebase.messaging)
}

tasks.register("scanAndroidLibreForNonFreeClasses", Exec::class) {
    group = "verification"
    description = "Scanning the libre APK for non-free components like Google Services"
    dependsOn("assembleLibreDebug")

    val file = layout.buildDirectory.file("outputs/apk/libre/debug/Tammy-libre-debug.apk")
    inputs.file(file).withPropertyName("apkFile")
    commandLine("fdroid", "scanner", file.get().asFile.absolutePath, "-v")

    val outputStream = ByteArrayOutputStream()
    errorOutput = outputStream
    doLast {
        val problemClasses = outputStream.toByteArray().toString(Charsets.UTF_8).split("\n")
            .filter { it.contains("Problem: found class") }
            .map { it.split(": ")[2].replace("found class ", "") }
        if (problemClasses.isNotEmpty()) {
            problemClasses.forEach {
                println("Found problem in $it")
            }

            throw GradleException("F-Droid scanner found ${problemClasses.size} problems")
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
        "aab", "Android", "googlePlay",
        listOf("bundleGooglePlayRelease"),
        "$appName-googlePlay-release.aab"
    ),
    Distribution(
        "apk", "Android", "googlePlay",
        listOf("assembleGooglePlayRelease"),
        "$appName-googlePlay-release.apk"
    ),
    Distribution(
        "aab", "Android", "libre",
        listOf("bundleLibreRelease"),
        "$appName-libre-release.aab"
    ),
    Distribution(
        "apk", "Android", "libre",
        listOf("assembleLibreRelease"),
        "$appName-libre-release.apk"
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
val publisherCN = "CN=connect2x GmbH, O=connect2x GmbH, L=Dresden, C=DE"

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
    from(projectDir.resolve("src").resolve("jvmMain").resolve("resources")) {
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
        "/v",
        "/debug",
        "/fd",
        "SHA256",
        "/tr",
        "http://timestamp.acs.microsoft.com",
        "/td",
        "SHA256",
        "/dlib",
        "C:/MicrosoftArtifactSigningClientTools/Azure.CodeSigning.Dlib.dll",
        "/dmdf",
        project.layout.projectDirectory.file("azure_artifact_signing_metadata.json").asFile.absolutePath,
    )
    System.getenv("WINDOWS_CODE_SIGNING_TIMESTAMP_SERVER")
        ?.takeIf { it.isNotBlank() }
        ?.let { args("/tr", it) } // timestamp server

    val certFileProvider = layout.buildDirectory.file("cert.pfx")
    doFirst {
        val certFile = certFileProvider.get().asFile
        val thumbprint = System.getenv("WINDOWS_CODE_SIGNING_THUMBPRINT")?.takeIf { it.isNotBlank() }
        when {
            certFile.exists() -> {
                args("/f", certFile.absolutePath)
                System.getenv("WINDOWS_CERT_PASSWORD")?.takeIf { it.isNotBlank() }?.let { args("/p", it) }
            }
            thumbprint != null -> args("/sha1", thumbprint)
            else -> logger.warn("Neither WINDOWS_CERT_FILE_BASE64 nor WINDOWS_CODE_SIGNING_THUMBPRINT provided — MSIX will be unsigned")
        }
    }
    args(misxDistribution.originalFileName)
    dependsOn(packageReleaseMsix)
    onlyIf {
        val certFile = certFileProvider.get().asFile
        val thumbprint = System.getenv("WINDOWS_CODE_SIGNING_THUMBPRINT")
        os.isWindows && isRelease && (certFile.exists() || !thumbprint.isNullOrBlank())
    }
}

// #####################################################################################################################
// flatpak
// #####################################################################################################################

flatpak {
    applicationId.set(appId)
    applicationName.set(appName)

    flatpakRemoteName.set("flathub")
    flatpakRemoteLocation.set("https://dl.flathub.org/repo/flathub.flatpakrepo")
    // This is explicitly not in the build directory as this would bloat the cache by about 3GB without
    // actually being able to reuse anything.
    flatpakCacheDirectory.set(layout.projectDirectory.dir(".flatpak-cache"))

    // The map can be generated by running the following step locally
    //   - `flatpak remote-add --user flathub https://dl.flathub.org/repo/flathub.flatpakrepo`
    //   - `flatpak install --user org.freedesktop.Sdk org.freedesktop.Platform`
    //   - `./flatpak/helper.sh`
    //
    // If you are actively using flatpak I would recommend to run this in a fresh docker image
    // so that these results are not influenced by local settings.

    flatpakDependencies.set(
        mapOf(
            "runtime/org.freedesktop.Sdk/x86_64/25.08" to "d1eb1c10d166e7a611e0f26d2332aa9265ce2d048b054dca07f7919258ee1a0b",
            "runtime/org.freedesktop.Platform.GL.default/x86_64/25.08" to "68b343b37bb6ddfa82518be6c32e28428f36179f1c8536d33d6fff6bc5fc79f1",
            "runtime/org.freedesktop.Sdk.Locale/x86_64/25.08" to "65e3c95b6a2bd2e81271c76822eb0e16e2573b05b253c9a222da36674f387d43",
            "runtime/org.freedesktop.Platform.codecs-extra/x86_64/25.08-extra" to "7116be6864f5bfe54ccf6311a20d43616f910a3fb05c2f578a1b3dc4dc09dc0f",
            "runtime/org.freedesktop.Platform.GL.default/x86_64/25.08-extra" to "c3bb4d072d8f2a5b6cee1566fd3becf5b817e6358cefda5aabbfc86d7608ff32",
            "runtime/org.freedesktop.Platform/x86_64/25.08" to "a0b687431459280667d830df5c9d0f25e47b9b7d78f212a858eefac086ed2909",
            "runtime/org.freedesktop.Platform.Locale/x86_64/25.08" to "40d64910991e3e290580e161c3719128cfe8227fbcd8a260e540c5239517c942",
        )
    )

    desktopTemplate.set(file("flatpak/app.desktop.tmpl"))
    manifestTemplate.set(file("flatpak/manifest.json.tmpl"))
    metainfoTemplate.set(file("flatpak/metainfo.xml.tmpl"))
    iconsDirectory.set(layout.projectDirectory.dir("flatpak/icons"))

    appDistributionDirectory.set(provider {
        tasks
            .named<AbstractJPackageTask>("createReleaseDistributable")
            .flatMap { it.destinationDir.dir(appName) }
    }.flatMap { it })

    developerName.set(publisherName)
    publishedVersion.set(appVersion)
    homepage.set(appHomepage)
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

    archiveFileName.set(platformZipDistribution.originalFileName)
    destinationDirectory.set(zipDistributionDir)
    dependsOn.addAll(
        listOf(
            "createReleaseDistributable",
            copyMsixLogos
        )
    )// copyMsixLogos because of implicit dependency
}

val webZipDistribution = distributions.first { it.type == "zip" && it.platform == "Web" }

val packageReleaseWebZip by tasks.registering(Zip::class) {
    group = "compose desktop"
    from(webDistributionDir.map { it.dir("composeWebCompatibility/productionExecutable") })
    archiveFileName.set(webZipDistribution.originalFileName)
    destinationDirectory.set(zipDistributionDir)
    dependsOn.add("composeCompatibilityBrowserDistribution")
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
    val aabDistributions = distributions.filter { it.type == "aab" && it.platform == "Android" }
    val apkDistributions = distributions.filter { it.type == "apk" && it.platform == "Android" }
    doLast {
        for (distribution in aabDistributions) {
            val path =
                "outputs/bundle/${distribution.architecture}Release/${distribution.originalFileName}"
            uploadToPackageRegistry(
                layout.buildDirectory.get().file(path).asFile.toPath(),
                distribution
            )
        }
        for (distribution in apkDistributions) {
            val path =
                "outputs/apk/${distribution.architecture}/release/${distribution.originalFileName}"
            uploadToPackageRegistry(
                layout.buildDirectory.get().file(path).asFile.toPath(),
                distribution
            )
        }
    }
    dependsOn.addAll(aabDistributions.flatMap { it.tasks })
    dependsOn.addAll(apkDistributions.flatMap { it.tasks })
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
    val websiteBaseUrl = "https://telecrypt.io"
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

#!/usr/bin/env kotlin
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.reader
import kotlin.io.path.readText
import kotlin.io.path.writeText

fun readConfigValue(config: String, key: String, default: String? = null): String {
    val regex = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"")
    return regex.find(config)?.groupValues?.get(1)
        ?: default ?: error("Missing key $key in branding config")
}

val configPath = args.firstOrNull() ?: "branding/branding.json"
val configFile = File(configPath)
require(configFile.exists()) { "brandify: config file not found: $configPath" }
val configText = configFile.readText()

val appName = readConfigValue(configText, "appName")
val androidAppIdRaw = readConfigValue(configText, "androidAppId", "")
val iosBundleIdRaw = readConfigValue(configText, "iosBundleId", androidAppIdRaw)
val iconDir = Path.of(readConfigValue(configText, "iconDir"))
require(Files.isDirectory(iconDir)) { "brandify: icon directory not found: $iconDir" }
val androidAppId = androidAppIdRaw.trim()
val skipAndroid = androidAppId.isBlank()
val iosBundleId = iosBundleIdRaw.trim().ifEmpty { androidAppId }
val androidDevAppId = if (skipAndroid) "" else "$androidAppId.dev"

fun replaceRegex(path: Path, regex: Regex, replacement: String) {
    if (!path.exists()) return
    val text = path.readText()
    val updated = regex.replace(text, replacement)
    if (text != updated) {
        path.writeText(updated)
    }
}

fun replaceLiteral(path: Path, marker: String, replacement: String) {
    if (!path.exists()) return
    val text = path.readText()
    if (marker in text) {
        path.writeText(text.replace(marker, replacement))
    }
}

replaceRegex(Path.of("build.gradle.kts"), Regex("val appName = \"[^\"]+\""), "val appName = \"$appName\"")
if (!skipAndroid) {
    replaceRegex(Path.of("build.gradle.kts"), Regex("val appId = \"[^\"]+\""), "val appId = \"$androidAppId\"")
}
replaceRegex(Path.of("settings.gradle.kts"), Regex("rootProject.name = \"[^\"]+\""), "rootProject.name = \"$appName\"")
replaceRegex(Path.of("fastlane/Appfile"), Regex("app_identifier \"[^\"]+\""), "app_identifier \"$iosBundleId\"")
if (!skipAndroid) {
    replaceRegex(Path.of("fastlane/Appfile"), Regex("package_name \"[^\"]+\""), "package_name \"$androidAppId\"")
}
replaceRegex(Path.of("iosApp/Configuration/Config.xcconfig"), Regex("PRODUCT_NAME=.*"), "PRODUCT_NAME=$appName")
replaceRegex(Path.of("iosApp/Configuration/Config.xcconfig"), Regex("PRODUCT_BUNDLE_IDENTIFIER=.*"), "PRODUCT_BUNDLE_IDENTIFIER=$iosBundleId")
replaceLiteral(Path.of("iosApp/iosApp/Info.plist"), "de.connect2x.tammy", iosBundleId)

listOf(
    Path.of("flatpak/metainfo.xml.tmpl"),
    Path.of("flatpak/manifest.json.tmpl"),
    Path.of("flatpak/app.desktop.tmpl")
).forEach { path ->
    replaceLiteral(path, "Tammy", appName)
    if (!skipAndroid) {
        replaceLiteral(path, "de.connect2x.tammy", androidAppId)
    }
}

// google-services.json replacements
val googleServices = Path.of("google-services.json")
if (!skipAndroid && googleServices.exists()) {
    val text = googleServices.readText()
    val updated = text
        .replace("\"package_name\": \"de.connect2x.tammy.dev\"", "\"package_name\": \"$androidDevAppId\"")
        .replace("\"package_name\": \"de.connect2x.tammy\"", "\"package_name\": \"$androidAppId\"")
    if (text != updated) {
        googleServices.writeText(updated)
    }
}

if (!skipAndroid) {
    replaceLiteral(Path.of("src/androidMain/AndroidManifest.xml"), "de.connect2x.tammy", androidAppId)
}

fun copyTree(source: Path, destination: Path) {
    if (!source.exists()) return
    if (!destination.exists()) destination.createDirectories()
    Files.walk(source).use { stream ->
        stream.forEach { src ->
            val relative = source.relativize(src)
            val target = destination.resolve(relative)
            if (Files.isDirectory(src)) {
                if (!target.exists()) target.createDirectories()
            } else {
                target.parent?.createDirectories()
                Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            }
        }
    }
}

if (!skipAndroid) {
    copyTree(iconDir.resolve("android"), Path.of("src/androidMain/res"))
}
copyTree(iconDir.resolve("ios").resolve("AppIcon.appiconset"), Path.of("iosApp/iosApp/Assets.xcassets/AppIcon.appiconset"))
copyTree(iconDir.resolve("desktop"), Path.of("src/desktopMain/resources"))
copyTree(iconDir.resolve("desktop-msix"), Path.of("build/compose/binaries/main-release/msix"))

println("brandify: applied branding for $appName (${if (skipAndroid) "<androidAppId missing>" else androidAppId} / ${iosBundleId.ifBlank { "<iosBundleId missing>" }})")

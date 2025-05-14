package de.connect2x.tammy

enum class Flavor { PROD, DEV }

interface CommonBuildConfig {
    val version: String
    val flavor: Flavor
    val appName: String
    val appId: String
    val licenses: String
    val privacyInfo: String
    val imprint: String
}

@Suppress("NO_ACTUAL_FOR_EXPECT") // This links to sources generated on demand
expect val BuildConfig: CommonBuildConfig
package de.connect2x.tammy

import de.connect2x.messenger.compose.view.startMessenger

suspend fun main() = startMessenger(
    appName = BuildConfig.appName,
    version = BuildConfig.version,
    configuration = tammyConfiguration(),
)

package de.connect2x.tammy.desktop

import de.connect2x.messenger.desktop.startMessenger
import de.connect2x.tammy.BuildConfig
import de.connect2x.tammy.tammyConfiguration

fun main(args: Array<String>) = startMessenger(
    appName = BuildConfig.appName,
    version = BuildConfig.version,
    configuration = tammyConfiguration(),
    args = args,
)
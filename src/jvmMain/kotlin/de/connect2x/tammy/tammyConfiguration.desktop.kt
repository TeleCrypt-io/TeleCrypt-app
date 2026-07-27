package de.connect2x.tammy

import de.connect2x.trixnity.messenger.util.RootPath
import okio.Path.Companion.toPath

internal actual fun getDevRootPath(): RootPath? =
    System.getenv("TRIXNITY_MESSENGER_ROOT_PATH")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { RootPath(it.toPath()) }
        ?: RootPath("./app-data".toPath())

internal actual val platformDatabaseEncryptionEnabled: Boolean = true

package de.connect2x.tammy

import de.connect2x.trixnity.messenger.util.RootPath
import okio.Path.Companion.toPath

internal actual fun getDevRootPath(): RootPath? =
    if (System.getenv("TRIXNITY_MESSENGER_ROOT_PATH") == null)
        RootPath("./app-data".toPath())
    else null

internal actual val platformEncryptLocalData: Boolean = true
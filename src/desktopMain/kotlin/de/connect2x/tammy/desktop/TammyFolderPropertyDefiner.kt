package de.connect2x.tammy.desktop

import ch.qos.logback.core.PropertyDefinerBase
import de.connect2x.tammy.BuildConfig
import de.connect2x.tammy.Flavor
import de.connect2x.trixnity.messenger.util.getAppPath

class TammyFolderPropertyDefiner : PropertyDefinerBase() {
    override fun getPropertyValue(): String =
        if (System.getenv("TRIXNITY_MESSENGER_ROOT_PATH") == null && BuildConfig.flavor == Flavor.DEV) {
            "./app-data"
        } else getAppPath(BuildConfig.appNameCleaned).toString()
}

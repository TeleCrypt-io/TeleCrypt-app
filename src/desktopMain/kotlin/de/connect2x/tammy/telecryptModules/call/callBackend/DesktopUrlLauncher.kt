package de.connect2x.tammy.telecryptModules.call.callBackend

import java.awt.Desktop
import java.net.URI

class DesktopUrlLauncher : UrlLauncher {
    override fun openUrl(url: String) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

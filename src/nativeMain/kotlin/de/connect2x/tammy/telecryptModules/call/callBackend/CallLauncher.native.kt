package de.connect2x.tammy.telecryptModules.call.callBackend

import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * iOS/Native implementation of CallLauncher
 * Opens Element Call in Safari or the default browser
 */
class ElementCallLauncherImpl : CallLauncher {

    override fun launchCall(roomId: String, roomName: String, displayName: String): String {
        val url = buildElementCallUrl(roomId, roomName, displayName)
        joinByUrl(url)
        return url
    }

    override fun joinByUrl(url: String) {
        val nsUrl = NSURL.URLWithString(url) ?: return
        UIApplication.sharedApplication.openURL(nsUrl)
    }

    override fun isCallAvailable(roomId: String): Boolean {
        // Calls are always available on iOS (Safari is always present)
        return true
    }
}

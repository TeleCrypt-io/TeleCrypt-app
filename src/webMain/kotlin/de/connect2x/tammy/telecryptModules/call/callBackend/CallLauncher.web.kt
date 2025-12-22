package de.connect2x.tammy.telecryptModules.call.callBackend

import kotlinx.browser.window

/**
 * Web implementation of CallLauncher
 * Opens Element Call in a new browser tab
 */
actual class ElementCallLauncherImpl : CallLauncher {

    actual override fun launchCall(roomId: String, roomName: String, displayName: String): String {
        val url = buildElementCallUrl(roomId, roomName, displayName)
        joinByUrl(url)
        return url
    }

    actual override fun joinByUrl(url: String) {
        // Open in new tab
        window.open(url, "_blank")
    }

    actual override fun isCallAvailable(roomId: String): Boolean {
        // Calls are always available on web
        return true
    }
}

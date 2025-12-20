package de.connect2x.tammy.telecryptModules.call.callBackend

import kotlinx.browser.window

/**
 * Web implementation of CallLauncher
 * Opens Element Call in a new browser tab
 */
actual class ElementCallLauncherImpl : CallLauncher {

    companion object {
        private const val ELEMENT_CALL_BASE_URL = "https://call.element.io/room/#"
    }

    actual override fun launchCall(roomId: String, roomName: String, displayName: String) {
        // Build Element Call URL according to the documentation
        val encodedRoomName = encodeURIComponent(roomName)
        val encodedRoomId = encodeURIComponent(roomId)
        val encodedDisplayName = encodeURIComponent(displayName)

        val url = "$ELEMENT_CALL_BASE_URL/$encodedRoomName?" +
                "roomId=$encodedRoomId" +
                "&displayName=$encodedDisplayName" +
                "&confineToRoom=true"

        joinByUrl(url)
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

// JS interop for encodeURIComponent
private external fun encodeURIComponent(str: String): String
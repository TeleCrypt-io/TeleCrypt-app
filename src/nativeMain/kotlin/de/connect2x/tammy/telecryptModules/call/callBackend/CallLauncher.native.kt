package de.connect2x.tammy.telecryptModules.call.callBackend

import platform.Foundation.NSURL
import platform.Foundation.NSCharacterSet
import platform.Foundation.URLQueryAllowedCharacterSet
import platform.Foundation.stringByAddingPercentEncodingWithAllowedCharacters
import platform.UIKit.UIApplication

/**
 * iOS/Native implementation of CallLauncher
 * Opens Element Call in Safari or the default browser
 */
actual class ElementCallLauncherImpl : CallLauncher {

    companion object {
        private const val ELEMENT_CALL_BASE_URL = "https://call.element.io/room/#"
    }

    actual override fun launchCall(roomId: String, roomName: String, displayName: String) {
        // Build Element Call URL according to the documentation
        val encodedRoomName = roomName.encodeUrl()
        val encodedRoomId = roomId.encodeUrl()
        val encodedDisplayName = displayName.encodeUrl()

        val url = "$ELEMENT_CALL_BASE_URL/$encodedRoomName?" +
                "roomId=$encodedRoomId" +
                "&displayName=$encodedDisplayName" +
                "&confineToRoom=true"

        joinByUrl(url)
    }

    actual override fun joinByUrl(url: String) {
        val nsUrl = NSURL.URLWithString(url) ?: return
        UIApplication.sharedApplication.openURL(nsUrl)
    }

    actual override fun isCallAvailable(roomId: String): Boolean {
        // Calls are always available on iOS (Safari is always present)
        return true
    }

    private fun String.encodeUrl(): String {
        return this.stringByAddingPercentEncodingWithAllowedCharacters(
            NSCharacterSet.URLQueryAllowedCharacterSet
        ) ?: this
    }
}
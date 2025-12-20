package de.connect2x.tammy.telecryptModules.call.callBackend

import java.net.URLEncoder

/**
 * Desktop implementation of CallLauncher
 * Opens Element Call in the system's default browser
 */
actual class ElementCallLauncherImpl : CallLauncher {

    companion object {
        // Element Call standalone mode - direct room URL
        private const val ELEMENT_CALL_BASE_URL = "https://call.element.io"
    }

    override fun launchCall(roomId: String, roomName: String, displayName: String) {
        // Element Call standalone mode - creates ad-hoc rooms without needing Matrix roomId
        // URL format: https://call.element.io/<room_name>?displayName=...
        // Note: roomId param not used because RoomHeaderInfo doesn't expose it (trixnity limitation)
        val encodedRoomName = URLEncoder.encode(roomName, "UTF-8")
        val encodedDisplayName = URLEncoder.encode(displayName, "UTF-8")

        // Standalone mode URL - creates/joins room by name only
        val url = "$ELEMENT_CALL_BASE_URL/$encodedRoomName?" +
                "displayName=$encodedDisplayName" +
                "&confineToRoom=true"

        joinByUrl(url)
    }

    override fun joinByUrl(url: String) {
        val os = System.getProperty("os.name").lowercase()

        try {
            when {
                os.contains("win") ->
                    Runtime.getRuntime().exec(arrayOf("rundll32", "url.dll,FileProtocolHandler", url))

                os.contains("mac") ->
                    Runtime.getRuntime().exec(arrayOf("open", url))

                os.contains("nix") || os.contains("nux") ->
                    Runtime.getRuntime().exec(arrayOf("xdg-open", url))

                else -> error("Unsupported OS: $os")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun isCallAvailable(roomId: String): Boolean {
        // Calls are always available on desktop (browser is always present)
        return true
    }
}
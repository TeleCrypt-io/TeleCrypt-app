package de.connect2x.tammy.telecryptModules.call.callBackend

import java.net.URLEncoder

/**
 * Desktop implementation of CallLauncher
 * Opens Element Call in the system's default browser
 */
actual class ElementCallLauncherImpl : CallLauncher {

    companion object {
        private const val ELEMENT_CALL_BASE_URL = "https://call.element.io/room/#"
    }

    actual override fun launchCall(roomId: String, roomName: String, displayName: String) {
        // Build Element Call URL according to the documentation:
        // https://call.element.io/room/#/<room_name>?roomId=!id:domain&displayName=...
        val encodedRoomName = URLEncoder.encode(roomName, "UTF-8")
        val encodedRoomId = URLEncoder.encode(roomId, "UTF-8")
        val encodedDisplayName = URLEncoder.encode(displayName, "UTF-8")

        val url = "$ELEMENT_CALL_BASE_URL/$encodedRoomName?" +
                "roomId=$encodedRoomId" +
                "&displayName=$encodedDisplayName" +
                "&confineToRoom=true"

        joinByUrl(url)
    }

    actual override fun joinByUrl(url: String) {
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

    actual override fun isCallAvailable(roomId: String): Boolean {
        // Calls are always available on desktop (browser is always present)
        return true
    }
}
package de.connect2x.tammy.telecryptModules.call.callBackend

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.net.URLEncoder

/**
 * Android implementation of CallLauncher
 * Opens Element Call in the device's default browser
 */
actual class ElementCallLauncherImpl : CallLauncher, KoinComponent {

    private val context: Context by inject()

    companion object {
        private const val ELEMENT_CALL_BASE_URL = "https://call.element.io/room/#"
    }

    override fun launchCall(roomId: String, roomName: String, displayName: String) {
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

    override fun joinByUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun isCallAvailable(roomId: String): Boolean {
        // Calls are always available on Android (browser is always present)
        return true
    }
}
package de.connect2x.tammy.telecryptModules.call.callBackend

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Android implementation of CallLauncher
 * Opens Element Call in the device's default browser
 */
actual class ElementCallLauncherImpl : CallLauncher, KoinComponent {

    private val context: Context by inject()

    override fun launchCall(roomId: String, roomName: String, displayName: String): String {
        val url = buildElementCallUrl(roomId, roomName, displayName)
        joinByUrl(url)
        return url
    }

    actual override fun joinByUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    actual override fun isCallAvailable(roomId: String): Boolean {
        // Calls are always available on Android (browser is always present)
        return true
    }
}

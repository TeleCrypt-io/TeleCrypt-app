package de.connect2x.tammy.telecryptModules.call.callBackend

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri


/**
 * Android implementation of CallLauncher
 * Opens Element Call in the device's default browser
 */
class ElementCallLauncherImpl(private val appContext: Context) : CallLauncher {

    override fun launchCall(roomId: String, roomName: String, displayName: String): String {
        val url = buildElementCallUrl(roomId, roomName, displayName)
        joinByUrl(url)
        return url
    }

    override fun joinByUrl(url: String) {
        val context = appContext
        val uri = url.toUri()
        val customTabsIntent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()

        if (context is Activity) {
            customTabsIntent.launchUrl(context, uri)
        } else {
            customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            customTabsIntent.launchUrl(context, uri)
        }
    }

    override fun isCallAvailable(roomId: String): Boolean {
        // Calls are always available on Android (browser is always present)
        return true
    }
}

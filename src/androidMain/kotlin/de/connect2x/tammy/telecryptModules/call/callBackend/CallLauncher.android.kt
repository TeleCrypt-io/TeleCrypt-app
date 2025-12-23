package de.connect2x.tammy.telecryptModules.call.callBackend

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger { }


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
        val uri = url.toUri()

        val elementGreen = "#0DBD8B".toColorInt()

        val colorParams = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(elementGreen)
            .build()

        val customTabsIntent = CustomTabsIntent.Builder()
            .setShowTitle(false)
            .setUrlBarHidingEnabled(true)
            .setInstantAppsEnabled(false)
            .setDefaultColorSchemeParams(colorParams)
            .build()

        customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        try {
            appContext.packageManager.getPackageInfo("com.android.chrome", 0)
            customTabsIntent.intent.setPackage("com.android.chrome")
        }
        catch  (_: PackageManager.NameNotFoundException){
            log.warn { "no Chrome" } // TODO try access other CustomTabs browsers
        }
        customTabsIntent.launchUrl(appContext, uri)
    }


    override fun isCallAvailable(roomId: String): Boolean {
        // Calls are always available on Android (browser is always present)
        return true
    }
}

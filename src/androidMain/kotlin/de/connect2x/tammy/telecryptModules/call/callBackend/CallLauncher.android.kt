package de.connect2x.tammy.telecryptModules.call.callBackend

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent

class ElementCallActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra("EXTRA_CALL_URL") ?: return finish()
        try {
            webView = WebView(this)
            setContentView(webView)
            configureWebView()
            webView.loadUrl(url)
        } catch (_: Throwable) {
            openInCustomTabs(url)
            finish()
        }
    }

    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            databaseEnabled = true
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }
        }

        webView.webViewClient = WebViewClient()
    }

    private fun openInCustomTabs(url: String) {
        val uri = android.net.Uri.parse(url)
        try {
            val customTabsIntent = CustomTabsIntent.Builder().build()
            customTabsIntent.launchUrl(this, uri)
        } catch (_: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }
}

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
        val intent = Intent(appContext, ElementCallActivity::class.java).apply {
            putExtra("EXTRA_CALL_URL", url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
    }


    override fun isCallAvailable(roomId: String): Boolean {
        // Calls are always available on Android (browser is always present)
        return true
    }
}

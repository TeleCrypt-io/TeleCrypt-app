package de.connect2x.tammy.telecryptModules.call.callBackend

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent

private const val EXTRA_CALL_URL = "EXTRA_CALL_URL"
private const val EXTRA_CALL_SESSION_SCRIPT = "EXTRA_CALL_SESSION_SCRIPT"
private const val EXTRA_WIDGET_MODE = "EXTRA_WIDGET_MODE"

class ElementCallActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_CALL_URL) ?: return finish()
        val sessionScript = intent.getStringExtra(EXTRA_CALL_SESSION_SCRIPT)
        val widgetMode = intent.getBooleanExtra(EXTRA_WIDGET_MODE, false)
        try {
            webView = WebView(this)
            setContentView(webView)
            configureWebView(sessionScript, widgetMode)
            webView.loadUrl(url)
        } catch (_: Throwable) {
            if (!widgetMode) {
                openInCustomTabs(url)
            }
            finish()
        }
    }

    private fun configureWebView(sessionScript: String?, widgetMode: Boolean) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            @Suppress("DEPRECATION")
            databaseEnabled = true
            // Widget host page is served over plain http://127.0.0.1 but
            // embeds the EC iframe from https://call.element.io. Allow that
            // mixed content so the iframe can load.
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowContentAccess = true
            allowFileAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
        }

        // Grant camera / microphone / display-capture to anything the page asks for.
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (!sessionScript.isNullOrBlank()) {
                    view.evaluateJavascript(sessionScript, null)
                }
            }
        }
    }

    override fun onDestroy() {
        runCatching {
            webView.loadUrl("about:blank")
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
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

    companion object {
        fun newIntent(
            context: Context,
            url: String,
            sessionScript: String? = null,
            widgetMode: Boolean = false,
        ): Intent = Intent(context, ElementCallActivity::class.java).apply {
            putExtra(EXTRA_CALL_URL, url)
            if (sessionScript != null) putExtra(EXTRA_CALL_SESSION_SCRIPT, sessionScript)
            putExtra(EXTRA_WIDGET_MODE, widgetMode)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}

/**
 * Android implementation of [CallLauncher].
 *
 * Widget mode: [joinByWidgetUrl] launches [ElementCallActivity] pointing at
 * the local widget-host URL served by [WidgetBridgeServer]. The WebView
 * loads `http://127.0.0.1:<port>/widget-host.html` which then embeds
 * `https://call.element.io/...` in an iframe. All Matrix API calls go
 * through the WebSocket bridge.
 *
 * Legacy standalone mode: [joinByUrl] / [joinByUrlWithSession] open the
 * EC URL directly in the WebView (or Chrome Custom Tabs as a fallback).
 * Kept for incoming/manual invocations that still produce a standalone
 * URL, though widget mode is the default for outgoing calls.
 */
class ElementCallLauncherImpl(private val appContext: Context) : CallLauncher {

    override fun launchCall(roomId: String, roomName: String, displayName: String): String {
        val url = buildElementCallUrl(roomId, roomName, displayName)
        joinByUrl(url)
        return url
    }

    override fun joinByUrl(url: String) {
        joinByUrlWithSession(url, null)
    }

    override fun joinByUrlWithSession(url: String, session: ElementCallSession?) {
        val sessionScript = session?.let { buildElementCallSessionInitScript(it) }
        val intent = ElementCallActivity.newIntent(
            context = appContext,
            url = url,
            sessionScript = sessionScript,
            widgetMode = false,
        )
        appContext.startActivity(intent)
    }

    override fun joinByWidgetUrl(hostUrl: String) {
        println("[Call] Android widget mode: opening host page in WebView: $hostUrl")
        val intent = ElementCallActivity.newIntent(
            context = appContext,
            url = hostUrl,
            sessionScript = null,
            widgetMode = true,
        )
        appContext.startActivity(intent)
    }

    override fun isCallAvailable(roomId: String): Boolean {
        return true
    }
}

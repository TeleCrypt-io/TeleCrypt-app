package de.connect2x.tammy.telecryptModules.call.callBackend

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

private const val EXTRA_CALL_URL = "EXTRA_CALL_URL"
private const val EXTRA_CALL_SESSION_SCRIPT = "EXTRA_CALL_SESSION_SCRIPT"
private const val EXTRA_WIDGET_MODE = "EXTRA_WIDGET_MODE"
private const val REQUEST_AV_PERMISSIONS = 1001

class ElementCallActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var pendingUrl: String? = null
    private var pendingSessionScript: String? = null
    private var pendingWidgetMode: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_CALL_URL) ?: return finish()
        val sessionScript = intent.getStringExtra(EXTRA_CALL_SESSION_SCRIPT)
        val widgetMode = intent.getBooleanExtra(EXTRA_WIDGET_MODE, false)

        pendingUrl = url
        pendingSessionScript = sessionScript
        pendingWidgetMode = widgetMode

        if (hasMediaPermissions()) {
            startWebView(url, sessionScript, widgetMode)
        } else {
            // Android runtime perms are required before WebView's
            // onPermissionRequest can grant getUserMedia — granting at the
            // WebView layer alone leaves the OS-level camera/mic blocked,
            // which silently breaks EC's local preview and mute toggles.
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                REQUEST_AV_PERMISSIONS,
            )
        }
    }

    private fun hasMediaPermissions(): Boolean {
        val camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        return camera == PackageManager.PERMISSION_GRANTED && mic == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_AV_PERMISSIONS) return
        // Even if the user denied, still load the WebView so EC can show its
        // own permission-denied UI rather than us showing a black screen.
        val url = pendingUrl ?: return finish()
        startWebView(url, pendingSessionScript, pendingWidgetMode)
    }

    private fun startWebView(url: String, sessionScript: String?, widgetMode: Boolean) {
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

        // Grant camera / microphone / display-capture to anything the page asks for,
        // but only if the underlying Android runtime permission is actually granted —
        // otherwise the WebView layer happily approves the request while the OS
        // returns no devices, and EC silently shows an empty mute toggle.
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val want = request.resources.filter { resource ->
                        when (resource) {
                            PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                                ContextCompat.checkSelfPermission(
                                    this@ElementCallActivity,
                                    Manifest.permission.CAMERA,
                                ) == PackageManager.PERMISSION_GRANTED
                            PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                                ContextCompat.checkSelfPermission(
                                    this@ElementCallActivity,
                                    Manifest.permission.RECORD_AUDIO,
                                ) == PackageManager.PERMISSION_GRANTED
                            else -> true
                        }
                    }.toTypedArray()
                    if (want.isNotEmpty()) {
                        request.grant(want)
                    } else {
                        request.deny()
                    }
                }
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
            if (::webView.isInitialized) {
                webView.loadUrl("about:blank")
                webView.stopLoading()
                webView.destroy()
            }
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

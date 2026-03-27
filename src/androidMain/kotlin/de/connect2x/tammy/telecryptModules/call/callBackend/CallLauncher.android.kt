package de.connect2x.tammy.telecryptModules.call.callBackend

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebSettings
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent

private const val EXTRA_CALL_URL = "EXTRA_CALL_URL"
private const val EXTRA_CALL_SESSION_SCRIPT = "EXTRA_CALL_SESSION_SCRIPT"

class ElementCallActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_CALL_URL) ?: return finish()
        val sessionScript = intent.getStringExtra(EXTRA_CALL_SESSION_SCRIPT)
        try {
            webView = WebView(this)
            setContentView(webView)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            configureWebView(sessionScript)
            onBackPressedDispatcher.addCallback(
                this,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        if (webView.canGoBack()) {
                            webView.goBack()
                        } else {
                            finish()
                        }
                    }
                },
            )
            webView.loadUrl(url)
        } catch (_: Throwable) {
            openInCustomTabs(url)
            finish()
        }
    }

    private fun configureWebView(sessionScript: String?) {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setAcceptThirdPartyCookies(webView, true)
            }
        }

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            databaseEnabled = true
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            useWideViewPort = true
            loadWithOverviewMode = true
        }

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
        joinByUrlWithSession(url, null)
    }

    override fun joinByUrlWithSession(url: String, session: ElementCallSession?) {
        val intent = Intent(appContext, ElementCallActivity::class.java).apply {
            putExtra(EXTRA_CALL_URL, url)
            if (session != null) {
                putExtra(EXTRA_CALL_SESSION_SCRIPT, buildElementCallSessionInitScript(session))
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
    }


    override fun isCallAvailable(roomId: String): Boolean {
        // Calls are always available on Android (browser is always present)
        return true
    }
}

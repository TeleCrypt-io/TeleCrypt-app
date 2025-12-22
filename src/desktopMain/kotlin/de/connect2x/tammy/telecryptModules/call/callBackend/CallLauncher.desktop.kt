package de.connect2x.tammy.telecryptModules.call.callBackend

import com.github.winterreisender.webviewko.WebviewKo

fun openWebView(url: String) {
    WebviewKo().run {
        title("telecrypt-messenger call")
        size(800, 600, WebviewKo.WindowHint.Max)
        url(url)
        show()
    }
}

fun openUrlInBrowser(url: String) {
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

/**
 * Desktop implementation of CallLauncher
 * Opens Element Call in the system's default browser
 */
actual class ElementCallLauncherImpl : CallLauncher {

    override fun launchCall(roomId: String, roomName: String, displayName: String): String {
        val url = buildElementCallUrl(roomId, roomName, displayName)
        joinByUrl(url)
        return url
    }

    override fun joinByUrl(url: String) {
        if (System.getProperty("os.name").lowercase().contains("linux")) {
            openUrlInBrowser(url)
        } else {
            try {
                openWebView(url)
            } catch (e: Throwable) {
                println(e.message)
                openUrlInBrowser(url)
            }
        }
    }

    override fun isCallAvailable(roomId: String): Boolean {
        // Calls are always available on desktop (browser is always present)
        return true
    }
}

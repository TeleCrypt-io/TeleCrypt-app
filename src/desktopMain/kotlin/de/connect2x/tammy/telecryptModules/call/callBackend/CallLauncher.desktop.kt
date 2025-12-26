package de.connect2x.tammy.telecryptModules.call.callBackend

import com.github.winterreisender.webviewko.WebviewKo
import com.github.winterreisender.webviewko.WebviewKoAWT
import java.awt.EventQueue
import java.awt.Toolkit
import java.util.function.Consumer
import javax.swing.JFrame
import javax.swing.WindowConstants

fun openWebView(url: String) {
    EventQueue.invokeLater {
        val frame = JFrame("telecrypt-messenger call")
        frame.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        val screen = Toolkit.getDefaultToolkit().screenSize
        frame.setSize((screen.width * 0.75).toInt(), (screen.height * 0.75).toInt())
        frame.setLocationRelativeTo(null)
        frame.isAlwaysOnTop = true
        frame.isAlwaysOnTop = false
        frame.toFront()
        frame.requestFocus()

        val view = WebviewKoAWT(
            0,
            null,
            Consumer<WebviewKo> { webview ->
                webview.title("telecrypt-messenger call")
                webview.init(
                    "window.addEventListener('load', () => {" +
                        "try { window.focus(); } catch (e) {}" +
                    "});"
                )
                webview.navigate(url)
            }
        )
        frame.add(view)
        frame.isVisible = true
        frame.toFront()
        frame.requestFocus()
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
class ElementCallLauncherImpl : CallLauncher {

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

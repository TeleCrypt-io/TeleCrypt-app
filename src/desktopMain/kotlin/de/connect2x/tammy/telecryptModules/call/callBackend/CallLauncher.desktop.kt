package de.connect2x.tammy.telecryptModules.call.callBackend

import com.github.winterreisender.webviewko.WebviewKo
import com.github.winterreisender.webviewko.WebviewKo.WindowHint
import java.io.File
import kotlin.concurrent.thread

private const val CALL_WINDOW_TITLE = "TeleCrypt Call"

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

private fun openExternalCallWindow(url: String) {
    val os = System.getProperty("os.name").lowercase()
    if (!os.contains("win")) {
        openUrlInBrowser(url)
        return
    }
    val browser = findWindowsAppBrowser()
    if (browser == null) {
        openUrlInBrowser(url)
        return
    }
    val process = ProcessBuilder(
        browser,
        "--app=$url",
        "--new-window",
    ).start()
    bringProcessToFront(process.pid())
}

private fun openCallWindow(url: String, session: ElementCallSession?) {
    if (session != null && tryOpenEmbeddedWindow(url, session)) {
        return
    }
    openExternalCallWindow(url)
}

private fun tryOpenEmbeddedWindow(url: String, session: ElementCallSession): Boolean {
    return runCatching {
        val initScript = buildElementCallSessionInitScript(session)
        thread(start = true, isDaemon = false, name = "ElementCallWebview") {
            val webview = WebviewKo(0, null)
            webview.title(CALL_WINDOW_TITLE)
            webview.size(1280, 800, WindowHint.None)
            webview.init(initScript)
            webview.navigate(url)
            webview.show()
            thread(start = true, isDaemon = true, name = "ElementCallBringToFront") {
                repeat(6) {
                    Thread.sleep(400)
                    bringWindowToFrontByTitle(CALL_WINDOW_TITLE)
                }
            }
            thread(start = true, isDaemon = true, name = "ElementCallInjectSession") {
                repeat(8) {
                    Thread.sleep(600)
                    webview.dispatch { eval(initScript) }
                }
            }
            webview.start()
            webview.destroy()
        }
    }.isSuccess
}

private fun findWindowsAppBrowser(): String? {
    val programFiles = System.getenv("ProgramFiles").orEmpty()
    val programFilesX86 = System.getenv("ProgramFiles(x86)").orEmpty()
    val candidates = listOf(
        "$programFiles\\Microsoft\\Edge\\Application\\msedge.exe",
        "$programFilesX86\\Microsoft\\Edge\\Application\\msedge.exe",
        "$programFiles\\Google\\Chrome\\Application\\chrome.exe",
        "$programFilesX86\\Google\\Chrome\\Application\\chrome.exe",
    )
    return candidates.firstOrNull { it.isNotBlank() && File(it).exists() }
}

private fun bringProcessToFront(pid: Long) {
    try {
        Runtime.getRuntime().exec(
            arrayOf(
                "powershell",
                "-Command",
                "\$ws = New-Object -ComObject WScript.Shell; Start-Sleep -Milliseconds 300; \$ws.AppActivate($pid) | Out-Null",
            )
        )
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private fun bringWindowToFrontByTitle(title: String) {
    val os = System.getProperty("os.name").lowercase()
    if (!os.contains("win")) {
        return
    }
    val escapedTitle = title.replace("'", "''")
    try {
        Runtime.getRuntime().exec(
            arrayOf(
                "powershell",
                "-Command",
                "\$ws = New-Object -ComObject WScript.Shell; Start-Sleep -Milliseconds 500; \$ws.AppActivate('$escapedTitle') | Out-Null",
            )
        )
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
        openCallWindow(url, null)
    }

    override fun joinByUrlWithSession(url: String, session: ElementCallSession?) {
        openCallWindow(url, session)
    }

    override fun isCallAvailable(roomId: String): Boolean {
        // Calls are always available on desktop (browser is always present)
        return true
    }
}

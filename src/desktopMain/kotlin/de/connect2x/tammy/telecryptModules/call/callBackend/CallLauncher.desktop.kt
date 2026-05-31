package de.connect2x.tammy.telecryptModules.call.callBackend

import com.github.winterreisender.webviewko.WebviewKo
import com.github.winterreisender.webviewko.WebviewKo.WindowHint
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.net.URL
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

// ── Chrome CDP-based session injection (macOS / Linux fallback) ──────────

/**
 * Finds a Chrome or Chromium binary on macOS.
 */
private fun findMacChrome(): String? {
    val candidates = listOf(
        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
        "/Applications/Chromium.app/Contents/MacOS/Chromium",
        "/Applications/Google Chrome Canary.app/Contents/MacOS/Google Chrome Canary",
        "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser",
        "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
        "/Applications/Yandex.app/Contents/MacOS/Yandex",
    )
    return candidates.firstOrNull { File(it).exists() }
}

/**
 * Finds a free TCP port for Chrome DevTools Protocol.
 */
private fun findFreePort(): Int {
    return ServerSocket(0).use { it.localPort }
}

/**
 * Resolves the `.well-known/matrix/client` from the server name (not the homeserver URL).
 *
 * Element Call discovers LiveKit transports by fetching `.well-known/matrix/client`
 * from the homeserver URL domain (e.g., `cht.antidote.network`). However, the
 * `org.matrix.msc4143.rtc_foci` config is typically on the server name domain
 * (e.g., `antidote.network`). This function fetches the correct `.well-known`
 * from the server name so we can inject it via fetch interception.
 *
 * @param serverName The Matrix server name (e.g., "antidote.network")
 * @param homeserverUrl The homeserver base URL (e.g., "https://cht.antidote.network")
 * @return The well-known JSON string with rtc_foci, or null if not available
 */
private fun resolveWellKnownWithRtcFoci(serverName: String, homeserverUrl: String): String? {
    // First, try the homeserver URL itself — maybe it has rtc_foci
    val homeserverDomain = runCatching { URI(homeserverUrl).host }.getOrNull()
    if (homeserverDomain != null) {
        val hsWellKnown = fetchWellKnown(homeserverDomain)
        if (hsWellKnown != null && hsWellKnown.contains("rtc_foci")) {
            println("[Call] Found rtc_foci in homeserver .well-known ($homeserverDomain)")
            return hsWellKnown
        }
    }

    // If homeserver domain differs from server name, try the server name
    if (serverName.isNotBlank() && serverName != homeserverDomain) {
        val serverWellKnown = fetchWellKnown(serverName)
        if (serverWellKnown != null && serverWellKnown.contains("rtc_foci")) {
            println("[Call] Found rtc_foci in server-name .well-known ($serverName)")
            // Merge: take the rtc_foci from server-name but keep homeserver base_url
            return buildMergedWellKnown(homeserverUrl, serverWellKnown)
        }
    }

    println("[Call] No rtc_foci found in any .well-known")
    return null
}

/**
 * Fetches `https://<domain>/.well-known/matrix/client` and returns the body, or null.
 */
private fun fetchWellKnown(domain: String): String? {
    return runCatching {
        val url = URL("https://$domain/.well-known/matrix/client")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/json")
        try {
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().readText()
            } else {
                println("[Call] .well-known from $domain returned HTTP ${conn.responseCode}")
                null
            }
        } finally {
            conn.disconnect()
        }
    }.onFailure { e ->
        println("[Call] Failed to fetch .well-known from $domain: ${e.message}")
    }.getOrNull()
}

/**
 * Builds a merged well-known JSON that has the correct homeserver base_url
 * and the rtc_foci from the server-name well-known.
 */
private fun buildMergedWellKnown(homeserverUrl: String, serverWellKnown: String): String {
    // Extract rtc_foci array from server well-known
    val rtcFociMatch = """"org\.matrix\.msc4143\.rtc_foci"\s*:\s*(\[[\s\S]*?\])""".toRegex()
        .find(serverWellKnown)
    val rtcFoci = rtcFociMatch?.groupValues?.get(1) ?: "[]"

    return """{"m.homeserver":{"base_url":"${escapeJsonString(homeserverUrl)}"},"org.matrix.msc4143.rtc_foci":$rtcFoci}"""
}

/**
 * Extracts the server name from a Matrix room ID (e.g., "antidote.network" from "!abc:antidote.network").
 */
private fun extractServerName(roomId: String): String? {
    if (!roomId.startsWith("!") || !roomId.contains(":")) return null
    return roomId.substringAfter(":").trim().takeIf { it.isNotBlank() }
}

/**
 * Opens Element Call in Chrome --app mode with session injection via
 * Chrome DevTools Protocol (CDP).
 *
 * Flow:
 * 1. Launch Chrome with --remote-debugging-port, --user-data-dir, --app=about:blank
 * 2. Connect to CDP, inject script via Page.addScriptToEvaluateOnNewDocument
 * 3. Navigate to the actual Element Call URL via Page.navigate
 * 4. The injected script runs before page JS, setting localStorage
 *
 * This approach works on macOS where WebviewKo crashes with SIGSEGV.
 */
private fun openWithChromeCdp(url: String, session: ElementCallSession, roomId: String? = null): Boolean {
    val os = System.getProperty("os.name").lowercase()
    val chrome = when {
        os.contains("mac") -> findMacChrome()
        os.contains("nix") || os.contains("nux") -> findLinuxChromium()
        os.contains("win") -> findWindowsAppBrowser()
        else -> null
    }
    if (chrome == null) {
        println("[Call] No Chrome/Chromium found for CDP injection")
        return false
    }

    // Resolve .well-known with rtc_foci BEFORE launching the browser.
    // Element Call fetches .well-known from the homeserver URL domain (e.g., cht.antidote.network)
    // but the rtc_foci config is typically on the server name domain (e.g., antidote.network).
    // We resolve it here and inject it via fetch interception in the pre-load script.
    val serverName = roomId?.let { extractServerName(it) }
    val wellKnownJson = if (serverName != null) {
        println("[Call] Resolving .well-known for server name: $serverName (homeserver: ${session.homeserver})")
        resolveWellKnownWithRtcFoci(serverName, session.homeserver)
    } else {
        println("[Call] No roomId provided, skipping .well-known resolution")
        null
    }
    if (wellKnownJson != null) {
        println("[Call] Will inject .well-known with rtc_foci into Element Call")
    }

    val debugPort = findFreePort()
    val rootPath = System.getenv("TRIXNITY_MESSENGER_ROOT_PATH") ?: "./app-data"
    val browserDataPath = File(rootPath, "browser-data-call").absolutePath

    println("[Call] Launching Chrome with CDP on port $debugPort")
    println("[Call] Chrome binary: $chrome")
    println("[Call] User data dir: $browserDataPath")

    // Kill any existing browser process using the same user-data-dir
    // (leftover from a previous call session). Without this, the new process
    // just sends the URL to the old one and exits, ignoring --remote-debugging-port.
    killExistingBrowserDataProcess(browserDataPath)

    // Remove Chrome's lock file to prevent "profile in use" errors
    removeBrowserLockFiles(browserDataPath)

    return runCatching {
        // Launch Chrome with about:blank first — we'll navigate after injection
        val process = ProcessBuilder(
            chrome,
            "--app=about:blank",
            "--new-window",
            "--remote-debugging-port=$debugPort",
            "--user-data-dir=$browserDataPath",
            "--no-first-run",
            "--no-default-browser-check",
        ).start()

        // Inject session in a background thread
        thread(start = true, isDaemon = true, name = "ChromeCdpInjector") {
            try {
                injectSessionViaCdp(debugPort, session, url, wellKnownJson)
            } catch (e: Exception) {
                println("[Call] CDP injection failed: ${e.message}")
                e.printStackTrace()
            }
        }
        true
    }.onFailure { e ->
        println("[Call] Failed to launch Chrome with CDP: ${e.message}")
    }.isSuccess
}

/**
 * Kills any existing browser process that uses the given user-data-dir.
 * This prevents the "new process delegates to old process" problem where
 * Chrome ignores --remote-debugging-port because an existing instance
 * already owns the profile directory.
 */
private fun killExistingBrowserDataProcess(browserDataPath: String) {
    val os = System.getProperty("os.name").lowercase()
    try {
        if (os.contains("mac") || os.contains("nix") || os.contains("nux")) {
            // Use pgrep/pkill to find processes with the user-data-dir argument
            val escapedPath = browserDataPath.replace(" ", "\\ ")
            val result = ProcessBuilder("sh", "-c", "pgrep -f 'user-data-dir=$escapedPath'")
                .redirectErrorStream(true)
                .start()
            val pids = result.inputStream.bufferedReader().readText().trim()
            result.waitFor()
            if (pids.isNotBlank()) {
                println("[Call] CDP: Killing existing browser processes with user-data-dir: PIDs=$pids")
                ProcessBuilder("sh", "-c", "pkill -f 'user-data-dir=$escapedPath'")
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
                // Wait a moment for processes to die
                Thread.sleep(1500)
            } else {
                println("[Call] CDP: No existing browser processes found for user-data-dir")
            }
        } else if (os.contains("win")) {
            // On Windows, use taskkill (less precise, but works)
            println("[Call] CDP: Skipping process cleanup on Windows (not needed with unique data dirs)")
        }
    } catch (e: Exception) {
        println("[Call] CDP: Failed to kill existing browser processes: ${e.message}")
    }
}

/**
 * Removes Chrome/Chromium lock files from the user-data-dir to prevent
 * "profile already in use" errors.
 */
private fun removeBrowserLockFiles(browserDataPath: String) {
    try {
        val lockFiles = listOf("SingletonLock", "SingletonSocket", "SingletonCookie")
        for (name in lockFiles) {
            val lockFile = File(browserDataPath, name)
            if (lockFile.exists()) {
                val deleted = lockFile.delete()
                println("[Call] CDP: Removed lock file $name: $deleted")
            }
        }
    } catch (e: Exception) {
        println("[Call] CDP: Failed to remove lock files: ${e.message}")
    }
}

/**
 * Connects to Chrome DevTools Protocol and injects the session into localStorage,
 * intercepts fetch for .well-known to provide rtc_foci (LiveKit transport),
 * then navigates to the Element Call URL.
 *
 * Strategy: Use Page.addScriptToEvaluateOnNewDocument to inject a script that
 * sets localStorage AND monkey-patches window.fetch BEFORE the page's own
 * JavaScript runs. Then navigate to the actual Element Call URL.
 *
 * @param wellKnownJson If non-null, the .well-known JSON with rtc_foci to inject
 *   via fetch interception. Element Call will see this when it queries
 *   .well-known/matrix/client on the homeserver domain.
 */
private fun injectSessionViaCdp(
    port: Int,
    session: ElementCallSession,
    targetUrl: String,
    wellKnownJson: String? = null,
) {
    // Wait for Chrome to start and expose a CDP target
    var targetWsUrl: String? = null
    for (attempt in 1..30) {
        Thread.sleep(1000)
        targetWsUrl = findCdpTarget(port, requiredUrlPrefix = null)
        if (targetWsUrl != null) break
        if (attempt % 5 == 0) {
            println("[Call] CDP: Waiting for Chrome to start... (attempt $attempt/30)")
        }
    }
    if (targetWsUrl == null) {
        println("[Call] CDP: Could not find any target after 30 seconds")
        return
    }
    println("[Call] CDP: Found target WebSocket: $targetWsUrl")

    val wsUri = URI(targetWsUrl)

    // Build the localStorage injection script
    val sessionJson = buildSessionJson(session)
    // Escape for embedding inside a JS string literal (single-quoted)
    val escapedJson = sessionJson
        .replace("\\", "\\\\")
        .replace("'", "\\'")

    // Build the fetch interception part (only if we have well-known with rtc_foci)
    // Build the .well-known and rtc/transports interception blocks.
    // These are embedded INSIDE the main fetch wrapper in the injection script,
    // so they must only contain if-blocks that return early, not their own fetch wrapper.
    val fetchInterceptBlock = if (wellKnownJson != null) {
        val escapedWellKnown = wellKnownJson
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "")
            .replace("\r", "")

        """
                    // 2. Intercept .well-known/matrix/client to provide rtc_foci for LiveKit
                    if (url.indexOf('.well-known/matrix/client') !== -1) {
                        console.log('[TeleCrypt] Intercepted .well-known request: ' + url);
                        var wellKnownBody = '$escapedWellKnown';
                        return Promise.resolve(new Response(wellKnownBody, {
                            status: 200,
                            statusText: 'OK',
                            headers: {'Content-Type': 'application/json'}
                        }));
                    }
                    // 3. Intercept RTC transports endpoint (returns 404 on server)
                    if (url.indexOf('/_matrix/client/') !== -1 && url.indexOf('/rtc/transports') !== -1) {
                        console.log('[TeleCrypt] Intercepted rtc/transports request: ' + url);
                        return Promise.resolve(new Response('{"transports":[]}', {
                            status: 200,
                            statusText: 'OK',
                            headers: {'Content-Type': 'application/json'}
                        }));
                    }
        """.trimIndent()
    } else {
        "// No .well-known interception (rtc_foci not resolved)"
    }

    val injectionScript = """
        (function() {
            try {
                localStorage.setItem('matrix-auth-store', '$escapedJson');
                console.log('[TeleCrypt] Session injected into localStorage via pre-load script');
                console.log('[TeleCrypt] Session JSON: ' + localStorage.getItem('matrix-auth-store'));
            } catch(e) {
                console.error('[TeleCrypt] Failed to inject session:', e);
            }
            try {
                // ── Comprehensive fetch interception ──
                // Element Call v0.19.1 in standalone mode:
                // 1. Ignores URL hash parameters (skipLobby, perParticipantE2EE, intent)
                // 2. Tries to register a new user via POST /register before using localStorage
                // 3. Fetches config.json, .well-known, rtc/transports
                //
                // We intercept ALL of these to make EC work with our injected session.
                var __tcSession = JSON.parse('$escapedJson');
                var __origFetch = window.fetch;
                window.fetch = function(input, init) {
                    // Robust URL extraction: handle string, Request object, URL object
                    var url = '';
                    if (typeof input === 'string') {
                        url = input;
                    } else if (input instanceof Request) {
                        url = input.url;
                    } else if (input && input.url) {
                        url = input.url;
                    } else if (input && typeof input.toString === 'function') {
                        url = input.toString();
                    }
                    // Robust method extraction: check init first, then Request object
                    var method = 'GET';
                    if (init && init.method) {
                        method = init.method.toUpperCase();
                    } else if (input instanceof Request && input.method) {
                        method = input.method.toUpperCase();
                    }

                    // ── Auth interception: /register ──
                    // Element Call standalone mode tries POST /register to create a new user.
                    // We intercept this and return a fake successful response with our session
                    // credentials, making EC think registration succeeded with our user.
                    if (url.indexOf('/_matrix/client/') !== -1 && url.indexOf('/register') !== -1) {
                        console.log('[TeleCrypt] Intercepted /register request: ' + method + ' ' + url);
                        // For the initial register call (to get flows), return UIAA flows
                        // For the actual register call, return our session credentials
                        if (method === 'POST') {
                            var body = null;
                            try { body = init && init.body ? JSON.parse(init.body) : null; } catch(e) {}
                            // If this is a guest registration or has no auth, return our credentials
                            var registerResponse = {
                                user_id: __tcSession.user_id,
                                device_id: __tcSession.device_id,
                                access_token: __tcSession.access_token,
                                home_server: __tcSession.user_id.split(':')[1]
                            };
                            console.log('[TeleCrypt] Returning fake register response for user: ' + __tcSession.user_id);
                            return Promise.resolve(new Response(JSON.stringify(registerResponse), {
                                status: 200,
                                statusText: 'OK',
                                headers: {'Content-Type': 'application/json'}
                            }));
                        }
                    }

                    // ── Auth interception: /login ──
                    // If EC falls back to login flow, intercept it too
                    if (url.indexOf('/_matrix/client/') !== -1 && url.indexOf('/login') !== -1 && url.indexOf('/login/') === -1) {
                        if (method === 'POST') {
                            console.log('[TeleCrypt] Intercepted /login request: ' + url);
                            var loginResponse = {
                                user_id: __tcSession.user_id,
                                device_id: __tcSession.device_id,
                                access_token: __tcSession.access_token,
                                home_server: __tcSession.user_id.split(':')[1]
                            };
                            return Promise.resolve(new Response(JSON.stringify(loginResponse), {
                                status: 200,
                                statusText: 'OK',
                                headers: {'Content-Type': 'application/json'}
                            }));
                        }
                        if (method === 'GET') {
                            console.log('[TeleCrypt] Intercepted GET /login (flows): ' + url);
                            var flowsResponse = {
                                flows: [
                                    { type: 'm.login.password' },
                                    { type: 'm.login.token' }
                                ]
                            };
                            return Promise.resolve(new Response(JSON.stringify(flowsResponse), {
                                status: 200,
                                statusText: 'OK',
                                headers: {'Content-Type': 'application/json'}
                            }));
                        }
                    }

                    // NOTE: config.json patching, URLSearchParams override, and
                    // RTCRtpScriptTransform stubbing have been REMOVED. These were
                    // attempts to disable per-participant E2EE in Element Call
                    // standalone mode. They did not work (see docs/CALLS_E2EE_PLAN.md):
                    //   • config.json is loaded via <link rel=preload>, not fetch();
                    //   • URLSearchParams override doesn't reach EC's URL parsing;
                    //   • SFrame encryption runs in a Web Worker created from a
                    //     blob: URL, so main-thread RTCRtp* stubs don't propagate.
                    // The proper fix is widget mode (Matrix Widget API) where EC
                    // delegates Olm/E2EE crypto to the host (TeleCrypt) — see plan.

                    $fetchInterceptBlock

                    return __origFetch.apply(this, arguments);
                };
                console.log('[TeleCrypt] Fetch interception installed (register + login + .well-known + rtc/transports)');
            } catch(e) {
                console.error('[TeleCrypt] Failed to install fetch interception:', e);
            }
        })();
    """.trimIndent()

    // Open a persistent WebSocket connection for multiple CDP commands
    val cdpConnection = CdpConnection.open(wsUri)
    if (cdpConnection == null) {
        println("[Call] CDP: Failed to open WebSocket connection")
        return
    }

    try {
        // Step 1: Enable Page domain (required for addScriptToEvaluateOnNewDocument)
        println("[Call] CDP: Enabling Page domain...")
        val enableResult = cdpConnection.sendCommand(1, "Page.enable", "{}")
        println("[Call] CDP: Page.enable result: $enableResult")

        // Step 2: Add script to evaluate on new document (runs before page JS)
        println("[Call] CDP: Adding pre-load injection script...")
        val escapedScript = injectionScript
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        val addScriptParams = """{"source":"$escapedScript"}"""
        val addScriptResult = cdpConnection.sendCommand(2, "Page.addScriptToEvaluateOnNewDocument", addScriptParams)
        println("[Call] CDP: addScriptToEvaluateOnNewDocument result: $addScriptResult")

        // Step 3: Navigate to the actual Element Call URL
        println("[Call] CDP: Navigating to $targetUrl")
        val escapedUrl = targetUrl.replace("\"", "\\\"")
        val navigateParams = """{"url":"$escapedUrl"}"""
        val navigateResult = cdpConnection.sendCommand(3, "Page.navigate", navigateParams)
        println("[Call] CDP: Page.navigate result: $navigateResult")

        // Step 4: Wait for page to load
        // After Page.navigate, Chrome sends many events. We sleep and then drain them.
        println("[Call] CDP: Waiting for page to load...")
        Thread.sleep(8000)

        // Drain all accumulated events from the navigation
        val drained = cdpConnection.drainEvents()
        println("[Call] CDP: Drained $drained events after navigation")

        // Step 5: Verify localStorage was set by the pre-load script
        val verifyJs = "localStorage.getItem('matrix-auth-store') ? 'HAS_SESSION' : 'NO_SESSION'"
        val verifyResult = cdpConnection.sendCommand(4, "Runtime.evaluate", """{"expression":"$verifyJs"}""")
        println("[Call] CDP: Verification result: $verifyResult")

        if (verifyResult != null && verifyResult.contains("HAS_SESSION")) {
            println("[Call] CDP: ✅ Session successfully injected! Element Call should authenticate.")
        } else {
            // Fallback: try direct Runtime.evaluate injection + reload
            println("[Call] CDP: Pre-load script didn't set localStorage. Trying direct injection...")
            val directJs = "try { localStorage.setItem('matrix-auth-store', '$escapedJson'); 'ok'; } catch(e) { 'error: ' + e.message; }"
            val directResult = cdpConnection.sendCommand(5, "Runtime.evaluate", """{"expression":"${directJs.replace("\"", "\\\"")}"}""")
            println("[Call] CDP: Direct injection result: $directResult")

            if (directResult != null && directResult.contains("\"value\":\"ok\"")) {
                println("[Call] CDP: Direct injection succeeded, reloading page...")
                Thread.sleep(500)
                cdpConnection.drainEvents()
                cdpConnection.sendCommand(6, "Page.reload", """{"ignoreCache":true}""")
                println("[Call] CDP: Page reload triggered")
            } else {
                println("[Call] CDP: ❌ All injection attempts failed")
            }
        }

        // Step 6: Enable console log monitoring to diagnose Element Call issues
        println("[Call] CDP: Enabling console log monitoring...")
        cdpConnection.drainEvents()
        cdpConnection.sendCommand(10, "Runtime.enable", "{}")
        cdpConnection.drainEvents()
        cdpConnection.sendCommand(11, "Log.enable", "{}")
        cdpConnection.drainEvents()

        // Monitor console logs in a background thread for 120 seconds
        // Also write full error frames to a log file for post-mortem analysis
        val errorLogFile = File("/tmp/telecrypt-cdp-errors.log")
        thread(start = true, isDaemon = true, name = "CdpConsoleMonitor") {
            try {
                println("[Call] CDP: Console monitor started (120 seconds)")
                errorLogFile.writeText("=== TeleCrypt CDP Error Log — ${java.time.Instant.now()} ===\n\n")
                val monitorStart = System.currentTimeMillis()
                while (System.currentTimeMillis() - monitorStart < 120_000) {
                    val frame = cdpConnection.readOneFrame()
                    if (frame != null) {
                        val isException = frame.contains("\"method\":\"Runtime.exceptionThrown\"")
                        val isLogEntry = frame.contains("\"method\":\"Log.entryAdded\"")
                        val isConsole = frame.contains("\"method\":\"Runtime.consoleAPICalled\"")

                        if (isException || isLogEntry || isConsole) {
                            // For errors/exceptions, log the FULL frame to file and print more to console
                            if (isException || isLogEntry) {
                                errorLogFile.appendText("--- ${if (isException) "EXCEPTION" else "LOG_ENTRY"} ---\n")
                                errorLogFile.appendText(frame)
                                errorLogFile.appendText("\n\n")
                            }

                            // Extract message text with much larger limits
                            val msgMatch = """"text"\s*:\s*"([^"]{0,5000})"""".toRegex().find(frame)
                            val descMatch = """"description"\s*:\s*"([^"]{0,5000})"""".toRegex().find(frame)
                            val valueMatch = """"value"\s*:\s*"([^"]{0,5000})"""".toRegex().find(frame)
                            // Also extract URL for Log.entryAdded
                            val urlMatch = """"url"\s*:\s*"([^"]{0,2000})"""".toRegex().find(frame)
                            // Extract stack trace for exceptions
                            val stackMatch = """"stackTrace"\s*:\s*\{[^}]*"description"\s*:\s*"([^"]{0,3000})"""".toRegex().find(frame)
                            // Extract line/column for errors
                            val lineMatch = """"lineNumber"\s*:\s*(\d+)""".toRegex().find(frame)
                            val colMatch = """"columnNumber"\s*:\s*(\d+)""".toRegex().find(frame)

                            val msg = msgMatch?.groupValues?.get(1)
                                ?: descMatch?.groupValues?.get(1)
                                ?: valueMatch?.groupValues?.get(1)

                            val prefix = when {
                                isException -> "[Call] EC EXCEPTION"
                                isLogEntry -> "[Call] EC LOG"
                                else -> "[Call] EC console"
                            }

                            if (msg != null) {
                                println("$prefix: $msg")
                                urlMatch?.groupValues?.get(1)?.let { println("$prefix   url: $it") }
                                lineMatch?.groupValues?.get(1)?.let { line ->
                                    val col = colMatch?.groupValues?.get(1) ?: "?"
                                    println("$prefix   at line:$line col:$col")
                                }
                                stackMatch?.groupValues?.get(1)?.let { println("$prefix   stack: $it") }
                            } else {
                                // No regex match — print as much of the raw frame as possible
                                println("$prefix (raw): ${frame.take(3000)}")
                            }
                        }
                    } else {
                        Thread.sleep(100)
                    }
                }
                println("[Call] CDP: Console monitor finished. Error log: ${errorLogFile.absolutePath}")
            } catch (e: Exception) {
                println("[Call] CDP: Console monitor error: ${e.message}")
            } finally {
                cdpConnection.close()
            }
        }
        // Don't close connection here — the monitor thread owns it now
        return
    } catch (e: Exception) {
        println("[Call] CDP: Error during injection: ${e.message}")
        e.printStackTrace()
        cdpConnection.close()
    }
}

/**
 * Finds a CDP target (page) by querying Chrome's /json endpoint.
 * If [requiredUrlPrefix] is set, only returns a target whose URL starts with that prefix.
 */
private fun findCdpTarget(port: Int, requiredUrlPrefix: String? = null): String? {
    return runCatching {
        val url = URI("http://127.0.0.1:$port/json").toURL()
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        conn.requestMethod = "GET"
        val response = conn.inputStream.bufferedReader().readText()
        conn.disconnect()

        println("[Call] CDP /json response (first 500 chars): ${response.take(500)}")

        // Parse JSON array of targets manually
        // Each target has "url": "...", "webSocketDebuggerUrl": "ws://..."
        val targetPattern = """\{[^}]*"url"\s*:\s*"([^"]*)"[^}]*"webSocketDebuggerUrl"\s*:\s*"(ws://[^"]+)"[^}]*\}""".toRegex()
        val reversePattern = """\{[^}]*"webSocketDebuggerUrl"\s*:\s*"(ws://[^"]+)"[^}]*"url"\s*:\s*"([^"]*)"[^}]*\}""".toRegex()

        // Try both orderings of url and webSocketDebuggerUrl
        for (match in targetPattern.findAll(response)) {
            val targetUrl = match.groupValues[1]
            val wsUrl = match.groupValues[2]
            if (requiredUrlPrefix == null || targetUrl.startsWith(requiredUrlPrefix)) {
                println("[Call] CDP: target url=$targetUrl ws=$wsUrl")
                return@runCatching wsUrl
            }
        }
        for (match in reversePattern.findAll(response)) {
            val wsUrl = match.groupValues[1]
            val targetUrl = match.groupValues[2]
            if (requiredUrlPrefix == null || targetUrl.startsWith(requiredUrlPrefix)) {
                println("[Call] CDP: target url=$targetUrl ws=$wsUrl")
                return@runCatching wsUrl
            }
        }

        // Fallback: just find any webSocketDebuggerUrl
        if (requiredUrlPrefix == null) {
            val fallbackRegex = """"webSocketDebuggerUrl"\s*:\s*"(ws://[^"]+)"""".toRegex()
            val fallbackMatch = fallbackRegex.find(response)
            return@runCatching fallbackMatch?.groupValues?.get(1)
        }
        null
    }.getOrNull()
}

// ── CDP WebSocket connection ─────────────────────────────────────────────

/**
 * A persistent CDP WebSocket connection that supports sending multiple commands.
 * Uses raw sockets with proper binary WebSocket frame handling (no bufferedReader).
 */
private class CdpConnection private constructor(
    private val socket: java.net.Socket,
    private val output: OutputStream,
    private val input: InputStream,
) : AutoCloseable {

    companion object {
        fun open(wsUri: URI): CdpConnection? {
            return runCatching {
                val host = wsUri.host ?: "127.0.0.1"
                val port = wsUri.port
                val path = wsUri.path ?: "/"

                val socket = java.net.Socket(host, port)
                socket.soTimeout = 10_000
                val output = socket.getOutputStream()
                val input = socket.getInputStream()

                // WebSocket handshake
                val key = java.util.Base64.getEncoder().encodeToString(
                    ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
                )
                val handshake = "GET $path HTTP/1.1\r\n" +
                    "Host: $host:$port\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\n" +
                    "Sec-WebSocket-Version: 13\r\n" +
                    "\r\n"
                output.write(handshake.toByteArray(Charsets.US_ASCII))
                output.flush()

                // Read HTTP response headers byte-by-byte to avoid over-reading
                val headerBuf = StringBuilder()
                var prev = 0
                var curr: Int
                var crlfCount = 0
                while (true) {
                    curr = input.read()
                    if (curr == -1) break
                    headerBuf.append(curr.toChar())
                    if (curr == '\n'.code && prev == '\r'.code) {
                        crlfCount++
                        if (crlfCount >= 2) break
                    } else if (curr != '\r'.code) {
                        crlfCount = 0
                    }
                    prev = curr
                }

                val headers = headerBuf.toString()
                if (!headers.contains("101")) {
                    println("[Call] CDP: WebSocket handshake failed: ${headers.take(200)}")
                    socket.close()
                    return@runCatching null
                }
                println("[Call] CDP: WebSocket handshake successful")

                CdpConnection(socket, output, input)
            }.getOrNull()
        }
    }

    /**
     * Drains all pending frames from the socket (events accumulated during sleep).
     * Returns the number of frames drained.
     */
    fun drainEvents(): Int {
        var count = 0
        val oldTimeout = socket.soTimeout
        socket.soTimeout = 200 // short timeout for draining
        try {
            while (true) {
                val frame = readWebSocketFrame()
                if (frame != null) {
                    count++
                    println("[Call] CDP: drained event: ${frame.take(120)}...")
                } else {
                    break
                }
            }
        } catch (_: Exception) {
            // timeout or read error — done draining
        }
        socket.soTimeout = oldTimeout
        return count
    }

    /**
     * Reads a single WebSocket frame with a 2-second timeout.
     * Returns the frame content or null if timeout/error.
     */
    fun readOneFrame(): String? {
        val oldTimeout = socket.soTimeout
        socket.soTimeout = 2000
        return try {
            readWebSocketFrame()
        } catch (_: Exception) {
            null
        } finally {
            socket.soTimeout = oldTimeout
        }
    }

    /**
     * Sends a CDP command and waits for the response with the matching id.
     * Skips CDP events (which have "method" field) while waiting.
     */
    fun sendCommand(id: Int, method: String, params: String): String? {
        return runCatching {
            val message = """{"id":$id,"method":"$method","params":$params}"""
            println("[Call] CDP: >>> sending: ${message.take(200)}")
            sendWebSocketFrame(message)

            // Read responses until we find one with our id (skip events)
            // CDP can send many events (especially after Page.navigate), so try many times
            for (attempt in 1..100) {
                val response = readWebSocketFrame()
                if (response == null) {
                    println("[Call] CDP: <<< frame $attempt: null (timeout or close)")
                    // null could mean timeout — give up
                    break
                }
                // Check if this response matches our command id
                if (response.contains("\"id\":$id") || response.contains("\"id\": $id")) {
                    println("[Call] CDP: <<< response for id=$id: ${response.take(200)}")
                    return@runCatching response
                }
                // It's a CDP event, log and skip it
                if (response.contains("exceptionThrown") || response.contains("error") || response.contains("Failed")) {
                    println("[Call] CDP: <<< ERROR event (attempt $attempt): ${response.take(2000)}")
                } else {
                    println("[Call] CDP: <<< event (skipping, attempt $attempt): ${response.take(500)}...")
                }
            }
            println("[Call] CDP: <<< gave up waiting for id=$id after 100 frames")
            null
        }.getOrNull()
    }

    /**
     * Sends a WebSocket text frame (masked, as required by client-to-server).
     */
    private fun sendWebSocketFrame(message: String) {
        val payload = message.toByteArray(Charsets.UTF_8)
        val frame = mutableListOf<Byte>()

        // FIN + opcode 0x1 (text)
        frame.add(0x81.toByte())

        // Mask bit set + payload length
        val maskBit = 0x80
        when {
            payload.size < 126 -> frame.add((maskBit or payload.size).toByte())
            payload.size < 65536 -> {
                frame.add((maskBit or 126).toByte())
                frame.add((payload.size shr 8).toByte())
                frame.add((payload.size and 0xFF).toByte())
            }
            else -> {
                frame.add((maskBit or 127).toByte())
                for (i in 7 downTo 0) {
                    frame.add((payload.size.toLong() shr (8 * i) and 0xFF).toByte())
                }
            }
        }

        // Masking key (4 random bytes)
        val maskKey = ByteArray(4).also { java.security.SecureRandom().nextBytes(it) }
        frame.addAll(maskKey.toList())

        // Masked payload
        for (i in payload.indices) {
            frame.add((payload[i].toInt() xor maskKey[i % 4].toInt()).toByte())
        }

        output.write(frame.toByteArray())
        output.flush()
    }

    /**
     * Reads a single WebSocket frame from the input stream.
     * Handles variable-length payload headers correctly.
     */
    private fun readWebSocketFrame(): String? {
        return runCatching {
            // Read first 2 bytes: FIN/opcode + mask/length
            val header = ByteArray(2)
            if (readFully(header, 2) < 2) return@runCatching null

            val opcode = header[0].toInt() and 0x0F
            val masked = (header[1].toInt() and 0x80) != 0
            var payloadLen = (header[1].toInt() and 0x7F).toLong()

            // Extended payload length
            when (payloadLen.toInt()) {
                126 -> {
                    val ext = ByteArray(2)
                    readFully(ext, 2)
                    payloadLen = ((ext[0].toInt() and 0xFF).toLong() shl 8) or
                        (ext[1].toInt() and 0xFF).toLong()
                }
                127 -> {
                    val ext = ByteArray(8)
                    readFully(ext, 8)
                    payloadLen = 0L
                    for (i in 0..7) {
                        payloadLen = (payloadLen shl 8) or (ext[i].toInt() and 0xFF).toLong()
                    }
                }
            }

            // Masking key (if present — server frames are usually unmasked)
            val maskKey = if (masked) {
                val mk = ByteArray(4)
                readFully(mk, 4)
                mk
            } else null

            // Read payload (cap at 256KB to avoid OOM)
            val safeLen = minOf(payloadLen, 256 * 1024L).toInt()
            val payload = ByteArray(safeLen)
            readFully(payload, safeLen)

            // Unmask if needed
            if (maskKey != null) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                }
            }

            // Only handle text frames (opcode 1) and continuation (opcode 0)
            if (opcode == 1 || opcode == 0) {
                String(payload, Charsets.UTF_8)
            } else if (opcode == 8) {
                // Close frame
                null
            } else {
                // Ping (9), Pong (10), etc. — skip
                null
            }
        }.getOrNull()
    }

    /**
     * Reads exactly [count] bytes from the input stream into [buffer].
     * Returns the number of bytes actually read.
     */
    private fun readFully(buffer: ByteArray, count: Int): Int {
        var totalRead = 0
        while (totalRead < count) {
            val n = input.read(buffer, totalRead, count - totalRead)
            if (n == -1) break
            totalRead += n
        }
        return totalRead
    }

    override fun close() {
        runCatching { socket.close() }
    }
}

/**
 * Builds the JSON string for localStorage["matrix-auth-store"].
 *
 * Element Call v0.19.1 reads this object to authenticate. The key fields are:
 * - user_id, device_id, access_token — standard Matrix credentials
 * - passwordlessUser — must be false so Element Call doesn't treat this as a guest
 * - homeserver — the homeserver base URL. Without this, Element Call cannot determine
 *   which server to connect to and falls back to the registration flow (POST /register),
 *   which fails with 403 and causes "UNKNOWN ERROR" on the remote side.
 */
private fun buildSessionJson(session: ElementCallSession): String {
    val userId = escapeJsonString(session.userId)
    val deviceId = escapeJsonString(session.deviceId)
    val accessToken = escapeJsonString(session.accessToken)
    val homeserver = escapeJsonString(session.homeserver)
    val passwordlessUser = session.passwordlessUser
    return """{"user_id":"$userId","device_id":"$deviceId","access_token":"$accessToken","homeserver":"$homeserver","passwordlessUser":$passwordlessUser}"""
}

private fun escapeJsonString(value: String): String {
    val out = StringBuilder(value.length + 8)
    for (ch in value) {
        when (ch) {
            '\\' -> out.append("\\\\")
            '"' -> out.append("\\\"")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> out.append(ch)
        }
    }
    return out.toString()
}

// ── External browser window (no session injection) ───────────────────────

/**
 * Opens the call URL in an external browser window (Chrome/Edge --app mode on
 * Windows/Linux, system browser on macOS as fallback).
 */
private fun openExternalCallWindow(url: String) {
    val os = System.getProperty("os.name").lowercase()
    when {
        os.contains("win") -> {
            val browser = findWindowsAppBrowser()
            if (browser != null) {
                val rootPath = System.getenv("TRIXNITY_MESSENGER_ROOT_PATH") ?: "./app-data"
                val browserDataPath = File(rootPath, "browser-data").absolutePath
                val process = ProcessBuilder(
                    browser,
                    "--app=$url",
                    "--new-window",
                    "--user-data-dir=$browserDataPath"
                ).start()
                bringProcessToFront(process.pid())
            } else {
                openUrlInBrowser(url)
            }
        }
        os.contains("nix") || os.contains("nux") -> {
            val chromium = findLinuxChromium()
            if (chromium != null) {
                val rootPath = System.getenv("TRIXNITY_MESSENGER_ROOT_PATH") ?: "./app-data"
                val browserDataPath = File(rootPath, "browser-data").absolutePath
                ProcessBuilder(
                    chromium,
                    "--app=$url",
                    "--new-window",
                    "--disable-extensions",
                    "--user-data-dir=$browserDataPath"
                ).start()
            } else {
                openUrlInBrowser(url)
            }
        }
        else -> {
            openUrlInBrowser(url)
        }
    }
}

// ── WebviewKo embedded window (Windows) ──────────────────────────────────

/**
 * Attempts to open Element Call in an embedded WebviewKo window with
 * JavaScript injection that writes session credentials into localStorage.
 *
 * Returns `true` if the window was launched successfully.
 * NOTE: This crashes on macOS (SIGSEGV in libobjc), so it's only used on Windows.
 */
private fun tryOpenEmbeddedWindow(url: String, session: ElementCallSession): Boolean {
    return runCatching {
        val initScript = buildElementCallSessionInitScript(session)
        println("[Call] Starting embedded WebView window")
        thread(start = true, isDaemon = false, name = "ElementCallWebview") {
            try {
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
                webview.start()
                webview.destroy()
            } catch (e: Exception) {
                println("[Call] WebView error: ${e.message}")
                e.printStackTrace()
            }
        }
    }.onFailure { e ->
        println("[Call] Failed to start embedded WebView: ${e.message}")
    }.isSuccess
}

// ── Main entry point ─────────────────────────────────────────────────────

/**
 * Opens the call window with the best available method for the current platform:
 *
 * 1. **Windows**: WebviewKo embedded window with JS injection (sets localStorage)
 * 2. **macOS/Linux**: Chrome --app mode with CDP session injection + fetch interception
 * 3. **Fallback**: System browser (no session injection — user sees "Join as guest")
 */
private fun openCallWindow(url: String, session: ElementCallSession?, roomId: String? = null) {
    if (session != null) {
        println("[Call] Opening call with session: userId=${session.userId} deviceId=${session.deviceId} homeserver=${session.homeserver}")
    } else {
        println("[Call] Opening call without session — Element Call may show 'Join as guest'")
    }
    println("[Call] URL: $url")

    // Extract roomId from URL if not provided directly
    val effectiveRoomId = roomId ?: extractRoomIdFromUrl(url)
    if (effectiveRoomId != null) {
        println("[Call] Room ID: $effectiveRoomId")
    }

    val os = System.getProperty("os.name").lowercase()

    if (session != null) {
        // On Windows, try WebviewKo first (it works there)
        if (os.contains("win")) {
            if (tryOpenEmbeddedWindow(url, session)) {
                println("[Call] Opened in embedded WebView with session injection")
                return
            }
            println("[Call] WebView failed on Windows, trying Chrome CDP")
        }

        // On all platforms, try Chrome CDP injection (with .well-known fetch interception)
        if (openWithChromeCdp(url, session, effectiveRoomId)) {
            println("[Call] Opened in Chrome with CDP session injection")
            return
        }
        println("[Call] Chrome CDP not available, falling back to system browser")
    }

    println("[Call] Falling back to external browser (no session injection)")
    openExternalCallWindow(url)
}

/**
 * Extracts the Matrix room ID from an Element Call URL.
 * The URL contains `roomId=%21abc%3Aserver.name` (URL-encoded `!abc:server.name`).
 */
private fun extractRoomIdFromUrl(url: String): String? {
    // Look for roomId= in the URL (could be in query or hash fragment)
    val match = """roomId=([^&]+)""".toRegex().find(url) ?: return null
    val encoded = match.groupValues[1]
    // URL-decode: %21 -> !, %3A -> :, etc.
    return runCatching { java.net.URLDecoder.decode(encoded, "UTF-8") }.getOrNull()
}

// ── Browser discovery helpers ────────────────────────────────────────────

private fun findLinuxChromium(): String? {
    val candidates = listOf("chromium", "chromium-browser", "google-chrome", "google-chrome-stable")
    for (name in candidates) {
        val result = runCatching {
            val process = ProcessBuilder("which", name).start()
            val exitCode = process.waitFor()
            if (exitCode == 0) name else null
        }.getOrNull()
        if (result != null) return result
    }
    return null
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

// ── CallLauncher implementation ──────────────────────────────────────────

/**
 * Desktop implementation of CallLauncher.
 * Opens Element Call with session injection via WebviewKo (Windows) or
 * Chrome CDP (macOS/Linux), falling back to the system browser.
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

    /**
     * Widget‑mode: open the host URL directly in a Chromium‑based browser via
     * `--app=<hostUrl>` without any CDP injection. EC inside the iframe gets
     * Matrix credentials over postMessage from the host page, so we don't need
     * to touch localStorage or intercept fetch.
     */
    override fun joinByWidgetUrl(hostUrl: String) {
        println("[Call] Widget mode: opening host page directly: $hostUrl")
        val os = System.getProperty("os.name").lowercase()
        val browser = when {
            os.contains("mac") -> findMacChrome()
            os.contains("nix") || os.contains("nux") -> findLinuxChromium()
            os.contains("win") -> findWindowsAppBrowser()
            else -> null
        }
        if (browser == null) {
            println("[Call] Widget mode: no Chromium‑based browser found, falling back to system browser")
            openUrlInBrowser(hostUrl)
            return
        }
        try {
            val rootPath = System.getenv("TRIXNITY_MESSENGER_ROOT_PATH") ?: "./app-data"
            val browserDataPath = File(rootPath, "browser-data-widget").absolutePath
            // Avoid stale singleton from previous run
            killExistingBrowserDataProcess(browserDataPath)
            removeBrowserLockFiles(browserDataPath)
            val cmd = mutableListOf(
                browser,
                "--app=$hostUrl",
                "--new-window",
                "--user-data-dir=$browserDataPath",
                "--no-first-run",
                "--no-default-browser-check",
                "--autoplay-policy=no-user-gesture-required",
                // Prevent the browser from restoring its previous session on launch.
                // Without this, Yandex (and some Chromium forks) open their last-session
                // tabs in a separate window alongside the --app window on every call.
                "--no-restore-last-session",
                "--restore-last-session=false",
            )
            val process = ProcessBuilder(cmd).start()
            currentWidgetProcess = process
            println("[Call] Widget mode: launched ${browser.substringAfterLast('/')} with --app=$hostUrl")
        } catch (e: Exception) {
            println("[Call] Widget mode: failed to launch browser, falling back: ${e.message}")
            openUrlInBrowser(hostUrl)
        }
    }

    override fun isCallAvailable(roomId: String): Boolean {
        return true
    }

    companion object {
        @Volatile private var currentWidgetProcess: Process? = null

        /**
         * Terminate the widget browser window opened by the most recent
         * [joinByWidgetUrl] call. Safe to call from any thread.
         * Wired from [DesktopWidgetBridgeManager] into [WidgetApiHandler]'s
         * onClose callback, fired when EC sends `io.element.close`.
         */
        fun closeCurrent() {
            val p = currentWidgetProcess ?: return
            currentWidgetProcess = null
            runCatching { p.destroy() }
            println("[Call] Widget mode: browser process destroyed")
        }
    }
}

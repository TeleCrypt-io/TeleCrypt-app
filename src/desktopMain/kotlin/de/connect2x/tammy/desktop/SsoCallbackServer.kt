package de.connect2x.tammy.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.net.InetSocketAddress

/**
 * Local HTTP server to handle SSO callbacks.
 * 
 * Instead of relying on Windows custom URL scheme registration,
 * we start a local HTTP server that can receive the SSO callback
 * from the browser.
 * 
 * The redirect URL becomes: http://localhost:47824/sso?state=xxx
 */
object SsoCallbackServer {
    private const val PORT = 47824
    private var server: HttpServer? = null
    
    private val _callbackFlow = MutableSharedFlow<SsoCallback>(extraBufferCapacity = 10)
    
    /**
     * Data class for SSO callback parameters
     */
    data class SsoCallback(
        val state: String,
        val loginToken: String
    )
    
    /**
     * Flow of SSO callback parameters received
     */
    val callbackFlow: SharedFlow<SsoCallback> = _callbackFlow
    
    /**
     * Start the local HTTP server for SSO callbacks
     */
    fun start(scope: CoroutineScope) {
        try {
            server = HttpServer.create(InetSocketAddress(PORT), 0).apply {
                createContext("/sso") { exchange ->
                    handleSsoCallback(exchange, scope)
                }
                createContext("/") { exchange ->
                    handleRoot(exchange)
                }
                executor = null
                start()
            }
            println("[SsoCallbackServer] Started on port $PORT")
        } catch (e: Exception) {
            println("[SsoCallbackServer] Failed to start: ${e.message}")
        }
    }
    
    private fun handleSsoCallback(exchange: HttpExchange, scope: CoroutineScope) {
        val query = exchange.requestURI.query ?: ""
        val params = query.split("&").associate { 
            val parts = it.split("=", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else parts[0] to ""
        }
        
        val state = params["state"] ?: ""
        val loginToken = params["loginToken"] ?: ""
        
        println("[SsoCallbackServer] Received callback - state: $state, loginToken: ${loginToken.take(10)}...")
        
        // Emit the callback to the flow
        if (state.isNotEmpty() && loginToken.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                _callbackFlow.emit(SsoCallback(state, loginToken))
            }
        }
        
        // Send success response to browser with auto-close script
        val response = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>TeleCrypt - SSO Success</title>
                <style>
                    body { font-family: system-ui, sans-serif; display: flex; justify-content: center; align-items: center; height: 100vh; margin: 0; background: #1a1a2e; color: #eee; }
                    .container { text-align: center; }
                    h1 { color: #4ade80; }
                    p { color: #aaa; }
                </style>
            </head>
            <body>
                <div class="container">
                    <h1>✓ Login Successful</h1>
                    <p>You can close this window and return to TeleCrypt.</p>
                </div>
                <script>
                    // Try to close window after 2 seconds
                    setTimeout(function() { window.close(); }, 2000);
                </script>
            </body>
            </html>
        """.trimIndent()
        
        exchange.responseHeaders.add("Content-Type", "text/html")
        exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
        exchange.responseBody.use { it.write(response.toByteArray()) }
    }
    
    private fun handleRoot(exchange: HttpExchange) {
        val response = "TeleCrypt SSO Callback Server"
        exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
        exchange.responseBody.use { it.write(response.toByteArray()) }
    }
    
    /**
     * Get the redirect URL to use for SSO
     */
    fun getRedirectUrl(): String = "http://localhost:$PORT/sso"
    
    fun stop() {
        server?.stop(0)
    }
}

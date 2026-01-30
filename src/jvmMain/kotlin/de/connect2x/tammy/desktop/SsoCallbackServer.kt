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
 * Trixnity-messenger on desktop doesn't have its own HTTP server,
 * so we provide one to receive SSO callbacks from the browser.
 * 
 * The redirect URL is: http://localhost:47824/sso?state=xxx&loginToken=yyy
 * 
 * After receiving the callback, the user needs to restart the app
 * for trixnity to process the login token.
 */
object SsoCallbackServer {
    private const val PORT = 47824
    private var server: HttpServer? = null
    
    private val _callbackFlow = MutableSharedFlow<SsoCallback>(extraBufferCapacity = 10)
    
    data class SsoCallback(
        val state: String,
        val loginToken: String
    )
    
    val callbackFlow: SharedFlow<SsoCallback> = _callbackFlow
    
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
        
        if (state.isNotEmpty() && loginToken.isNotEmpty()) {
            // Emit to flow
            scope.launch(Dispatchers.IO) {
                _callbackFlow.emit(SsoCallback(state, loginToken))
            }
            
            // Save callback URL to temp file for next startup
            val callbackUrl = "com.zendev.telecrypt://localhost/sso?state=$state&loginToken=$loginToken"
            
            // Inject into runtime flow immediately
            scope.launch {
                SingleInstanceManager.injectDeeplink(callbackUrl)
            }
            
            val tempFile = java.io.File(System.getProperty("java.io.tmpdir"), "telecrypt_sso_callback.txt")
            tempFile.writeText(callbackUrl)
            println("[SsoCallbackServer] Saved callback for next startup")
        }
        
        // Send user-friendly response
        val response = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>TeleCrypt - Login Successful</title>
                <style>
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    body { 
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        display: flex; 
                        justify-content: center; 
                        align-items: center; 
                        min-height: 100vh; 
                        background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
                        color: #eee; 
                    }
                    .container { 
                        text-align: center; 
                        padding: 40px;
                        background: rgba(255,255,255,0.05);
                        border-radius: 16px;
                        backdrop-filter: blur(10px);
                        max-width: 500px;
                    }
                    h1 { 
                        color: #4ade80; 
                        font-size: 28px;
                        margin-bottom: 20px;
                    }
                    .icon {
                        font-size: 64px;
                        margin-bottom: 20px;
                    }
                    p { 
                        color: #aaa; 
                        margin-bottom: 15px;
                        line-height: 1.6;
                    }
                    .instruction {
                        background: rgba(74, 222, 128, 0.1);
                        border: 1px solid rgba(74, 222, 128, 0.3);
                        border-radius: 8px;
                        padding: 20px;
                        margin-top: 20px;
                    }
                    .instruction strong {
                        color: #4ade80;
                        display: block;
                        margin-bottom: 10px;
                    }
                    .steps {
                        text-align: left;
                        color: #ccc;
                    }
                    .steps li {
                        margin-bottom: 8px;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="icon">✓</div>
                    <h1>Login Successful!</h1>
                    <p>Your authentication was successful.</p>
                    <div class="instruction">
                        <strong>Next:</strong>
                        <p>You can return to TeleCrypt. Login should complete automatically.</p>
                    </div>
                    <p style="margin-top: 20px; font-size: 14px; color: #888;">
                        You can close this browser tab.
                    </p>
                </div>
            </body>
            </html>
        """.trimIndent()
        
        val responseBytes = response.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(200, responseBytes.size.toLong())
        exchange.responseBody.use { it.write(responseBytes) }
    }
    
    private fun handleRoot(exchange: HttpExchange) {
        val response = "TeleCrypt SSO Callback Server"
        exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
        exchange.responseBody.use { it.write(response.toByteArray()) }
    }
    
    fun getRedirectUrl(): String = "http://localhost:$PORT/sso"
    
    fun stop() {
        server?.stop(0)
    }
}

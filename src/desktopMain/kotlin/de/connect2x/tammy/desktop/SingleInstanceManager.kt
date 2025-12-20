package de.connect2x.tammy.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

/**
 * Manages single-instance behavior for the desktop application.
 * Uses a local TCP socket to:
 * 1. Detect if another instance is running
 * 2. Forward deeplink URLs to the running instance
 * 3. Receive deeplink URLs from new instances
 */
object SingleInstanceManager {
    private const val PORT = 47823 // Random high port for IPC
    private var serverSocket: ServerSocket? = null
    
    private val _deeplinkFlow = MutableSharedFlow<String>(extraBufferCapacity = 10)
    
    /**
     * Flow of deeplink URLs received from other app instances
     */
    val deeplinkFlow: SharedFlow<String> = _deeplinkFlow
    
    /**
     * Try to become the primary instance.
     * @return true if this is the primary instance, false if another instance is running
     */
    fun tryAcquireLock(): Boolean {
        return try {
            serverSocket = ServerSocket(PORT)
            true
        } catch (e: Exception) {
            // Port already in use - another instance is running
            false
        }
    }
    
    /**
     * Start listening for deeplink URLs from other instances.
     * Call this only if tryAcquireLock() returned true.
     */
    fun startListening(scope: CoroutineScope) {
        val server = serverSocket ?: return
        
        scope.launch(Dispatchers.IO) {
            while (!server.isClosed) {
                try {
                    val client = server.accept()
                    handleClient(client)
                } catch (e: Exception) {
                    // Socket closed or error
                    break
                }
            }
        }
    }
    
    private suspend fun handleClient(client: Socket) {
        try {
            client.use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val url = reader.readLine()
                if (!url.isNullOrBlank()) {
                    println("[SingleInstance] Received deeplink: $url")
                    _deeplinkFlow.emit(url)
                }
            }
        } catch (e: Exception) {
            println("[SingleInstance] Error handling client: ${e.message}")
        }
    }
    
    /**
     * Send a deeplink URL to the running instance and exit.
     * Call this if tryAcquireLock() returned false.
     * @return true if successfully sent, false otherwise
     */
    fun sendDeeplinkToRunningInstance(url: String): Boolean {
        return try {
            Socket("localhost", PORT).use { socket ->
                val writer = PrintWriter(socket.getOutputStream(), true)
                writer.println(url)
            }
            println("[SingleInstance] Sent deeplink to running instance: $url")
            true
        } catch (e: Exception) {
            println("[SingleInstance] Failed to send deeplink: ${e.message}")
            false
        }
    }
    
    /**
     * Clean up resources
     */
    fun shutdown() {
        serverSocket?.close()
    }
}

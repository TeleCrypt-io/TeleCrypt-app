package de.connect2x.tammy.desktop

import de.connect2x.messenger.desktop.startMessenger
import de.connect2x.tammy.tammyConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

/**
 * Main entry point for TeleCrypt Desktop.
 * Handles single-instance behavior and deeplink forwarding for SSO callbacks.
 * 
 * The SSO flow works as follows:
 * 1. User clicks SSO login in the app
 * 2. Browser opens the SSO provider
 * 3. After authentication, browser redirects to com.zendev.telecrypt://...
 * 4. Windows opens tools/telecrypt-url-handler.bat with the URL
 * 5. Handler sends URL to running app via TCP socket (port 47823)
 * 6. App saves the URL to a temp file and exits
 * 7. Handler relaunches the app which reads the URL from args
 */
fun main(args: Array<String>) {
    // Check if we have a pending SSO callback from previous instance
    val pendingCallbackFile = File(System.getProperty("java.io.tmpdir"), "telecrypt_sso_callback.txt")
    val pendingCallback = if (pendingCallbackFile.exists()) {
        val callback = pendingCallbackFile.readText().trim()
        pendingCallbackFile.delete()
        println("[Main] Found pending SSO callback: $callback")
        callback
    } else null
    
    // Merge command line deeplink with pending callback
    val deeplinkUrl = args.firstOrNull { it.startsWith("com.zendev.telecrypt://") }
        ?: pendingCallback
    
    // Try to become the primary instance
    if (!SingleInstanceManager.tryAcquireLock()) {
        // Another instance is running - forward the deeplink and exit
        if (deeplinkUrl != null) {
            println("[Main] Another instance running, forwarding deeplink...")
            SingleInstanceManager.sendDeeplinkToRunningInstance(deeplinkUrl)
        } else {
            println("[Main] Another instance is already running. Bringing to front...")
            SingleInstanceManager.sendDeeplinkToRunningInstance("focus")
        }
        exitProcess(0)
    }
    
    // We are the primary instance
    println("[Main] Primary instance starting...")
    if (deeplinkUrl != null) {
        println("[Main] Starting with deeplink: $deeplinkUrl")
    }
    
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // Start listening for deeplinks from other instances (including URL handler)
    SingleInstanceManager.startListening(scope)
    
    // Collect deeplinks from socket (received from Windows URL handler)
    scope.launch {
        SingleInstanceManager.deeplinkFlow.collect { url ->
            println("[Main] Received deeplink via socket: $url")
            if (url.startsWith("com.zendev.telecrypt://") && url.contains("loginToken")) {
                // This is an SSO callback - save it and request app restart
                println("[Main] SSO callback received! Saving for restart...")
                pendingCallbackFile.writeText(url)
                
                // Give a moment for file write
                delay(100)
                
                println("[Main] Please restart the application to complete login.")
                println("[Main] The SSO token has been saved and will be processed on next start.")
                
                // In development mode, we can't easily restart
                // For now, just notify the user
            }
        }
    }
    
    // Build args to pass to startMessenger
    val messengerArgs = if (deeplinkUrl != null) {
        println("[Main] Passing deeplink to messenger: $deeplinkUrl")
        arrayOf(deeplinkUrl)
    } else {
        args
    }
    
    // Start the messenger with standard configuration
    startMessenger(
        configuration = tammyConfiguration(),
        args = messengerArgs,
    )
    
    // Cleanup on exit
    SingleInstanceManager.shutdown()
}
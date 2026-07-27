package de.connect2x.tammy

import de.connect2x.lognity.api.backend.Backend
import de.connect2x.lognity.backend.DefaultBackend
import de.connect2x.lognity.config.CoreConfigExtension
import de.connect2x.lognity.config.SerializableConfig
import de.connect2x.lognity.config.extension.ConfigExtension
import de.connect2x.lognity.config.setDefaultConfig
import de.connect2x.trixnity.messenger.compose.view.startMultiMessenger
import de.connect2x.trixnity.messenger.util.getAppPath
import de.connect2x.tammy.telecryptModules.call.callBackend.CallLauncher
import de.connect2x.tammy.telecryptModules.call.callBackend.buildElementCallUrl
import de.connect2x.tammy.telecryptModules.call.callBackend.resolveElementCallSession
import de.connect2x.tammy.telecryptModules.call.callBackend.resolveHomeserverUrl
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.File
import kotlin.system.exitProcess
import de.connect2x.trixnity.client.MatrixClient
import org.koin.core.Koin
import org.koin.core.scope.Scope
import org.koin.dsl.module
import de.connect2x.trixnity.messenger.viewmodel.connecting.SSOLoginViewModel
import kotlin.reflect.KClass

@OptIn(ExperimentalCoroutinesApi::class)
object Main {
    private fun configureLogging() {
        Backend.set(DefaultBackend)
        SerializableConfig uses CoreConfigExtension
        SerializableConfig uses ConfigExtension {
            registerProvider("MESSENGER_DIR") {
                if (System.getenv("TAMMY_ROOT_PATH") == null && BuildConfig.flavor == Flavor.DEV) {
                    "./app-data"
                } else getAppPath(BuildConfig.appId).toString()
            }
        }
        checkNotNull(this::class.java.getResourceAsStream("/lognity.json")).use { stream ->
            Backend.setDefaultConfig(stream.asSource().buffered())
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        configureLogging()

        val allowMultiInstance = System.getenv("TRIXNITY_MESSENGER_MULTI_INSTANCE") == "1" ||
            !System.getenv("TRIXNITY_MESSENGER_ROOT_PATH").isNullOrBlank()
        // Check if we have a pending SSO callback from previous session
        val pendingCallbackFile = File(System.getProperty("java.io.tmpdir"), "telecrypt_sso_callback.txt")
        val pendingCallback = if (pendingCallbackFile.exists()) {
            val callback = pendingCallbackFile.readText().trim()
            pendingCallbackFile.delete()
            callback
        } else null

        // Check command line for deeplink
        val deeplinkUrl = args.firstOrNull { it.startsWith("telecrypt://") }
            ?: pendingCallback

        // Try to become the primary instance unless explicitly disabled.
        if (!allowMultiInstance) {
            if (!SingleInstanceManager.tryAcquireLock()) {
                if (deeplinkUrl != null) {
                    println("[Main] Another instance running, forwarding deeplink...")
                    SingleInstanceManager.sendDeeplinkToRunningInstance(deeplinkUrl)
                } else {
                    SingleInstanceManager.sendDeeplinkToRunningInstance("focus")
                }
                exitProcess(0)
            }
            println("[Main] Primary instance starting...")
        } else {
            println("[Main] Multi-instance mode enabled (no single-instance lock).")
        }

        // Isolate WebView data directories to prevent conflicts between instances
        val rootPath = System.getenv("TRIXNITY_MESSENGER_ROOT_PATH") ?: "./app-data"
        val webviewDataPath = File(rootPath, "webview-data").absolutePath
        System.setProperty("WEBVIEW2_USER_DATA_FOLDER", webviewDataPath)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // Start local HTTP server to receive SSO callbacks (only for primary instance).
        if (!allowMultiInstance) {
            SsoCallbackServer.start(scope)
            // Start listening for deeplinks from other instances
            SingleInstanceManager.startListening(scope)
        }

        startMultiMessenger(if (deeplinkUrl != null) arrayOf(deeplinkUrl) else args) {
            tammyConfiguration {
                // Inject Runtime Handler using Koin
                modulesFactories += {
                    module {
                        single(createdAtStart = true) {
                            SsoRuntimeHandler(getKoin(), SingleInstanceManager.deeplinkFlow, scope)
                        }
                    }
                }

                messengerConfiguration {
                    appUriSsoRedirect = "http://localhost:47824/sso"
                }
            }
        }

        // Cleanup on exit
        if (!allowMultiInstance) {
            SsoCallbackServer.stop()
            SingleInstanceManager.shutdown()
        }
    }
}

/**
 * runtime handler that listens for SSO callbacks and injects them into MatrixClient
 */
class SsoRuntimeHandler(
    private val koin: Koin,
    private val deeplinkFlow: SharedFlow<String>,
    private val scope: CoroutineScope
) {

    init {
        println("[SsoRuntimeHandler] Initialized. Listening for SSO tokens...")
        scope.launch {
            deeplinkFlow.collect { url ->
                if (url.contains("loginToken")) {
                    println("[SsoRuntimeHandler] Received SSO callback: $url")
                    handleSsoCallback(url)
                } else if (url.startsWith("telecrypt://call")) {
                    println("[SsoRuntimeHandler] Received call deeplink: $url")
                    handleCallDeepLink(url)
                }
            }
        }
    }

    private suspend fun handleSsoCallback(url: String) {
        try {
            val params = url.substringAfter("?").split("&").associate {
                val parts = it.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else parts[0] to ""
            }
            val state = decodeParam(params["state"] ?: "")
            val loginToken = decodeParam(params["loginToken"] ?: "")

            if (loginToken.isNotEmpty()) {
                println("[SsoRuntimeHandler] Found login token: ${loginToken.take(10)}...")
                println("[SsoRuntimeHandler] INJECTION READY! Login Token: ${loginToken.take(10)}...")

                val resumeUrl = "http://localhost:47824/sso?state=$state&loginToken=$loginToken"

                val ssoViewModel = awaitInstance(SSOLoginViewModel::class)
                if (ssoViewModel != null && state.isNotEmpty()) {
                    ssoViewModel.resumeLogin(resumeUrl)
                    println("[SsoRuntimeHandler] SSO resumeLogin invoked")
                    return
                }

                println("[SsoRuntimeHandler] SSOLoginViewModel used — no direct login fallback")
            }
        } catch (e: Exception) {
            println("[SsoRuntimeHandler] Failed to process SSO: ${e.message}")
            e.printStackTrace()
        }
    }

    private suspend fun handleCallDeepLink(callUrl: String) {
        val parsed = runCatching { Url(callUrl) }.getOrNull() ?: return
        val roomId = parsed.parameters["roomId"] ?: return
        val roomName = parsed.parameters["roomName"] ?: "Call"
        val mode = parsed.parameters["mode"]?.lowercase()
        val callLauncher = awaitInstance(CallLauncher::class) ?: return
        val matrixClient = awaitInstance(MatrixClient::class)
        val session = resolveElementCallSession(matrixClient)
        if (session == null) {
            println("[SsoRuntimeHandler] Call session unavailable. Please re-login.")
            return
        }
        val displayName = session?.displayName ?: resolveDisplayName(matrixClient)
        val homeserverUrl = session?.homeserver?.ifBlank {
            resolveHomeserverUrl(matrixClient).ifBlank { "" }
        }?.ifBlank { null }
        val url = buildElementCallUrl(
            roomId,
            roomName,
            displayName,
            intent = "join_existing",
            sendNotificationType = null,
            skipLobby = true,
            homeserver = homeserverUrl,
            hideHeader = true,
            disableVideo = (mode == "audio"),
            session = session,
        )
        callLauncher.joinByUrlWithSession(url, session)
    }

    private fun resolveDisplayName(matrixClient: MatrixClient?): String {
        val displayName = matrixClient?.profile?.value?.let { profile ->
            (profile[de.connect2x.trixnity.clientserverapi.model.user.ProfileField.DisplayName.Key] as? de.connect2x.trixnity.clientserverapi.model.user.ProfileField.DisplayName)?.value
        }?.trim().orEmpty()
        return displayName.ifEmpty { matrixClient?.userId?.full ?: "TeleCrypt User" }
    }

    private fun decodeParam(value: String): String {
        return runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }

    private suspend fun <T : Any> awaitInstance(
        type: KClass<T>,
        timeoutMs: Long = 10000,
        intervalMs: Long = 200,
    ): T? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var instance = findInstance(type)
        while (instance == null && System.currentTimeMillis() < deadline) {
            delay(intervalMs)
            instance = findInstance(type)
        }
        if (instance == null) {
            println("[SsoRuntimeHandler] ${type.simpleName} not ready after ${timeoutMs}ms")
        }
        return instance
    }

    private fun <T : Any> findInstance(type: KClass<T>): T? {
        val rootInstance = koin.getOrNull<T>(type, null, null)
        if (rootInstance != null) return rootInstance
        return findInScopes(type)
    }

    private fun <T : Any> findInScopes(type: KClass<T>): T? {
        val scopes = getAllScopes()
        for (scope in scopes) {
            val instance: T? = try {
                scope.get(type, null, null)
            } catch (_: Exception) {
                null
            }
            if (instance != null) return instance
        }
        return null
    }

    private fun getAllScopes(): List<Scope> {
        val scopeRegistry = runCatching {
            koin.javaClass.methods.firstOrNull { it.name == "getScopeRegistry" }?.invoke(koin)
        }.getOrNull() ?: runCatching {
            val field = koin.javaClass.getDeclaredField("scopeRegistry")
            field.isAccessible = true
            field.get(koin)
        }.getOrNull() ?: return emptyList()

        val scopes = runCatching {
            scopeRegistry.javaClass.methods.firstOrNull { it.name == "getAllScopes" }?.invoke(scopeRegistry)
        }.getOrNull() as? Collection<*>

        val list = scopes?.mapNotNull { it as? Scope }?.toMutableList() ?: mutableListOf()
        val rootScope = runCatching {
            scopeRegistry.javaClass.methods.firstOrNull { it.name == "getRootScope" }?.invoke(scopeRegistry) as? Scope
        }.getOrNull()
        if (rootScope != null && list.none { it.id == rootScope.id }) {
            list.add(0, rootScope)
        }
        return list
    }
}

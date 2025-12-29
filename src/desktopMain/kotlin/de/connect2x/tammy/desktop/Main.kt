package de.connect2x.tammy.desktop

import de.connect2x.messenger.desktop.startMessenger
import de.connect2x.tammy.tammyConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.system.exitProcess
import org.koin.dsl.module
import net.folivo.trixnity.client.MatrixClient
import net.folivo.trixnity.clientserverapi.model.authentication.LoginType
import de.connect2x.trixnity.messenger.MatrixClients
import de.connect2x.trixnity.messenger.MatrixMessengerSettingsBase
import de.connect2x.trixnity.messenger.MatrixMessengerSettingsHolder
import de.connect2x.trixnity.messenger.i18n.I18n
import de.connect2x.trixnity.messenger.settings.update
import de.connect2x.trixnity.messenger.util.GetDefaultDeviceDisplayName
import de.connect2x.trixnity.messenger.util.UrlHandler
import de.connect2x.trixnity.messenger.viewmodel.connecting.AddMatrixAccountState
import de.connect2x.trixnity.messenger.viewmodel.connecting.loginCatching
import de.connect2x.trixnity.messenger.viewmodel.connecting.SSOLoginViewModel
import de.connect2x.trixnity.messenger.viewmodel.connecting.SSOState
import io.ktor.http.Url
import org.koin.core.Koin
import kotlin.reflect.KClass
import java.net.URLDecoder
import java.net.URLEncoder
import org.koin.core.scope.Scope
import de.connect2x.tammy.telecryptModules.call.callBackend.CallLauncher
import de.connect2x.tammy.telecryptModules.call.callBackend.buildElementCallUrl
import de.connect2x.tammy.telecryptModules.call.callBackend.resolveElementCallSession
import de.connect2x.tammy.telecryptModules.call.callBackend.resolveHomeserverUrl

/**
 * Main entry point for TeleCrypt Desktop.
 * Provides Runtime SSO Injection to handle state loss on restart.
 */
fun main(args: Array<String>) {
    // Check if we have a pending SSO callback from previous session
    val pendingCallbackFile = File(System.getProperty("java.io.tmpdir"), "telecrypt_sso_callback.txt")
    val pendingCallback = if (pendingCallbackFile.exists()) {
        val callback = pendingCallbackFile.readText().trim()
        pendingCallbackFile.delete()
        callback
    } else null
    
    // Check command line for deeplink
    val deeplinkUrl = args.firstOrNull { it.startsWith("com.zendev.telecrypt://") }
        ?: pendingCallback
    
    // Try to become the primary instance
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
    
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // Start local HTTP server to receive SSO callbacks
    SsoCallbackServer.start(scope)
    
    // Start listening for deeplinks from other instances
    SingleInstanceManager.startListening(scope)
    
    startMessenger(
        configuration = tammyConfiguration {
            urlProtocol = "http"
            urlHost = "localhost:47824"
            
            // Inject Runtime Handler using Koin
            modulesFactories += {
                module {
                    // Eagerly create SsoRuntimeHandler - remove valid dependency from constructor!
                    single(createdAtStart = true) {
                        SsoRuntimeHandler(getKoin(), SingleInstanceManager.deeplinkFlow, scope)
                    }
                }
            }
            
            messengerConfiguration {
                ssoRedirectPath = "sso"
            }
        },
        args = if (deeplinkUrl != null) arrayOf(deeplinkUrl) else args,
    )
    
    // Cleanup on exit
    SsoCallbackServer.stop()
    SingleInstanceManager.shutdown()
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
                } else if (url.startsWith("com.zendev.telecrypt://call")) {
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

                val encodedState = URLEncoder.encode(state, "UTF-8")
                val encodedToken = URLEncoder.encode(loginToken, "UTF-8")
                val resumeUrl = Url("http://localhost:47824/sso?state=$encodedState&loginToken=$encodedToken")

                if (emitUrlToHandler(resumeUrl)) {
                    println("[SsoRuntimeHandler] UrlHandler accepted SSO resume URL")
                    return
                }

                val ssoViewModel = awaitInstance(SSOLoginViewModel::class)
                if (ssoViewModel != null && state.isNotEmpty()) {
                    ssoViewModel.resumeLogin(resumeUrl)
                    println("[SsoRuntimeHandler] SSO resumeLogin invoked")
                    return
                }

                if (resumeViaSettings(state, loginToken)) {
                    println("[SsoRuntimeHandler] SSO resumed via settings fallback")
                    return
                }
                
                try {
                    val client = awaitInstance(MatrixClient::class)
                    if (client != null) {
                         println("[SsoRuntimeHandler] MatrixClient available. Attempting login...")
                         client.api.authentication.login(
                             type = LoginType.Token(), 
                             token = loginToken
                         )
                         println("[SsoRuntimeHandler] Login command sent successfully!")
                    } else {
                         println("[SsoRuntimeHandler] MatrixClient is NULL (not found in Koin)")
                    }
                } catch (e: Exception) {
                    println("[SsoRuntimeHandler] Login API call failed: ${e.message}")
                    e.printStackTrace() 
                }
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
        val homeserverUrl = session?.homeserver?.ifBlank { null }
            ?: resolveHomeserverUrl(matrixClient).ifBlank { null }
        val url = buildElementCallUrl(
            roomId,
            roomName,
            displayName,
            intent = "join_existing",
            sendNotificationType = null,
            skipLobby = true,
            homeserver = homeserverUrl,
            callMode = mode,
        )
        callLauncher.joinByUrlWithSession(url, session)
    }

    private fun resolveDisplayName(matrixClient: MatrixClient?): String {
        val displayName = matrixClient?.displayName?.value?.trim().orEmpty()
        return displayName.ifEmpty { matrixClient?.userId?.full ?: "TeleCrypt User" }
    }

    private suspend fun resumeViaSettings(state: String, loginToken: String): Boolean {
        if (state.isEmpty()) {
            return false
        }
        val match = findSsoStateMatch(state) ?: return false
        val settingsHolder = match.settingsHolder
        val ssoState = match.ssoState

        val matrixClients = match.matrixClients ?: awaitInstance(MatrixClients::class) ?: return false
        val i18n = awaitInstance(I18n::class) ?: return false
        val deviceName = awaitInstance(GetDefaultDeviceDisplayName::class)?.invoke()
            ?: "TeleCrypt Desktop"
        val addState = MutableStateFlow<AddMatrixAccountState>(AddMatrixAccountState.Connecting)

        return runCatching {
            matrixClients.loginCatching(
                ssoState.serverUrl,
                loginToken,
                deviceName,
                addState,
                i18n,
            ) {}
            settingsHolder.update(MatrixMessengerSettingsBase.serializer()) { current ->
                current.copy(ssoState = null)
            }
            true
        }.onFailure {
            println("[SsoRuntimeHandler] SSO fallback login failed: ${it.message}")
        }.getOrDefault(false)
    }

    private fun decodeParam(value: String): String {
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }

    private data class SsoStateMatch(
        val settingsHolder: MatrixMessengerSettingsHolder,
        val ssoState: SSOState,
        val matrixClients: MatrixClients?,
    )

    private suspend fun findSsoStateMatch(state: String): SsoStateMatch? {
        val scopes = getAllScopes()
        for (scope in scopes) {
            val match = findSsoStateMatchInScope(scope, state)
            if (match != null) return match
        }

        val rootHolder = runCatching { koin.getOrNull<MatrixMessengerSettingsHolder>() }.getOrNull()
        if (rootHolder != null) {
            rootHolder.waitForInit()
            val ssoState = rootHolder.value.base.ssoState
            if (ssoState != null && ssoState.state == state) {
                val clients = runCatching { koin.getOrNull<MatrixClients>() }.getOrNull()
                return SsoStateMatch(rootHolder, ssoState, clients)
            }
        }

        println("[SsoRuntimeHandler] No stored SSO state found for state=$state")
        return null
    }

    private suspend fun findSsoStateMatchInScope(scope: Scope, state: String): SsoStateMatch? {
        val settingsHolder = runCatching { scope.get<MatrixMessengerSettingsHolder>(null, null) }.getOrNull()
            ?: return null
        settingsHolder.waitForInit()
        val ssoState = settingsHolder.value.base.ssoState ?: return null
        if (ssoState.state != state) {
            return null
        }
        val matrixClients = runCatching { scope.get<MatrixClients>(null, null) }.getOrNull()
        return SsoStateMatch(settingsHolder, ssoState, matrixClients)
    }

    private fun emitUrlToHandler(url: Url): Boolean {
        val handler = findInstance(UrlHandler::class) ?: return false
        val flow = findUrlHandlerFlow(handler) ?: return false
        return runCatching { flow.tryEmit(url) }.getOrDefault(false)
    }

    private fun findUrlHandlerFlow(handler: UrlHandler): MutableSharedFlow<Url>? {
        var clazz: Class<*>? = handler.javaClass
        while (clazz != null) {
            val method = clazz.declaredMethods.firstOrNull { it.name == "getUrlHandlerFlow" }
            if (method != null) {
                method.isAccessible = true
                val flow = runCatching { method.invoke(handler) }.getOrNull()
                return flow as? MutableSharedFlow<Url>
            }
            clazz = clazz.superclass
        }
        return null
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

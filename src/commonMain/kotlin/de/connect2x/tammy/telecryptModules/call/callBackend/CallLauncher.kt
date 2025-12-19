package de.connect2x.tammy.telecryptModules.call.callBackend

import net.folivo.trixnity.client.MatrixClient
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Interface for launching Element Call from Tammy
 */
interface CallLauncher {
    /**
     * Launch Element Call for a specific room
     */
    fun launchCall(roomId: String)

    fun joinByUrl(url: String)

    /**
     * Check if Element Call is available for the given room
     * @param roomId The Matrix room ID to check
     * @return true if Element Call can be launched for this room
     */
    fun isCallAvailable(roomId: String): Boolean
}

class ElementCallLauncherImpl(
    private val urlLauncher: UrlLauncher
) : CallLauncher, KoinComponent {

    private val matrixClient: MatrixClient by inject()

    override fun launchCall(roomId: String) {
        // 1. Calculate url for current call
        // Using Element Call default instance for now: https://call.element.io
        // Ideally we should use a custom instance or configured one
        val url = "https://call.element.io/$roomId"

        // 2. Open URL
        joinByUrl(url)

        // 3. TODO: Send Matrix Event (m.call.invite or similar)
        // launching this in background to not block UI
        // GlobalScope.launch { matrixClient.room.sendMessage(...) }
    }

    override fun joinByUrl(url: String) {
        urlLauncher.openUrl(url)
    }

    override fun isCallAvailable(roomId: String): Boolean {
        return true
    }
}

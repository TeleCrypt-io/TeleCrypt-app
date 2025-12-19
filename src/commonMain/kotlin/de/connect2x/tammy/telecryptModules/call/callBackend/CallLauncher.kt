package de.connect2x.tammy.telecryptModules.call.callBackend

import net.folivo.trixnity.client.MatrixClient
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Interface for launching Element Call from Tammy
 */
interface CallLauncher {
    /**
     * Launch Element Call
     */
    fun launchCall()

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

    override fun launchCall() {
        // TODO: Get current room ID from context/navigation
        // For now, opening Element Call home page
        val url = "https://call.element.io/"

        // Open URL
        joinByUrl(url)

        // TODO: Send Matrix Event (m.call.invite or similar)
        // This requires access to current room context
    }

    override fun joinByUrl(url: String) {
        urlLauncher.openUrl(url)
    }

    override fun isCallAvailable(roomId: String): Boolean {
        return true
    }
}


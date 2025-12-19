package de.connect2x.tammy.telecryptModules.call.callBackend

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

/**
 * Platform-specific implementation of CallLauncher
 */
expect class ElementCallLauncherImpl() : CallLauncher

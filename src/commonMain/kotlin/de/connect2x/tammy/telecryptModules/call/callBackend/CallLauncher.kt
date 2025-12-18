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

expect class ElementCallLauncherImpl() : CallLauncher {
    override fun launchCall()
        // TODO: Implement Element Call launch logic
        // This should:
        // 1. Get the current Matrix client
        // 2. Prepare Element Call configuration (room ID, user credentials, etc.)
        // 3. Calculate url for current call
        // 4. Use joinByUrl to reach web interface

        // NOTE: Probably implementations of this method will be identical on all platforms
        // Consider moving this method to another not platform-specific entity

    override fun joinByUrl(url: String)
        //TODO: Call web implementations on all platforms
        // This is pure frontend

    override fun isCallAvailable(roomId: String): Boolean
        // TODO: Implement availability check
        // This should check if:
        // 1. The room supports Element Call
        // 2. The user has necessary permissions
        // 3. Element Call is properly configured
}
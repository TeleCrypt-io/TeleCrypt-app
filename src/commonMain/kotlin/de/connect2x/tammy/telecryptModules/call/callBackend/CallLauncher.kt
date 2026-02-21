package de.connect2x.tammy.telecryptModules.call.callBackend

/**
 * Interface for launching Element Call from Tammy
 */
interface CallLauncher {
    /**
     * Launch Element Call for a specific room
     * @param roomId The Matrix room ID (e.g., "!roomid:homeserver.org")
     * @param roomName Display name of the room
     * @param displayName Display name of the current user
     * @return The URL used to open the call
     */
    fun launchCall(roomId: String, roomName: String, displayName: String): String

    /**
     * Join a call by URL directly
     */
    fun joinByUrl(url: String)

    /**
     * Join a call using a Matrix session (avoids guest registration prompts)
     */
    fun joinByUrlWithSession(url: String, session: ElementCallSession?) {
        joinByUrl(url)
    }

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

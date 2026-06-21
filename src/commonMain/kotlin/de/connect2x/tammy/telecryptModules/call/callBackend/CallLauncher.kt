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
     * Open a widget‑bridge host page in a browser. The page is served by
     * an embedded HTTP server (WidgetBridgeServer) and embeds Element Call
     * in an `<iframe>`. EC inside the iframe gets Matrix credentials over
     * `postMessage` from the host, so no localStorage/CDP injection is needed.
     */
    fun joinByWidgetUrl(hostUrl: String) {
        joinByUrl(hostUrl)
    }

    /**
     * Tear down the current call browser/window, if any. Used on leave so the
     * embedded Element Call stops and can no longer re-publish our membership.
     * Default no-op for platforms that manage their call surface differently.
     */
    fun hangUp() {}

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

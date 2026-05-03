package de.connect2x.tammy.telecryptModules.call.callBackend

private const val ELEMENT_CALL_BASE_URL = "https://call.element.io/room/#/"

/**
 * Builds the Element Call URL with all necessary query parameters.
 *
 * Key parameters for proper call flow:
 * - skipLobby=true: bypasses the Element Call lobby screen for seamless UX
 * - hideHeader=true: hides the Element Call header bar for cleaner embedded experience
 * - confineToRoom=true: prevents navigation away from the call room
 * - appPrompt=false: prevents "open in app" prompt
 * - homeserver: MUST be set so Element Call knows which Matrix server to connect to
 *
 * NOTE: Session credentials (userId, deviceId, accessToken) are NOT passed in the URL.
 * Element Call reads credentials exclusively from localStorage["matrix-auth-store"].
 * On Android, the WebView injects JS to set this key before the page loads.
 * On Desktop, WebviewKo's init() script does the same.
 * The [session] parameter is only used here to resolve the homeserver URL.
 */
internal fun buildElementCallUrl(
    roomId: String,
    roomName: String,
    displayName: String,
    intent: String = "start_call",
    sendNotificationType: String? = "ring",
    skipLobby: Boolean? = true,
    waitForCallPickup: Boolean? = null,
    homeserver: String? = null,
    hideScreensharing: Boolean? = true,
    hideHeader: Boolean? = true,
    autoLeave: Boolean? = null,
    disableAudio: Boolean? = null,
    disableVideo: Boolean? = null,
    perParticipantE2EE: Boolean? = true,
    session: ElementCallSession? = null,
): String {
    val alias = roomName.trim().ifEmpty { "call" }
    val encodedAlias = encodeComponent(alias)
    val encodedRoomId = encodeComponent(roomId)
    val encodedDisplayName = encodeComponent(displayName)
    val encodedIntent = encodeComponent(intent)

    val roomIdParam = if (isMatrixRoomId(roomId)) "roomId=$encodedRoomId&" else ""
    val viaServersParam = buildViaServersParam(roomId)
    val resolvedHomeserver = homeserver
        ?.takeIf { it.isNotBlank() }
        ?: session?.homeserver?.takeIf { it.isNotBlank() }
        ?: deriveHomeserverFromRoomId(roomId)
    val homeserverParam = resolvedHomeserver?.let { "homeserver=${encodeComponent(it)}&" } ?: ""
    val notificationParam = sendNotificationType?.let { "sendNotificationType=${encodeComponent(it)}&" } ?: ""
    val skipLobbyParam = skipLobby?.let { "skipLobby=${encodeComponent(it.toString())}&" } ?: ""
    val waitForPickupParam = waitForCallPickup?.let {
        "waitForCallPickup=${encodeComponent(it.toString())}&"
    } ?: ""
    val hideScreenshareParam = hideScreensharing?.let { "hideScreensharing=${encodeComponent(it.toString())}&" } ?: ""
    val hideHeaderParam = hideHeader?.let { "hideHeader=${encodeComponent(it.toString())}&" } ?: ""
    val autoLeaveParam = autoLeave?.let { "autoLeave=${encodeComponent(it.toString())}&" } ?: ""

    val disableAudioParam = disableAudio?.let { "disableAudio=${it}&" } ?: ""
    val disableVideoParam = disableVideo?.let { "disableVideo=${it}&" } ?: ""

    // Per-participant E2EE is ENABLED by default. In widget mode (the proper
    // architecture, see docs/CALLS_E2EE_PLAN.md), Element Call routes all Olm
    // crypto through the host (TeleCrypt) via Widget API, so SFrame keys are
    // distributed using the same vodozemac account that TeleCrypt uses — no
    // split-brain, no MissingKey, video works in both directions.
    val e2eeParam = perParticipantE2EE?.let { "perParticipantE2EE=${it}&" } ?: ""

    // NOTE: Session credentials are NOT passed in the URL — Element Call ignores them.
    // Authentication is handled via localStorage["matrix-auth-store"] injection
    // (WebView JS injection on Android, WebviewKo init script on Desktop).

    return "$ELEMENT_CALL_BASE_URL$encodedAlias?" +
        "${roomIdParam}${viaServersParam}${homeserverParam}displayName=$encodedDisplayName&confineToRoom=true&appPrompt=false&" +
        "${notificationParam}${skipLobbyParam}${waitForPickupParam}${hideScreenshareParam}${hideHeaderParam}${autoLeaveParam}" +
        "${disableAudioParam}${disableVideoParam}${e2eeParam}intent=$encodedIntent"
}

private fun isMatrixRoomId(roomId: String): Boolean {
    return roomId.startsWith("!") && roomId.contains(":")
}

private fun buildViaServersParam(roomId: String): String {
    if (!isMatrixRoomId(roomId)) {
        return ""
    }
    val server = roomId.substringAfter(":", "")
    if (server.isBlank()) {
        return ""
    }
    return "viaServers=${encodeComponent(server)}&"
}

private fun deriveHomeserverFromRoomId(roomId: String): String? {
    if (!isMatrixRoomId(roomId)) {
        return null
    }
    val server = roomId.substringAfter(":", "").trim()
    if (server.isBlank()) {
        return null
    }
    return "https://$server"
}

private fun encodeComponent(value: String): String {
    val bytes = value.encodeToByteArray()
    val out = StringBuilder(bytes.size * 3)
    for (byte in bytes) {
        val b = byte.toInt() and 0xFF
        val ch = b.toChar()
        if (isUnreserved(ch)) {
            out.append(ch)
        } else {
            out.append('%')
            out.append(HEX[b ushr 4])
            out.append(HEX[b and 0x0F])
        }
    }
    return out.toString()
}

private fun isUnreserved(ch: Char): Boolean {
    return (ch in 'A'..'Z') ||
        (ch in 'a'..'z') ||
        (ch in '0'..'9') ||
        ch == '-' || ch == '.' || ch == '_' || ch == '~'
}

private val HEX = charArrayOf(
    '0', '1', '2', '3', '4', '5', '6', '7',
    '8', '9', 'A', 'B', 'C', 'D', 'E', 'F',
)

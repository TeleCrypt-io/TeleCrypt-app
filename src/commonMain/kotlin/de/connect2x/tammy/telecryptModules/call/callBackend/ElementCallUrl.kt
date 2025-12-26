package de.connect2x.tammy.telecryptModules.call.callBackend

private const val ELEMENT_CALL_BASE_URL = "https://call.element.io/room/#/"

internal fun buildElementCallUrl(
    roomId: String,
    roomName: String,
    displayName: String,
    intent: String = "start_call",
    sendNotificationType: String? = "ring",
): String {
    val alias = roomName.trim().ifEmpty { "call" }
    val encodedAlias = encodeComponent(alias)
    val encodedRoomId = encodeComponent(roomId)
    val encodedDisplayName = encodeComponent(displayName)
    val encodedIntent = encodeComponent(intent)

    val roomIdParam = if (isMatrixRoomId(roomId)) "roomId=$encodedRoomId&" else ""
    val viaServersParam = buildViaServersParam(roomId)
    val homeserverParam = buildHomeserverParam(roomId)
    val notificationParam = sendNotificationType?.let { "sendNotificationType=${encodeComponent(it)}&" } ?: ""
    return "$ELEMENT_CALL_BASE_URL$encodedAlias?" +
        "${roomIdParam}${viaServersParam}${homeserverParam}displayName=$encodedDisplayName&confineToRoom=true&appPrompt=false&" +
        "${notificationParam}intent=$encodedIntent"
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

private fun buildHomeserverParam(roomId: String): String {
    if (!isMatrixRoomId(roomId)) {
        return ""
    }
    val server = roomId.substringAfter(":", "")
    if (server.isBlank()) {
        return ""
    }
    return "homeserver=${encodeComponent("https://$server")}&"
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

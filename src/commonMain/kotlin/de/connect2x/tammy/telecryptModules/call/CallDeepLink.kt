package de.connect2x.tammy.telecryptModules.call

fun buildTelecryptCallDeepLink(roomId: String, roomName: String, mode: CallMode): String {
    val encodedRoomId = encodeQueryComponent(roomId)
    val encodedRoomName = encodeQueryComponent(roomName)
    val encodedMode = encodeQueryComponent(mode.name.lowercase())
    return "com.zendev.telecrypt://call?roomId=$encodedRoomId&roomName=$encodedRoomName&mode=$encodedMode"
}

private fun encodeQueryComponent(value: String): String {
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

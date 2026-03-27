package de.connect2x.tammy.telecryptModules.call.callRtc

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MatrixRtcTransportsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesStableTransportsKey() {
        val body =
            """
            {
              "transports": [
                {
                  "type": "livekit",
                  "uri": "wss://sfu.example.org",
                  "params": { "jwt": "token" }
                }
              ]
            }
            """.trimIndent()

        val transports = parseRtcTransportsResponse(body, json)

        assertEquals(1, transports.size)
        assertEquals("livekit", transports.first().type)
        assertEquals("wss://sfu.example.org", transports.first().uri)
        assertEquals("token", transports.first().params["jwt"]?.toString()?.trim('"'))
    }

    @Test
    fun parsesUnstableRtcTransportsKey() {
        val body =
            """
            {
              "rtc_transports": [
                {
                  "type": "turn",
                  "uri": "turn:turn.example.org:3478",
                  "params": { "username": "u", "password": "p" }
                }
              ]
            }
            """.trimIndent()

        val transports = parseRtcTransportsResponse(body, json)

        assertEquals(1, transports.size)
        assertEquals("turn", transports.first().type)
        assertEquals("turn:turn.example.org:3478", transports.first().uri)
        assertEquals("u", transports.first().params["username"]?.toString()?.trim('"'))
    }

    @Test
    fun ignoresInvalidTransportItems() {
        val body =
            """
            {
              "transports": [
                { "uri": "wss://missing-type.example.org" },
                { "type": "livekit", "uri": "wss://ok.example.org" }
              ]
            }
            """.trimIndent()

        val transports = parseRtcTransportsResponse(body, json)

        assertEquals(1, transports.size)
        assertEquals("livekit", transports.first().type)
        assertEquals("wss://ok.example.org", transports.first().uri)
    }

    @Test
    fun returnsEmptyWhenNoTransportKeysPresent() {
        val body = """{ "foo": [] }"""

        val transports = parseRtcTransportsResponse(body, json)

        assertTrue(transports.isEmpty())
    }
}

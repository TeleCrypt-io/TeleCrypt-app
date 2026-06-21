package de.connect2x.tammy.telecryptModules.call.widgetBridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebRtcStatsParserTest {

    private fun stats(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

    @Test
    fun extractsRttJitterLossFpsAndResolution() {
        val q = WebRtcStatsParser.parse(
            stats(
                """
                [
                  {"type":"candidate-pair","nominated":true,"currentRoundTripTime":0.042},
                  {"type":"inbound-rtp","kind":"video","jitter":0.005,"packetsReceived":980,
                   "packetsLost":20,"bytesReceived":1500000,"framesPerSecond":29.5,
                   "frameWidth":1280,"frameHeight":720},
                  {"type":"outbound-rtp","bytesSent":800000}
                ]
                """.trimIndent(),
            ),
        )
        assertEquals(42.0, q.rttMs)
        assertEquals(5.0, q.jitterMs)
        assertEquals(980, q.packetsReceived)
        assertEquals(20, q.packetsLost)
        assertEquals(2.0, q.packetLossPct) // 20 / 1000
        assertEquals(29.5, q.framesPerSecond)
        assertEquals("1280x720", q.resolution)
        assertEquals(1500000, q.inboundBytes)
        assertEquals(800000, q.outboundBytes)
    }

    @Test
    fun aggregatesMultipleInboundStreams() {
        val q = WebRtcStatsParser.parse(
            stats(
                """
                [
                  {"type":"inbound-rtp","jitter":0.002,"packetsReceived":100,"packetsLost":5},
                  {"type":"inbound-rtp","jitter":0.009,"packetsReceived":200,"packetsLost":0}
                ]
                """.trimIndent(),
            ),
        )
        assertEquals(300, q.packetsReceived)
        assertEquals(5, q.packetsLost)
        // jitter is the worst (max) across streams
        assertEquals(9.0, q.jitterMs)
    }

    @Test
    fun fallsBackToRemoteInboundRttWhenNoCandidatePair() {
        val q = WebRtcStatsParser.parse(
            stats("""[{"type":"remote-inbound-rtp","roundTripTime":0.1}]"""),
        )
        assertEquals(100.0, q.rttMs)
    }

    @Test
    fun emptyReportYieldsZeroedSnapshot() {
        val q = WebRtcStatsParser.parse(stats("[]"))
        assertNull(q.rttMs)
        assertNull(q.resolution)
        assertEquals(0.0, q.packetLossPct)
        assertTrue(q.packetsReceived == 0L && q.packetsLost == 0L)
    }
}

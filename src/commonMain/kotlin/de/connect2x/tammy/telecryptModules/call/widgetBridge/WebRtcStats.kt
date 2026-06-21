package de.connect2x.tammy.telecryptModules.call.widgetBridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A compact connection-quality snapshot extracted from a WebRTC `getStats()`
 * report. Cumulative counters (bytes, packets) are point-in-time; bitrate and
 * loss-rate trends are derived downstream from a series of snapshots.
 */
data class WebRtcQuality(
    val rttMs: Double? = null,
    val jitterMs: Double? = null,
    val packetsReceived: Long = 0,
    val packetsLost: Long = 0,
    val framesPerSecond: Double? = null,
    val frameWidth: Long? = null,
    val frameHeight: Long? = null,
    val inboundBytes: Long = 0,
    val outboundBytes: Long = 0,
) {
    /** Cumulative packet-loss percentage over the connection's lifetime. */
    val packetLossPct: Double
        get() {
            val total = packetsReceived + packetsLost
            return if (total > 0) 100.0 * packetsLost / total else 0.0
        }

    val resolution: String?
        get() = if (frameWidth != null && frameHeight != null) "${frameWidth}x${frameHeight}" else null
}

/**
 * Parses a serialized `RTCStatsReport` (the array of stat objects produced by
 * `Array.from(report.values())`) into a [WebRtcQuality] snapshot.
 *
 * Pure and platform-independent, so it is unit-tested directly. The browser-side
 * collector (injected into the Element Call frame via CDP) ships these reports
 * to the bridge over the widget WebSocket.
 */
object WebRtcStatsParser {

    fun parse(stats: JsonArray): WebRtcQuality {
        var rttMs: Double? = null
        var jitterMs: Double? = null
        var packetsReceived = 0L
        var packetsLost = 0L
        var fps: Double? = null
        var width: Long? = null
        var height: Long? = null
        var inboundBytes = 0L
        var outboundBytes = 0L

        for (element in stats) {
            val obj = element as? JsonObject ?: continue
            when (obj.str("type")) {
                "candidate-pair" -> {
                    // Use the nominated/selected pair's RTT.
                    val nominated = obj.bool("nominated") == true || obj.bool("selected") == true
                    val rtt = obj.num("currentRoundTripTime")
                    if (nominated && rtt != null) rttMs = rtt * 1000.0
                    else if (rttMs == null && rtt != null) rttMs = rtt * 1000.0
                }

                "inbound-rtp" -> {
                    obj.num("jitter")?.let { j -> jitterMs = maxOf(jitterMs ?: 0.0, j * 1000.0) }
                    packetsReceived += obj.long("packetsReceived") ?: 0
                    packetsLost += obj.long("packetsLost") ?: 0
                    inboundBytes += obj.long("bytesReceived") ?: 0
                    obj.num("framesPerSecond")?.let { fps = it }
                    obj.long("frameWidth")?.let { width = it }
                    obj.long("frameHeight")?.let { height = it }
                }

                "outbound-rtp" -> {
                    outboundBytes += obj.long("bytesSent") ?: 0
                }

                "remote-inbound-rtp" -> {
                    // Sender-side RTT estimate; only use it if no candidate-pair RTT.
                    if (rttMs == null) obj.num("roundTripTime")?.let { rttMs = it * 1000.0 }
                }
            }
        }

        return WebRtcQuality(
            rttMs = rttMs,
            jitterMs = jitterMs,
            packetsReceived = packetsReceived,
            packetsLost = packetsLost,
            framesPerSecond = fps,
            frameWidth = width,
            frameHeight = height,
            inboundBytes = inboundBytes,
            outboundBytes = outboundBytes,
        )
    }

    private fun JsonObject.prim(key: String): JsonPrimitive? = this[key] as? JsonPrimitive
    private fun JsonObject.str(key: String): String? = prim(key)?.content
    private fun JsonObject.num(key: String): Double? = prim(key)?.content?.toDoubleOrNull()
    private fun JsonObject.long(key: String): Long? = prim(key)?.content?.toDoubleOrNull()?.toLong()
    private fun JsonObject.bool(key: String): Boolean? = when (prim(key)?.content) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

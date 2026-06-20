package de.connect2x.tammy.telecryptModules.call.widgetBridge

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/** Phases of a call, timestamped once each (ms since bridge start). */
enum class CallPhase {
    BRIDGE_START,
    PRELOAD_DONE,
    JOIN_SENT,
    FIRST_KEY_SENT,
    FIRST_SELF_ECHO,
    FIRST_REMOTE_MEMBER,
    FIRST_KEY_RECEIVED,
    CALL_END,
}

/** Per-call counters for Widget API traffic, key exchange and reliability. */
enum class CallCounter {
    READ_EVENTS,
    SEND_EVENT,
    SEND_STATE_EVENT,
    SEND_TO_DEVICE,
    DELAYED_SCHEDULED,
    DELAYED_COMMITTED,
    SELF_ECHO,
    KEY_SENT,
    KEY_RECEIVED,
    SYNC_RESTART,
}

/**
 * Collects timing and traffic metrics for a single call so the bridge can print
 * a summary when the call ends. Best-effort and lock-free: counters use plain
 * array writes (a lost increment under contention is acceptable for a metric),
 * phases are recorded once. Times are monotonic ms since the bridge started.
 */
class CallMetrics {
    private val start = TimeSource.Monotonic.markNow()
    private val phaseAt = arrayOfNulls<Long>(CallPhase.entries.size)
    private val counters = LongArray(CallCounter.entries.size)

    @Volatile
    private var lastQuality: WebRtcQuality? = null
    private var qualitySamples = 0L

    init {
        mark(CallPhase.BRIDGE_START)
    }

    /** Records the latest WebRTC connection-quality snapshot (from the EC frame). */
    fun recordQuality(quality: WebRtcQuality) {
        lastQuality = quality
        qualitySamples++
    }

    fun mark(phase: CallPhase) {
        val i = phase.ordinal
        if (phaseAt[i] == null) phaseAt[i] = start.elapsedNow().inWholeMilliseconds
    }

    fun inc(counter: CallCounter) {
        counters[counter.ordinal]++
    }

    fun count(counter: CallCounter): Long = counters[counter.ordinal]

    fun phaseMs(phase: CallPhase): Long? = phaseAt[phase.ordinal]

    /**
     * One-line JSON record of this call (JSON Lines format) for offline
     * aggregation across many calls. Phases are ms-since-bridge-start or null.
     */
    fun toJsonLine(): String = buildJsonObject {
        put("schema", JsonPrimitive("telecrypt.call.metrics/v1"))
        putJsonObject("latency_ms") {
            for (p in CallPhase.entries) {
                put(p.name.lowercase(), phaseAt[p.ordinal]?.let { JsonPrimitive(it) } ?: JsonNull)
            }
        }
        putJsonObject("counters") {
            for (c in CallCounter.entries) put(c.name.lowercase(), JsonPrimitive(counters[c.ordinal]))
        }
        lastQuality?.let { q ->
            putJsonObject("quality") {
                put("samples", JsonPrimitive(qualitySamples))
                put("rtt_ms", q.rttMs?.let { JsonPrimitive(it) } ?: JsonNull)
                put("jitter_ms", q.jitterMs?.let { JsonPrimitive(it) } ?: JsonNull)
                put("packet_loss_pct", JsonPrimitive(q.packetLossPct))
                put("fps", q.framesPerSecond?.let { JsonPrimitive(it) } ?: JsonNull)
                put("resolution", q.resolution?.let { JsonPrimitive(it) } ?: JsonNull)
                put("inbound_bytes", JsonPrimitive(q.inboundBytes))
                put("outbound_bytes", JsonPrimitive(q.outboundBytes))
            }
        }
    }.toString()

    /** Render the metrics as aligned lines suitable for a log block / slide. */
    fun summaryLines(): List<String> {
        fun ms(p: CallPhase) = phaseAt[p.ordinal]?.let { "${it} ms" } ?: "—"
        val lines = mutableListOf<String>()
        lines += "===== Call metrics ====="
        lines += "Latency (ms since bridge start):"
        lines += "  preload done          : ${ms(CallPhase.PRELOAD_DONE)}"
        lines += "  join state sent        : ${ms(CallPhase.JOIN_SENT)}"
        lines += "  first self-echo        : ${ms(CallPhase.FIRST_SELF_ECHO)}"
        lines += "  first remote member    : ${ms(CallPhase.FIRST_REMOTE_MEMBER)}"
        lines += "  first key sent (E2EE)  : ${ms(CallPhase.FIRST_KEY_SENT)}"
        lines += "  first key received     : ${ms(CallPhase.FIRST_KEY_RECEIVED)}"
        lines += "  call end               : ${ms(CallPhase.CALL_END)}"
        lines += "E2EE keys: sent=${count(CallCounter.KEY_SENT)} received=${count(CallCounter.KEY_RECEIVED)}"
        lines += "Reliability: sync restarts=${count(CallCounter.SYNC_RESTART)} self-echoes=${count(CallCounter.SELF_ECHO)}"
        lastQuality?.let { q ->
            lines += "WebRTC (last of $qualitySamples): rtt=${q.rttMs?.let { "${it}ms" } ?: "—"} " +
                "jitter=${q.jitterMs?.let { "${it}ms" } ?: "—"} loss=${q.packetLossPct}% " +
                "fps=${q.framesPerSecond ?: "—"} res=${q.resolution ?: "—"}"
        }
        lines += "Widget API: read_events=${count(CallCounter.READ_EVENTS)} " +
            "send_event=${count(CallCounter.SEND_EVENT)} (state=${count(CallCounter.SEND_STATE_EVENT)}) " +
            "send_to_device=${count(CallCounter.SEND_TO_DEVICE)} " +
            "delayed=${count(CallCounter.DELAYED_SCHEDULED)}/${count(CallCounter.DELAYED_COMMITTED)} (scheduled/committed)"
        lines += "========================"
        return lines
    }
}

package de.connect2x.tammy.telecryptModules.call.widgetBridge

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallMetricsTest {

    @Test
    fun bridgeStartIsMarkedOnCreation() {
        val m = CallMetrics()
        assertNotNull(m.phaseMs(CallPhase.BRIDGE_START))
        assertNull(m.phaseMs(CallPhase.CALL_END))
    }

    @Test
    fun incrementsAccumulate() {
        val m = CallMetrics()
        repeat(3) { m.inc(CallCounter.READ_EVENTS) }
        m.inc(CallCounter.KEY_SENT)
        assertEquals(3, m.count(CallCounter.READ_EVENTS))
        assertEquals(1, m.count(CallCounter.KEY_SENT))
        assertEquals(0, m.count(CallCounter.KEY_RECEIVED))
    }

    @Test
    fun phaseIsRecordedOnlyOnce() {
        val m = CallMetrics()
        m.mark(CallPhase.JOIN_SENT)
        val first = m.phaseMs(CallPhase.JOIN_SENT)
        m.mark(CallPhase.JOIN_SENT)
        assertEquals(first, m.phaseMs(CallPhase.JOIN_SENT))
    }

    @Test
    fun jsonLineHasSchemaLatencyAndCounters() {
        val m = CallMetrics()
        m.mark(CallPhase.JOIN_SENT)
        m.inc(CallCounter.KEY_SENT)
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(m.toJsonLine()).jsonObject
        assertEquals("telecrypt.call.metrics/v1", obj["schema"]!!.jsonPrimitive.content)
        assertNotNull(obj["latency_ms"]!!.jsonObject["join_sent"])
        assertEquals("1", obj["counters"]!!.jsonObject["key_sent"]!!.jsonPrimitive.content)
        // an un-marked phase serializes as JSON null
        assertTrue(obj["latency_ms"]!!.jsonObject["call_end"] is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun summaryReflectsCountersAndPhases() {
        val m = CallMetrics()
        m.inc(CallCounter.KEY_SENT)
        m.inc(CallCounter.KEY_RECEIVED)
        m.inc(CallCounter.SYNC_RESTART)
        m.mark(CallPhase.CALL_END)
        val text = m.summaryLines().joinToString("\n")
        assertTrue(text.contains("Call metrics"))
        assertTrue(text.contains("sent=1 received=1"), text)
        assertTrue(text.contains("sync restarts=1"), text)
    }
}

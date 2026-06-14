package de.connect2x.tammy.telecryptModules.call.widgetBridge

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

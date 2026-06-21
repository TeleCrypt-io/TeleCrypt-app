package de.connect2x.tammy.telecryptModules.call.callRtc

import de.connect2x.tammy.trixnity.callRtc.InMemoryMatrixRtcCallStateStore
import de.connect2x.tammy.trixnity.callRtc.MatrixRtcService
import de.connect2x.tammy.trixnity.callRtc.MatrixRtcSlotEvent
import net.folivo.trixnity.core.model.RoomId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MatrixRtcWatcherTest {

    private fun watcher(): Pair<MatrixRtcWatcher, MatrixRtcService> {
        val service = MatrixRtcService(InMemoryMatrixRtcCallStateStore()) { 0L }
        return MatrixRtcWatcherImpl(service) to service
    }

    @Test
    fun roomStateReflectsServiceUpdates() {
        val (watcher, service) = watcher()
        val roomId = RoomId("!r:example.org")
        service.applySlotEvent(MatrixRtcSlotEvent(roomId, "slot", "call-1", true))
        assertTrue(watcher.roomState(roomId).value.incoming)
    }

    @Test
    fun ackIncomingClearsIncomingFlag() {
        val (watcher, service) = watcher()
        val roomId = RoomId("!r:example.org")
        service.applySlotEvent(MatrixRtcSlotEvent(roomId, "slot", "call-1", true))
        watcher.ackIncoming(roomId, "call-1")
        assertFalse(watcher.roomState(roomId).value.incoming)
    }

    @Test
    fun allRoomStatesFlowIsExposed() {
        val (watcher, _) = watcher()
        assertNotNull(watcher.allRoomStates)
    }
}

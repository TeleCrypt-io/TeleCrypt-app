package de.connect2x.tammy.telecryptModules.call.callRtc

import de.connect2x.tammy.trixnityProposal.callRtc.InMemoryMatrixRtcCallStateStore
import de.connect2x.tammy.trixnityProposal.callRtc.MatrixRtcMemberEvent
import de.connect2x.tammy.trixnityProposal.callRtc.MatrixRtcService
import de.connect2x.tammy.trixnityProposal.callRtc.MatrixRtcSlotEvent
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MatrixRtcServiceTest {
    @Test
    fun incomingStateClearsAfterAckAndLocalJoin() {
        val store = InMemoryMatrixRtcCallStateStore()
        val service = MatrixRtcService(store) { 0L }
        val roomId = RoomId("!r:example.org")

        service.applySlotEvent(MatrixRtcSlotEvent(roomId, "slot", "call-1", true))
        val state1 = service.observeRoom(roomId).value
        assertTrue(state1.incoming)
        assertFalse(state1.localJoined)

        service.acknowledgeIncoming(roomId, "call-1")
        assertFalse(service.observeRoom(roomId).value.incoming)

        service.applySlotEvent(MatrixRtcSlotEvent(roomId, "slot", "call-2", true))
        assertTrue(service.observeRoom(roomId).value.incoming)

        service.applyMemberEvent(
            MatrixRtcMemberEvent(
                roomId = roomId,
                slotId = "slot",
                callId = "call-2",
                stickyKey = "local",
                userId = UserId("@me:example.org"),
                deviceId = "DEV",
                expiresAtMs = 0L,
                isLocal = true,
                connected = true,
            )
        )
        val state2 = service.observeRoom(roomId).value
        assertTrue(state2.localJoined)
        assertFalse(state2.incoming)
    }

    @Test
    fun rtcActiveRequiresNonExpiredParticipants() {
        var now = 1000L
        val store = InMemoryMatrixRtcCallStateStore()
        val service = MatrixRtcService(store) { now }
        val roomId = RoomId("!r:example.org")

        service.applySlotEvent(MatrixRtcSlotEvent(roomId, "slot", "call-1", true))
        assertFalse(service.observeRoom(roomId).value.rtcActive)

        service.applyMemberEvent(
            MatrixRtcMemberEvent(
                roomId = roomId,
                slotId = "slot",
                callId = "call-1",
                stickyKey = "remote",
                userId = UserId("@remote:example.org"),
                deviceId = "DEV2",
                expiresAtMs = now + 50,
                isLocal = false,
                connected = true,
            )
        )
        assertTrue(service.observeRoom(roomId).value.rtcActive)
        assertEquals(1, service.observeRoom(roomId).value.participantsCount)

        now += 100
        service.applySlotEvent(MatrixRtcSlotEvent(roomId, "slot", "call-1", true))
        assertFalse(service.observeRoom(roomId).value.rtcActive)
        assertEquals(0, service.observeRoom(roomId).value.participantsCount)
    }
}

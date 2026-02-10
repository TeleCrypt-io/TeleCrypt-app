package de.connect2x.tammy.telecryptModules.call.callRtc

import de.connect2x.tammy.trixnityProposal.callRtc.InMemoryMatrixRtcCallStateStore
import de.connect2x.tammy.trixnityProposal.callRtc.MatrixRtcMemberEvent
import de.connect2x.tammy.trixnityProposal.callRtc.MatrixRtcService
import de.connect2x.tammy.trixnityProposal.callRtc.MatrixRtcSlotEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
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

    @Test
    fun closingSlotResetsSessionAndParticipants() {
        val store = InMemoryMatrixRtcCallStateStore()
        val service = MatrixRtcService(store) { 10L }
        val roomId = RoomId("!r:example.org")

        service.applySlotEvent(MatrixRtcSlotEvent(roomId, "slot", "call-1", true))
        service.applyMemberEvent(
            MatrixRtcMemberEvent(
                roomId = roomId,
                slotId = "slot",
                callId = "call-1",
                stickyKey = "remote",
                userId = UserId("@remote:example.org"),
                deviceId = "DEV2",
                expiresAtMs = 0L,
                isLocal = false,
                connected = true,
            )
        )

        val opened = service.observeRoom(roomId).value
        assertTrue(opened.slotOpen)
        assertEquals(1, opened.participantsCount)
        assertTrue(opened.session != null)

        service.applySlotEvent(MatrixRtcSlotEvent(roomId, "slot", null, false))
        val closed = service.observeRoom(roomId).value
        assertFalse(closed.slotOpen)
        assertTrue(closed.session == null)
        assertEquals(0, closed.participantsCount)
        assertFalse(closed.rtcActive)
        assertFalse(closed.incoming)
    }

    @Test
    fun disconnectedMemberRemovesParticipant() {
        val store = InMemoryMatrixRtcCallStateStore()
        val service = MatrixRtcService(store) { 0L }
        val roomId = RoomId("!r:example.org")

        service.applySlotEvent(MatrixRtcSlotEvent(roomId, "slot", "call-1", true))
        service.applyMemberEvent(
            MatrixRtcMemberEvent(
                roomId = roomId,
                slotId = "slot",
                callId = "call-1",
                stickyKey = "remote",
                userId = UserId("@remote:example.org"),
                deviceId = "DEV2",
                expiresAtMs = 0L,
                isLocal = false,
                connected = true,
            )
        )
        assertEquals(1, service.observeRoom(roomId).value.participantsCount)

        service.applyMemberEvent(
            MatrixRtcMemberEvent(
                roomId = roomId,
                slotId = "slot",
                callId = "call-1",
                stickyKey = "remote",
                userId = UserId("@remote:example.org"),
                deviceId = "DEV2",
                expiresAtMs = 0L,
                isLocal = false,
                connected = false,
            )
        )
        assertEquals(0, service.observeRoom(roomId).value.participantsCount)
    }

    @Test
    fun roomStateIncludesOnlyActiveSlotAndCallParticipants() {
        val store = InMemoryMatrixRtcCallStateStore()
        val service = MatrixRtcService(store) { 0L }
        val roomId = RoomId("!r:example.org")

        service.applySlotEvent(MatrixRtcSlotEvent(roomId, "slot-a", "call-1", true))
        service.applyMemberEvent(
            MatrixRtcMemberEvent(
                roomId = roomId,
                slotId = "slot-a",
                callId = "call-2",
                stickyKey = "wrong-call",
                userId = UserId("@wrong-call:example.org"),
                deviceId = "D1",
                expiresAtMs = 0L,
                isLocal = false,
                connected = true,
            )
        )
        service.applyMemberEvent(
            MatrixRtcMemberEvent(
                roomId = roomId,
                slotId = "slot-b",
                callId = "call-1",
                stickyKey = "wrong-slot",
                userId = UserId("@wrong-slot:example.org"),
                deviceId = "D2",
                expiresAtMs = 0L,
                isLocal = false,
                connected = true,
            )
        )
        service.applyMemberEvent(
            MatrixRtcMemberEvent(
                roomId = roomId,
                slotId = "slot-a",
                callId = "call-1",
                stickyKey = "valid",
                userId = UserId("@valid:example.org"),
                deviceId = "D3",
                expiresAtMs = 0L,
                isLocal = false,
                connected = true,
            )
        )

        val state = service.observeRoom(roomId).value
        assertEquals(1, state.participantsCount)
        assertEquals("@valid:example.org", state.participants.first().userId.full)
    }

    @Test
    fun allRoomStatesEmitsWhenRoomStateChanges() = runTest {
        val store = InMemoryMatrixRtcCallStateStore()
        val service = MatrixRtcService(store) { 0L }
        val roomId = RoomId("!r:example.org")

        val emittedValues = mutableListOf<de.connect2x.tammy.trixnityProposal.callRtc.MatrixRtcRoomState>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            service.allRoomStates.take(1).toList(emittedValues)
        }
        service.applySlotEvent(MatrixRtcSlotEvent(roomId, "slot", "call-1", true))
        collector.join()
        val emitted = emittedValues.first()
        assertEquals(roomId, emitted.roomId)
        assertTrue(emitted.slotOpen)
        assertEquals("call-1", emitted.session?.callId)
    }
}

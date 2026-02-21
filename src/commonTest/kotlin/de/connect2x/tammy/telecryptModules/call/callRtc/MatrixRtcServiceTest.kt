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

    @Test
    fun multiDeviceParticipantsAggregateByUserId() {
        val store = InMemoryMatrixRtcCallStateStore()
        val service = MatrixRtcService(store) { 0L }
        val roomId = RoomId("!r:example.org")
        val userId = UserId("@same:example.org")

        service.applySlotEvent(MatrixRtcSlotEvent(roomId, "slot", "call-1", true))
        service.applyMemberEvent(
            MatrixRtcMemberEvent(
                roomId = roomId,
                slotId = "slot",
                callId = "call-1",
                stickyKey = "sticky-A",
                userId = userId,
                deviceId = "DEV-A",
                expiresAtMs = 0L,
                isLocal = false,
                connected = true,
            )
        )
        service.applyMemberEvent(
            MatrixRtcMemberEvent(
                roomId = roomId,
                slotId = "slot",
                callId = "call-1",
                stickyKey = "sticky-B",
                userId = userId,
                deviceId = "DEV-B",
                expiresAtMs = 0L,
                isLocal = false,
                connected = true,
            )
        )

        val state = service.observeRoom(roomId).value
        assertEquals(2, state.participantsCount)
        assertEquals(1, state.aggregatedParticipantsCount)
        val aggregated = state.aggregatedParticipants.single()
        assertEquals(userId, aggregated.userId)
        assertEquals(2, aggregated.devicesCount)
        assertEquals(2, aggregated.connectedDevicesCount)
        assertEquals(0, aggregated.localDevicesCount)
        assertTrue(aggregated.anyConnected)
        assertFalse(aggregated.anyLocal)
    }

    @Test
    fun disconnectRemovesOnlyOneStickyKey() {
        val store = InMemoryMatrixRtcCallStateStore()
        val service = MatrixRtcService(store) { 0L }
        val roomId = RoomId("!r:example.org")
        val userId = UserId("@same:example.org")

        service.applySlotEvent(MatrixRtcSlotEvent(roomId, "slot", "call-1", true))
        service.applyMemberEvent(
            MatrixRtcMemberEvent(
                roomId = roomId,
                slotId = "slot",
                callId = "call-1",
                stickyKey = "sticky-A",
                userId = userId,
                deviceId = "DEV-A",
                expiresAtMs = 0L,
                isLocal = false,
                connected = true,
            )
        )
        service.applyMemberEvent(
            MatrixRtcMemberEvent(
                roomId = roomId,
                slotId = "slot",
                callId = "call-1",
                stickyKey = "sticky-B",
                userId = userId,
                deviceId = "DEV-B",
                expiresAtMs = 0L,
                isLocal = false,
                connected = true,
            )
        )
        assertEquals(2, service.observeRoom(roomId).value.participantsCount)
        assertEquals(1, service.observeRoom(roomId).value.aggregatedParticipantsCount)

        service.applyMemberEvent(
            MatrixRtcMemberEvent(
                roomId = roomId,
                slotId = "slot",
                callId = "call-1",
                stickyKey = "sticky-A",
                userId = userId,
                deviceId = "DEV-A",
                expiresAtMs = 0L,
                isLocal = false,
                connected = false,
            )
        )

        val state = service.observeRoom(roomId).value
        assertEquals(1, state.participantsCount)
        assertEquals(1, state.aggregatedParticipantsCount)
        val aggregated = state.aggregatedParticipants.single()
        assertEquals(1, aggregated.devicesCount)
        assertEquals(1, aggregated.connectedDevicesCount)
        assertEquals("sticky-B", state.participants.single().stickyKey)
    }

    @Test
    fun rtcActiveStaysTrueIfOneDeviceDisconnectsButAnotherRemains() {
        val store = InMemoryMatrixRtcCallStateStore()
        val service = MatrixRtcService(store) { 0L }
        val roomId = RoomId("!r:example.org")
        val userId = UserId("@same:example.org")

        service.applySlotEvent(MatrixRtcSlotEvent(roomId, "slot", "call-1", true))
        service.applyMemberEvent(
            MatrixRtcMemberEvent(
                roomId = roomId,
                slotId = "slot",
                callId = "call-1",
                stickyKey = "sticky-A",
                userId = userId,
                deviceId = "DEV-A",
                expiresAtMs = 0L,
                isLocal = false,
                connected = true,
            )
        )
        service.applyMemberEvent(
            MatrixRtcMemberEvent(
                roomId = roomId,
                slotId = "slot",
                callId = "call-1",
                stickyKey = "sticky-B",
                userId = userId,
                deviceId = "DEV-B",
                expiresAtMs = 0L,
                isLocal = false,
                connected = true,
            )
        )

        assertTrue(service.observeRoom(roomId).value.rtcActive)
        assertEquals(2, service.observeRoom(roomId).value.participantsCount)
        assertEquals(1, service.observeRoom(roomId).value.aggregatedParticipantsCount)

        service.applyMemberEvent(
            MatrixRtcMemberEvent(
                roomId = roomId,
                slotId = "slot",
                callId = "call-1",
                stickyKey = "sticky-A",
                userId = userId,
                deviceId = "DEV-A",
                expiresAtMs = 0L,
                isLocal = false,
                connected = false,
            )
        )

        val stateAfter = service.observeRoom(roomId).value
        assertTrue(stateAfter.rtcActive)
        assertEquals(1, stateAfter.participantsCount)
        assertEquals(1, stateAfter.aggregatedParticipantsCount)
        assertEquals(1, stateAfter.aggregatedParticipants.single().connectedDevicesCount)
    }

    @Test
    fun localDeviceJoinClearsIncomingAndSetsLocalAggregation() {
        val store = InMemoryMatrixRtcCallStateStore()
        val service = MatrixRtcService(store) { 0L }
        val roomId = RoomId("!r:example.org")

        service.applySlotEvent(MatrixRtcSlotEvent(roomId, "slot", "call-1", true))

        // Remote joins first -> should be incoming.
        service.applyMemberEvent(
            MatrixRtcMemberEvent(
                roomId = roomId,
                slotId = "slot",
                callId = "call-1",
                stickyKey = "remote-1",
                userId = UserId("@remote:example.org"),
                deviceId = "DEV-R",
                expiresAtMs = 0L,
                isLocal = false,
                connected = true,
            )
        )

        val stateIncoming = service.observeRoom(roomId).value
        assertTrue(stateIncoming.rtcActive)
        assertTrue(stateIncoming.incoming)
        assertFalse(stateIncoming.localJoined)

        // Local joins with null deviceId -> should clear incoming and mark local.
        service.applyMemberEvent(
            MatrixRtcMemberEvent(
                roomId = roomId,
                slotId = "slot",
                callId = "call-1",
                stickyKey = "local-1",
                userId = UserId("@me:example.org"),
                deviceId = null,
                expiresAtMs = 0L,
                isLocal = true,
                connected = true,
            )
        )

        val state = service.observeRoom(roomId).value
        assertTrue(state.rtcActive)
        assertTrue(state.localJoined)
        assertFalse(state.incoming)
        assertEquals(2, state.participantsCount)
        assertEquals(2, state.aggregatedParticipantsCount)

        val localAgg = state.aggregatedParticipants.single { it.userId == UserId("@me:example.org") }
        assertEquals(1, localAgg.devicesCount)
        assertEquals(1, localAgg.connectedDevicesCount)
        assertEquals(1, localAgg.localDevicesCount)
        assertTrue(localAgg.anyLocal)
        assertTrue(localAgg.anyConnected)

        val localDeviceParticipant = localAgg.deviceParticipants.single()
        assertEquals("local-1", localDeviceParticipant.stickyKey)
        assertEquals("local-1", localDeviceParticipant.deviceKey())
    }
}

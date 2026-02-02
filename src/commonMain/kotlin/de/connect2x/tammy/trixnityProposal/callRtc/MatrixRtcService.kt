package de.connect2x.tammy.trixnityProposal.callRtc

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import net.folivo.trixnity.core.model.RoomId

class MatrixRtcService(
    private val callStateStore: MatrixRtcCallStateStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private fun initialRoomState(roomId: RoomId): MatrixRtcRoomState {
        return MatrixRtcRoomState(
            roomId = roomId,
            slotId = MATRIX_RTC_DEFAULT_SLOT_ID,
            slotOpen = false,
            session = null,
            participants = emptyList(),
            participantsCount = 0,
            localJoined = false,
            rtcActive = false,
            incoming = false,
            phase = MatrixRtcCallPhase.IDLE,
        )
    }
    private data class RoomHolder(
        val roomId: RoomId,
        var slotId: String = MATRIX_RTC_DEFAULT_SLOT_ID,
        var slotOpen: Boolean = false,
        var activeCallId: String? = null,
        var sessionStartedAtMs: Long = 0L,
        val participants: MutableMap<String, MatrixRtcParticipant> = mutableMapOf(),
        val state: MutableStateFlow<MatrixRtcRoomState>,
    )

    private val rooms = mutableMapOf<RoomId, RoomHolder>()
    private val _allRoomStates = MutableSharedFlow<MatrixRtcRoomState>(extraBufferCapacity = 32)
    val allRoomStates: SharedFlow<MatrixRtcRoomState> = _allRoomStates.asSharedFlow()

    fun observeRoom(roomId: RoomId): StateFlow<MatrixRtcRoomState> = holderFor(roomId).state

    fun acknowledgeIncoming(roomId: RoomId, callId: String) {
        if (callId.isBlank()) return
        callStateStore.setLastSeenCallId(roomId, callId)
        refresh(roomId)
    }

    fun applySlotEvent(slot: MatrixRtcSlotEvent) {
        val holder = holderFor(slot.roomId)
        holder.slotId = slot.slotId.ifBlank { MATRIX_RTC_DEFAULT_SLOT_ID }
        if (!slot.open || slot.callId.isNullOrBlank()) {
            holder.slotOpen = false
            holder.activeCallId = null
            holder.sessionStartedAtMs = 0L
            holder.participants.clear()
            refresh(slot.roomId)
            return
        }
        val newCallId = slot.callId
        val now = nowMs()
        if (holder.activeCallId != newCallId) {
            holder.participants.clear()
            holder.sessionStartedAtMs = now
        }
        holder.slotOpen = true
        holder.activeCallId = newCallId
        refresh(slot.roomId)
    }

    fun applyMemberEvent(member: MatrixRtcMemberEvent) {
        val holder = holderFor(member.roomId)
        if (!member.connected) {
            holder.participants.remove(member.stickyKey)
            refresh(member.roomId)
            return
        }
        holder.participants[member.stickyKey] = MatrixRtcParticipant(
            stickyKey = member.stickyKey,
            slotId = member.slotId.ifBlank { MATRIX_RTC_DEFAULT_SLOT_ID },
            callId = member.callId,
            userId = member.userId,
            deviceId = member.deviceId,
            connected = member.connected,
            expiresAtMs = member.expiresAtMs,
            isLocal = member.isLocal,
        )
        refresh(member.roomId)
    }

    private fun refresh(roomId: RoomId) {
        val holder = holderFor(roomId)
        val now = nowMs()
        purgeExpiredParticipants(holder, now)
        val newState = buildState(holder, now)
        holder.state.value = newState
        _allRoomStates.tryEmit(newState)
    }

    private fun purgeExpiredParticipants(holder: RoomHolder, nowMs: Long) {
        val iterator = holder.participants.iterator()
        while (iterator.hasNext()) {
            val participant = iterator.next().value
            if (participant.isExpired(nowMs)) {
                iterator.remove()
            }
        }
    }

    private fun buildState(holder: RoomHolder, nowMs: Long): MatrixRtcRoomState {
        val callId = if (holder.slotOpen) holder.activeCallId else null
        val participants = if (callId.isNullOrBlank()) {
            emptyList()
        } else {
            holder.participants.values.filter { participant ->
                participant.slotId == holder.slotId &&
                    participant.callId == callId &&
                    !participant.isExpired(nowMs)
            }
        }
        val localJoined = participants.any { it.isLocal }
        val rtcActive = holder.slotOpen && participants.isNotEmpty()
        val lastSeenCallId = callStateStore.getLastSeenCallId(holder.roomId)
        val incoming = holder.slotOpen && !localJoined && !callId.isNullOrBlank() && callId != lastSeenCallId
        val phase = when {
            incoming -> MatrixRtcCallPhase.INCOMING
            rtcActive -> MatrixRtcCallPhase.IN_CALL
            else -> MatrixRtcCallPhase.IDLE
        }
        val session = callId?.let {
            MatrixRtcCallSession(
                slotId = holder.slotId,
                callId = it,
                startedAtMs = holder.sessionStartedAtMs.takeIf { it > 0 } ?: nowMs,
            )
        }
        return MatrixRtcRoomState(
            roomId = holder.roomId,
            slotId = holder.slotId,
            slotOpen = holder.slotOpen,
            session = session,
            participants = participants,
            participantsCount = participants.size,
            localJoined = localJoined,
            rtcActive = rtcActive,
            incoming = incoming,
            phase = phase,
        )
    }

    private fun holderFor(roomId: RoomId): RoomHolder {
        return rooms.getOrPut(roomId) {
            RoomHolder(
                roomId = roomId,
                state = MutableStateFlow(initialRoomState(roomId)),
            )
        }
    }

}

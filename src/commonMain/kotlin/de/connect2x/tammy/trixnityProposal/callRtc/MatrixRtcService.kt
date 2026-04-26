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
            aggregatedParticipants = emptyList(),
            aggregatedParticipantsCount = 0,
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
        // DIAGNOSTIC: Log every slot event to trace incoming call detection
        println("[Call][DIAG] applySlotEvent room=${slot.roomId.full} open=${slot.open} callId=${slot.callId} slotId=${slot.slotId}")
        if (!slot.open || slot.callId.isNullOrBlank()) {
            holder.slotOpen = false
            holder.activeCallId = null
            holder.sessionStartedAtMs = 0L
            holder.participants.clear()
            // Clear lastSeenCallId when slot closes — this allows the NEXT call
            // in this room to be detected as incoming. Without this, the same callId
            // would be permanently blocked by lastSeenCallId from a previous call.
            callStateStore.clearLastSeenCallId(slot.roomId)
            println("[Call][DIAG] Slot closed, cleared lastSeenCallId for room=${slot.roomId.full}")
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

        // Auto-open slot when we see a remote member event with a valid callId
        // but no slot is currently open. This handles the case where the remote
        // client (e.g., ElementX) starts a call using only m.rtc.member state events
        // without publishing a separate m.rtc.slot event. Without this, the
        // incoming call detection would never trigger because slotOpen stays false.
        if (!holder.slotOpen && member.callId.isNotBlank() && !member.isLocal) {
            println(
                "[Call][DIAG] Auto-opening slot from member event: room=${member.roomId.full} " +
                    "callId=${member.callId} user=${member.userId.full} device=${member.deviceId}"
            )
            holder.slotOpen = true
            holder.activeCallId = member.callId
            holder.slotId = member.slotId.ifBlank { MATRIX_RTC_DEFAULT_SLOT_ID }
            if (holder.sessionStartedAtMs == 0L) {
                holder.sessionStartedAtMs = nowMs()
            }
        }

        refresh(member.roomId)
    }

    private fun refresh(roomId: RoomId) {
        val holder = holderFor(roomId)
        val now = nowMs()
        purgeNonActiveAndExpiredParticipants(holder, now)
        val newState = buildState(holder, now)
        holder.state.value = newState
        _allRoomStates.tryEmit(newState)
    }

    private fun purgeNonActiveAndExpiredParticipants(holder: RoomHolder, nowMs: Long) {
        val activeCallId = holder.activeCallId
        val activeSlotId = holder.slotId
        val iterator = holder.participants.iterator()
        while (iterator.hasNext()) {
            val participant = iterator.next().value
            // For the new per-device MSC3401 format, call_id is empty and we generate
            // synthetic callIds like "msc3401_deviceId". These won't match the slot's
            // activeCallId. We keep participants if:
            // 1. Their callId matches the active callId exactly, OR
            // 2. Their callId starts with "msc3401_" (synthetic from per-device format), OR
            // 3. The active callId starts with "msc3401_" (slot was auto-opened from member event)
            val callIdMatches = participant.callId == activeCallId ||
                participant.callId.startsWith("msc3401_") ||
                (activeCallId?.startsWith("msc3401_") == true)
            val keepForActiveSession = holder.slotOpen &&
                !activeCallId.isNullOrBlank() &&
                participant.slotId == activeSlotId &&
                callIdMatches
            val remove = !keepForActiveSession || participant.isExpired(nowMs)
            if (remove) iterator.remove()
        }
    }

    private fun buildState(holder: RoomHolder, nowMs: Long): MatrixRtcRoomState {
        val callId = if (holder.slotOpen) holder.activeCallId else null
        val participants = if (callId.isNullOrBlank()) {
            emptyList()
        } else {
            holder.participants.values.filter { participant ->
                // Same lenient callId matching as in purge:
                // MSC3401 per-device format uses synthetic callIds like "msc3401_deviceId"
                val callIdMatches = participant.callId == callId ||
                    participant.callId.startsWith("msc3401_") ||
                    callId.startsWith("msc3401_")
                participant.slotId == holder.slotId &&
                    callIdMatches &&
                    !participant.isExpired(nowMs)
            }
        }

        val aggregatedParticipants = participants
            .groupBy { it.userId }
            .map { (userId, devices) ->
                val connectedDevicesCount = devices.count { it.connected }
                val localDevicesCount = devices.count { it.isLocal && it.connected }
                MatrixRtcAggregatedParticipant(
                    userId = userId,
                    deviceParticipants = devices.sortedBy { it.deviceKey() },
                    devicesCount = devices.size,
                    connectedDevicesCount = connectedDevicesCount,
                    localDevicesCount = localDevicesCount,
                    anyLocal = localDevicesCount > 0,
                    anyConnected = connectedDevicesCount > 0,
                )
            }
            .sortedBy { it.userId.full }

        val localJoined = participants.any { it.isLocal }
        val rtcActive = holder.slotOpen && participants.isNotEmpty()
        val lastSeenCallId = callStateStore.getLastSeenCallId(holder.roomId)
        val incoming = holder.slotOpen && !localJoined && !callId.isNullOrBlank() && callId != lastSeenCallId
        // DIAGNOSTIC: Log incoming flag computation whenever slotOpen is true
        if (holder.slotOpen) {
            println(
                "[Call][DIAG] buildState room=${holder.roomId.full} slotOpen=${holder.slotOpen} " +
                    "callId=$callId localJoined=$localJoined lastSeenCallId=$lastSeenCallId " +
                    "incoming=$incoming participants=${participants.size}"
            )
        }
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
            aggregatedParticipants = aggregatedParticipants,
            aggregatedParticipantsCount = aggregatedParticipants.size,
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

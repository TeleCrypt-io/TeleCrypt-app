package de.connect2x.tammy.telecryptModules.call.callRtc

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import net.folivo.trixnity.core.model.RoomId

data class MatrixRtcSlotUpdate(
    val roomId: RoomId,
    val slotId: String,
    val callId: String?,
    val open: Boolean,
)

data class MatrixRtcMemberUpdate(
    val roomId: RoomId,
    val slotId: String,
    val callId: String,
    val stickyKey: String,
    val userId: String,
    val deviceId: String?,
    val expiresAtMs: Long,
    val isLocal: Boolean,
    val connected: Boolean = true,
)

data class MatrixRtcMember(
    val slotId: String,
    val callId: String,
    val stickyKey: String,
    val userId: String,
    val deviceId: String?,
    val expiresAtMs: Long,
    val isLocal: Boolean,
) {
    fun isExpired(nowMs: Long): Boolean {
        if (expiresAtMs <= 0L) return false
        return nowMs >= expiresAtMs
    }
}

data class MatrixRtcRoomState(
    val roomId: RoomId,
    val slotId: String,
    val slotOpen: Boolean,
    val activeCallId: String?,
    val activeMembers: List<MatrixRtcMember>,
    val activeMembersCount: Int,
    val localJoined: Boolean,
    val rtcActive: Boolean,
    val incoming: Boolean,
)

interface MatrixRtcCallStateStore {
    fun getLastSeenCallId(roomId: RoomId): String?
    fun setLastSeenCallId(roomId: RoomId, callId: String)
}

class InMemoryMatrixRtcCallStateStore : MatrixRtcCallStateStore {
    private val lastSeenByRoom = mutableMapOf<RoomId, String>()

    override fun getLastSeenCallId(roomId: RoomId): String? = lastSeenByRoom[roomId]

    override fun setLastSeenCallId(roomId: RoomId, callId: String) {
        if (callId.isBlank()) return
        lastSeenByRoom[roomId] = callId
    }
}

interface MatrixRtcWatcher {
    fun roomState(roomId: RoomId): StateFlow<MatrixRtcRoomState>
    fun ackIncoming(roomId: RoomId, callId: String)
    val allRoomStates: SharedFlow<MatrixRtcRoomState>

    // Event source hooks: will be used by the Matrix sync adapter.
    fun applySlotUpdate(update: MatrixRtcSlotUpdate)
    fun applyMemberUpdate(update: MatrixRtcMemberUpdate)
}

class MatrixRtcWatcherImpl(
    private val stateStore: MatrixRtcCallStateStore,
    private val nowMs: () -> Long = ::currentTimeMillis,
) : MatrixRtcWatcher {
    private data class RoomHolder(
        val roomId: RoomId,
        var slotId: String,
        var slotOpen: Boolean,
        var activeCallId: String?,
        val members: MutableMap<String, MatrixRtcMember>,
        val state: MutableStateFlow<MatrixRtcRoomState>,
        var lastLoggedState: MatrixRtcRoomState? = null,
    )

    private val rooms = mutableMapOf<RoomId, RoomHolder>()
    private val _allRoomStates = MutableSharedFlow<MatrixRtcRoomState>(extraBufferCapacity = 16)
    override val allRoomStates: SharedFlow<MatrixRtcRoomState> = _allRoomStates.asSharedFlow()

    override fun roomState(roomId: RoomId): StateFlow<MatrixRtcRoomState> {
        return holderFor(roomId).state
    }

    override fun ackIncoming(roomId: RoomId, callId: String) {
        if (callId.isBlank()) return
        stateStore.setLastSeenCallId(roomId, callId)
        refresh(roomId)
    }

    override fun applySlotUpdate(update: MatrixRtcSlotUpdate) {
        val holder = holderFor(update.roomId)
        holder.slotId = update.slotId.ifBlank { MATRIX_RTC_DEFAULT_SLOT_ID }
        if (!update.open || update.callId.isNullOrBlank()) {
            holder.slotOpen = false
            holder.activeCallId = null
            holder.members.clear()
            refresh(update.roomId)
            return
        }
        val prevCallId = holder.activeCallId
        holder.slotOpen = true
        holder.activeCallId = update.callId
        if (prevCallId != null && prevCallId != update.callId) {
            holder.members.clear()
        }
        refresh(update.roomId)
    }

    override fun applyMemberUpdate(update: MatrixRtcMemberUpdate) {
        val holder = holderFor(update.roomId)
        if (!update.connected) {
            holder.members.remove(update.stickyKey)
            refresh(update.roomId)
            return
        }
        holder.members[update.stickyKey] = MatrixRtcMember(
            slotId = update.slotId,
            callId = update.callId,
            stickyKey = update.stickyKey,
            userId = update.userId,
            deviceId = update.deviceId,
            expiresAtMs = update.expiresAtMs,
            isLocal = update.isLocal,
        )
        refresh(update.roomId)
    }

    private fun holderFor(roomId: RoomId): RoomHolder {
        return rooms.getOrPut(roomId) {
            val initial = MatrixRtcRoomState(
                roomId = roomId,
                slotId = MATRIX_RTC_DEFAULT_SLOT_ID,
                slotOpen = false,
                activeCallId = null,
                activeMembers = emptyList(),
                activeMembersCount = 0,
                localJoined = false,
                rtcActive = false,
                incoming = false,
            )
            RoomHolder(
                roomId = roomId,
                slotId = MATRIX_RTC_DEFAULT_SLOT_ID,
                slotOpen = false,
                activeCallId = null,
                members = mutableMapOf(),
                state = MutableStateFlow(initial),
            )
        }
    }

    private fun refresh(roomId: RoomId) {
        val holder = holderFor(roomId)
        val now = nowMs()
        purgeExpiredMembers(holder, now)
        val newState = buildState(holder, now)
        holder.state.value = newState
        _allRoomStates.tryEmit(newState)
        logStateChange(holder, newState)
    }

    private fun purgeExpiredMembers(holder: RoomHolder, now: Long) {
        val iterator = holder.members.iterator()
        while (iterator.hasNext()) {
            val member = iterator.next().value
            if (member.isExpired(now)) {
                iterator.remove()
            }
        }
    }

    private fun buildState(holder: RoomHolder, now: Long): MatrixRtcRoomState {
        val callId = holder.activeCallId
        val activeMembers = if (!holder.slotOpen || callId.isNullOrBlank()) {
            emptyList()
        } else {
            holder.members.values.filter { member ->
                member.slotId == holder.slotId &&
                    member.callId == callId &&
                    !member.isExpired(now)
            }
        }
        val localJoined = activeMembers.any { it.isLocal }
        val rtcActive = holder.slotOpen && activeMembers.isNotEmpty()
        val lastSeenCallId = stateStore.getLastSeenCallId(holder.roomId)
        val incoming = holder.slotOpen && !localJoined && callId != null && callId != lastSeenCallId
        return MatrixRtcRoomState(
            roomId = holder.roomId,
            slotId = holder.slotId,
            slotOpen = holder.slotOpen,
            activeCallId = callId,
            activeMembers = activeMembers,
            activeMembersCount = activeMembers.size,
            localJoined = localJoined,
            rtcActive = rtcActive,
            incoming = incoming,
        )
    }

    private fun logStateChange(holder: RoomHolder, state: MatrixRtcRoomState) {
        val last = holder.lastLoggedState
        val changed = last == null ||
            last.incoming != state.incoming ||
            last.activeCallId != state.activeCallId ||
            last.activeMembersCount != state.activeMembersCount ||
            last.localJoined != state.localJoined ||
            last.slotOpen != state.slotOpen
        if (!changed) return
        println(
            "[Call] RTC state room=${state.roomId.full} slotOpen=${state.slotOpen} " +
                "callId=${state.activeCallId} members=${state.activeMembersCount} " +
                "localJoined=${state.localJoined} incoming=${state.incoming}"
        )
        holder.lastLoggedState = state
    }
}

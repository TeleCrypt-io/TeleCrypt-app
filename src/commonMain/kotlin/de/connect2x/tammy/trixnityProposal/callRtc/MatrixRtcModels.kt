package de.connect2x.tammy.trixnityProposal.callRtc

import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.UserId

const val MATRIX_RTC_DEFAULT_SLOT_ID = "default"

/**
 * Normalized representation of a MatrixRTC slot event.
 */
data class MatrixRtcSlotEvent(
    val roomId: RoomId,
    val slotId: String = MATRIX_RTC_DEFAULT_SLOT_ID,
    val callId: String?,
    val open: Boolean,
)

/**
 * Normalized representation of a MatrixRTC member event.
 */
data class MatrixRtcMemberEvent(
    val roomId: RoomId,
    val slotId: String = MATRIX_RTC_DEFAULT_SLOT_ID,
    val callId: String,
    val stickyKey: String,
    val userId: UserId,
    val deviceId: String?,
    val expiresAtMs: Long,
    val connected: Boolean,
    val isLocal: Boolean,
)

/**
 * Tracks a participant that is attached to the active slot/session.
 */
data class MatrixRtcParticipant(
    val stickyKey: String,
    val slotId: String,
    val callId: String,
    val userId: UserId,
    val deviceId: String?,
    val connected: Boolean,
    val expiresAtMs: Long,
    val isLocal: Boolean,
) {
    fun deviceKey(): String = deviceId ?: stickyKey

    fun isExpired(nowMs: Long): Boolean {
        if (expiresAtMs <= 0L) return false
        return nowMs >= expiresAtMs
    }
}

/**
 * Aggregated view of a participant across devices.
 *
 * A single Matrix user may have multiple concurrent RTC participants (one per device),
 * represented as distinct sticky keys. Aggregation is therefore done by [userId] and
 * retains the underlying device-level participants.
 */
data class MatrixRtcAggregatedParticipant(
    val userId: UserId,
    val deviceParticipants: List<MatrixRtcParticipant>,
    val devicesCount: Int,
    val connectedDevicesCount: Int = devicesCount,
    val localDevicesCount: Int = deviceParticipants.count { it.isLocal && it.connected },
    val anyLocal: Boolean = localDevicesCount > 0,
    val anyConnected: Boolean = connectedDevicesCount > 0,
)

/**
 * Summary of the currently open call session.
 */
data class MatrixRtcCallSession(
    val slotId: String,
    val callId: String,
    val startedAtMs: Long,
)

/**
 * Indicates the high–level phase of the call as far as the room is concerned.
 */
enum class MatrixRtcCallPhase {
    IDLE,
    INCOMING,
    IN_CALL,
}

/**
 * Derived snapshot of the MatrixRTC portion of the room state.
 */
data class MatrixRtcRoomState(
    val roomId: RoomId,
    val slotId: String,
    val slotOpen: Boolean,
    val session: MatrixRtcCallSession?,
    val participants: List<MatrixRtcParticipant>,
    val participantsCount: Int,
    val aggregatedParticipants: List<MatrixRtcAggregatedParticipant> = emptyList(),
    val aggregatedParticipantsCount: Int = 0,
    val localJoined: Boolean,
    val rtcActive: Boolean,
    val incoming: Boolean,
    val phase: MatrixRtcCallPhase,
)

/**
 * Minimal storage for the last seen call ID inside a room.
 */
interface MatrixRtcCallStateStore {
    fun getLastSeenCallId(roomId: RoomId): String?
    fun setLastSeenCallId(roomId: RoomId, callId: String)
}

/**
 * Simple in-memory implementation of [MatrixRtcCallStateStore].
 */
class InMemoryMatrixRtcCallStateStore : MatrixRtcCallStateStore {
    private val lastSeen = mutableMapOf<RoomId, String>()

    override fun getLastSeenCallId(roomId: RoomId): String? = lastSeen[roomId]

    override fun setLastSeenCallId(roomId: RoomId, callId: String) {
        if (callId.isBlank()) return
        lastSeen[roomId] = callId
    }
}

package de.connect2x.tammy.trixnityProposal.callRtc

import kotlinx.serialization.json.JsonObject
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.UserId
import kotlin.runCatching

object MatrixRtcEventParser {
    fun parseSlotEvent(
        roomId: RoomId,
        slotId: String,
        content: JsonObject,
    ): MatrixRtcSlotEvent = parseSlotEvent(
        roomId = roomId,
        slotId = slotId,
        content = MatrixRtcRawEventContentMapper.slot(content),
    )

    fun parseSlotEvent(
        roomId: RoomId,
        slotId: String,
        content: MatrixRtcSlotContentSource,
    ): MatrixRtcSlotEvent {
        if (content.applicationType == null && content.callId == null) {
            return MatrixRtcSlotEvent(
                roomId = roomId,
                slotId = slotId.ifBlank { MATRIX_RTC_DEFAULT_SLOT_ID },
                callId = null,
                open = false,
            )
        }

        val appType = content.applicationType
        if (appType != APP_TYPE_M_CALL) {
            return MatrixRtcSlotEvent(
                roomId = roomId,
                slotId = slotId.ifBlank { MATRIX_RTC_DEFAULT_SLOT_ID },
                callId = null,
                open = false,
            )
        }

        val callId = content.callId
        return MatrixRtcSlotEvent(
            roomId = roomId,
            slotId = slotId.ifBlank { MATRIX_RTC_DEFAULT_SLOT_ID },
            callId = callId,
            open = !callId.isNullOrBlank(),
        )
    }

    fun parseMemberEvent(
        roomId: RoomId,
        senderUserId: String,
        content: JsonObject,
        stateKey: String?,
        localUserId: UserId?,
        localDeviceId: String?,
        nowMs: () -> Long,
        originTimestampMs: Long? = null,
        unsignedAgeMs: Long? = null,
    ): MatrixRtcMemberEvent? = parseMemberEvent(
        roomId = roomId,
        senderUserId = senderUserId,
        content = MatrixRtcRawEventContentMapper.member(content),
        stateKey = stateKey,
        localUserId = localUserId,
        localDeviceId = localDeviceId,
        nowMs = nowMs,
        originTimestampMs = originTimestampMs,
        unsignedAgeMs = unsignedAgeMs,
    )

    fun parseMemberEvent(
        roomId: RoomId,
        senderUserId: String,
        content: MatrixRtcMemberContentSource,
        stateKey: String?,
        localUserId: UserId?,
        localDeviceId: String?,
        nowMs: () -> Long,
        originTimestampMs: Long? = null,
        unsignedAgeMs: Long? = null,
    ): MatrixRtcMemberEvent? {
        val localNow = nowMs()
        val serverNow = originTimestampMs?.let { origin -> origin + (unsignedAgeMs ?: 0L) }
        val now = serverNow ?: localNow
        val slotId = (
            content.slotId
        )?.ifBlank { MATRIX_RTC_DEFAULT_SLOT_ID } ?: MATRIX_RTC_DEFAULT_SLOT_ID

        val stickyKey = (
            content.stickyKey
                ?: content.unstableStickyKey
                ?: stateKey
        )?.takeIf { it.isNotBlank() } ?: return null

        if (content.disconnected) {
            return disconnectEvent(roomId, slotId, stickyKey, senderUserId, content, localUserId, localDeviceId)
        }

        val appType = content.applicationType
        if (appType != null && appType != APP_TYPE_M_CALL) {
            return null
        }

        val callId = content.callId
        val memberId = content.memberId
        val claimedUserId = content.claimedUserId
        val claimedDeviceId = content.claimedDeviceId

        val userId = (
            claimedUserId?.let { parseUserId(it) }
                ?: parseUserId(senderUserId)
        ) ?: return null

        val deviceId = claimedDeviceId
            ?: memberId
                ?.substringAfter("device:", "")
                ?.takeIf { it.isNotBlank() }

        val connected = isValidConnectEvent(
            slotId = slotId,
            stickyKey = stickyKey,
            callId = callId,
            memberId = memberId,
            rtcTransportTypes = content.rtcTransportTypes,
        )

        val expiresAtMs = resolveExpiresAtMs(content, now = now, originTimestampMs = originTimestampMs)
        return MatrixRtcMemberEvent(
            roomId = roomId,
            slotId = slotId,
            callId = callId ?: "",
            stickyKey = stickyKey,
            userId = userId,
            deviceId = deviceId,
            expiresAtMs = expiresAtMs,
            connected = connected,
            isLocal = isLocalMember(userId, deviceId, localUserId, localDeviceId),
        )
    }

    private fun disconnectEvent(
        roomId: RoomId,
        slotId: String,
        stickyKey: String,
        senderUserId: String,
        content: MatrixRtcMemberContentSource,
        localUserId: UserId?,
        localDeviceId: String?,
    ): MatrixRtcMemberEvent? {
        val claimedUserId = content.claimedUserId
        val userId = (
            claimedUserId?.let { parseUserId(it) }
                ?: parseUserId(senderUserId)
        ) ?: return null
        return MatrixRtcMemberEvent(
            roomId = roomId,
            slotId = slotId,
            callId = "",
            stickyKey = stickyKey,
            userId = userId,
            deviceId = null,
            expiresAtMs = 0L,
            connected = false,
            isLocal = isLocalMember(userId, null, localUserId, localDeviceId),
        )
    }

    private fun isValidConnectEvent(
        slotId: String,
        stickyKey: String,
        callId: String?,
        memberId: String?,
        rtcTransportTypes: List<String>,
    ): Boolean {
        if (slotId.isBlank()) return false
        if (callId.isNullOrBlank()) return false
        if (memberId.isNullOrBlank()) return false
        if (stickyKey != memberId) return false
        return rtcTransportTypes.any { it.isNotBlank() }
    }

    private fun isLocalMember(userId: UserId, deviceId: String?, localUserId: UserId?, localDeviceId: String?): Boolean {
        val localUser = localUserId ?: return false
        if (localUser != userId) return false
        val localDevice = localDeviceId ?: return true
        return deviceId == null || deviceId == localDevice
    }

    private fun parseUserId(value: String): UserId? {
        return runCatching { UserId(value) }.getOrNull()
    }

    private fun resolveExpiresAtMs(content: MatrixRtcMemberContentSource, now: Long, originTimestampMs: Long?): Long {
        val absolute = findLong(content.expiryCandidates, ABSOLUTE_EXPIRY_KEYS)
        if (absolute != null) {
            val ts = normalizeTimestamp(absolute.value)
            return if (ts <= 0L) now else ts
        }
        val duration = findLong(content.expiryCandidates, DURATION_EXPIRY_KEYS)
        if (duration != null) {
            val delta = normalizeDuration(duration.value, duration.key)
            val base = originTimestampMs ?: now
            // Non-positive TTL == expired
            return base + delta.coerceAtLeast(0L)
        }
        return 0L
    }

    private fun findLong(candidates: List<MatrixRtcExpiryCandidate>, keys: List<String>): KeyedLong? =
        candidates.firstNotNullOfOrNull { candidate ->
            if (candidate.key in keys) KeyedLong(candidate.key, candidate.value) else null
        }

    private fun normalizeTimestamp(value: Long): Long {
        return if (value < MILLIS_THRESHOLD) value * 1000 else value
    }

    private fun normalizeDuration(value: Long, key: String): Long {
        return if (key.contains("ms")) value else normalizeTimestamp(value)
    }

    private data class KeyedLong(val key: String, val value: Long)

    private const val APP_TYPE_M_CALL = MATRIX_RTC_APP_TYPE_CALL
    private const val MILLIS_THRESHOLD = 1_000_000_000_000L

    private val ABSOLUTE_EXPIRY_KEYS = MATRIX_RTC_ABSOLUTE_EXPIRY_KEYS
    private val DURATION_EXPIRY_KEYS = MATRIX_RTC_DURATION_EXPIRY_KEYS
}

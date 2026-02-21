package de.connect2x.tammy.trixnityProposal.callRtc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.UserId
import kotlin.runCatching

object MatrixRtcEventParser {
    fun parseSlotEvent(
        roomId: RoomId,
        slotId: String,
        content: JsonObject,
    ): MatrixRtcSlotEvent {
        if (content.isEmpty()) {
            return MatrixRtcSlotEvent(
                roomId = roomId,
                slotId = slotId.ifBlank { MATRIX_RTC_DEFAULT_SLOT_ID },
                callId = null,
                open = false,
            )
        }

        val application = content.obj("application")
        val appType = application?.string("type")
        if (appType != APP_TYPE_M_CALL) {
            return MatrixRtcSlotEvent(
                roomId = roomId,
                slotId = slotId.ifBlank { MATRIX_RTC_DEFAULT_SLOT_ID },
                callId = null,
                open = false,
            )
        }

        val callId = parseCallIdFromApplication(application)
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
    ): MatrixRtcMemberEvent? {
        val localNow = nowMs()
        val serverNow = originTimestampMs?.let { origin -> origin + (unsignedAgeMs ?: 0L) }
        val now = serverNow ?: localNow
        val slotId = (
            content.string("slot_id")
                ?: content.string("slotId")
                ?: content.string("slot")
        )?.ifBlank { MATRIX_RTC_DEFAULT_SLOT_ID } ?: MATRIX_RTC_DEFAULT_SLOT_ID

        val stickyKey = (
            content.string("sticky_key")
                ?: content.string("stickyKey")
                ?: content.string("msc4354_sticky_key")
                ?: stateKey
        )?.takeIf { it.isNotBlank() } ?: return null

        if (content.boolean("disconnected") == true) {
            return disconnectEvent(roomId, slotId, stickyKey, senderUserId, content, localUserId, localDeviceId)
        }

        val application = content.obj("application")
        val appType = application?.string("type")
        if (appType != null && appType != APP_TYPE_M_CALL) {
            return null
        }

        val callId = parseCallIdFromApplication(application)
        val member = content.obj("member")
        val memberId = member?.string("id")
        val claimedUserId = member?.string("claimed_user_id")
        val claimedDeviceId = member?.string("claimed_device_id")

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
            rtcTransports = content["rtc_transports"] as? JsonArray ?: content["transports"] as? JsonArray,
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
        content: JsonObject,
        localUserId: UserId?,
        localDeviceId: String?,
    ): MatrixRtcMemberEvent? {
        val claimedUserId = content.obj("member")?.string("claimed_user_id")
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
        rtcTransports: JsonArray?,
    ): Boolean {
        if (slotId.isBlank()) return false
        if (callId.isNullOrBlank()) return false
        if (memberId.isNullOrBlank()) return false
        if (stickyKey != memberId) return false
        if (rtcTransports == null) return false
        if (rtcTransports.isEmpty()) return false
        val hasAnyTypedTransport = rtcTransports.any { element ->
            val obj = element as? JsonObject ?: return@any false
            val type = obj.string("type")
            !type.isNullOrBlank()
        }
        return hasAnyTypedTransport
    }

    private fun parseCallIdFromApplication(application: JsonObject?): String? {
        if (application == null) return null
        val appType = application.string("type")
        if (appType != APP_TYPE_M_CALL) return null

        val dotted = application.string("m.call.id")
        if (!dotted.isNullOrBlank()) return dotted

        val nested = application.obj("m.call")
        return (
            nested?.string("id")
                ?: nested?.string("call_id")
                ?: nested?.string("callId")
        )?.takeIf { it.isNotBlank() }
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

    private fun resolveExpiresAtMs(raw: JsonObject, now: Long, originTimestampMs: Long?): Long {
        val absolute = findLong(raw, ABSOLUTE_EXPIRY_KEYS)
        if (absolute != null) {
            val ts = normalizeTimestamp(absolute.value)
            return if (ts <= 0L) now else ts
        }
        val duration = findLong(raw, DURATION_EXPIRY_KEYS)
        if (duration != null) {
            val delta = normalizeDuration(duration.value, duration.key)
            val base = originTimestampMs ?: now
            // Non-positive TTL == expired
            return base + delta.coerceAtLeast(0L)
        }
        return 0L
    }

    private fun findLong(raw: JsonObject, keys: List<String>): KeyedLong? {
        for (key in keys) {
            val value = raw.long(key)
            if (value != null) {
                return KeyedLong(key, value)
            }
        }
        return null
    }

    private fun normalizeTimestamp(value: Long): Long {
        return if (value < MILLIS_THRESHOLD) value * 1000 else value
    }

    private fun normalizeDuration(value: Long, key: String): Long {
        return if (key.contains("ms")) value else normalizeTimestamp(value)
    }

    private data class KeyedLong(val key: String, val value: Long)

    private fun JsonObject.string(key: String): String? = get(key).asString()

    private fun JsonObject.long(key: String): Long? = get(key).asLong()

    private fun JsonObject.boolean(key: String): Boolean? = get(key).asBoolean()

    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

    private fun JsonElement?.asString(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        return primitive.content.takeIf { it.isNotBlank() }
    }

    private fun JsonElement?.asLong(): Long? {
        val primitive = this as? JsonPrimitive ?: return null
        return primitive.content.toLongOrNull()
    }

    private fun JsonElement?.asBoolean(): Boolean? {
        val primitive = this as? JsonPrimitive ?: return null
        return when (primitive.content.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    private const val APP_TYPE_M_CALL = "m.call"
    private const val MILLIS_THRESHOLD = 1_000_000_000_000L

    private val ABSOLUTE_EXPIRY_KEYS = listOf("expires_ts", "expires_ts_ms", "expires_at")
    private val DURATION_EXPIRY_KEYS = listOf(
        "expires",
        "expires_in",
        "ttl",
        "expires_ms",
        "expires_in_ms",
        "ttl_ms",
        "sticky_duration_ttl_ms",
        "msc4354_sticky_duration_ttl_ms",
    )
}

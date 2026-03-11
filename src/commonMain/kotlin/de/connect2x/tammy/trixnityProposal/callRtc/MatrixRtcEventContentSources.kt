package de.connect2x.tammy.trixnityProposal.callRtc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class MatrixRtcSlotContentSource(
    val applicationType: String? = null,
    val callId: String? = null,
)

data class MatrixRtcMemberContentSource(
    val slotId: String? = null,
    val stickyKey: String? = null,
    val unstableStickyKey: String? = null,
    val disconnected: Boolean = false,
    val applicationType: String? = null,
    val callId: String? = null,
    val memberId: String? = null,
    val claimedUserId: String? = null,
    val claimedDeviceId: String? = null,
    val rtcTransportTypes: List<String> = emptyList(),
    val expiryCandidates: List<MatrixRtcExpiryCandidate> = emptyList(),
)

data class MatrixRtcExpiryCandidate(
    val key: String,
    val value: Long,
)

object MatrixRtcRawEventContentMapper {
    fun slot(content: JsonObject): MatrixRtcSlotContentSource {
        val application = content.obj("application")
        return MatrixRtcSlotContentSource(
            applicationType = application?.string("type"),
            callId = parseCallIdFromApplication(application),
        )
    }

    fun member(content: JsonObject): MatrixRtcMemberContentSource {
        val application = content.obj("application")
        val member = content.obj("member")
        val rtcTransports = (content["rtc_transports"] as? JsonArray ?: content["transports"] as? JsonArray)
            .orEmpty()
            .mapNotNull { (it as? JsonObject)?.string("type") }

        val expiryCandidates = (ABSOLUTE_EXPIRY_KEYS + DURATION_EXPIRY_KEYS).mapNotNull { key ->
            content.long(key)?.let { MatrixRtcExpiryCandidate(key, it) }
        }

        return MatrixRtcMemberContentSource(
            slotId = content.string("slot_id") ?: content.string("slotId") ?: content.string("slot"),
            stickyKey = content.string("sticky_key") ?: content.string("stickyKey"),
            unstableStickyKey = content.string("msc4354_sticky_key"),
            disconnected = content.boolean("disconnected") == true,
            applicationType = application?.string("type"),
            callId = parseCallIdFromApplication(application),
            memberId = member?.string("id"),
            claimedUserId = member?.string("claimed_user_id"),
            claimedDeviceId = member?.string("claimed_device_id"),
            rtcTransportTypes = rtcTransports,
            expiryCandidates = expiryCandidates,
        )
    }

    private fun parseCallIdFromApplication(application: JsonObject?): String? {
        if (application == null) return null
        if (application.string("type") != MATRIX_RTC_APP_TYPE_CALL) return null

        val dotted = application.string("m.call.id")
        if (!dotted.isNullOrBlank()) return dotted

        val nested = application.obj("m.call")
        return (
            nested?.string("id")
                ?: nested?.string("call_id")
                ?: nested?.string("callId")
            )?.takeIf { it.isNotBlank() }
    }

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
}

internal const val MATRIX_RTC_APP_TYPE_CALL = "m.call"

internal val MATRIX_RTC_ABSOLUTE_EXPIRY_KEYS = listOf("expires_ts", "expires_ts_ms", "expires_at")

internal val MATRIX_RTC_DURATION_EXPIRY_KEYS = listOf(
    "expires",
    "expires_in",
    "ttl",
    "expires_ms",
    "expires_in_ms",
    "ttl_ms",
    "sticky_duration_ttl_ms",
    "msc4354_sticky_duration_ttl_ms",
)

private val ABSOLUTE_EXPIRY_KEYS = MATRIX_RTC_ABSOLUTE_EXPIRY_KEYS
private val DURATION_EXPIRY_KEYS = MATRIX_RTC_DURATION_EXPIRY_KEYS
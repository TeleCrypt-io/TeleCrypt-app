package de.connect2x.tammy.telecryptModules.call.callRtc

import de.connect2x.tammy.trixnityProposal.callRtc.MatrixRtcEventParser
import de.connect2x.tammy.trixnityProposal.callRtc.MatrixRtcMemberEvent
import de.connect2x.tammy.trixnityProposal.callRtc.MatrixRtcService
import de.connect2x.tammy.trixnityProposal.callRtc.MatrixRtcSlotEvent
import de.connect2x.tammy.trixnityProposal.callRtc.MATRIX_RTC_DEFAULT_SLOT_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import de.connect2x.tammy.telecryptModules.call.widgetBridge.BridgeForwardingRegistry
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import net.folivo.trixnity.client.store.AccountStore
import net.folivo.trixnity.clientserverapi.client.SyncApiClient
import net.folivo.trixnity.core.EventHandler
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.UserId
import net.folivo.trixnity.core.model.events.ClientEvent
import net.folivo.trixnity.core.model.events.UnknownEventContent
import net.folivo.trixnity.core.subscribeEachEventAsFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.runCatching

class MatrixRtcSyncEventHandler(
    private val syncApi: SyncApiClient,
    private val rtcService: MatrixRtcService,
    private val accountStore: AccountStore,
    private val bridgeRegistry: BridgeForwardingRegistry? = null,
    private val nowMs: () -> Long = ::currentTimeMillis,
) : EventHandler {
    private val started = AtomicBoolean(false)
    private val loggedFirstEvent = AtomicBoolean(false)
    private val eventCount = AtomicLong(0)
    @Volatile
    private var localUserId: UserId? = null
    @Volatile
    private var localDeviceId: String? = null

    override fun startInCoroutineScope(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) {
            return
        }
        println("[Call] MatrixRtcSyncEventHandler started")
        scope.launch {
            accountStore.getAccountAsFlow().collectLatest { account ->
                localUserId = account?.userId
                localDeviceId = account?.deviceId
                if (account != null) {
                    println("[Call][DIAG] Local identity: userId=${account.userId.full} deviceId=${account.deviceId}")
                }
            }
        }
        syncApi.subscribeEachEventAsFlow()
            .onEach { event ->
                if (loggedFirstEvent.compareAndSet(false, true)) {
                    val eventClass = event::class.simpleName
                    val contentClass = event.content::class.simpleName
                    val type = (event.content as? UnknownEventContent)?.eventType
                    println("[Call] First sync event class=$eventClass content=$contentClass type=$type")
                }
                // DIAGNOSTIC: Log ALL UnknownEventContent events to see what types arrive
                val unknown = event.content as? UnknownEventContent
                if (unknown != null) {
                    val t = unknown.eventType
                    // Log RTC-related types always; also log call/member types from MSC3401
                    if (t.contains("rtc") || t.contains("call") || t.contains("msc4143") || t.contains("msc3401") || t.contains("member")) {
                        val roomId = when (event) {
                            is ClientEvent.RoomEvent.StateEvent<*> -> event.roomId?.full
                            is ClientEvent.RoomEvent.MessageEvent<*> -> event.roomId?.full
                            is ClientEvent.EphemeralEvent<*> -> event.roomId?.full
                            else -> null
                        }
                        println("[Call][DIAG] Sync event type=$t class=${event::class.simpleName} room=$roomId")
                    }
                }
                // Also log non-UnknownEventContent events that might be call-related
                // (some events may be deserialized into known types)
                if (unknown == null && eventCount.incrementAndGet() % 500 == 0L) {
                    println("[Call][DIAG] Processed ${eventCount.get()} sync events total (latest class=${event::class.simpleName})")
                }
                handleEvent(event)
                forwardToBridgeIfNeeded(event)
            }
            .launchIn(scope)
    }

    /**
     * Пересылает релевантные sync‑события в активный widget‑bridge для EC iframe.
     *
     * Forwards:
     *   - state events `org.matrix.msc3401.call.member` / `m.call.member` — в мост этой комнаты;
     *   - to-device events `m.call.encryption_keys` — во ВСЕ мосты пользователя
     *     (to-device не привязан к комнате).
     */
    private fun forwardToBridgeIfNeeded(event: ClientEvent<*>) {
        val registry = bridgeRegistry ?: return
        val unknown = event.content as? UnknownEventContent ?: return
        val eventType = unknown.eventType
        
        when (event) {
            is ClientEvent.RoomEvent.StateEvent<*> -> {
                if (eventType != MatrixRtcEventTypes.MSC3401_CALL_MEMBER &&
                    eventType != MatrixRtcEventTypes.CALL_MEMBER
                ) return
                val roomId = event.roomId ?: return
                val localUser = localUserId ?: return
                val session = registry.forRoom(localUser, roomId) ?: return
                val envelope = buildJsonObject {
                    put("type", JsonPrimitive(eventType))
                    put("state_key", JsonPrimitive(event.stateKey))
                    put("sender", JsonPrimitive(event.sender.full))
                    put("event_id", JsonPrimitive(event.id.full))
                    put("room_id", JsonPrimitive(roomId.full))
                    put("origin_server_ts", JsonPrimitive(event.originTimestamp))
                    put("content", unknown.raw)
                }
                println("[Call] Forward state event type=$eventType room=${roomId.full} stateKey=${event.stateKey} -> bridge")
                runCatching { session.forwardSyncEvent(envelope) }
                    .onFailure { println("[Call] forwardSyncEvent failed: ${it.message}") }
            }
            is ClientEvent.ToDeviceEvent<*> -> {
                if (eventType != "m.call.encryption_keys" && eventType != "io.element.call.encryption_keys") return
                val localUser = localUserId ?: return
                val sessions = registry.sessionsForUser(localUser)
                if (sessions.isEmpty()) return
                val envelope = buildJsonObject {
                    put("type", JsonPrimitive(eventType))
                    put("sender", JsonPrimitive(event.sender.full))
                    put("content", unknown.raw)
                }
                println("[Call] Forward to-device type=$eventType sender=${event.sender.full} -> ${sessions.size} bridge(s)")
                sessions.forEach { session ->
                    runCatching { session.forwardToDeviceEvent(envelope) }
                        .onFailure { println("[Call] forwardToDeviceEvent failed: ${it.message}") }
                }
            }
            else -> Unit
        }
    }

    private fun handleEvent(event: ClientEvent<*>) {
        val unknown = event.content as? UnknownEventContent ?: return
        val normalized = MatrixRtcEventTypes.normalize(unknown.eventType) ?: return
        when (event) {
            is ClientEvent.RoomEvent.StateEvent<*> -> {
                println("[Call] RTC event type=$normalized class=StateEvent")
                handleStateEvent(event, unknown.raw, normalized)
            }
            is ClientEvent.EphemeralEvent<*> -> {
                println("[Call] RTC event type=$normalized class=EphemeralEvent")
                if (normalized == MatrixRtcEventTypes.MEMBER) {
                    handleMemberEvent(event, unknown.raw)
                }
            }
            is ClientEvent.RoomAccountDataEvent<*> -> {
                println("[Call] RTC event type=$normalized class=RoomAccountDataEvent")
                if (normalized == MatrixRtcEventTypes.MEMBER) {
                    handleMemberAccountDataEvent(event, unknown.raw)
                }
            }
            is ClientEvent.RoomEvent.MessageEvent<*> -> {
                println("[Call] RTC event type=$normalized class=MessageEvent")
                if (normalized == MatrixRtcEventTypes.MEMBER) {
                    handleMemberMessageEvent(event, unknown.raw)
                }
            }
            else -> Unit
        }
    }

    private fun handleStateEvent(
        event: ClientEvent.RoomEvent.StateEvent<*>,
        raw: JsonObject,
        normalized: String,
    ) {
        val roomId = event.roomId ?: return
        if (normalized == MatrixRtcEventTypes.SLOT) {
            val slotId = event.stateKey.takeIf { it.isNotBlank() } ?: MATRIX_RTC_DEFAULT_SLOT_ID
            val slotEvent = MatrixRtcEventParser.parseSlotEvent(roomId, slotId, raw)
            println("[Call] RTC slot update room=${roomId.full} slot=$slotId callId=${slotEvent.callId}")
            rtcService.applySlotEvent(slotEvent)
            return
        }
        if (normalized == MatrixRtcEventTypes.MEMBER) {
            val eventType = (event.content as? UnknownEventContent)?.eventType ?: ""
            // MSC3401 call.member events use a different format with "memberships" array.
            // The state key is the user ID, and each membership entry has device_id, call_id, etc.
            if (eventType == MatrixRtcEventTypes.MSC3401_CALL_MEMBER || eventType == MatrixRtcEventTypes.CALL_MEMBER) {
                handleMsc3401MemberStateEvent(event, raw)
                return
            }
            val stickyKey = event.stateKey.takeIf { it.isNotBlank() }
            val sender = event.sender ?: return
            println("[Call] RTC member state event room=${roomId.full} sender=${sender.full}")
            applyMemberRaw(
                roomId = roomId,
                senderUserId = sender.full,
                raw = raw,
                stateKey = stickyKey,
                originTimestampMs = event.originTimestamp,
                unsignedAgeMs = event.unsigned?.age,
            )
        }
    }

    /**
     * Handles MSC3401 `org.matrix.msc3401.call.member` / `m.call.member` state events.
     *
     * Format:
     * ```json
     * {
     *   "memberships": [
     *     {
     *       "application": "m.call",
     *       "call_id": "",
     *       "device_id": "DEVICEID",
     *       "expires": 14400000,
     *       "foci_active": [{"type": "livekit", ...}],
     *       "membershipID": "...",
     *       "scope": "m.room"
     *     }
     *   ]
     * }
     * ```
     * State key is the user ID (e.g., `@user:server`).
     */
    private fun handleMsc3401MemberStateEvent(
        event: ClientEvent.RoomEvent.StateEvent<*>,
        raw: JsonObject,
    ) {
        val roomId = event.roomId ?: return
        val stateKey = event.stateKey // e.g. "_@user:server_deviceId_m.call" or user ID
        val sender = event.sender ?: return

        // DIAGNOSTIC: Log raw JSON to understand the structure
        println("[Call][DIAG] MSC3401 state raw keys=${raw.keys} stateKey=$stateKey sender=${sender.full}")
        println("[Call][DIAG] MSC3401 state raw JSON (first 500): ${raw.toString().take(500)}")

        // Determine the effective content — some serialization paths wrap in "content"
        val effectiveContent = (raw["content"] as? JsonObject) ?: raw

        // ── NEW per-device format (MSC4143 / modern ElementX) ──
        // Each device has its own state event with state key like "_@user:server_deviceId_m.call"
        // The content IS the membership itself: {application, call_id, device_id, foci_preferred, ...}
        // There is NO "memberships" array wrapper.
        val application = effectiveContent.string("application")
        val deviceIdDirect = effectiveContent.string("device_id")

        if (application != null && deviceIdDirect != null) {
            // This is the new per-device format — parse directly
            parseSingleMembershipFromContent(
                content = effectiveContent,
                roomId = roomId,
                stateKey = stateKey,
                sender = sender,
                originTimestamp = event.originTimestamp,
            )
            return
        }

        // ── OLD format with "memberships" array ──
        var memberships = effectiveContent["memberships"] as? kotlinx.serialization.json.JsonArray
        if (memberships == null) {
            memberships = raw["memberships"] as? kotlinx.serialization.json.JsonArray
        }

        if (memberships != null && memberships.isNotEmpty()) {
            val userId = stateKey.takeIf { it.isNotBlank() } ?: sender.full
            println("[Call] MSC3401 member state (old format): room=${roomId.full} user=$userId memberships=${memberships.size}")
            for (entry in memberships) {
                val obj = entry as? JsonObject ?: continue
                parseSingleMembershipFromContent(
                    content = obj,
                    roomId = roomId,
                    stateKey = stateKey,
                    sender = sender,
                    originTimestamp = event.originTimestamp,
                )
            }
            return
        }

        // Empty content = user left the call. Send disconnect.
        val userId = extractUserIdFromStateKey(stateKey) ?: sender.full
        val deviceIdFromKey = extractDeviceIdFromStateKey(stateKey)
        println("[Call] MSC3401 member state: empty content room=${roomId.full} user=$userId device=$deviceIdFromKey — treating as disconnect")
        val disconnectKey = if (deviceIdFromKey != null) "msc3401_${userId}_${deviceIdFromKey}" else "msc3401_${userId}"
        val memberEvent = de.connect2x.tammy.trixnityProposal.callRtc.MatrixRtcMemberEvent(
            roomId = roomId,
            slotId = MATRIX_RTC_DEFAULT_SLOT_ID,
            callId = "",
            stickyKey = disconnectKey,
            userId = net.folivo.trixnity.core.model.UserId(userId),
            deviceId = deviceIdFromKey,
            expiresAtMs = 0L,
            connected = false,
            isLocal = userId == localUserId?.full,
        )
        rtcService.applyMemberEvent(memberEvent)
    }

    /**
     * Parse a single membership from content JSON (works for both new per-device format
     * and individual entries in old "memberships" array).
     *
     * New format example:
     * ```json
     * {"application":"m.call","call_id":"","scope":"m.room","device_id":"fuUcOArGkK",
     *  "membershipID":"@user:server:deviceId","expires":14400000,
     *  "focus_active":{"type":"livekit",...},"foci_preferred":[...]}
     * ```
     */
    private fun parseSingleMembershipFromContent(
        content: JsonObject,
        roomId: net.folivo.trixnity.core.model.RoomId,
        stateKey: String,
        sender: net.folivo.trixnity.core.model.UserId,
        originTimestamp: Long?,
    ) {
        val deviceId = content.string("device_id")
        if (deviceId == null) {
            println("[Call] MSC3401: skipping membership without device_id in room=${roomId.full}")
            return
        }

        val userId = extractUserIdFromStateKey(stateKey) ?: sender.full
        val callId = content.string("call_id") ?: ""
        val membershipId = content.string("membershipID") ?: content.string("membership_id") ?: ""
        val scope = content.string("scope") ?: "m.room"
        val expiresMs = content.long("expires") ?: content.long("expires_ts") ?: 0L

        // Check for active focus — new format uses "focus_active" (object), old uses "foci_active" (array)
        val focusActive = content["focus_active"] as? JsonObject
        val fociPreferred = content["foci_preferred"] as? kotlinx.serialization.json.JsonArray
        val fociActive = content["foci_active"] as? kotlinx.serialization.json.JsonArray
        // Connected if has any focus info (active focus object OR preferred foci array OR old foci_active array)
        val hasTransport = focusActive != null || (fociPreferred != null && fociPreferred.isNotEmpty()) || (fociActive != null && fociActive.isNotEmpty())

        val stickyKey = "msc3401_${userId}_${deviceId}"
        val isLocal = userId == localUserId?.full && deviceId == localDeviceId

        val now = nowMs()
        val expiresAtMs = if (expiresMs > 0L) {
            (originTimestamp ?: now) + expiresMs
        } else {
            0L
        }

        // For the new per-device format (MSC4143), call_id is always empty string "".
        // The membershipID is a participant identifier, NOT a call ID.
        // We use a wildcard marker "*" that the participant filter recognizes as
        // "matches any active call in the room". This ensures participants from
        // per-device state events are included regardless of the slot's callId.
        val effectiveCallId = if (callId.isNotBlank()) callId else "*"

        println(
            "[Call] MSC3401 membership: user=$userId device=$deviceId callId='$callId' " +
                "effectiveCallId='$effectiveCallId' scope=$scope hasTransport=$hasTransport " +
                "expires=$expiresMs isLocal=$isLocal focusActive=${focusActive != null} " +
                "fociPreferred=${fociPreferred?.size ?: 0}"
        )

        val memberEvent = de.connect2x.tammy.trixnityProposal.callRtc.MatrixRtcMemberEvent(
            roomId = roomId,
            slotId = MATRIX_RTC_DEFAULT_SLOT_ID,
            callId = effectiveCallId,
            stickyKey = stickyKey,
            userId = net.folivo.trixnity.core.model.UserId(userId),
            deviceId = deviceId,
            expiresAtMs = expiresAtMs,
            connected = hasTransport,
            isLocal = isLocal,
        )
        rtcService.applyMemberEvent(memberEvent)
    }

    /**
     * Extract user ID from per-device state key format: "_@user:server_deviceId_m.call"
     * Returns null if the state key doesn't match this format.
     */
    private fun extractUserIdFromStateKey(stateKey: String): String? {
        // Format: _@user:server_deviceId_m.call
        if (!stateKey.startsWith("_@")) return null
        // Find the second underscore after the user ID (which contains ':')
        val afterPrefix = stateKey.substring(1) // "@user:server_deviceId_m.call"
        val colonIdx = afterPrefix.indexOf(':')
        if (colonIdx < 0) return null
        // Find the underscore after server name
        val afterColon = afterPrefix.substring(colonIdx + 1) // "server_deviceId_m.call"
        val underscoreIdx = afterColon.indexOf('_')
        if (underscoreIdx < 0) return null
        return afterPrefix.substring(0, colonIdx + 1 + underscoreIdx) // "@user:server"
    }

    /**
     * Extract device ID from per-device state key format: "_@user:server_deviceId_m.call"
     * Returns null if the state key doesn't match this format.
     */
    private fun extractDeviceIdFromStateKey(stateKey: String): String? {
        // Format: _@user:server_deviceId_m.call
        if (!stateKey.startsWith("_@")) return null
        val afterPrefix = stateKey.substring(1) // "@user:server_deviceId_m.call"
        val colonIdx = afterPrefix.indexOf(':')
        if (colonIdx < 0) return null
        val afterColon = afterPrefix.substring(colonIdx + 1) // "server_deviceId_m.call"
        val firstUnderscore = afterColon.indexOf('_')
        if (firstUnderscore < 0) return null
        val remainder = afterColon.substring(firstUnderscore + 1) // "deviceId_m.call"
        val lastUnderscore = remainder.lastIndexOf('_')
        return if (lastUnderscore > 0) remainder.substring(0, lastUnderscore) else remainder
    }

    private fun handleMemberEvent(event: ClientEvent.EphemeralEvent<*>, raw: JsonObject) {
        val roomId = event.roomId ?: return
        val sender = event.sender ?: return
        println("[Call] RTC member ephemeral room=${roomId.full} sender=${sender.full}")
        applyMemberRaw(
            roomId = roomId,
            senderUserId = sender.full,
            raw = raw,
            stateKey = null,
            originTimestampMs = null,
            unsignedAgeMs = null,
        )
    }

    private fun handleMemberMessageEvent(event: ClientEvent.RoomEvent.MessageEvent<*>, raw: JsonObject) {
        val roomId = event.roomId ?: return
        val sender = event.sender ?: return
        val eventType = (event.content as? UnknownEventContent)?.eventType ?: ""
        println("[Call] RTC member message room=${roomId.full} sender=${sender.full} type=$eventType")

        // MSC3401 call.member events can arrive as timeline MessageEvents too.
        // They use the "memberships" array format instead of the m.rtc.member format.
        if (eventType == MatrixRtcEventTypes.MSC3401_CALL_MEMBER || eventType == MatrixRtcEventTypes.CALL_MEMBER) {
            handleMsc3401MemberMessageEvent(event, raw)
            return
        }

        applyMemberRaw(
            roomId = roomId,
            senderUserId = sender.full,
            raw = raw,
            stateKey = null,
            originTimestampMs = event.originTimestamp,
            unsignedAgeMs = event.unsigned?.age,
        )
    }

    /**
     * Handles MSC3401 call.member events arriving as timeline MessageEvents.
     * Same parsing logic as handleMsc3401MemberStateEvent but adapted for MessageEvent.
     */
    private fun handleMsc3401MemberMessageEvent(
        event: ClientEvent.RoomEvent.MessageEvent<*>,
        raw: JsonObject,
    ) {
        val roomId = event.roomId ?: return
        val sender = event.sender ?: return
        val userId = sender.full

        // DIAGNOSTIC: Log raw JSON to understand the structure
        println("[Call][DIAG] MSC3401 message raw keys=${raw.keys} sender=${sender.full}")
        println("[Call][DIAG] MSC3401 message raw JSON (first 500): ${raw.toString().take(500)}")

        val effectiveContent = (raw["content"] as? JsonObject) ?: raw

        // ── NEW per-device format ──
        val application = effectiveContent.string("application")
        val deviceIdDirect = effectiveContent.string("device_id")
        if (application != null && deviceIdDirect != null) {
            // Use sender as stateKey for message events (no stateKey available)
            parseSingleMembershipFromContent(
                content = effectiveContent,
                roomId = roomId,
                stateKey = sender.full,
                sender = sender,
                originTimestamp = event.originTimestamp,
            )
            return
        }

        // ── OLD format with "memberships" array ──
        var memberships = effectiveContent["memberships"] as? kotlinx.serialization.json.JsonArray
        if (memberships == null) {
            memberships = raw["memberships"] as? kotlinx.serialization.json.JsonArray
        }

        if (memberships != null && memberships.isNotEmpty()) {
            println("[Call] MSC3401 member message (old format): room=${roomId.full} user=$userId memberships=${memberships.size}")
            for (entry in memberships) {
                val obj = entry as? JsonObject ?: continue
                parseSingleMembershipFromContent(
                    content = obj,
                    roomId = roomId,
                    stateKey = sender.full,
                    sender = sender,
                    originTimestamp = event.originTimestamp,
                )
            }
            return
        }

        // Empty content = disconnect
        println("[Call] MSC3401 member message: empty content room=${roomId.full} user=$userId — treating as disconnect")
        val disconnectKey = "msc3401_${userId}"
        val memberEvent = de.connect2x.tammy.trixnityProposal.callRtc.MatrixRtcMemberEvent(
            roomId = roomId,
            slotId = MATRIX_RTC_DEFAULT_SLOT_ID,
            callId = "",
            stickyKey = disconnectKey,
            userId = net.folivo.trixnity.core.model.UserId(userId),
            deviceId = null,
            expiresAtMs = 0L,
            connected = false,
            isLocal = userId == localUserId?.full,
        )
        rtcService.applyMemberEvent(memberEvent)
    }

    private fun handleMemberAccountDataEvent(
        event: ClientEvent.RoomAccountDataEvent<*>,
        raw: JsonObject,
    ) {
        val roomId = event.roomId
        val sender = localUserId ?: return
        println("[Call] RTC member account data room=${roomId.full} sender=${sender.full}")
        applyMemberRaw(
            roomId = roomId,
            senderUserId = sender.full,
            raw = raw,
            stateKey = event.key,
            originTimestampMs = null,
            unsignedAgeMs = null,
        )
    }

    private fun applyMemberRaw(
        roomId: RoomId,
        senderUserId: String,
        raw: JsonObject,
        stateKey: String?,
        originTimestampMs: Long?,
        unsignedAgeMs: Long?,
    ) {
        val memberEvent = MatrixRtcEventParser.parseMemberEvent(
            roomId = roomId,
            senderUserId = senderUserId,
            content = raw,
            stateKey = stateKey,
            localUserId = localUserId,
            localDeviceId = localDeviceId,
            nowMs = nowMs,
            originTimestampMs = originTimestampMs,
            unsignedAgeMs = unsignedAgeMs,
        ) ?: return
        rtcService.applyMemberEvent(memberEvent)
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

    private companion object
}

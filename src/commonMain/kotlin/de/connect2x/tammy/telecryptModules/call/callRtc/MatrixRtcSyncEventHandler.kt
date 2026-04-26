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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.folivo.trixnity.client.store.AccountStore
import net.folivo.trixnity.clientserverapi.client.SyncApiClient
import net.folivo.trixnity.core.EventHandler
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.UserId
import net.folivo.trixnity.core.model.events.ClientEvent
import net.folivo.trixnity.core.model.events.UnknownEventContent
import net.folivo.trixnity.core.subscribeEachEventAsFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.runCatching

class MatrixRtcSyncEventHandler(
    private val syncApi: SyncApiClient,
    private val rtcService: MatrixRtcService,
    private val accountStore: AccountStore,
    private val nowMs: () -> Long = ::currentTimeMillis,
) : EventHandler {
    private val started = AtomicBoolean(false)
    private val loggedFirstEvent = AtomicBoolean(false)
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
                    // Log RTC-related types always; log others only occasionally to avoid spam
                    if (t.contains("rtc") || t.contains("call") || t.contains("msc4143") || t.contains("msc4354")) {
                        println("[Call][DIAG] Sync event type=$t class=${event::class.simpleName}")
                    }
                }
                handleEvent(event)
            }
            .launchIn(scope)
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
        val stateKey = event.stateKey // user ID
        val sender = event.sender ?: return
        val userId = stateKey.takeIf { it.isNotBlank() } ?: sender.full

        val memberships = raw["memberships"] as? kotlinx.serialization.json.JsonArray
        if (memberships == null || memberships.isEmpty()) {
            // Empty memberships = user left the call. Send disconnect for this user.
            println("[Call] MSC3401 member state: empty memberships room=${roomId.full} user=$userId — treating as disconnect")
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
            return
        }

        println("[Call] MSC3401 member state: room=${roomId.full} user=$userId memberships=${memberships.size}")

        for (entry in memberships) {
            val obj = entry as? JsonObject ?: continue
            val deviceId = obj.string("device_id") ?: continue
            val callId = obj.string("call_id") ?: ""
            val membershipId = obj.string("membershipID") ?: obj.string("membership_id") ?: ""
            val scope = obj.string("scope") ?: "m.room"
            val expiresMs = obj.long("expires") ?: obj.long("expires_ts") ?: 0L

            // Check if this membership has active foci (transport info)
            val fociActive = obj["foci_active"] as? kotlinx.serialization.json.JsonArray
            val hasTransport = fociActive != null && fociActive.isNotEmpty()

            val stickyKey = "msc3401_${userId}_${deviceId}"
            val isLocal = userId == localUserId?.full && deviceId == localDeviceId

            // Compute expiration time
            val now = nowMs()
            val originTs = event.originTimestamp
            val expiresAtMs = if (expiresMs > 0L) {
                (originTs ?: now) + expiresMs
            } else {
                0L
            }

            println(
                "[Call] MSC3401 membership: user=$userId device=$deviceId callId=$callId " +
                    "scope=$scope hasTransport=$hasTransport expires=$expiresMs isLocal=$isLocal"
            )

            val memberEvent = de.connect2x.tammy.trixnityProposal.callRtc.MatrixRtcMemberEvent(
                roomId = roomId,
                slotId = MATRIX_RTC_DEFAULT_SLOT_ID,
                callId = callId.ifBlank { membershipId.ifBlank { "msc3401_$deviceId" } },
                stickyKey = stickyKey,
                userId = net.folivo.trixnity.core.model.UserId(userId),
                deviceId = deviceId,
                expiresAtMs = expiresAtMs,
                connected = hasTransport,
                isLocal = isLocal,
            )
            rtcService.applyMemberEvent(memberEvent)
        }
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
        println("[Call] RTC member message room=${roomId.full} sender=${sender.full}")
        applyMemberRaw(
            roomId = roomId,
            senderUserId = sender.full,
            raw = raw,
            stateKey = null,
            originTimestampMs = event.originTimestamp,
            unsignedAgeMs = event.unsigned?.age,
        )
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

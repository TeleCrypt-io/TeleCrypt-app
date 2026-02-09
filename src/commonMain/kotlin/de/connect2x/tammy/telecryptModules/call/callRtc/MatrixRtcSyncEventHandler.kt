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

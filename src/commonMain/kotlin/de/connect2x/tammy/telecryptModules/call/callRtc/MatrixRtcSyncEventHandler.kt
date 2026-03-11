package de.connect2x.tammy.telecryptModules.call.callRtc

import de.connect2x.tammy.trixnityProposal.callRtc.MatrixRtcEventParser
import de.connect2x.tammy.trixnityProposal.callRtc.MatrixRtcService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import net.folivo.trixnity.client.store.AccountStore
import net.folivo.trixnity.clientserverapi.client.SyncApiClient
import net.folivo.trixnity.core.EventHandler
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.UserId
import net.folivo.trixnity.core.model.events.ClientEvent
import net.folivo.trixnity.core.subscribeEachEventAsFlow
import java.util.concurrent.atomic.AtomicBoolean

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
                val rtcEvent = MatrixRtcIncomingEventMapper.from(event) ?: return@onEach
                if (loggedFirstEvent.compareAndSet(false, true)) {
                    val eventClass = event::class.simpleName
                    val contentClass = event.content::class.simpleName
                    println("[Call] First RTC sync event class=$eventClass content=$contentClass type=${rtcEvent.normalizedType}")
                }
                handleEvent(rtcEvent)
            }
            .launchIn(scope)
    }

    private fun handleEvent(event: MatrixRtcIncomingEvent) {
        when (event) {
            is MatrixRtcIncomingEvent.SlotState -> {
                println("[Call] RTC event type=${event.normalizedType} class=StateEvent")
                handleSlotStateEvent(event)
            }
            is MatrixRtcIncomingEvent.Member -> {
                println("[Call] RTC event type=${event.normalizedType} class=${event.kind}")
                handleMemberEvent(event)
            }
        }
    }

    private fun handleSlotStateEvent(
        event: MatrixRtcIncomingEvent.SlotState,
    ) {
        val slotEvent = MatrixRtcEventParser.parseSlotEvent(event.roomId, event.slotId, event.content)
        println("[Call] RTC slot update room=${event.roomId.full} slot=${event.slotId} callId=${slotEvent.callId}")
        rtcService.applySlotEvent(slotEvent)
    }

    private fun handleMemberEvent(event: MatrixRtcIncomingEvent.Member) {
        val senderUserId = event.senderUserId ?: localUserId?.full ?: return
        println("[Call] RTC member ${event.kind.name.lowercase()} room=${event.roomId.full} sender=$senderUserId")
        applyMemberContent(
            roomId = event.roomId,
            senderUserId = senderUserId,
            content = event.content,
            stateKey = event.stateKey,
            originTimestampMs = event.originTimestampMs,
            unsignedAgeMs = event.unsignedAgeMs,
        )
    }

    private fun applyMemberContent(
        roomId: RoomId,
        senderUserId: String,
        content: de.connect2x.tammy.trixnityProposal.callRtc.MatrixRtcMemberContentSource,
        stateKey: String?,
        originTimestampMs: Long?,
        unsignedAgeMs: Long?,
    ) {
        val memberEvent = MatrixRtcEventParser.parseMemberEvent(
            roomId = roomId,
            senderUserId = senderUserId,
            content = content,
            stateKey = stateKey,
            localUserId = localUserId,
            localDeviceId = localDeviceId,
            nowMs = nowMs,
            originTimestampMs = originTimestampMs,
            unsignedAgeMs = unsignedAgeMs,
        ) ?: return
        rtcService.applyMemberEvent(memberEvent)
    }

    private companion object
}

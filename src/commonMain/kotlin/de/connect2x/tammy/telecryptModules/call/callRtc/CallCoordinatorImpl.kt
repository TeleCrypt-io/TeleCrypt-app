package de.connect2x.tammy.telecryptModules.call.callRtc

import de.connect2x.tammy.telecryptModules.call.CallMode
import de.connect2x.tammy.telecryptModules.call.buildTelecryptCallDeepLink
import de.connect2x.tammy.telecryptModules.call.callBackend.CallLauncher
import de.connect2x.tammy.telecryptModules.call.callBackend.buildElementCallUrl
import de.connect2x.tammy.telecryptModules.call.callBackend.resolveElementCallSession
import de.connect2x.tammy.telecryptModules.call.callBackend.resolveHomeserverUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.encodeURLPath
import io.ktor.http.isSuccess
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import net.folivo.trixnity.core.model.events.UnknownEventContent
import net.folivo.trixnity.client.MatrixClient
import net.folivo.trixnity.core.model.RoomId
import kotlin.random.Random

class CallCoordinatorImpl(
    private val callLauncher: CallLauncher,
    private val watcher: MatrixRtcWatcher,
) : CallCoordinator {
    private data class ActiveCallSession(
        val callId: String,
        val slotId: String,
        val stickyKey: String,
        val rtcTransports: List<MatrixRtcTransport>,
        val refreshJob: Job,
        val memberSendMode: MemberSendMode,
    )

    private sealed class MemberSendMode {
        data class Sticky(val eventType: String) : MemberSendMode()
        object StateFallback : MemberSendMode()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeCalls = mutableMapOf<RoomId, ActiveCallSession>()
    private var cachedTransports: List<MatrixRtcTransport> = emptyList()
    private var cachedTransportsAtMs: Long = 0L
    private val nowMs: () -> Long = ::currentTimeMillis
    private val forceStateFallback: Boolean = true

    override suspend fun startCall(
        matrixClient: MatrixClient,
        roomId: RoomId,
        roomName: String,
        isDirect: Boolean,
        mode: CallMode,
    ): CallStartResult {
        val session = resolveElementCallSession(matrixClient)
            ?: return CallStartResult(
                ok = false,
                userMessage = "Call unavailable. Please re-login.",
            )
        val callId = generateCallId()
        val slotId = MATRIX_RTC_DEFAULT_SLOT_ID
        stopActiveSession(matrixClient, roomId, endForAll = false)
        publishSlot(matrixClient, roomId, slotId, callId)
        val transports = resolveRtcTransports(matrixClient)
        startMemberRefresh(matrixClient, roomId, slotId, callId, transports)
        watcher.ackIncoming(roomId, callId)
        val displayName = resolveDisplayName(matrixClient, session.displayName)
        val homeserverUrl = session.homeserver.ifBlank {
            resolveHomeserverUrl(matrixClient).ifBlank { "" }
        }.ifBlank { null }
        val intent = if (isDirect) "start_call_dm" else "start_call"
        val sendNotificationType = if (isDirect) "ring" else "notification"
        val waitForCallPickup = isDirect
        
        val callUrl = buildElementCallUrl(
            roomId.full,
            roomName,
            displayName,
            intent = intent,
            sendNotificationType = sendNotificationType,
            skipLobby = false, // Changed to false to prevent camera/mic flickering during init
            waitForCallPickup = waitForCallPickup,
            homeserver = homeserverUrl,
            callMode = mode.name.lowercase(),
            autoJoin = false, // Changed to false for stability
            disableVideo = (mode == CallMode.AUDIO),
        )
        println("[Call] Launching Element Call: $callUrl")
        println(
            "[Call] Session user=${session.userId} device=${session.deviceId} " +
                "hs=${session.homeserver}"
        )
        callLauncher.joinByUrlWithSession(callUrl, session)
        val deepLink = buildTelecryptCallDeepLink(roomId.full, roomName, mode)
        return CallStartResult(ok = true, deepLink = deepLink)
    }

    override suspend fun joinCall(
        matrixClient: MatrixClient,
        roomId: RoomId,
        roomName: String,
        mode: CallMode,
    ): CallStartResult {
        val session = resolveElementCallSession(matrixClient)
            ?: return CallStartResult(
                ok = false,
                userMessage = "Call unavailable. Please re-login.",
            )
        val state = watcher.roomState(roomId).value
        val sessionState = state.session
            ?: return CallStartResult(
                ok = false,
                userMessage = "No active call in this room.",
            )
        val callId = sessionState.callId
        val slotId = sessionState.slotId.ifBlank { MATRIX_RTC_DEFAULT_SLOT_ID }
        stopActiveSession(matrixClient, roomId, endForAll = false)
        val transports = resolveRtcTransports(matrixClient)
        startMemberRefresh(matrixClient, roomId, slotId, callId, transports)
        watcher.ackIncoming(roomId, callId)
        val displayName = resolveDisplayName(matrixClient, session.displayName)
        val homeserverUrl = session.homeserver.ifBlank {
            resolveHomeserverUrl(matrixClient).ifBlank { "" }
        }.ifBlank { null }
        
        // Fix: for incoming calls, NEVER skip lobby or auto-join.
        // This gives the user time to see the lobby and press Join in the browser.
        val callUrl = buildElementCallUrl(
            roomId.full,
            roomName,
            displayName,
            intent = "join_existing",
            sendNotificationType = null,
            skipLobby = false, 
            waitForCallPickup = false,
            homeserver = homeserverUrl,
            callMode = mode.name.lowercase(),
            autoJoin = false,
            disableVideo = (mode == CallMode.AUDIO),
        )
        println("[Call] Joining Element Call: $callUrl")
        callLauncher.joinByUrlWithSession(callUrl, session)
        val deepLink = buildTelecryptCallDeepLink(roomId.full, roomName, mode)
        return CallStartResult(ok = true, deepLink = deepLink)
    }

    override suspend fun leaveCall(
        matrixClient: MatrixClient,
        roomId: RoomId,
        endForAll: Boolean,
    ): Boolean {
        return stopActiveSession(matrixClient, roomId, endForAll)
    }

    override fun declineCall(roomId: RoomId, callId: String) {
        watcher.ackIncoming(roomId, callId)
    }

    private suspend fun stopActiveSession(
        matrixClient: MatrixClient,
        roomId: RoomId,
        endForAll: Boolean,
    ): Boolean {
        val session = activeCalls.remove(roomId) ?: return false
        session.refreshJob.cancel()
        publishMember(
            matrixClient,
            roomId,
            session.slotId,
            session.callId,
            session.stickyKey,
            session.rtcTransports,
            disconnected = true,
            sendMode = session.memberSendMode,
        )
        if (endForAll) {
            publishSlot(matrixClient, roomId, session.slotId, null)
        }
        return true
    }

    private suspend fun startMemberRefresh(
        matrixClient: MatrixClient,
        roomId: RoomId,
        slotId: String,
        callId: String,
        rtcTransports: List<MatrixRtcTransport>,
    ) {
        val stickyKey = buildStickyKey(matrixClient)
        val sendMode = publishMember(
            matrixClient,
            roomId,
            slotId,
            callId,
            stickyKey,
            rtcTransports,
            disconnected = false,
            sendMode = null,
        )
        val refreshJob = scope.launch {
            while (isActive) {
                delay(MEMBER_REFRESH_MS)
                publishMember(
                    matrixClient,
                    roomId,
                    slotId,
                    callId,
                    stickyKey,
                    rtcTransports,
                    disconnected = false,
                    sendMode = sendMode,
                )
            }
        }
        activeCalls[roomId] = ActiveCallSession(
            callId = callId,
            slotId = slotId,
            stickyKey = stickyKey,
            rtcTransports = rtcTransports,
            refreshJob = refreshJob,
            memberSendMode = sendMode,
        )
    }

    private suspend fun publishSlot(
        matrixClient: MatrixClient,
        roomId: RoomId,
        slotId: String,
        callId: String?,
    ) {
        val content = callId?.let { buildSlotContent(it) } ?: JsonObject(emptyMap())
        val event = UnknownEventContent(content, MatrixRtcEventTypes.SLOT)
        runCatching {
            matrixClient.api.room.sendStateEvent(roomId, event, slotId)
        }.onFailure { error ->
            println("[Call] Failed to publish slot: ${error.message}")
        }
    }

    private suspend fun publishMember(
        matrixClient: MatrixClient,
        roomId: RoomId,
        slotId: String,
        callId: String,
        stickyKey: String,
        rtcTransports: List<MatrixRtcTransport>,
        disconnected: Boolean,
        sendMode: MemberSendMode?,
    ): MemberSendMode {
        val content = buildMemberContent(
            matrixClient = matrixClient,
            slotId = slotId,
            callId = callId,
            stickyKey = stickyKey,
            rtcTransports = rtcTransports,
            disconnected = disconnected,
        )
        val mode = sendMode ?: resolveMemberSendMode(matrixClient, roomId, content)
        if (sendMode == null) {
            if (mode is MemberSendMode.Sticky && forceStateFallback) {
                sendMemberStateFallback(matrixClient, roomId, stickyKey, content)
            }
            return mode
        }
        when (mode) {
            is MemberSendMode.Sticky -> {
                val sent = sendStickyMemberEvent(
                    matrixClient,
                    roomId,
                    mode.eventType,
                    content,
                    MEMBER_TTL_MS,
                )
                if (!sent) {
                    println("[Call] Failed to publish sticky member event.")
                } else if (forceStateFallback) {
                    sendMemberStateFallback(matrixClient, roomId, stickyKey, content)
                }
            }
            MemberSendMode.StateFallback -> {
                sendMemberStateFallback(matrixClient, roomId, stickyKey, content)
            }
        }
        return mode
    }

    private suspend fun resolveMemberSendMode(
        matrixClient: MatrixClient,
        roomId: RoomId,
        content: JsonObject,
    ): MemberSendMode {
        if (sendStickyMemberEvent(
                matrixClient,
                roomId,
                MatrixRtcEventTypes.MEMBER,
                content,
                MEMBER_TTL_MS,
            )
        ) {
            println("[Call] RTC member send: sticky ${MatrixRtcEventTypes.MEMBER}")
            return MemberSendMode.Sticky(MatrixRtcEventTypes.MEMBER)
        }
        if (sendStickyMemberEvent(
                matrixClient,
                roomId,
                MatrixRtcEventTypes.UNSTABLE_MEMBER,
                content,
                MEMBER_TTL_MS,
            )
        ) {
            println("[Call] RTC member send: sticky ${MatrixRtcEventTypes.UNSTABLE_MEMBER}")
            return MemberSendMode.Sticky(MatrixRtcEventTypes.UNSTABLE_MEMBER)
        }
        println("[Call] RTC member send: state fallback")
        sendMemberStateFallback(matrixClient, roomId, content.string("sticky_key"), content)
        return MemberSendMode.StateFallback
    }

    private suspend fun sendStickyMemberEvent(
        matrixClient: MatrixClient,
        roomId: RoomId,
        eventType: String,
        content: JsonObject,
        stickyDurationMs: Long,
    ): Boolean {
        val baseUrl = matrixClient.api.baseClient.baseUrl ?: return false
        val url = buildStickySendUrl(baseUrl, roomId.full, eventType, buildTxnId(), stickyDurationMs)
        val body = matrixClient.api.json.encodeToString(JsonObject.serializer(), content)
        val response = runCatching {
            matrixClient.api.baseClient.baseClient.put(url) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }.getOrNull() ?: return false
        return response.status.isSuccess()
    }

    private suspend fun sendMemberStateFallback(
        matrixClient: MatrixClient,
        roomId: RoomId,
        stickyKey: String?,
        content: JsonObject,
    ) {
        val stateKey = stickyKey?.takeIf { it.isNotBlank() } ?: STICKY_KEY_PREFIX
        val event = UnknownEventContent(content, MatrixRtcEventTypes.MEMBER)
        runCatching {
            matrixClient.api.room.sendStateEvent(roomId, event, stateKey)
        }.onFailure { error ->
            println("[Call] Failed to publish member: ${error.message}")
        }
    }

    private fun buildSlotContent(callId: String): JsonObject {
        val callObject = buildJsonObject {
            put("id", JsonPrimitive(callId))
        }
        val application = buildJsonObject {
            put("type", JsonPrimitive("m.call"))
            put("url", JsonPrimitive(ELEMENT_CALL_BASE_URL))
            put("m.call", callObject)
        }
        return buildJsonObject {
            put("application", application)
        }
    }

    private fun buildMemberContent(
        matrixClient: MatrixClient,
        slotId: String,
        callId: String,
        stickyKey: String,
        rtcTransports: List<MatrixRtcTransport>,
        disconnected: Boolean,
    ): JsonObject {
        val userId = matrixClient.userId.full
        val deviceId = matrixClient.deviceId
        val expiresAtMs = nowMs() + MEMBER_TTL_MS
        val member = buildJsonObject {
            val deviceValue = deviceId.takeIf { it.isNotBlank() }
            if (deviceValue != null) {
                put("id", JsonPrimitive("device:$deviceValue"))
                put("claimed_device_id", JsonPrimitive(deviceValue))
            }
            put("claimed_user_id", JsonPrimitive(userId))
        }
        val callObject = buildJsonObject {
            put("id", JsonPrimitive(callId))
        }
        val application = buildJsonObject {
            put("type", JsonPrimitive("m.call"))
            put("m.call", callObject)
        }
        return buildJsonObject {
            put("slot_id", JsonPrimitive(slotId))
            put("application", application)
            put("member", member)
            put("rtc_transports", encodeRtcTransports(rtcTransports))
            put("sticky_key", JsonPrimitive(stickyKey))
            put("expires_ts", JsonPrimitive(expiresAtMs))
            if (disconnected) {
                put("disconnected", JsonPrimitive(true))
            }
        }
    }

    private fun buildStickyKey(matrixClient: MatrixClient): String {
        val device = matrixClient.deviceId.trim()
        return if (device.isNotEmpty()) {
            "$STICKY_KEY_PREFIX-$device"
        } else {
            "$STICKY_KEY_PREFIX-${generateCallId()}"
        }
    }

    private fun generateCallId(): String {
        val bytes = ByteArray(16) { Random.nextInt(0, 256).toByte() }
        bytes[6] = (bytes[6].toInt() and 0x0F or 0x40).toByte()
        bytes[8] = (bytes[8].toInt() and 0x3F or 0x80).toByte()
        return bytes.joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }
            .replaceFirst(
                "(.{8})(.{4})(.{4})(.{4})(.{12})".toRegex(),
                "$1-$2-$3-$4-$5",
            )
    }

    private fun buildTxnId(): String {
        val suffix = generateCallId()
        return "rtc-$suffix"
    }

    private fun buildStickySendUrl(
        baseUrl: Url,
        roomId: String,
        eventType: String,
        txnId: String,
        stickyDurationMs: Long,
    ): String {
        val base = baseUrl.toString().trimEnd('/')
        val encodedRoomId = roomId.encodeURLPath()
        val encodedEventType = eventType.encodeURLPath()
        val encodedTxnId = txnId.encodeURLPath()
        val path =
            "/_matrix/client/v3/rooms/$encodedRoomId/send/$encodedEventType/$encodedTxnId"
        return "$base$path?org.matrix.msc4354.sticky_duration_ms=$stickyDurationMs"
    }

    private suspend fun resolveRtcTransports(matrixClient: MatrixClient): List<MatrixRtcTransport> {
        val now = nowMs()
        if (cachedTransports.isNotEmpty() && now - cachedTransportsAtMs < TRANSPORT_CACHE_MS) {
            return cachedTransports
        }
        val transports = runCatching { discoverRtcTransports(matrixClient) }
            .getOrDefault(emptyList())
        if (transports.isNotEmpty()) {
            cachedTransports = transports
            cachedTransportsAtMs = now
        }
        return transports
    }

    private fun encodeRtcTransports(transports: List<MatrixRtcTransport>): JsonArray {
        if (transports.isEmpty()) {
            return JsonArray(emptyList())
        }
        return buildJsonArray {
            transports.forEach { transport ->
                add(
                    buildJsonObject {
                        put("type", JsonPrimitive(transport.type))
                        transport.uri?.let { put("uri", JsonPrimitive(it)) }
                        if (transport.params.isNotEmpty()) {
                            put("params", transport.params)
                        }
                    }
                )
            }
        }
    }

    private fun JsonObject.string(key: String): String? {
        return (get(key) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    }
}

private fun resolveDisplayName(matrixClient: MatrixClient, sessionName: String): String {
    val displayName = sessionName.trim().ifEmpty {
        matrixClient.displayName.value?.trim().orEmpty()
    }
    return displayName.ifEmpty { matrixClient.userId.full }
}

private const val MEMBER_TTL_MS = 20_000L
private const val MEMBER_REFRESH_MS = 10_000L
private const val TRANSPORT_CACHE_MS = 5 * 60_000L
private const val STICKY_KEY_PREFIX = "telecrypt"
private const val ELEMENT_CALL_BASE_URL = "https://call.element.io"

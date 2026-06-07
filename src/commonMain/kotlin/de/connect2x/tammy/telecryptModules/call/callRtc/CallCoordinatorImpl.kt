package de.connect2x.tammy.telecryptModules.call.callRtc

import de.connect2x.tammy.telecryptModules.call.callLog

import de.connect2x.tammy.telecryptModules.call.CallMode
import de.connect2x.tammy.telecryptModules.call.buildTelecryptCallDeepLink
import de.connect2x.tammy.telecryptModules.call.callBackend.CallLauncher
import de.connect2x.tammy.telecryptModules.call.callBackend.buildElementCallUrl
import de.connect2x.tammy.telecryptModules.call.callBackend.buildElementCallWidgetUrl
import de.connect2x.tammy.telecryptModules.call.callBackend.resolveElementCallSession
import de.connect2x.tammy.telecryptModules.call.callBackend.resolveHomeserverUrl
import de.connect2x.tammy.telecryptModules.call.widgetBridge.BridgeForwardingRegistry
import de.connect2x.tammy.telecryptModules.call.widgetBridge.WidgetBridgeManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import net.folivo.trixnity.core.model.events.UnknownEventContent
import net.folivo.trixnity.client.MatrixClient
import net.folivo.trixnity.core.model.RoomId
import kotlin.random.Random

/**
 * Call coordinator that manages the lifecycle of calls.
 *
 * IMPORTANT DESIGN DECISION (dual-client fix):
 * TeleCrypt does NOT publish m.rtc.member events. Only m.rtc.slot events are published
 * to open/close the call session in the room. Element Call (opened in the browser) is
 * the sole Matrix client that handles WebRTC media and publishes its own member events
 * with its own device_id. If TeleCrypt also published member events, remote peers would
 * see TWO participants (TeleCrypt's signaling ghost + Element Call's real media peer),
 * causing video/audio to never connect.
 *
 * The slot event tells the room "a call is happening"; Element Call's member events
 * tell the room "this device is participating with media".
 */
class CallCoordinatorImpl(
    private val callLauncher: CallLauncher,
    private val watcher: MatrixRtcWatcher,
    private val widgetBridgeManager: WidgetBridgeManager,
    private val bridgeRegistry: BridgeForwardingRegistry,
) : CallCoordinator {
    /**
     * Tracks active call sessions per room. Only stores slot-level info now —
     * member refresh has been removed (Element Call handles its own membership).
     *
     * [bridgeSession] is non-null when the desktop widget bridge is active for this
     * room — it must be closed when the call ends to release the WS server / port.
     */
    private data class ActiveCallSession(
        val callId: String,
        val slotId: String,
        val bridgeSession: WidgetBridgeManager.BridgeSession? = null,
    )

    private val activeCalls = mutableMapOf<RoomId, ActiveCallSession>()

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

        // Clear any stale ghost membership state events left over from previous
        // sessions (crashes, killed-with-active-call, MSC4140 delayed events that
        // never fired). Without this, Element Call sees the user already "in"
        // the call and the room contains zombie participants forever.
        clearOwnGhostMembership(matrixClient, roomId)

        // Publish slot event to signal "a call is active in this room".
        // We do NOT publish member events — Element Call handles that.
        publishSlot(matrixClient, roomId, slotId, callId)

        watcher.ackIncoming(roomId, callId)
        val displayName = resolveDisplayName(matrixClient, session.displayName)
        // Use the server name (from room ID) as the homeserver parameter for Element Call.
        // Element Call fetches .well-known/matrix/client from this domain to discover
        // LiveKit transports (rtc_foci). The server name (e.g., "antidote.network") has
        // the correct .well-known, while the homeserver URL (e.g., "cht.antidote.network")
        // may not. Element Call will then use the base_url from .well-known for API calls.
        val serverName = roomId.full.substringAfter(":", "").trim()
        val homeserverUrl = if (serverName.isNotBlank()) {
            "https://$serverName"
        } else {
            session.homeserver.ifBlank {
                resolveHomeserverUrl(matrixClient).ifBlank { "" }
            }.ifBlank { null }
        }
        // Element Call v0.19.1 only recognizes: "start_call", "join_existing", "room"
        // "start_call_dm" is parsed as "unknown" and causes skipLobby to be ignored.
        val intent = "start_call"
        val sendNotificationType = if (isDirect) "ring" else "notification"
        val waitForCallPickup = isDirect

        val standaloneUrl = buildElementCallUrl(
            roomId.full,
            roomName,
            displayName,
            intent = intent,
            sendNotificationType = sendNotificationType,
            skipLobby = true,
            waitForCallPickup = waitForCallPickup,
            homeserver = homeserverUrl,
            hideHeader = true,
            disableVideo = (mode == CallMode.AUDIO),
            session = session,
        )

        val bridgeSession = startWidgetBridge(
            matrixClient = matrixClient,
            roomId = roomId,
            roomName = roomName,
            displayName = displayName,
            session = session,
            homeserverUrl = homeserverUrl,
            intent = intent,
            mode = mode,
        )
        val urlToOpen = bridgeSession?.hostUrl ?: standaloneUrl

        activeCalls[roomId] = ActiveCallSession(
            callId = callId,
            slotId = slotId,
            bridgeSession = bridgeSession,
        )
        if (bridgeSession != null) {
            bridgeRegistry.register(matrixClient.userId, roomId, bridgeSession)
        }

        callLog("[Call] Launching Element Call (widget=${bridgeSession != null}): $urlToOpen")
        callLog(
            "[Call] Session user=${session.userId} device=${session.deviceId} " +
                "hs=${session.homeserver}"
        )
        if (bridgeSession != null) {
            callLauncher.joinByWidgetUrl(urlToOpen)
        } else {
            callLauncher.joinByUrlWithSession(urlToOpen, session)
        }
        val deepLink = buildTelecryptCallDeepLink(roomId.full, roomName, mode)
        return CallStartResult(ok = true, deepLink = deepLink)
    }

    /**
     * Removes any leftover `m.rtc.member` / `org.matrix.msc3401.call.member`
     * state events whose `sender` is the local user. These accumulate when
     * Element Call's MSC4140 delayed "leave" tombstones don't fire (we used
     * to fake-ack them; even with the new flush-on-teardown logic, prior
     * crashed sessions may still have ghosts on the server).
     *
     * Without this, when the user joins a call EC reads the room state, sees
     * its own user already "participating", and the room shows duplicate
     * ghost participants. Worse, EC may refuse to publish a fresh membership.
     */
    private suspend fun clearOwnGhostMembership(matrixClient: MatrixClient, roomId: RoomId) {
        val localUser = matrixClient.userId
        val memberTypes = setOf(
            MatrixRtcEventTypes.MEMBER,                 // m.rtc.member
            MatrixRtcEventTypes.UNSTABLE_MEMBER,        // org.matrix.msc4143.rtc.member
            MatrixRtcEventTypes.MSC3401_CALL_MEMBER,    // org.matrix.msc3401.call.member
            "m.call.member",
        )
        val state = runCatching { matrixClient.api.room.getState(roomId).getOrThrow() }
            .onFailure { callLog("[Call] clearOwnGhostMembership: getState failed: ${it.message}") }
            .getOrNull() ?: return

        var cleared = 0
        for (evt in state) {
            if (evt.sender != localUser) continue
            val content = evt.content
            // Resolve the event type string regardless of whether the content
            // was parsed into a typed Trixnity model or is still raw Unknown.
            val typeStr: String = if (content is UnknownEventContent) {
                content.eventType
            } else {
                // Trixnity strips the wire `type` for typed contents — fall back
                // to class-name heuristics. For MSC3401/MSC4143 we always get
                // UnknownEventContent (no model), so this branch is rare.
                content::class.simpleName.orEmpty()
            }
            if (typeStr !in memberTypes) continue
            // Skip if already cleared (empty raw content).
            if (content is UnknownEventContent && content.raw == JsonObject(emptyMap())) continue
            val stateKey = evt.stateKey
            val ok = runCatching {
                matrixClient.api.room.sendStateEvent(
                    roomId,
                    UnknownEventContent(JsonObject(emptyMap()), typeStr),
                    stateKey,
                )
                true
            }.onFailure {
                callLog("[Call] clearOwnGhostMembership: sendStateEvent type=$typeStr stateKey=$stateKey failed: ${it.message}")
            }.getOrDefault(false)
            if (ok) {
                cleared++
                callLog("[Call] clearOwnGhostMembership: cleared type=$typeStr stateKey=$stateKey")
            }
        }
        if (cleared > 0) {
            callLog("[Call] clearOwnGhostMembership: cleared $cleared stale ghost member state event(s) for ${localUser.full} in ${roomId.full}")
        } else {
            callLog("[Call] clearOwnGhostMembership: no stale ghosts found for ${localUser.full} in ${roomId.full}")
        }
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

        // Clear our own stale ghost members before EC reads room state.
        clearOwnGhostMembership(matrixClient, roomId)

        // No member publishing — Element Call handles its own membership.
        watcher.ackIncoming(roomId, callId)
        val displayName = resolveDisplayName(matrixClient, session.displayName)
        // Use server name for homeserver param (same logic as startCall)
        val serverName = roomId.full.substringAfter(":", "").trim()
        val homeserverUrl = if (serverName.isNotBlank()) {
            "https://$serverName"
        } else {
            session.homeserver.ifBlank {
                resolveHomeserverUrl(matrixClient).ifBlank { "" }
            }.ifBlank { null }
        }

        val standaloneUrl = buildElementCallUrl(
            roomId.full,
            roomName,
            displayName,
            intent = "join_existing",
            sendNotificationType = null,
            skipLobby = true,
            waitForCallPickup = false,
            homeserver = homeserverUrl,
            hideHeader = true,
            disableVideo = (mode == CallMode.AUDIO),
            session = session,
        )

        val bridgeSession = startWidgetBridge(
            matrixClient = matrixClient,
            roomId = roomId,
            roomName = roomName,
            displayName = displayName,
            session = session,
            homeserverUrl = homeserverUrl,
            intent = "join_existing",
            mode = mode,
        )
        val urlToOpen = bridgeSession?.hostUrl ?: standaloneUrl

        activeCalls[roomId] = ActiveCallSession(
            callId = callId,
            slotId = slotId,
            bridgeSession = bridgeSession,
        )
        if (bridgeSession != null) {
            bridgeRegistry.register(matrixClient.userId, roomId, bridgeSession)
        }

        callLog("[Call] Joining Element Call (widget=${bridgeSession != null}): $urlToOpen")
        if (bridgeSession != null) {
            callLauncher.joinByWidgetUrl(urlToOpen)
        } else {
            callLauncher.joinByUrlWithSession(urlToOpen, session)
        }
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
        // Unregister from forwarding registry BEFORE closing the bridge.
        if (session.bridgeSession != null) {
            bridgeRegistry.unregister(matrixClient.userId, roomId)
        }
        // Tear down widget bridge (closes WS server, releases port).
        runCatching { session.bridgeSession?.close() }
            .onFailure { callLog("[Call] bridgeSession.close() failed: ${it.message}") }
        // No member disconnect event needed — Element Call sends its own disconnect
        // when the browser tab/window closes.
        if (endForAll) {
            // Close the slot to signal "call ended for everyone"
            publishSlot(matrixClient, roomId, session.slotId, null)
        }
        return true
    }

    /**
     * Поднимает widget‑bridge (если платформа поддерживает) и возвращает [BridgeSession].
     * На non‑desktop платформах (NoopWidgetBridgeManager) вернёт `null`, и caller
     * откатится на обычный standalone‑URL — это фоллбек, чтобы не сломать существующее
     * поведение android/web.
     */
    private suspend fun startWidgetBridge(
        matrixClient: MatrixClient,
        roomId: RoomId,
        roomName: String,
        displayName: String,
        session: de.connect2x.tammy.telecryptModules.call.callBackend.ElementCallSession,
        homeserverUrl: String?,
        intent: String,
        mode: CallMode,
    ): WidgetBridgeManager.BridgeSession? {
        val baseUrl = homeserverUrl?.trimEnd('/') ?: session.homeserver.ifBlank {
            resolveHomeserverUrl(matrixClient)
        }.trimEnd('/')
        if (baseUrl.isBlank()) {
            callLog("[Call] startWidgetBridge: blank baseUrl, skipping widget mode")
            return null
        }
        val plainUserId = session.userId
        val plainDeviceId = session.deviceId
        if (plainUserId.isBlank() || plainDeviceId.isBlank()) {
            callLog("[Call] startWidgetBridge: blank userId/deviceId, skipping widget mode")
            return null
        }
        return runCatching {
            widgetBridgeManager.start(
                matrixClient = matrixClient,
                roomId = roomId,
                userId = plainUserId,
                deviceId = plainDeviceId,
                baseUrl = baseUrl,
            ) { parentUrl, widgetId ->
                buildElementCallWidgetUrl(
                    widgetId = widgetId,
                    parentUrl = parentUrl,
                    userId = plainUserId,
                    deviceId = plainDeviceId,
                    baseUrl = baseUrl,
                    roomId = roomId.full,
                    roomName = roomName,
                    displayName = displayName,
                    skipLobby = true,
                    hideHeader = true,
                    disableAudio = false,
                    disableVideo = (mode == CallMode.AUDIO),
                    intent = intent,
                )
            }
        }.onFailure {
            callLog("[Call] widgetBridgeManager.start() failed: ${it.message}")
        }.getOrNull()
    }

    private suspend fun publishSlot(
        matrixClient: MatrixClient,
        roomId: RoomId,
        slotId: String,
        callId: String?,
    ) {
        val content = callId?.let { buildSlotContent(it) } ?: JsonObject(emptyMap())
        if (sendSlotStateEvent(matrixClient, roomId, slotId, content, MatrixRtcEventTypes.SLOT)) {
            return
        }
        if (sendSlotStateEvent(matrixClient, roomId, slotId, content, MatrixRtcEventTypes.UNSTABLE_SLOT)) {
            return
        }
        callLog("[Call] Failed to publish slot with both stable and unstable event types.")
    }

    private suspend fun sendSlotStateEvent(
        matrixClient: MatrixClient,
        roomId: RoomId,
        slotId: String,
        content: JsonObject,
        eventType: String,
    ): Boolean {
        val event = UnknownEventContent(content, eventType)
        return runCatching {
            matrixClient.api.room.sendStateEvent(roomId, event, slotId)
            true
        }.onFailure { error ->
            callLog("[Call] Failed to publish slot type=$eventType: ${error.message}")
        }.getOrDefault(false)
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
}

private fun resolveDisplayName(matrixClient: MatrixClient, sessionName: String): String {
    val displayName = sessionName.trim().ifEmpty {
        matrixClient.displayName.value?.trim().orEmpty()
    }
    return displayName.ifEmpty { matrixClient.userId.full }
}

private const val ELEMENT_CALL_BASE_URL = "https://call.element.io"

package de.connect2x.tammy.telecryptModules.call.widgetBridge

import android.content.Context
import de.connect2x.tammy.telecryptModules.call.callBackend.ElementCallActivity
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import net.folivo.trixnity.client.MatrixClient
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.UserId
import net.folivo.trixnity.core.model.events.ClientEvent
import net.folivo.trixnity.core.model.events.MessageEventContent
import net.folivo.trixnity.core.model.events.StateEventContent
import net.folivo.trixnity.core.model.events.ToDeviceEventContent
import net.folivo.trixnity.core.model.events.UnknownEventContent
import net.folivo.trixnity.core.serialization.events.EventContentSerializerMappings
import net.folivo.trixnity.core.serialization.events.StateEventContentSerializerMapping

import java.util.concurrent.ConcurrentHashMap

/**
 * Android-аналог [DesktopWidgetBridgeManager]. Логика коллбеков (sendToDevice,
 * sendStateEvent, readStateEvents и пр.) идентична desktop-версии — общий
 * `WidgetApiHandler` живёт в commonMain. Отличается только тем, что
 * [WidgetBridgeServer] на Android тянет шаблон `widget-host.html` из
 * `AssetManager` через [Context].
 */
class AndroidWidgetBridgeManager(
    private val context: Context,
) : WidgetBridgeManager {

    private val stateCache = ConcurrentHashMap<String, ConcurrentHashMap<String, JsonObject>>()

    override suspend fun start(
        matrixClient: MatrixClient,
        roomId: RoomId,
        userId: String,
        deviceId: String,
        baseUrl: String,
        widgetEcUrlBuilder: (parentUrl: String, widgetId: String) -> String,
    ): WidgetBridgeManager.BridgeSession {
        // Pre-fill state cache (mirror of desktop behaviour).
        val mappings = runCatching { matrixClient.di.get<EventContentSerializerMappings>() }.getOrNull()
        if (mappings != null) {
            val allStateResult = retryOnTransientFailure("getInitialState(${roomId.full})") {
                matrixClient.api.room.getState(roomId)
            }?.getOrNull()

            if (allStateResult != null) {
                var cachedCount = 0
                val json = matrixClient.api.json
                for (ev in allStateResult) {
                    runCatching {
                        val asJson = stateEventToCanonicalJson(json, mappings.state, ev)
                        val type = asJson["type"]?.jsonPrimitive?.contentOrNull
                        val stateKey = asJson["state_key"]?.jsonPrimitive?.contentOrNull
                        if (type != null && stateKey != null) {
                            stateCache.getOrPut(type) { ConcurrentHashMap() }[stateKey] = asJson
                            cachedCount++
                        }
                    }
                }
                println("[WidgetBridgeManager] preloaded $cachedCount state events into cache")
            }
        }

        val widgetId = "telecrypt-${System.currentTimeMillis().toString(36)}"

        val sendToDevice: suspend (eventType: String, messages: JsonObject, encrypted: Boolean) -> Boolean =
            { eventType, messages, encrypted -> doSendToDevice(matrixClient, eventType, messages, encrypted) }

        val sendStateEvent: suspend (eventType: String, stateKey: String, content: JsonObject) -> String? =
            { eventType, stateKey, content -> doSendStateEvent(matrixClient, roomId, eventType, stateKey, content) }

        val sendMessageEvent: suspend (eventType: String, content: JsonObject) -> String? =
            { eventType, content -> doSendMessageEvent(matrixClient, roomId, eventType, content) }

        val readStateEvents: suspend (eventType: String, stateKey: String?, limit: Int) -> List<JsonObject> =
            { eventType, stateKey, limit -> doReadStateEvents(matrixClient, roomId, eventType, stateKey, limit) }

        val getOpenIdToken: suspend () -> Map<String, String>? =
            { doGetOpenIdToken(matrixClient) }

        val handlerFactory = {
            WidgetApiHandler(
                widgetId = widgetId,
                userId = userId,
                deviceId = deviceId,
                roomId = roomId.full,
                matrixSendToDevice = sendToDevice,
                matrixSendStateEvent = sendStateEvent,
                matrixSendMessageEvent = sendMessageEvent,
                matrixReadStateEvents = readStateEvents,
                matrixGetOpenIdToken = getOpenIdToken,
                // When EC fires `io.element.close` (hang-up button), tear down
                // the WebView activity so we return to the chat instead of
                // hanging on a black screen.
                onClose = { ElementCallActivity.closeCurrent() },
            )
        }

        val server = WidgetBridgeServer(
            context = context,
            widgetId = widgetId,
            elementCallUrl = "about:blank",
            handlerFactory = handlerFactory,
        )
        server.start()

        val parentUrl = server.hostHtmlUrl
        val widgetUrl = widgetEcUrlBuilder(parentUrl, widgetId)
        server.setElementCallUrl(widgetUrl)
        println("[WidgetBridgeManager] started: hostUrl=$parentUrl widgetUrl=$widgetUrl")

        return object : WidgetBridgeManager.BridgeSession {
            override val hostUrl: String = parentUrl

            override fun forwardSyncEvent(rawEvent: JsonObject) {
                val type = rawEvent["type"]?.jsonPrimitive?.contentOrNull
                val stateKey = rawEvent["state_key"]?.jsonPrimitive?.contentOrNull
                if (type != null && stateKey != null) {
                    stateCache.getOrPut(type) { ConcurrentHashMap() }[stateKey] = rawEvent
                }

                runCatching { server.forwardSyncEvent(rawEvent) }
                    .onFailure { println("[WidgetBridgeManager] forwardSyncEvent failed: ${it.message}") }
            }

            override fun forwardToDeviceEvent(rawEvent: JsonObject) {
                runCatching { server.forwardToDeviceEvent(rawEvent) }
                    .onFailure { println("[WidgetBridgeManager] forwardToDeviceEvent failed: ${it.message}") }
            }

            override fun close() {
                runCatching { server.close() }
            }
        }
    }

    private suspend fun doGetOpenIdToken(matrixClient: MatrixClient): Map<String, String>? {
        val userIdObj = matrixClient.userId
        val result = runCatching {
            matrixClient.api.authentication.getOIDCRequestToken(userIdObj)
        }
        val callEx = result.exceptionOrNull()
        val apiResult = result.getOrNull()
        val apiEx = apiResult?.exceptionOrNull()
        val response = apiResult?.getOrNull()
        if (response == null) {
            println("[WidgetBridge] doGetOpenIdToken failed: callEx=${callEx?.message} apiEx=${apiEx?.message}")
            return null
        }
        println(
            "[WidgetBridge] doGetOpenIdToken ok: server=${response.matrixServerName} " +
                "expires_in=${response.expiresIn} token_len=${response.accessToken.length}"
        )
        return mapOf(
            "access_token" to response.accessToken,
            "expires_in" to response.expiresIn.toString(),
            "matrix_server_name" to response.matrixServerName,
            "token_type" to response.tokenType,
        )
    }

    private suspend fun doSendToDevice(
        matrixClient: MatrixClient,
        eventType: String,
        messages: JsonObject,
        encrypted: Boolean,
    ): Boolean {
        val olmEncryptionService = matrixClient.di.get<net.folivo.trixnity.crypto.olm.OlmEncryptionService>()
        val converted = mutableMapOf<UserId, Map<String, ToDeviceEventContent>>()
        for ((userKey, devicesElem) in messages) {
            val devices = devicesElem as? JsonObject ?: continue
            val perDevice = mutableMapOf<String, ToDeviceEventContent>()
            val userId = UserId(userKey)
            for ((deviceKey, contentElem) in devices) {
                val contentObj = (contentElem as? JsonObject) ?: continue
                val rawContent = UnknownEventContent(contentObj, eventType)
                if (encrypted) {
                    val encryptResult = olmEncryptionService.encryptOlm(
                        content = rawContent,
                        userId = userId,
                        deviceId = deviceKey
                    )
                    encryptResult.getOrNull()?.let {
                        perDevice[deviceKey] = it
                    } ?: run {
                        println("[WidgetBridge] doSendToDevice: encryptOlm failed for $userKey:$deviceKey — ${encryptResult.exceptionOrNull()?.message}")
                    }
                } else {
                    perDevice[deviceKey] = rawContent
                }
            }
            if (perDevice.isNotEmpty()) {
                converted[userId] = perDevice
            }
        }
        if (converted.isEmpty()) {
            println("[WidgetBridge] doSendToDevice type=$eventType: empty messages map, skipping")
            return true
        }
        val actualEventType = if (encrypted) "m.room.encrypted" else eventType
        val deviceCount = converted.values.sumOf { it.size }
        val result = runCatching {
            matrixClient.api.user.sendToDeviceUnsafe(actualEventType, converted)
        }
        val ex = result.exceptionOrNull()
        val apiResult = result.getOrNull()
        val apiEx = apiResult?.exceptionOrNull()
        val ok = result.isSuccess && apiResult?.isSuccess == true
        println(
            "[WidgetBridge] doSendToDevice type=$eventType users=${converted.size} devices=$deviceCount " +
                "ok=$ok callEx=${ex?.message} apiEx=${apiEx?.message}"
        )
        return ok
    }

    private suspend fun doSendStateEvent(
        matrixClient: MatrixClient,
        roomId: RoomId,
        eventType: String,
        stateKey: String,
        content: JsonObject,
    ): String? {
        val result = runCatching {
            matrixClient.api.room.sendStateEvent(
                roomId,
                UnknownEventContent(content, eventType),
                stateKey,
            )
        }
        val callEx = result.exceptionOrNull()
        val apiResult = result.getOrNull()
        val apiEx = apiResult?.exceptionOrNull()
        val eventId = apiResult?.getOrNull()?.full
        println(
            "[WidgetBridge] doSendStateEvent room=${roomId.full} type=$eventType stateKey='$stateKey' " +
                "contentKeys=${content.keys} eventId=$eventId callEx=${callEx?.message} apiEx=${apiEx?.message}"
        )
        return eventId
    }

    private suspend fun doSendMessageEvent(
        matrixClient: MatrixClient,
        roomId: RoomId,
        eventType: String,
        content: JsonObject,
    ): String? {
        val txnId = "telecrypt-${System.currentTimeMillis()}-${(0..Int.MAX_VALUE).random()}"
        val result = runCatching {
            matrixClient.api.room.sendMessageEvent(
                roomId,
                UnknownEventContent(content, eventType) as MessageEventContent,
                txnId,
            )
        }
        val callEx = result.exceptionOrNull()
        val apiResult = result.getOrNull()
        val apiEx = apiResult?.exceptionOrNull()
        val eventId = apiResult?.getOrNull()?.full
        println(
            "[WidgetBridge] doSendMessageEvent room=${roomId.full} type=$eventType " +
                "contentKeys=${content.keys} txnId=$txnId eventId=$eventId " +
                "callEx=${callEx?.message} apiEx=${apiEx?.message}"
        )
        return eventId
    }

    private suspend fun doReadStateEvents(
        matrixClient: MatrixClient,
        roomId: RoomId,
        eventType: String,
        stateKey: String?,
        limit: Int,
    ): List<JsonObject> {
        val json = matrixClient.api.json
        val mappings = runCatching { matrixClient.di.get<EventContentSerializerMappings>() }
            .onFailure { println("[WidgetBridge] failed to obtain EventContentSerializerMappings: ${it.message}") }
            .getOrElse { return emptyList() }

        val cachedEvents = stateCache[eventType]
        if (cachedEvents != null && cachedEvents.isNotEmpty()) {
            val candidates = if (stateKey != null) {
                val single = cachedEvents[stateKey]
                if (single != null) listOf(single) else emptyList()
            } else {
                cachedEvents.values.toList()
            }
            if (candidates.isNotEmpty()) {
                val result = candidates.take(limit)
                println(
                    "[WidgetBridge] doReadStateEvents(CACHE) room=${roomId.full} type=$eventType " +
                        "stateKey=$stateKey matched=${result.size}"
                )
                return result
            }
        }

        println(
            "[WidgetBridge] doReadStateEvents(CACHE MISS) room=${roomId.full} type=$eventType " +
                "stateKey=$stateKey — no events cached, trying HTTP"
        )

        if (stateKey != null) {
            val singleResult = retryOnTransientFailure("getStateEvent($eventType,$stateKey)") {
                matrixClient.api.room.getStateEvent(eventType, roomId, stateKey)
            }
            val singleEvent: Any? = singleResult?.getOrNull()
            if (singleEvent is ClientEvent.RoomEvent.StateEvent<*>) {
                val asJson = runCatching { stateEventToCanonicalJson(json, mappings.state, singleEvent) }
                    .onFailure { println("[WidgetBridge] stateEventToCanonicalJson failed (fast-path): ${it.message}") }
                    .getOrNull()
                if (asJson != null) {
                    println(
                        "[WidgetBridge] doReadStateEvents(HTTP) room=${roomId.full} type=$eventType stateKey=$stateKey " +
                            "fast-path getStateEvent ok"
                    )
                    return listOf(asJson)
                }
            }
        }

        val all = retryOnTransientFailure("getState(${roomId.full})") {
            matrixClient.api.room.getState(roomId)
        }?.getOrNull()
        if (all == null) {
            println(
                "[WidgetBridge] doReadStateEvents(HTTP) room=${roomId.full} type=$eventType FAILED after retries"
            )
            return emptyList()
        }
        val asJson = all.mapNotNull { ev ->
            runCatching { stateEventToCanonicalJson(json, mappings.state, ev) }
                .onFailure { println("[WidgetBridge] stateEventToCanonicalJson failed for ${ev.stateKey}: ${it.message}") }
                .getOrNull()
        }
        val typesSeen = asJson.mapNotNull { it["type"]?.jsonPrimitive?.contentOrNull }
            .groupingBy { it }.eachCount()
        val filtered = asJson.asSequence()
            .filter { it["type"]?.jsonPrimitive?.contentOrNull == eventType }
            .filter { stateKey == null || it["state_key"]?.jsonPrimitive?.contentOrNull == stateKey }
            .take(limit)
            .toList()
        println(
            "[WidgetBridge] doReadStateEvents(HTTP) room=${roomId.full} type=$eventType stateKey=$stateKey " +
                "totalState=${all.size} serialized=${asJson.size} matched=${filtered.size} " +
                "typesSeen=$typesSeen"
        )
        return filtered
    }

    private fun stateEventToCanonicalJson(
        json: Json,
        stateMappings: Set<StateEventContentSerializerMapping>,
        ev: ClientEvent.RoomEvent.StateEvent<*>,
    ): JsonObject {
        val content = ev.content
        val (typeStr, contentJson) = if (content is UnknownEventContent) {
            content.eventType to (content.raw as JsonObject)
        } else {
            val mapping = stateMappings.firstOrNull { it.kClass == content::class }
                ?: throw IllegalStateException(
                    "No StateEventContentSerializerMapping for ${content::class}"
                )
            @Suppress("UNCHECKED_CAST")
            val ser = mapping.serializer as KSerializer<StateEventContent>
            mapping.type to (json.encodeToJsonElement(ser, content) as JsonObject)
        }
        return buildJsonObject {
            put("type", JsonPrimitive(typeStr))
            put("state_key", JsonPrimitive(ev.stateKey))
            put("event_id", JsonPrimitive(ev.id.full))
            put("sender", JsonPrimitive(ev.sender.full))
            put("room_id", JsonPrimitive(ev.roomId.full))
            put("origin_server_ts", JsonPrimitive(ev.originTimestamp))
            put("content", contentJson)
            ev.unsigned?.let { unsigned ->
                runCatching {
                    val unsignedJson = json.encodeToJsonElement(unsigned) as? JsonObject
                    if (unsignedJson != null) put("unsigned", unsignedJson)
                }
            }
        }
    }

    private suspend fun <T> retryOnTransientFailure(
        label: String,
        block: suspend () -> Result<T>,
    ): Result<T>? {
        val maxAttempts = 3
        var lastEx: Throwable? = null
        repeat(maxAttempts) { attempt ->
            val outcome = runCatching { block() }
            val callEx = outcome.exceptionOrNull()
            val apiResult = outcome.getOrNull()
            val apiEx = apiResult?.exceptionOrNull()
            val transient = (callEx != null && isTransient(callEx)) ||
                (apiEx != null && isTransient(apiEx))
            if (apiResult != null && apiResult.isSuccess) return apiResult
            if (apiResult != null && !transient) return apiResult
            if (callEx != null && !transient) {
                println("[WidgetBridge] $label non-transient error on attempt ${attempt + 1}: ${callEx.message}")
                return null
            }
            lastEx = callEx ?: apiEx
            println("[WidgetBridge] $label transient failure attempt ${attempt + 1}/$maxAttempts: ${lastEx?.message}")
            if (attempt < maxAttempts - 1) {
                kotlinx.coroutines.delay(500L * (attempt + 1))
            }
        }
        println("[WidgetBridge] $label failed after $maxAttempts attempts: ${lastEx?.message}")
        return null
    }

    private fun isTransient(t: Throwable): Boolean {
        val msg = (t.message ?: "").lowercase()
        return msg.contains("timeout") ||
            msg.contains("timed out") ||
            msg.contains("connection reset") ||
            msg.contains("connection refused") ||
            msg.contains("connect reset") ||
            msg.contains("socket") ||
            msg.contains("eof") ||
            t::class.simpleName?.lowercase()?.contains("timeout") == true
    }
}

private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String?
    get() = if (this is kotlinx.serialization.json.JsonNull) null else content

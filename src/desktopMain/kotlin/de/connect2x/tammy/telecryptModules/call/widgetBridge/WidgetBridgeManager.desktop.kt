package de.connect2x.tammy.telecryptModules.call.widgetBridge

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
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

class DesktopWidgetBridgeManager : WidgetBridgeManager {

    override suspend fun start(
        matrixClient: MatrixClient,
        roomId: RoomId,
        userId: String,
        deviceId: String,
        baseUrl: String,
        widgetEcUrlBuilder: (parentUrl: String, widgetId: String) -> String,
    ): WidgetBridgeManager.BridgeSession {
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
            )
        }

        val server = WidgetBridgeServer(
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

    /**
     * Читает state events комнаты через `GET /rooms/{roomId}/state` и фильтрует.
     * Element Call запрашивает несколько типов: `m.room.create`, `m.room.member`,
     * `m.room.encryption`, `org.matrix.msc3401.call.member` и др.
     *
     * Сериализация выполняется через [MatrixClient.api.json], который имеет
     * контекстные сериализаторы для всех известных типов trixnity (включая
     * [UnknownEventContent] для нераспознанных), поэтому JSON получается
     * каноничный с правильным `type`.
     */
    private suspend fun doReadStateEvents(
        matrixClient: MatrixClient,
        roomId: RoomId,
        eventType: String,
        stateKey: String?,
        limit: Int,
    ): List<JsonObject> {
        val getStateResult = runCatching { matrixClient.api.room.getState(roomId) }
        val callEx = getStateResult.exceptionOrNull()
        val apiResult = getStateResult.getOrNull()
        val apiEx = apiResult?.exceptionOrNull()
        val all = apiResult?.getOrNull()
        if (all == null) {
            println(
                "[WidgetBridge] doReadStateEvents room=${roomId.full} type=$eventType FAILED: " +
                    "callEx=${callEx?.message} apiEx=${apiEx?.message}"
            )
            return emptyList()
        }
        val json = matrixClient.api.json
        val mappings = runCatching { matrixClient.di.get<EventContentSerializerMappings>() }
            .onFailure { println("[WidgetBridge] failed to obtain EventContentSerializerMappings: ${it.message}") }
            .getOrElse { return emptyList() }
        // Сериализуем каждое событие в JSON, собирая envelope вручную:
        // `serializersModule.serializer<StateEvent<*>>()` через рефлексию падает
        // на star projection, а encodeToString(<contextual mapping>, ev) требует
        // полный StateEventSerializer. Поэтому идём в обход: content
        // сериализуем через `mappings.state` (или берём `raw` для
        // UnknownEventContent), а envelope собираем через buildJsonObject.
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
            "[WidgetBridge] doReadStateEvents room=${roomId.full} type=$eventType stateKey=$stateKey " +
                "totalState=${all.size} serialized=${asJson.size} matched=${filtered.size} " +
                "typesSeen=$typesSeen"
        )
        return filtered
    }

    /**
     * Собирает canonical JSON для одного state event без обращения к
     * `serializersModule.serializer<StateEvent<*>>()` (он падает на star
     * projection через kotlinx.serialization reflection).
     *
     * Логика:
     *  - тип события: для `UnknownEventContent` берём из `content.eventType`,
     *    для остального ищем mapping по `kClass` в `mappings.state`;
     *  - content: для `UnknownEventContent` берём `content.raw` как есть,
     *    для остального сериализуем через `mapping.serializer`;
     *  - envelope: собираем вручную через `buildJsonObject` —
     *    `type`, `state_key`, `event_id`, `sender`, `room_id`,
     *    `origin_server_ts`, `content` (+ `unsigned`, если есть).
     */
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
}

private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String?
    get() = if (this is kotlinx.serialization.json.JsonNull) null else content

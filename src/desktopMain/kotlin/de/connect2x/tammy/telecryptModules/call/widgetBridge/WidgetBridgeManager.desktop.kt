package de.connect2x.tammy.telecryptModules.call.widgetBridge

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import net.folivo.trixnity.client.MatrixClient
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.UserId
import net.folivo.trixnity.core.model.events.ClientEvent
import net.folivo.trixnity.core.model.events.ToDeviceEventContent
import net.folivo.trixnity.core.model.events.UnknownEventContent

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

        val sendToDevice: suspend (eventType: String, messages: JsonObject) -> Boolean =
            { eventType, messages -> doSendToDevice(matrixClient, eventType, messages) }

        val sendStateEvent: suspend (eventType: String, stateKey: String, content: JsonObject) -> String? =
            { eventType, stateKey, content -> doSendStateEvent(matrixClient, roomId, eventType, stateKey, content) }

        val readStateEvents: suspend (eventType: String, stateKey: String?, limit: Int) -> List<JsonObject> =
            { eventType, stateKey, limit -> doReadStateEvents(matrixClient, roomId, eventType, stateKey, limit) }

        val handlerFactory = {
            WidgetApiHandler(
                widgetId = widgetId,
                userId = userId,
                deviceId = deviceId,
                roomId = roomId.full,
                matrixSendToDevice = sendToDevice,
                matrixSendStateEvent = sendStateEvent,
                matrixReadStateEvents = readStateEvents,
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

    private suspend fun doSendToDevice(
        matrixClient: MatrixClient,
        eventType: String,
        messages: JsonObject,
    ): Boolean {
        val converted = mutableMapOf<UserId, Map<String, ToDeviceEventContent>>()
        for ((userKey, devicesElem) in messages) {
            val devices = devicesElem as? JsonObject ?: continue
            val perDevice = mutableMapOf<String, ToDeviceEventContent>()
            for ((deviceKey, contentElem) in devices) {
                val contentObj = (contentElem as? JsonObject) ?: continue
                perDevice[deviceKey] = UnknownEventContent(contentObj, eventType)
            }
            if (perDevice.isNotEmpty()) {
                converted[UserId(userKey)] = perDevice
            }
        }
        if (converted.isEmpty()) {
            println("[WidgetBridge] doSendToDevice type=$eventType: empty messages map, skipping")
            return true
        }
        val deviceCount = converted.values.sumOf { it.size }
        val result = runCatching {
            matrixClient.api.user.sendToDeviceUnsafe(eventType, converted)
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
        // Сериализуем каждое событие в JSON через trixnity, чтобы получить
        // правильный `type` (и для известных, и для unknown content'ов).
        val asJson = all.mapNotNull { ev ->
            runCatching { stateEventToCanonicalJson(json, ev) }
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

    private fun stateEventToCanonicalJson(
        json: Json,
        ev: ClientEvent.RoomEvent.StateEvent<*>,
    ): JsonObject {
        // trixnity регистрирует StateEventSerializer как contextual в SerializersModule
        // (см. createMatrixEventSerializersModule.kt), поэтому resolved сериализатор
        // выдаст каноничный JSON с правильным `type` и `state_key`.
        @Suppress("UNCHECKED_CAST")
        val ser: KSerializer<ClientEvent.RoomEvent.StateEvent<*>> =
            json.serializersModule.serializer<ClientEvent.RoomEvent.StateEvent<*>>()
                as KSerializer<ClientEvent.RoomEvent.StateEvent<*>>
        val element = json.encodeToJsonElement(ser, ev)
        return element.jsonObject
    }
}

private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String?
    get() = if (this is kotlinx.serialization.json.JsonNull) null else content

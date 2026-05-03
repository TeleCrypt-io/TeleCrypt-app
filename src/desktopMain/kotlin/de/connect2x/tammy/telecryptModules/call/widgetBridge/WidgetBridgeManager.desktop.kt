package de.connect2x.tammy.telecryptModules.call.widgetBridge

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import net.folivo.trixnity.client.MatrixClient
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.UserId
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

        // Колбэки в Matrix через trixnity API.
        val sendToDevice: suspend (eventType: String, messages: JsonObject) -> Boolean =
            { eventType, messages -> doSendToDevice(matrixClient, eventType, messages) }

        val sendStateEvent: suspend (eventType: String, stateKey: String, content: JsonObject) -> String? =
            { eventType, stateKey, content -> doSendStateEvent(matrixClient, roomId, eventType, stateKey, content) }

        val handlerFactory = {
            WidgetApiHandler(
                widgetId = widgetId,
                userId = userId,
                deviceId = deviceId,
                roomId = roomId.full,
                matrixSendToDevice = sendToDevice,
                matrixSendStateEvent = sendStateEvent,
            )
        }

        // Сначала создаём сервер с placeholder URL — реальный widget URL соберём
        // после того как узнаем порт (parentUrl = http://127.0.0.1:<port>/widget-host.html).
        // Для этого делаем двухпроходный запуск: сперва биндим порт, потом строим URL.
        val placeholder = "about:blank"
        val server = WidgetBridgeServer(
            widgetId = widgetId,
            elementCallUrl = placeholder,
            handlerFactory = handlerFactory,
        )
        server.start()

        val parentUrl = server.hostHtmlUrl  // ссылка на нашу host‑страницу
        val widgetUrl = widgetEcUrlBuilder(parentUrl, widgetId)

        // Пере‑создаём сервер с настоящим widgetUrl — проще закрыть placeholder и поднять заново
        // (сервер маленький, переподнятие — миллисекунды).
        server.close()
        val realServer = WidgetBridgeServer(
            widgetId = widgetId,
            elementCallUrl = widgetUrl,
            handlerFactory = handlerFactory,
        )
        realServer.start()
        println("[WidgetBridgeManager] started: hostUrl=${realServer.hostHtmlUrl}")

        return object : WidgetBridgeManager.BridgeSession {
            override val hostUrl: String = realServer.hostHtmlUrl
            override fun close() {
                runCatching { realServer.close() }
            }
        }
    }

    private suspend fun doSendToDevice(
        matrixClient: MatrixClient,
        eventType: String,
        messages: JsonObject,
    ): Boolean {
        // Конвертируем widget‑api формат
        //   { "@user:hs": { "DEVICE": <content JsonObject> } }
        // в trixnity формат
        //   Map<UserId, Map<String, ToDeviceEventContent>>
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
        if (converted.isEmpty()) return true
        val result = runCatching {
            matrixClient.api.user.sendToDeviceUnsafe(eventType, converted)
        }
        return result.isSuccess && result.getOrNull()?.isSuccess == true
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
        return result.getOrNull()?.getOrNull()?.full
    }
}

package de.connect2x.tammy.telecryptModules.call.widgetBridge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Минимальная реализация серверной (host) стороны Matrix Widget API
 * (https://github.com/matrix-org/matrix-widget-api).
 *
 * Полностью платформо-независима: единственное, что от неё зависит, — это
 * [matrixSendToDevice] / [matrixSendStateEvent] callback'и (которые на десктопе
 * вызывают `MatrixClient.api.user.sendToDevice` / `room.sendStateEvent`).
 *
 * Внешний код:
 *   1. Создаёт [WidgetApiHandler], передавая widgetId и идентификаторы пользователя/комнаты.
 *   2. Получает текстовые JSON-сообщения от iframe (`postMessage` → WebSocket → сюда),
 *      вызывает [handleMessage]. Возвращает список JSON-строк, которые нужно отправить
 *      обратно во iframe (как ответы и/или host-инициированные запросы).
 *   3. При появлении новых событий из Matrix (sync) вызывает
 *      [forwardSyncEvent] / [forwardToDeviceEvent], получая JSON-строки для отправки.
 *
 * Поддерживаемые actions (из спецификации widget-api + MSC):
 *   - `supported_api_versions`         (toWidget)
 *   - `capabilities`                   (toWidget)
 *   - `notify_capabilities`            (toWidget)
 *   - `content_loaded`                 (fromWidget)
 *   - `send_event`                     (fromWidget) → matrixSendStateEvent
 *   - `send_to_device`                 (fromWidget) → matrixSendToDevice
 *   - `org.matrix.msc2876.read_events` (fromWidget) — пока no-op (возвращает {events: []})
 *   - `org.matrix.msc3869.read_relations` (fromWidget) — пока no-op
 *   - `org.matrix.msc3819.send_to_device` (alias) → matrixSendToDevice
 *   - `update_turn_servers`            (toWidget) — high-level helper
 */
class WidgetApiHandler(
    val widgetId: String,
    private val userId: String,
    private val deviceId: String,
    private val roomId: String,
    /**
     * Колбэк отправки to-device события через Matrix.
     * `eventType` = напр. `m.call.encryption_keys`.
     * `messages` = карта вида `{ "@user:hs": { "DEVICEID": <content JsonObject> } }` —
     * формат, который EC присылает в widget-api.
     * Возвращает true при успехе.
     */
    private val matrixSendToDevice: suspend (eventType: String, messages: JsonObject) -> Boolean,
    /**
     * Колбэк отправки state event через Matrix.
     * Возвращает eventId или null.
     */
    private val matrixSendStateEvent: suspend (eventType: String, stateKey: String, content: JsonObject) -> String?,
) {
    /**
     * Approved capabilities — заполняются после первого `capabilities` запроса.
     * Element Call всегда запрашивает фиксированный набор; мы просто подтверждаем все.
     */
    private val approvedCapabilities = mutableListOf<String>()

    /**
     * Наш счётчик requestId для host-инициированных сообщений (toWidget).
     */
    private var hostRequestSeq: Long = 0L

    private fun nextHostRequestId(): String = "host-${hostRequestSeq++}"

    /**
     * Обрабатывает одно входящее сообщение от iframe.
     *
     * @return список строк JSON, которые нужно переслать iframe.
     *         Обычно это один ответ; для `capabilities` handshake — два сообщения
     *         (ответ на supported_api_versions и host-инициированный capabilities request).
     */
    suspend fun handleMessage(rawJson: String): List<String> {
        val msg = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(rawJson).jsonObject }
            .getOrElse { return emptyList() }

        val api = msg["api"]?.jsonPrimitive?.contentOrNull() ?: return emptyList()
        val action = msg["action"]?.jsonPrimitive?.contentOrNull() ?: return emptyList()
        val requestId = msg["requestId"]?.jsonPrimitive?.contentOrNull() ?: return emptyList()
        val data = msg["data"] as? JsonObject ?: JsonObject(emptyMap())

        if (api != "fromWidget") {
            // Игнорируем эхо/левые сообщения.
            return emptyList()
        }

        return when (action) {
            "supported_api_versions" -> listOf(
                buildResponse(msg, buildJsonObject {
                    put("supported_versions", buildJsonArray {
                        add(JsonPrimitive("0.0.1"))
                        add(JsonPrimitive("0.0.2"))
                        add(JsonPrimitive("0.1.0"))
                    })
                })
            )

            "content_loaded" -> {
                // Подтверждаем загрузку и инициируем handshake capabilities.
                val ack = buildResponse(msg, buildJsonObject { /* empty */ })
                val capRequest = buildHostRequest(
                    action = "capabilities",
                    data = buildJsonObject { /* пустой data — widget сам перечислит свои capabilities */ },
                )
                listOf(ack, capRequest)
            }

            "capabilities" -> {
                // Element Call перечисляет нужные capabilities; одобряем все.
                val requested = (data["capabilities"] as? JsonArray)?.mapNotNull {
                    it.jsonPrimitive.contentOrNull()
                } ?: emptyList()
                approvedCapabilities.clear()
                approvedCapabilities.addAll(requested)
                println("[WidgetApi] approved ${requested.size} capabilities: $requested")
                listOf(
                    buildResponse(msg, buildJsonObject {
                        put("capabilities", buildJsonArray {
                            requested.forEach { add(JsonPrimitive(it)) }
                        })
                    })
                )
            }

            "send_event" -> {
                val type = data["type"]?.jsonPrimitive?.contentOrNull().orEmpty()
                val stateKey = data["state_key"]?.jsonPrimitive?.contentOrNull()
                val content = (data["content"] as? JsonObject) ?: JsonObject(emptyMap())
                if (stateKey == null) {
                    // Room (timeline) event — не нужен в EC widget-режиме.
                    listOf(errorResponse(msg, "room timeline events not supported"))
                } else {
                    val eventId = runCatching {
                        matrixSendStateEvent(type, stateKey, content)
                    }.getOrNull()
                    if (eventId != null) {
                        listOf(buildResponse(msg, buildJsonObject {
                            put("room_id", JsonPrimitive(roomId))
                            put("event_id", JsonPrimitive(eventId))
                        }))
                    } else {
                        listOf(errorResponse(msg, "send_event failed"))
                    }
                }
            }

            "send_to_device", "org.matrix.msc3819.send_to_device" -> {
                val type = data["type"]?.jsonPrimitive?.contentOrNull().orEmpty()
                val messages = (data["messages"] as? JsonObject) ?: JsonObject(emptyMap())
                val ok = runCatching { matrixSendToDevice(type, messages) }.getOrDefault(false)
                if (ok) {
                    listOf(buildResponse(msg, buildJsonObject { /* empty */ }))
                } else {
                    listOf(errorResponse(msg, "send_to_device failed"))
                }
            }

            "org.matrix.msc2876.read_events" -> {
                // EC просит прочитать историю state events; на старте этого достаточно — пустой ответ.
                listOf(buildResponse(msg, buildJsonObject {
                    put("events", JsonArray(emptyList()))
                }))
            }

            "org.matrix.msc3869.read_relations" -> {
                listOf(buildResponse(msg, buildJsonObject {
                    put("chunk", JsonArray(emptyList()))
                }))
            }

            "watch_turn_servers", "unwatch_turn_servers" -> {
                listOf(buildResponse(msg, buildJsonObject { /* empty */ }))
            }

            "subscribe_to_room", "unsubscribe_from_room" -> {
                listOf(buildResponse(msg, buildJsonObject { /* empty */ }))
            }

            else -> {
                println("[WidgetApi] unhandled action='$action' — replying empty ack")
                listOf(buildResponse(msg, buildJsonObject { /* empty */ }))
            }
        }
    }

    /**
     * Формирует toWidget-сообщение, которое нужно отправить во iframe,
     * когда из sync пришло state event для нашей комнаты.
     */
    fun forwardSyncEvent(rawEvent: JsonObject): String {
        return buildHostRequest(
            action = "send_event",
            data = rawEvent,
        )
    }

    /**
     * Формирует toWidget-сообщение для to-device события (m.call.encryption_keys и др.).
     */
    fun forwardToDeviceEvent(rawEvent: JsonObject): String {
        return buildHostRequest(
            action = "send_to_device",
            data = rawEvent,
        )
    }

    private fun buildResponse(original: JsonObject, responseData: JsonObject): String {
        val responseObj = buildJsonObject {
            original.forEach { (k, v) -> put(k, v) }
            put("response", responseData)
        }
        return responseObj.toString()
    }

    private fun errorResponse(original: JsonObject, message: String): String {
        val responseObj = buildJsonObject {
            original.forEach { (k, v) -> put(k, v) }
            put("response", buildJsonObject {
                put("error", buildJsonObject {
                    put("message", JsonPrimitive(message))
                })
            })
        }
        return responseObj.toString()
    }

    private fun buildHostRequest(action: String, data: JsonObject): String {
        return buildJsonObject {
            put("api", JsonPrimitive("toWidget"))
            put("widgetId", JsonPrimitive(widgetId))
            put("requestId", JsonPrimitive(nextHostRequestId()))
            put("action", JsonPrimitive(action))
            put("data", data)
        }.toString()
    }
}

private fun JsonPrimitive.contentOrNull(): String? =
    if (this is JsonNull) null else this.content

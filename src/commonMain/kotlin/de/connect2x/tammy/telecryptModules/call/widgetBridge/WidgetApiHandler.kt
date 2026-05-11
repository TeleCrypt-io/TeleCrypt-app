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
 * (https://github.com/matrix-org/matrix-widget-api), MSC2762/MSC2774/MSC2876/MSC3819.
 *
 * Поддерживаемые actions (fromWidget):
 *   - `supported_api_versions`
 *   - `content_loaded`
 *   - `capabilities` (ответ на host‑инициированный запрос)
 *   - `send_event` (state event → matrixSendStateEvent)
 *   - `send_to_device` / `org.matrix.msc3819.send_to_device` → matrixSendToDevice
 *   - `org.matrix.msc2876.read_events` → matrixReadStateEvents
 *   - `org.matrix.msc3869.read_relations` (no-op)
 *   - `watch_turn_servers` / `unwatch_turn_servers` (no-op)
 *   - `subscribe_to_room` / `unsubscribe_from_room` (no-op)
 *
 * Host‑инициированные сообщения (toWidget):
 *   - `capabilities` (просим перечислить нужные)
 *   - `send_event` — sync state event
 *   - `send_to_device` — to-device event
 */
class WidgetApiHandler(
    val widgetId: String,
    private val userId: String,
    private val deviceId: String,
    private val roomId: String,
    /** to-device через Matrix. Возвращает true при успехе. */
    private val matrixSendToDevice: suspend (eventType: String, messages: JsonObject) -> Boolean,
    /** state event через Matrix. Возвращает eventId или null. */
    private val matrixSendStateEvent: suspend (eventType: String, stateKey: String, content: JsonObject) -> String?,
    /**
     * Чтение state events комнаты:
     *   - `eventType` — фильтр типа (например, `org.matrix.msc3401.call.member`).
     *   - `stateKey` — если задан, ограничить одним state_key; иначе — все ключи.
     *   - `limit` — мягкий лимит на число событий.
     *
     * Должен вернуть массив сырых JSON‑событий с заполненными полями
     * (`type`, `state_key`, `sender`, `content`, `event_id`, `room_id`,
     * `origin_server_ts`).
     */
    private val matrixReadStateEvents: suspend (eventType: String, stateKey: String?, limit: Int) -> List<JsonObject>,
) {
    private val approvedCapabilities = mutableListOf<String>()

    private var hostRequestSeq: Long = 0L
    private fun nextHostRequestId(): String = "host-${hostRequestSeq++}"

    suspend fun handleMessage(rawJson: String): List<String> {
        val msg = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(rawJson).jsonObject }
            .getOrElse {
                println("[WidgetApi] failed to parse incoming JSON (${rawJson.length} bytes): ${it.message}")
                return emptyList()
            }

        // Специальный конверт от widget-host.html для проброса логов браузерной страницы
        // в Kotlin-логи. Не относится к Widget API, но крайне полезно для отладки.
        // Поле __hostLog сериализуется JS как boolean true → kotlinx-serialization
        // парсит его как JsonPrimitive с content="true" и isString=false.
        if (msg["__hostLog"] != null) {
            val level = msg["level"]?.jsonPrimitive?.contentOrNull() ?: "log"
            val text = msg["msg"]?.jsonPrimitive?.contentOrNull() ?: ""
            println("[WidgetHost/$level] $text")
            return emptyList()
        }

        val api = msg["api"]?.jsonPrimitive?.contentOrNull()
        val action = msg["action"]?.jsonPrimitive?.contentOrNull()
        val requestId = msg["requestId"]?.jsonPrimitive?.contentOrNull()
        if (api == null || action == null || requestId == null) {
            println("[WidgetApi] dropping malformed message: api=$api action=$action requestId=$requestId raw=${rawJson.take(300)}")
            return emptyList()
        }
        val data = msg["data"] as? JsonObject ?: JsonObject(emptyMap())

        if (api != "fromWidget") {
            // toWidget echo / replies — пока не используем.
            println("[WidgetApi] ignoring api=$api action=$action requestId=$requestId")
            return emptyList()
        }

        println(
            "[WidgetApi] <- action=$action requestId=$requestId dataKeys=${data.keys} dataPreview=${data.toString().take(400)}"
        )

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
                val ack = buildResponse(msg, buildJsonObject { /* empty */ })
                val capRequest = buildHostRequest(
                    action = "capabilities",
                    data = buildJsonObject { /* widget сам перечислит */ },
                )
                listOf(ack, capRequest)
            }

            "capabilities" -> {
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
                val type = data["type"]?.jsonPrimitive?.contentOrNull().orEmpty()
                // Per MSC2876: state_key may be:
                //   - absent / true (boolean) → match ANY state key
                //   - false → match only state_key = "" (empty string)
                //   - "specific_key" (string) → match exact value
                val stateKeyRaw = data["state_key"]
                val stateKey: String? = when {
                    stateKeyRaw == null -> null
                    stateKeyRaw is JsonPrimitive && stateKeyRaw.isString -> stateKeyRaw.content
                    stateKeyRaw is JsonPrimitive && stateKeyRaw.content == "true" -> null
                    stateKeyRaw is JsonPrimitive && stateKeyRaw.content == "false" -> ""
                    else -> null
                }
                val limit = data["limit"]?.jsonPrimitive?.contentOrNull()?.toIntOrNull() ?: 50
                val events = runCatching { matrixReadStateEvents(type, stateKey, limit) }
                    .getOrElse {
                        println("[WidgetApi] read_events failed: ${it.message}")
                        emptyList()
                    }
                println(
                    "[WidgetApi] read_events type=$type stateKey=$stateKey (raw=$stateKeyRaw) limit=$limit -> ${events.size} events"
                )
                listOf(buildResponse(msg, buildJsonObject {
                    put("events", buildJsonArray { events.forEach { add(it) } })
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
     * Sync state event → toWidget `send_event`.
     * `rawEvent` должен уже содержать поля Matrix‑события (type, state_key, content, и т. д.).
     */
    fun forwardSyncEvent(rawEvent: JsonObject): String {
        return buildHostRequest(action = "send_event", data = rawEvent)
    }

    /**
     * To-device event → toWidget `send_to_device`.
     * `rawEvent` должен содержать `type`, `sender`, `content`, опционально `encrypted`.
     */
    fun forwardToDeviceEvent(rawEvent: JsonObject): String {
        return buildHostRequest(action = "send_to_device", data = rawEvent)
    }

    private fun buildResponse(original: JsonObject, responseData: JsonObject): String {
        val action = original["action"]?.jsonPrimitive?.contentOrNull()
        val reqId = original["requestId"]?.jsonPrimitive?.contentOrNull()
        val responseObj = buildJsonObject {
            original.forEach { (k, v) -> put(k, v) }
            put("response", responseData)
        }
        val rendered = responseObj.toString()
        println(
            "[WidgetApi] -> response action=$action requestId=$reqId responseKeys=${responseData.keys} preview=${responseData.toString().take(300)}"
        )
        return rendered
    }

    private fun errorResponse(original: JsonObject, message: String): String {
        val action = original["action"]?.jsonPrimitive?.contentOrNull()
        val reqId = original["requestId"]?.jsonPrimitive?.contentOrNull()
        println("[WidgetApi] -> ERROR response action=$action requestId=$reqId message='$message'")
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
        val reqId = nextHostRequestId()
        println("[WidgetApi] => host request action=$action requestId=$reqId dataKeys=${data.keys}")
        return buildJsonObject {
            put("api", JsonPrimitive("toWidget"))
            put("widgetId", JsonPrimitive(widgetId))
            put("requestId", JsonPrimitive(reqId))
            put("action", JsonPrimitive(action))
            put("data", data)
        }.toString()
    }
}

private fun JsonPrimitive.contentOrNull(): String? =
    if (this is JsonNull) null else this.content

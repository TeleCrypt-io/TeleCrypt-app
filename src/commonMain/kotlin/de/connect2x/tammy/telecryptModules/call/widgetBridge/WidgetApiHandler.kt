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
    private val matrixSendToDevice: suspend (eventType: String, messages: JsonObject, encrypted: Boolean) -> Boolean,
    /** state event через Matrix. Возвращает eventId или null. */
    private val matrixSendStateEvent: suspend (eventType: String, stateKey: String, content: JsonObject) -> String?,
    /** room timeline (message) event через Matrix. Возвращает eventId или null. */
    private val matrixSendMessageEvent: suspend (eventType: String, content: JsonObject) -> String?,
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
    /**
     * Запрос OpenID-токена пользователя (MSC1960 / spec Matrix C-S
     * `POST /_matrix/client/v3/user/{userId}/openid/request_token`).
     * Возвращает мапу полей `{access_token, expires_in, matrix_server_name, token_type}`
     * либо null при ошибке.
     */
    private val matrixGetOpenIdToken: suspend () -> Map<String, String>?,
) {
    private val approvedCapabilities = mutableListOf<String>()

    private var hostRequestSeq: Long = 0L
    private fun nextHostRequestId(): String = "host-${hostRequestSeq++}"

    private var delayedEventCounter: Long = 0L

    /**
     * MSC4140 delayed-event cache.
     *
     * Element Call uses delayed events as a "dead man's switch": it sends a
     * disconnect/leave state event (typically empty content {}) with delay=90s,
     * then periodically calls `update_delayed_event(action=restart)` to keep it
     * alive while the call is active. When the user explicitly leaves, EC sends
     * `update_delayed_event(action=send)` — meaning "fire it now". On disconnect
     * without a clean leave (crash, network drop, app close), the heartbeat stops
     * and the server eventually commits the event automatically.
     *
     * Since we don't have real server-side delayed events here, we have to
     * emulate the bare minimum: remember what was scheduled and, on action=send,
     * actually post it via the Matrix API. Otherwise leave-state never lands and
     * the membership becomes a ghost.
     *
     * NOTE: we still cannot emulate the auto-fire-on-timeout part — if the
     * desktop crashes we can't post anything. A future improvement is to send
     * the leave directly on `CallCoordinator.leaveCall` / app shutdown.
     */
    private data class DelayedEvent(
        val type: String,
        val stateKey: String?,
        val content: JsonObject,
    )
    private val delayedEvents = mutableMapOf<String, DelayedEvent>()

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
                // MSC4140: if "delay" is present, this is a scheduled (delayed) event,
                // used by Element Call as a "dead man's switch" tombstone (typically
                // content={} sent with delay=90000ms — server holds it and only
                // commits if the widget stops refreshing it).
                // We cannot fulfill MSC4140 server-side scheduling from the bridge,
                // so we MUST NOT post the event immediately (that would instantly
                // overwrite the just-sent join state with an empty disconnect).
                // Instead we silently fake-ack and let EC keep heartbeating.
                val delay = data["delay"]?.jsonPrimitive?.contentOrNull()?.toLongOrNull()
                if (delay != null) {
                    val fakeDelayId = "delayed-${delayedEventCounter++}-${kotlin.random.Random.nextLong()}"
                    delayedEvents[fakeDelayId] = DelayedEvent(type, stateKey, content)
                    println(
                        "[WidgetApi] send_event MSC4140 delayed: type=$type stateKey=$stateKey " +
                            "contentKeys=${content.keys} delay=${delay}ms — cached, delay_id=$fakeDelayId " +
                            "(cache size=${delayedEvents.size})"
                    )
                    listOf(buildResponse(msg, buildJsonObject {
                        put("delay_id", JsonPrimitive(fakeDelayId))
                    }))
                } else {
                    val eventId = if (stateKey == null) {
                        runCatching { matrixSendMessageEvent(type, content) }
                            .onFailure { println("[WidgetApi] send_event (message) threw: ${it.message}") }
                            .getOrNull()
                    } else {
                        runCatching { matrixSendStateEvent(type, stateKey, content) }
                            .onFailure { println("[WidgetApi] send_event (state) threw: ${it.message}") }
                            .getOrNull()
                    }
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

            // MSC4140 / MSC4157 delayed event control: EC periodically calls this to keep
            // its disconnect-tombstone alive (action="restart"/"cancel"/"send").
            // Since we don't actually schedule anything, just ack everything.
            "org.matrix.msc4140.update_delayed_event", "org.matrix.msc4157.update_delayed_event", "update_delayed_event" -> {
                val delayId = data["delay_id"]?.jsonPrimitive?.contentOrNull()
                val delayedAction = data["action"]?.jsonPrimitive?.contentOrNull()
                val cached = delayId?.let { delayedEvents[it] }
                when (delayedAction) {
                    "send" -> {
                        if (cached != null) {
                            delayedEvents.remove(delayId)
                            val sk = cached.stateKey
                            val eventId = if (sk != null) {
                                runCatching { matrixSendStateEvent(cached.type, sk, cached.content) }
                                    .onFailure { println("[WidgetApi] delayed send_event (state) threw: ${it.message}") }
                                    .getOrNull()
                            } else {
                                runCatching { matrixSendMessageEvent(cached.type, cached.content) }
                                    .onFailure { println("[WidgetApi] delayed send_event (message) threw: ${it.message}") }
                                    .getOrNull()
                            }
                            println(
                                "[WidgetApi] update_delayed_event=send delay_id=$delayId committed " +
                                    "type=${cached.type} stateKey=$sk contentKeys=${cached.content.keys} eventId=$eventId"
                            )
                        } else {
                            println("[WidgetApi] update_delayed_event=send delay_id=$delayId — no cached payload, ack only")
                        }
                    }
                    "cancel" -> {
                        val removed = delayedEvents.remove(delayId) != null
                        println("[WidgetApi] update_delayed_event=cancel delay_id=$delayId removed=$removed")
                    }
                    "restart" -> {
                        // Heartbeat — nothing to do, we're not actually scheduling.
                        // (Avoid spamming the log: only print when cache miss.)
                        if (cached == null) {
                            println("[WidgetApi] update_delayed_event=restart delay_id=$delayId — UNKNOWN delay_id (cache miss)")
                        }
                    }
                    else -> {
                        println("[WidgetApi] update_delayed_event delay_id=$delayId action=$delayedAction — unknown action, ack only")
                    }
                }
                listOf(buildResponse(msg, buildJsonObject { /* empty ack */ }))
            }

            "send_to_device", "org.matrix.msc3819.send_to_device" -> {
                val type = data["type"]?.jsonPrimitive?.contentOrNull().orEmpty()
                val messages = (data["messages"] as? JsonObject) ?: JsonObject(emptyMap())
                val encryptedNode = data["encrypted"]?.jsonPrimitive
                val encrypted = encryptedNode != null && (encryptedNode.content == "true" || encryptedNode.contentOrNull() == "true")
                val ok = runCatching { matrixSendToDevice(type, messages, encrypted) }.getOrDefault(false)
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

            "get_openid" -> {
                // MSC1960. Пытаемся синхронно вернуть state=allowed с реальным токеном;
                // если запрос упал — возвращаем state=blocked, чтобы EC явно показал ошибку, а не висел.
                val token = runCatching { matrixGetOpenIdToken() }
                    .onFailure { println("[WidgetApi] get_openid: token fetch threw: ${it.message}") }
                    .getOrNull()
                if (token != null) {
                    println("[WidgetApi] get_openid: returning allowed, server=${token["matrix_server_name"]} expires_in=${token["expires_in"]}")
                    listOf(buildResponse(msg, buildJsonObject {
                        put("state", JsonPrimitive("allowed"))
                        put("access_token", JsonPrimitive(token["access_token"] ?: ""))
                        put("token_type", JsonPrimitive(token["token_type"] ?: "Bearer"))
                        put("matrix_server_name", JsonPrimitive(token["matrix_server_name"] ?: ""))
                        token["expires_in"]?.toLongOrNull()?.let {
                            put("expires_in", JsonPrimitive(it))
                        }
                    }))
                } else {
                    println("[WidgetApi] get_openid: token fetch failed — returning blocked")
                    listOf(buildResponse(msg, buildJsonObject {
                        put("state", JsonPrimitive("blocked"))
                    }))
                }
            }

            "io.element.join", "io.element.leave", "io.element.device_mute",
            "io.element.tile_layout", "io.element.spotlight_layout",
            "set_always_on_screen", "io.element.close" -> {
                listOf(buildResponse(msg, buildJsonObject { /* empty ack */ }))
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
     * Flush all cached MSC4140 delayed events by actually sending them via the
     * Matrix API. Called when the bridge is being torn down (call leave, app
     * close) so we don't leak ghost membership state events that EC was
     * counting on the homeserver to auto-fire.
     *
     * Returns the number of events committed.
     */
    suspend fun flushPendingDelayedEvents(): Int {
        if (delayedEvents.isEmpty()) return 0
        val snapshot = delayedEvents.toMap()
        delayedEvents.clear()
        println("[WidgetApi] flushPendingDelayedEvents: firing ${snapshot.size} pending event(s) on teardown")
        var committed = 0
        snapshot.forEach { (delayId, ev) ->
            val sk = ev.stateKey
            val eventId = if (sk != null) {
                runCatching { matrixSendStateEvent(ev.type, sk, ev.content) }
                    .onFailure { println("[WidgetApi] flushPendingDelayedEvents send_state(delay_id=$delayId) failed: ${it.message}") }
                    .getOrNull()
            } else {
                runCatching { matrixSendMessageEvent(ev.type, ev.content) }
                    .onFailure { println("[WidgetApi] flushPendingDelayedEvents send_message(delay_id=$delayId) failed: ${it.message}") }
                    .getOrNull()
            }
            if (eventId != null) committed++
            println(
                "[WidgetApi] flushPendingDelayedEvents committed delay_id=$delayId " +
                    "type=${ev.type} stateKey=$sk contentKeys=${ev.content.keys} eventId=$eventId"
            )
        }
        return committed
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

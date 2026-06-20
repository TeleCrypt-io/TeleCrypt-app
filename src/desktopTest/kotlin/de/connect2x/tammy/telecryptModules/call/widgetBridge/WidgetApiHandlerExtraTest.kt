package de.connect2x.tammy.telecryptModules.call.widgetBridge

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Edge-case coverage for [WidgetApiHandler] branches not in the main suite. */
class WidgetApiHandlerExtraTest {

    private var lastMessageType: String? = null

    private fun handler(token: Map<String, String>? = null) = WidgetApiHandler(
        widgetId = "w1",
        userId = "@a:example.org",
        deviceId = "DEV",
        roomId = "!r:example.org",
        matrixSendToDevice = { _, _, _ -> true },
        matrixSendStateEvent = { _, _, _ -> "\$e" },
        matrixSendMessageEvent = { type, _ -> lastMessageType = type; "\$m" },
        matrixReadStateEvents = { _, _, _ -> emptyList() },
        matrixGetOpenIdToken = { token },
    )

    private fun msg(action: String, data: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
        put("api", JsonPrimitive("fromWidget"))
        put("widgetId", JsonPrimitive("w1"))
        put("requestId", JsonPrimitive("r"))
        put("action", JsonPrimitive(action))
        put("data", data)
    }.toString()

    private fun parse(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun contentLoadedAcksAndRequestsCapabilities(): Unit = runBlocking {
        val out = handler().handleMessage(msg("content_loaded"))
        assertEquals(2, out.size)
        assertEquals("toWidget", parse(out[1])["api"]!!.jsonPrimitive.content)
        assertEquals("capabilities", parse(out[1])["action"]!!.jsonPrimitive.content)
    }

    @Test
    fun capabilitiesEchoesRequestedSet(): Unit = runBlocking {
        val data = buildJsonObject {
            put("capabilities", buildJsonArray {
                add(JsonPrimitive("org.matrix.msc2762.send.state_event"))
                add(JsonPrimitive("org.matrix.msc3819.send.to_device"))
            })
        }
        val out = handler().handleMessage(msg("capabilities", data))
        val caps = parse(out[0])["response"]!!.jsonObject["capabilities"]!!
        assertTrue(caps.toString().contains("send.to_device"))
    }

    @Test
    fun messageEventWithoutStateKeyHasNoSelfEcho(): Unit = runBlocking {
        val data = buildJsonObject {
            put("type", JsonPrimitive("m.reaction"))
            put("content", buildJsonObject { put("x", JsonPrimitive(1)) })
        }
        val out = handler().handleMessage(msg("send_event", data))
        assertEquals(1, out.size) // ack only, no echo
        assertEquals("m.reaction", lastMessageType)
    }

    @Test
    fun updateDelayedEventCancelIsAckOnly(): Unit = runBlocking {
        val out = handler().handleMessage(
            msg("update_delayed_event", buildJsonObject {
                put("delay_id", JsonPrimitive("nope"))
                put("action", JsonPrimitive("cancel"))
            }),
        )
        assertEquals(1, out.size)
    }

    @Test
    fun getOpenIdBlockedWhenNoToken(): Unit = runBlocking {
        val out = handler(token = null).handleMessage(msg("get_openid"))
        assertEquals("blocked", parse(out[0])["response"]!!.jsonObject["state"]!!.jsonPrimitive.content)
    }

    @Test
    fun turnServerAndRoomSubscriptionActionsAck(): Unit = runBlocking {
        for (a in listOf("watch_turn_servers", "unwatch_turn_servers", "subscribe_to_room", "unsubscribe_from_room")) {
            assertEquals(1, handler().handleMessage(msg(a)).size, "for $a")
        }
    }

    @Test
    fun readRelationsReturnsEmptyChunk(): Unit = runBlocking {
        val out = handler().handleMessage(msg("org.matrix.msc3869.read_relations"))
        val chunk = parse(out[0])["response"]!!.jsonObject["chunk"]
        assertEquals("[]", chunk.toString())
    }

    @Test
    fun unhandledActionRepliesEmptyAck(): Unit = runBlocking {
        val out = handler().handleMessage(msg("totally.unknown.action"))
        assertEquals(1, out.size)
        assertNull(parse(out[0])["response"]!!.jsonObject["error"])
    }

    @Test
    fun elementLifecycleActionsAck(): Unit = runBlocking {
        for (a in listOf("io.element.join", "io.element.leave", "io.element.device_mute", "set_always_on_screen")) {
            assertEquals(1, handler().handleMessage(msg(a)).size, "for $a")
        }
    }
}

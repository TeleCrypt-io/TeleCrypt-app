package de.connect2x.tammy.telecryptModules.call.widgetBridge

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [WidgetApiHandler] — the platform-independent Matrix Widget API
 * host used by the Element Call bridge. Exercises the behaviours that proved
 * critical for working calls: state-event self-echo, MSC4140 delayed-event
 * fake-ack + deferred commit, read_events wrapping, send_to_device, and the
 * io.element.close teardown signal.
 */
class WidgetApiHandlerTest {

    private val roomId = "!room:example.org"
    private val userId = "@alice:example.org"
    private val deviceId = "DEVICE1"

    /** Records callback invocations so tests can assert what reached trixnity. */
    private class Recorder {
        data class StateEvent(val type: String, val stateKey: String, val content: JsonObject)
        data class ToDevice(val type: String, val messages: JsonObject, val encrypted: Boolean)

        val stateEvents = mutableListOf<StateEvent>()
        val messageEvents = mutableListOf<Pair<String, JsonObject>>()
        val toDevices = mutableListOf<ToDevice>()
        var readCalls = 0
        var closeCalls = 0
    }

    private fun handler(
        rec: Recorder,
        stateEventId: String? = "\$evt1",
        sendToDeviceOk: Boolean = true,
        readResult: List<JsonObject> = emptyList(),
        openIdToken: Map<String, String>? = null,
    ) = WidgetApiHandler(
        widgetId = "w1",
        userId = userId,
        deviceId = deviceId,
        roomId = roomId,
        matrixSendToDevice = { type, messages, encrypted ->
            rec.toDevices += Recorder.ToDevice(type, messages, encrypted)
            sendToDeviceOk
        },
        matrixSendStateEvent = { type, stateKey, content ->
            rec.stateEvents += Recorder.StateEvent(type, stateKey, content)
            stateEventId
        },
        matrixSendMessageEvent = { type, content ->
            rec.messageEvents += (type to content)
            "\$msg1"
        },
        matrixReadStateEvents = { _, _, _ ->
            rec.readCalls++
            readResult
        },
        matrixGetOpenIdToken = { openIdToken },
        onClose = { rec.closeCalls++ },
    )

    private fun fromWidget(action: String, data: JsonObject, requestId: String = "req1"): String =
        buildJsonObject {
            put("api", JsonPrimitive("fromWidget"))
            put("widgetId", JsonPrimitive("w1"))
            put("requestId", JsonPrimitive(requestId))
            put("action", JsonPrimitive(action))
            put("data", data)
        }.toString()

    private fun parse(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test
    fun supportedApiVersionsReturnsVersions(): Unit = runBlocking {
        val out = handler(Recorder()).handleMessage(fromWidget("supported_api_versions", JsonObject(emptyMap())))
        assertEquals(1, out.size)
        val versions = parse(out[0])["response"]!!.jsonObject["supported_versions"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertTrue("0.1.0" in versions, "expected 0.1.0 in $versions")
    }

    @Test
    fun sendStateEventCommitsAndSelfEchoes(): Unit = runBlocking {
        val rec = Recorder()
        val data = buildJsonObject {
            put("type", JsonPrimitive("org.matrix.msc3401.call.member"))
            put("state_key", JsonPrimitive("_${userId}_${deviceId}_m.call"))
            put("content", buildJsonObject { put("application", JsonPrimitive("m.call")) })
        }
        val out = handler(rec).handleMessage(fromWidget("send_event", data))

        // One real state event committed to trixnity.
        assertEquals(1, rec.stateEvents.size)
        assertEquals("org.matrix.msc3401.call.member", rec.stateEvents[0].type)

        // Response (ack) + self-echo toWidget send_event = 2 outputs.
        assertEquals(2, out.size)
        val ack = parse(out[0])
        assertEquals("\$evt1", ack["response"]!!.jsonObject["event_id"]!!.jsonPrimitive.content)
        val echo = parse(out[1])
        assertEquals("toWidget", echo["api"]!!.jsonPrimitive.content)
        assertEquals("send_event", echo["action"]!!.jsonPrimitive.content)
        assertEquals(
            "_${userId}_${deviceId}_m.call",
            echo["data"]!!.jsonObject["state_key"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun delayedSendEventIsFakeAckedNotCommitted(): Unit = runBlocking {
        val rec = Recorder()
        val data = buildJsonObject {
            put("type", JsonPrimitive("org.matrix.msc3401.call.member"))
            put("state_key", JsonPrimitive("_${userId}_${deviceId}_m.call"))
            put("content", JsonObject(emptyMap()))
            put("delay", JsonPrimitive(90000))
        }
        val out = handler(rec).handleMessage(fromWidget("send_event", data))

        // MSC4140 dead-man's-switch must NOT be posted immediately.
        assertEquals(0, rec.stateEvents.size)
        assertEquals(1, out.size)
        val delayId = parse(out[0])["response"]!!.jsonObject["delay_id"]?.jsonPrimitive?.content
        assertNotNull(delayId, "expected a delay_id in the fake-ack")
    }

    @Test
    fun updateDelayedEventSendCommitsCachedTombstoneAndEchoes(): Unit = runBlocking {
        val rec = Recorder()
        val h = handler(rec)
        // First schedule a delayed (empty) tombstone.
        val delayId = parse(
            h.handleMessage(
                fromWidget(
                    "send_event",
                    buildJsonObject {
                        put("type", JsonPrimitive("org.matrix.msc3401.call.member"))
                        put("state_key", JsonPrimitive("_${userId}_${deviceId}_m.call"))
                        put("content", JsonObject(emptyMap()))
                        put("delay", JsonPrimitive(90000))
                    },
                ),
            )[0],
        )["response"]!!.jsonObject["delay_id"]!!.jsonPrimitive.content
        assertEquals(0, rec.stateEvents.size)

        // Now fire it.
        val out = h.handleMessage(
            fromWidget(
                "update_delayed_event",
                buildJsonObject {
                    put("delay_id", JsonPrimitive(delayId))
                    put("action", JsonPrimitive("send"))
                },
            ),
        )
        // The cached tombstone is now actually committed + self-echoed.
        assertEquals(1, rec.stateEvents.size)
        assertEquals("_${userId}_${deviceId}_m.call", rec.stateEvents[0].stateKey)
        assertEquals(2, out.size)
        assertEquals("toWidget", parse(out[1])["api"]!!.jsonPrimitive.content)
    }

    @Test
    fun updateDelayedEventRestartIsAckOnly(): Unit = runBlocking {
        val rec = Recorder()
        val out = handler(rec).handleMessage(
            fromWidget(
                "update_delayed_event",
                buildJsonObject {
                    put("delay_id", JsonPrimitive("unknown"))
                    put("action", JsonPrimitive("restart"))
                },
            ),
        )
        assertEquals(0, rec.stateEvents.size)
        assertEquals(1, out.size)
    }

    @Test
    fun readEventsWrapsResultInEventsArray(): Unit = runBlocking {
        val rec = Recorder()
        val ev = buildJsonObject {
            put("type", JsonPrimitive("m.room.create"))
            put("state_key", JsonPrimitive(""))
        }
        val out = handler(rec, readResult = listOf(ev)).handleMessage(
            fromWidget(
                "org.matrix.msc2876.read_events",
                buildJsonObject {
                    put("type", JsonPrimitive("m.room.create"))
                    put("state_key", JsonPrimitive(true))
                },
            ),
        )
        assertEquals(1, rec.readCalls)
        val events = parse(out[0])["response"]!!.jsonObject["events"]!!.jsonArray
        assertEquals(1, events.size)
        assertEquals("m.room.create", events[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun sendToDeviceForwardsEncryptedFlagAndAcksOnSuccess(): Unit = runBlocking {
        val rec = Recorder()
        val out = handler(rec, sendToDeviceOk = true).handleMessage(
            fromWidget(
                "send_to_device",
                buildJsonObject {
                    put("type", JsonPrimitive("io.element.call.encryption_keys"))
                    put("encrypted", JsonPrimitive(true))
                    put("messages", buildJsonObject {
                        put("@bob:example.org", buildJsonObject {
                            put("DEV", buildJsonObject { put("k", JsonPrimitive("v")) })
                        })
                    })
                },
            ),
        )
        assertEquals(1, rec.toDevices.size)
        assertEquals("io.element.call.encryption_keys", rec.toDevices[0].type)
        assertTrue(rec.toDevices[0].encrypted)
        // Success ack carries no error.
        assertNull(parse(out[0])["response"]!!.jsonObject["error"])
    }

    @Test
    fun sendToDeviceReturnsErrorOnFailure(): Unit = runBlocking {
        val rec = Recorder()
        val out = handler(rec, sendToDeviceOk = false).handleMessage(
            fromWidget(
                "send_to_device",
                buildJsonObject {
                    put("type", JsonPrimitive("io.element.call.encryption_keys"))
                    put("encrypted", JsonPrimitive(false))
                    put("messages", JsonObject(emptyMap()))
                },
            ),
        )
        val error = parse(out[0])["response"]!!.jsonObject["error"]
        assertNotNull(error, "failed send_to_device should produce an error response")
    }

    @Test
    fun ioElementCloseInvokesOnCloseAndAcks(): Unit = runBlocking {
        val rec = Recorder()
        val out = handler(rec).handleMessage(fromWidget("io.element.close", JsonObject(emptyMap())))
        assertEquals(1, rec.closeCalls)
        assertEquals(1, out.size)
    }

    @Test
    fun getOpenIdReturnsAllowedWhenTokenPresent(): Unit = runBlocking {
        val rec = Recorder()
        val out = handler(
            rec,
            openIdToken = mapOf(
                "access_token" to "tok",
                "token_type" to "Bearer",
                "matrix_server_name" to "example.org",
                "expires_in" to "3600",
            ),
        ).handleMessage(fromWidget("get_openid", JsonObject(emptyMap())))
        val resp = parse(out[0])["response"]!!.jsonObject
        assertEquals("allowed", resp["state"]!!.jsonPrimitive.content)
        assertEquals("tok", resp["access_token"]!!.jsonPrimitive.content)
    }

    @Test
    fun malformedMessageIsDropped(): Unit = runBlocking {
        // Missing "action" → handler drops it with no responses.
        val out = handler(Recorder()).handleMessage(
            buildJsonObject {
                put("api", JsonPrimitive("fromWidget"))
                put("requestId", JsonPrimitive("x"))
            }.toString(),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun flushPendingDelayedEventsCommitsCached(): Unit = runBlocking {
        val rec = Recorder()
        val h = handler(rec)
        h.handleMessage(
            fromWidget(
                "send_event",
                buildJsonObject {
                    put("type", JsonPrimitive("org.matrix.msc3401.call.member"))
                    put("state_key", JsonPrimitive("_${userId}_${deviceId}_m.call"))
                    put("content", JsonObject(emptyMap()))
                    put("delay", JsonPrimitive(90000))
                },
            ),
        )
        assertEquals(0, rec.stateEvents.size)
        val committed = h.flushPendingDelayedEvents()
        assertEquals(1, committed)
        assertEquals(1, rec.stateEvents.size)
    }
}

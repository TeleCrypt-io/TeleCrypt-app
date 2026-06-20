package de.connect2x.tammy.telecryptModules.call.callRtc

import de.connect2x.tammy.trixnity.callRtc.InMemoryMatrixRtcCallStateStore
import de.connect2x.tammy.trixnity.callRtc.MatrixRtcEventParser
import de.connect2x.tammy.trixnity.callRtc.MatrixRtcService
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.UserId
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Load/throughput test for the MatrixRTC signalling layer (parser + state
 * machine). It feeds a large stream of synthetic `m.call.member` events and
 * reports events/sec and per-event latency — the numbers used in the project
 * presentation for "нагрузочное тестирование сигнального слоя".
 *
 * Assertions only guard against gross regressions (a very low floor) so the
 * test never flakes on slow CI; the printed figures are the real result.
 */
class MatrixRtcLoadTest {

    private val n = (System.getenv("RTC_LOAD_N")?.toIntOrNull() ?: 30_000)
    private val roomCount = 100

    private fun memberContent(user: String, device: String): JsonObject = buildJsonObject {
        put("slot_id", JsonPrimitive("default"))
        put("sticky_key", JsonPrimitive("_${user}_${device}_m.call"))
        put("application", buildJsonObject {
            put("type", JsonPrimitive("m.call"))
            put("m.call.id", JsonPrimitive("call-1"))
        })
        put("member", buildJsonObject {
            put("id", JsonPrimitive("device:$device"))
            put("claimed_user_id", JsonPrimitive(user))
            put("claimed_device_id", JsonPrimitive(device))
        })
        put("rtc_transports", buildJsonArray { add(buildJsonObject { put("type", JsonPrimitive("livekit")) }) })
        put("expires_ts_ms", JsonPrimitive(Long.MAX_VALUE / 2))
    }

    private fun report(label: String, count: Int, nanos: Long) {
        val seconds = nanos / 1_000_000_000.0
        val perSec = count / seconds
        val usEach = nanos / 1000.0 / count
        println("[RTC-LOAD] %-22s n=%d  %.0f events/s  %.2f µs/event  (%.3fs total)"
            .format(label, count, perSec, usEach, seconds))
    }

    @Test
    fun parserThroughput() {
        val room = RoomId("!load:example.org")
        // Pre-build contents so we measure parsing, not JSON construction.
        val contents = Array(roomCount) { memberContent("@u$it:example.org", "DEV$it") }
        var parsed = 0
        val start = System.nanoTime()
        for (i in 0 until n) {
            val ev = MatrixRtcEventParser.parseMemberEvent(
                roomId = room,
                senderUserId = "@u${i % roomCount}:example.org",
                content = contents[i % roomCount],
                stateKey = null,
                localUserId = null,
                localDeviceId = null,
                nowMs = { 0L },
            )
            if (ev != null) parsed++
        }
        val elapsed = System.nanoTime() - start
        report("parser only", n, elapsed)
        assertTrue(parsed == n, "expected all $n events to parse, got $parsed")
        assertTrue(n / (elapsed / 1e9) > 1_000, "parser throughput unexpectedly low")
    }

    @Test
    fun fullPipelineThroughput() {
        val service = MatrixRtcService(InMemoryMatrixRtcCallStateStore()) { 0L }
        val rooms = Array(roomCount) { RoomId("!room$it:example.org") }
        // Open a slot in each room first so member events have a call to join.
        rooms.forEach { service.applySlotEvent(
            de.connect2x.tammy.trixnity.callRtc.MatrixRtcSlotEvent(it, "default", "call-1", true),
        ) }
        val contents = Array(roomCount) { memberContent("@u$it:example.org", "DEV$it") }
        var applied = 0
        val start = System.nanoTime()
        for (i in 0 until n) {
            val idx = i % roomCount
            val ev = MatrixRtcEventParser.parseMemberEvent(
                roomId = rooms[idx],
                senderUserId = "@u$idx:example.org",
                content = contents[idx],
                stateKey = null,
                localUserId = null,
                localDeviceId = null,
                nowMs = { 0L },
            ) ?: continue
            service.applyMemberEvent(ev)
            applied++
        }
        val elapsed = System.nanoTime() - start
        report("parse + state machine", n, elapsed)
        assertTrue(applied == n, "expected all events applied, got $applied")
        assertTrue(n / (elapsed / 1e9) > 1_000, "pipeline throughput unexpectedly low")
    }
}

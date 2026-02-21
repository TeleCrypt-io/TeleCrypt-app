package de.connect2x.tammy.trixnityProposal.callRtc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatrixRtcEventParserTest {
    private val roomId = RoomId("!r:example.org")

    private fun memberContent(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>): JsonObject {
        return JsonObject(pairs.toMap())
    }

    @Test
    fun slotEmptyContentIsClosed() {
        val slot = MatrixRtcEventParser.parseSlotEvent(roomId, "m.call#ROOM", JsonObject(emptyMap()))
        assertFalse(slot.open)
        assertNull(slot.callId)
        assertEquals("m.call#ROOM", slot.slotId)
    }

    @Test
    fun slotParsesCallIdFromDottedKey() {
        val slot = MatrixRtcEventParser.parseSlotEvent(
            roomId,
            "m.call#ROOM",
            JsonObject(
                mapOf(
                    "application" to JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("m.call"),
                            "m.call.id" to JsonPrimitive("call-123"),
                        )
                    )
                )
            )
        )
        assertTrue(slot.open)
        assertEquals("call-123", slot.callId)
    }

    @Test
    fun slotParsesCallIdFromNestedObject() {
        val slot = MatrixRtcEventParser.parseSlotEvent(
            roomId,
            "m.call#ROOM",
            JsonObject(
                mapOf(
                    "application" to JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("m.call"),
                            "m.call" to JsonObject(mapOf("id" to JsonPrimitive("call-456"))),
                        )
                    )
                )
            )
        )
        assertTrue(slot.open)
        assertEquals("call-456", slot.callId)
    }

    @Test
    fun memberConnectRequiresStickyKeyEqualsMemberId() {
        val member = MatrixRtcEventParser.parseMemberEvent(
            roomId = roomId,
            senderUserId = "@alice:example.org",
            content = JsonObject(
                mapOf(
                    "slot_id" to JsonPrimitive("m.call#ROOM"),
                    "sticky_key" to JsonPrimitive("sticky"),
                    "application" to JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("m.call"),
                            "m.call.id" to JsonPrimitive("call-1"),
                        )
                    ),
                    "member" to JsonObject(
                        mapOf(
                            "id" to JsonPrimitive("NOT-sticky"),
                            "claimed_user_id" to JsonPrimitive("@alice:example.org"),
                            "claimed_device_id" to JsonPrimitive("DEV"),
                        )
                    ),
                    "rtc_transports" to JsonArray(emptyList()),
                )
            ),
            stateKey = null,
            localUserId = null,
            localDeviceId = null,
            nowMs = { 0L },
        )
        assertNotNull(member)
        assertFalse(member.connected)
        assertEquals("sticky", member.stickyKey)
    }

    @Test
    fun memberConnectParsesCallIdAndDeviceAndIsLocal() {
        val member = MatrixRtcEventParser.parseMemberEvent(
            roomId = roomId,
            senderUserId = "@alice:example.org",
            content = JsonObject(
                mapOf(
                    "slot_id" to JsonPrimitive("m.call#ROOM"),
                    "sticky_key" to JsonPrimitive("xyz"),
                    "application" to JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("m.call"),
                            "m.call.id" to JsonPrimitive("call-2"),
                        )
                    ),
                    "member" to JsonObject(
                        mapOf(
                            "id" to JsonPrimitive("xyz"),
                            "claimed_user_id" to JsonPrimitive("@alice:example.org"),
                            "claimed_device_id" to JsonPrimitive("DEV1"),
                        )
                    ),
                    "rtc_transports" to JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "type" to JsonPrimitive("livekit"),
                                )
                            )
                        )
                    ),
                )
            ),
            stateKey = null,
            localUserId = UserId("@alice:example.org"),
            localDeviceId = "DEV1",
            nowMs = { 0L },
        )
        assertNotNull(member)
        assertTrue(member.connected)
        assertEquals("call-2", member.callId)
        assertEquals("DEV1", member.deviceId)
        assertTrue(member.isLocal)
    }

    @Test
    fun memberConnectRequiresRtcTransportsField() {
        val member = MatrixRtcEventParser.parseMemberEvent(
            roomId = roomId,
            senderUserId = "@alice:example.org",
            content = memberContent(
                "slot_id" to JsonPrimitive("m.call#ROOM"),
                "sticky_key" to JsonPrimitive("xyz"),
                "application" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("m.call"),
                        "m.call.id" to JsonPrimitive("call-2"),
                    )
                ),
                "member" to JsonObject(
                    mapOf(
                        "id" to JsonPrimitive("xyz"),
                        "claimed_user_id" to JsonPrimitive("@alice:example.org"),
                        "claimed_device_id" to JsonPrimitive("DEV1"),
                    )
                ),
            ),
            stateKey = null,
            localUserId = null,
            localDeviceId = null,
            nowMs = { 0L },
        )
        assertNotNull(member)
        assertFalse(member.connected)
        assertEquals("xyz", member.stickyKey)
        assertEquals("call-2", member.callId)
    }

    @Test
    fun memberDisconnectWithoutApplicationStillParses() {
        val member = MatrixRtcEventParser.parseMemberEvent(
            roomId = roomId,
            senderUserId = "@alice:example.org",
            content = JsonObject(
                mapOf(
                    "slot_id" to JsonPrimitive("m.call#ROOM"),
                    "sticky_key" to JsonPrimitive("abc"),
                )
            ),
            stateKey = null,
            localUserId = null,
            localDeviceId = null,
            nowMs = { 0L },
        )
        assertNotNull(member)
        assertFalse(member.connected)
        assertEquals("abc", member.stickyKey)
        assertEquals("", member.callId)
    }

    @Test
    fun memberDisconnectedTrueForcesDisconnect() {
        val member = MatrixRtcEventParser.parseMemberEvent(
            roomId = roomId,
            senderUserId = "@alice:example.org",
            content = memberContent(
                "slot_id" to JsonPrimitive("m.call#ROOM"),
                "sticky_key" to JsonPrimitive("abc"),
                "disconnected" to JsonPrimitive(true),
                "application" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("m.call"),
                        "m.call.id" to JsonPrimitive("call-999"),
                    )
                ),
                "member" to JsonObject(
                    mapOf(
                        "id" to JsonPrimitive("abc"),
                        "claimed_user_id" to JsonPrimitive("@alice:example.org"),
                        "claimed_device_id" to JsonPrimitive("DEV"),
                    )
                ),
                "rtc_transports" to JsonArray(emptyList()),
                "expires_ts" to JsonPrimitive("1700000000000"),
            ),
            stateKey = null,
            localUserId = UserId("@alice:example.org"),
            localDeviceId = "DEV",
            nowMs = { 0L },
        )
        assertNotNull(member)
        assertFalse(member.connected)
        assertEquals("", member.callId)
        assertEquals(0L, member.expiresAtMs)
        assertEquals("abc", member.stickyKey)
        assertTrue(member.isLocal)
    }

    @Test
    fun malformedMemberObjectDoesNotCrashAndIsDisconnect() {
        val member = MatrixRtcEventParser.parseMemberEvent(
            roomId = roomId,
            senderUserId = "@alice:example.org",
            content = memberContent(
                "slot_id" to JsonPrimitive("m.call#ROOM"),
                "sticky_key" to JsonPrimitive("abc"),
                "application" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("m.call"),
                        "m.call.id" to JsonPrimitive("call-x"),
                    )
                ),
                "member" to JsonPrimitive("not-an-object"),
                "rtc_transports" to JsonArray(emptyList()),
            ),
            stateKey = null,
            localUserId = null,
            localDeviceId = null,
            nowMs = { 0L },
        )
        assertNotNull(member)
        assertFalse(member.connected)
        assertEquals("abc", member.stickyKey)
        assertEquals("call-x", member.callId)
    }

    @Test
    fun invalidApplicationShapeDoesNotCrash() {
        val member = MatrixRtcEventParser.parseMemberEvent(
            roomId = roomId,
            senderUserId = "@alice:example.org",
            content = memberContent(
                "slot_id" to JsonPrimitive("m.call#ROOM"),
                "sticky_key" to JsonPrimitive("abc"),
                "application" to JsonPrimitive("not-an-object"),
            ),
            stateKey = null,
            localUserId = null,
            localDeviceId = null,
            nowMs = { 0L },
        )
        assertNotNull(member)
        assertFalse(member.connected)
        assertEquals("abc", member.stickyKey)
        assertEquals("", member.callId)
    }

    @Test
    fun invalidExpiryValueIsIgnored() {
        val now = 10_000L
        val member = MatrixRtcEventParser.parseMemberEvent(
            roomId = roomId,
            senderUserId = "@alice:example.org",
            content = memberContent(
                "slot_id" to JsonPrimitive("m.call#ROOM"),
                "sticky_key" to JsonPrimitive("abc"),
                "expires_ts" to JsonPrimitive("not-a-number"),
                "ttl_ms" to JsonPrimitive("500"),
            ),
            stateKey = null,
            localUserId = null,
            localDeviceId = null,
            nowMs = { now },
        )
        assertNotNull(member)
        assertEquals(now + 500L, member.expiresAtMs)
    }

    @Test
    fun missingStickyKeyAndStateKeyReturnsNull() {
        val member = MatrixRtcEventParser.parseMemberEvent(
            roomId = roomId,
            senderUserId = "@alice:example.org",
            content = memberContent(
                "slot_id" to JsonPrimitive("m.call#ROOM"),
            ),
            stateKey = null,
            localUserId = null,
            localDeviceId = null,
            nowMs = { 0L },
        )
        assertNull(member)
    }

    @Test
    fun slotIdAliasesAreAccepted() {
        val member1 = MatrixRtcEventParser.parseMemberEvent(
            roomId = roomId,
            senderUserId = "@alice:example.org",
            content = memberContent(
                "slotId" to JsonPrimitive("m.call#ROOM"),
                "sticky_key" to JsonPrimitive("abc"),
            ),
            stateKey = null,
            localUserId = null,
            localDeviceId = null,
            nowMs = { 0L },
        )
        assertNotNull(member1)
        assertEquals("m.call#ROOM", member1.slotId)

        val member2 = MatrixRtcEventParser.parseMemberEvent(
            roomId = roomId,
            senderUserId = "@alice:example.org",
            content = memberContent(
                "slot" to JsonPrimitive("m.call#ROOM"),
                "sticky_key" to JsonPrimitive("abc"),
            ),
            stateKey = null,
            localUserId = null,
            localDeviceId = null,
            nowMs = { 0L },
        )
        assertNotNull(member2)
        assertEquals("m.call#ROOM", member2.slotId)
    }

    @Test
    fun memberWithOtherApplicationTypeIsIgnored() {
        val member = MatrixRtcEventParser.parseMemberEvent(
            roomId = roomId,
            senderUserId = "@alice:example.org",
            content = JsonObject(
                mapOf(
                    "slot_id" to JsonPrimitive("m.game#ROOM"),
                    "sticky_key" to JsonPrimitive("abc"),
                    "application" to JsonObject(mapOf("type" to JsonPrimitive("m.game"))),
                )
            ),
            stateKey = null,
            localUserId = null,
            localDeviceId = null,
            nowMs = { 0L },
        )
        assertNull(member)
    }

    @Test
    fun memberStickyKeyFallsBackToStateKey() {
        val member = MatrixRtcEventParser.parseMemberEvent(
            roomId = roomId,
            senderUserId = "@alice:example.org",
            content = JsonObject(
                mapOf(
                    "slot_id" to JsonPrimitive("m.call#ROOM"),
                )
            ),
            stateKey = "state-key-sticky",
            localUserId = null,
            localDeviceId = null,
            nowMs = { 0L },
        )
        assertNotNull(member)
        assertEquals("state-key-sticky", member.stickyKey)
        assertFalse(member.connected)
    }

    @Test
    fun memberStickyKeySupportsMsc4354Alias() {
        val member = MatrixRtcEventParser.parseMemberEvent(
            roomId = roomId,
            senderUserId = "@alice:example.org",
            content = JsonObject(
                mapOf(
                    "slot_id" to JsonPrimitive("m.call#ROOM"),
                    "msc4354_sticky_key" to JsonPrimitive("alias-sticky"),
                )
            ),
            stateKey = null,
            localUserId = null,
            localDeviceId = null,
            nowMs = { 0L },
        )
        assertNotNull(member)
        assertEquals("alias-sticky", member.stickyKey)
        assertFalse(member.connected)
    }

    @Test
    fun expiresTsSecondsIsNormalizedToMs() {
        val now = 1_000L
        val member = MatrixRtcEventParser.parseMemberEvent(
            roomId = roomId,
            senderUserId = "@alice:example.org",
            content = JsonObject(
                mapOf(
                    "slot_id" to JsonPrimitive("m.call#ROOM"),
                    "sticky_key" to JsonPrimitive("abc"),
                    "expires_ts" to JsonPrimitive("1700000000"),
                )
            ),
            stateKey = null,
            localUserId = null,
            localDeviceId = null,
            nowMs = { now },
        )
        assertNotNull(member)
        assertEquals(1_700_000_000_000L, member.expiresAtMs)
    }

    @Test
    fun ttlMsIsTreatedAsDuration() {
        val now = 10_000L
        val member = MatrixRtcEventParser.parseMemberEvent(
            roomId = roomId,
            senderUserId = "@alice:example.org",
            content = JsonObject(
                mapOf(
                    "slot_id" to JsonPrimitive("m.call#ROOM"),
                    "sticky_key" to JsonPrimitive("abc"),
                    "ttl_ms" to JsonPrimitive("500"),
                )
            ),
            stateKey = null,
            localUserId = null,
            localDeviceId = null,
            nowMs = { now },
        )
        assertNotNull(member)
        assertEquals(now + 500L, member.expiresAtMs)
    }

    @Test
    fun ttlSecondsIsTreatedAsSecondsDuration() {
        val now = 10_000L
        val member = MatrixRtcEventParser.parseMemberEvent(
            roomId = roomId,
            senderUserId = "@alice:example.org",
            content = memberContent(
                "slot_id" to JsonPrimitive("m.call#ROOM"),
                "sticky_key" to JsonPrimitive("abc"),
                "ttl" to JsonPrimitive("5"),
            ),
            stateKey = null,
            localUserId = null,
            localDeviceId = null,
            nowMs = { now },
        )
        assertNotNull(member)
        assertEquals(now + 5_000L, member.expiresAtMs)
    }

    @Test
    fun ttlIsAnchoredToOriginTimestampWhenProvided() {
        val originTs = 1_000_000L
        val member = MatrixRtcEventParser.parseMemberEvent(
            roomId = roomId,
            senderUserId = "@alice:example.org",
            content = memberContent(
                "slot_id" to JsonPrimitive("m.call#ROOM"),
                "sticky_key" to JsonPrimitive("abc"),
                "ttl_ms" to JsonPrimitive("500"),
            ),
            stateKey = null,
            localUserId = null,
            localDeviceId = null,
            nowMs = { 10_000L },
            originTimestampMs = originTs,
            unsignedAgeMs = 123L,
        )
        assertNotNull(member)
        assertEquals(originTs + 500L, member.expiresAtMs)
    }

    @Test
    fun absoluteExpiryTakesPrecedenceOverDuration() {
        val now = 10_000L
        val member = MatrixRtcEventParser.parseMemberEvent(
            roomId = roomId,
            senderUserId = "@alice:example.org",
            content = memberContent(
                "slot_id" to JsonPrimitive("m.call#ROOM"),
                "sticky_key" to JsonPrimitive("abc"),
                "expires_ts" to JsonPrimitive("1700000000000"),
                "ttl_ms" to JsonPrimitive("500"),
            ),
            stateKey = null,
            localUserId = null,
            localDeviceId = null,
            nowMs = { now },
        )
        assertNotNull(member)
        assertEquals(1_700_000_000_000L, member.expiresAtMs)
    }
}

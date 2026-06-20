package de.connect2x.tammy.telecryptModules.call.widgetBridge

import kotlinx.serialization.json.JsonObject
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.UserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class BridgeForwardingRegistryTest {

    private val userA = UserId("@a:example.org")
    private val userB = UserId("@b:example.org")
    private val room1 = RoomId("!r1:example.org")
    private val room2 = RoomId("!r2:example.org")

    /** Minimal session stub — the registry only stores and returns it. */
    private fun session() = object : WidgetBridgeManager.BridgeSession {
        override val hostUrl: String = "http://127.0.0.1/host"
        override fun forwardSyncEvent(rawEvent: JsonObject) {}
        override fun forwardToDeviceEvent(rawEvent: JsonObject) {}
        override fun close() {}
    }

    @Test
    fun registerThenForRoomReturnsSameSession() {
        val reg = BridgeForwardingRegistry()
        val s = session()
        reg.register(userA, room1, s)
        assertSame(s, reg.forRoom(userA, room1))
    }

    @Test
    fun forRoomIsKeyedByUserAndRoom() {
        val reg = BridgeForwardingRegistry()
        val s1 = session()
        reg.register(userA, room1, s1)
        // different user, same room → no match
        assertNull(reg.forRoom(userB, room1))
        // same user, different room → no match
        assertNull(reg.forRoom(userA, room2))
    }

    @Test
    fun unregisterRemovesSession() {
        val reg = BridgeForwardingRegistry()
        reg.register(userA, room1, session())
        reg.unregister(userA, room1)
        assertNull(reg.forRoom(userA, room1))
    }

    @Test
    fun sessionsForUserReturnsAllRoomsOfThatUserOnly() {
        val reg = BridgeForwardingRegistry()
        reg.register(userA, room1, session())
        reg.register(userA, room2, session())
        reg.register(userB, room1, session())
        assertEquals(2, reg.sessionsForUser(userA).size)
        assertEquals(1, reg.sessionsForUser(userB).size)
    }

    @Test
    fun registerSameKeyTwiceOverwrites() {
        val reg = BridgeForwardingRegistry()
        val first = session()
        val second = session()
        reg.register(userA, room1, first)
        reg.register(userA, room1, second)
        assertSame(second, reg.forRoom(userA, room1))
        assertEquals(1, reg.sessionsForUser(userA).size)
    }
}

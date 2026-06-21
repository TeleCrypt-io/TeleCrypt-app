package de.connect2x.tammy.telecryptModules.call.callBackend

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ElementCallUrlTest {

    private val roomId = "!abc:example.org"

    @Test
    fun standaloneUrlHasBaseAliasAndCoreParams() {
        val url = buildElementCallUrl(roomId, "Team Room", "Alice")
        assertTrue(url.startsWith("https://call.element.io/room/#/"), url)
        // alias derived from room name, space percent-encoded
        assertContains(url, "Team%20Room?")
        assertContains(url, "roomId=%21abc%3Aexample.org")
        assertContains(url, "displayName=Alice")
        assertContains(url, "confineToRoom=true")
        assertContains(url, "appPrompt=false")
        assertContains(url, "intent=start_call")
    }

    @Test
    fun viaServersAndHomeserverDerivedFromRoomId() {
        val url = buildElementCallUrl(roomId, "r", "n")
        assertContains(url, "viaServers=example.org")
        // homeserver derived from the room id server part
        assertContains(url, "homeserver=https%3A%2F%2Fexample.org")
    }

    @Test
    fun explicitHomeserverWinsOverDerived() {
        val url = buildElementCallUrl(roomId, "r", "n", homeserver = "https://hs.test")
        assertContains(url, "homeserver=https%3A%2F%2Fhs.test")
        assertFalse(url.contains("homeserver=https%3A%2F%2Fexample.org"))
    }

    @Test
    fun nonMatrixRoomIdOmitsRoomIdAndViaServers() {
        val url = buildElementCallUrl("plain-room", "r", "n")
        assertFalse(url.contains("roomId="))
        assertFalse(url.contains("viaServers="))
    }

    @Test
    fun emptyRoomNameFallsBackToCallAlias() {
        val url = buildElementCallUrl(roomId, "   ", "n")
        assertContains(url, "/room/#/call?")
    }

    @Test
    fun optionalFlagsRenderedWhenSetAndOmittedWhenNull() {
        val url = buildElementCallUrl(
            roomId, "r", "n",
            sendNotificationType = "ring",
            skipLobby = true,
            hideHeader = true,
            disableAudio = true,
            disableVideo = false,
            perParticipantE2EE = true,
            waitForCallPickup = null,
            autoLeave = null,
        )
        assertContains(url, "sendNotificationType=ring")
        assertContains(url, "skipLobby=true")
        assertContains(url, "hideHeader=true")
        assertContains(url, "disableAudio=true")
        assertContains(url, "disableVideo=false")
        assertContains(url, "perParticipantE2EE=true")
        assertFalse(url.contains("waitForCallPickup="))
        assertFalse(url.contains("autoLeave="))
    }

    @Test
    fun displayNameSpecialCharsArePercentEncoded() {
        val url = buildElementCallUrl(roomId, "r", "Привет & Co")
        assertContains(url, "displayName=%D0%9F") // cyrillic encoded
        assertContains(url, "%20%26%20") // " & " encoded
    }

    @Test
    fun widgetUrlCarriesWidgetHandshakeParams() {
        val url = buildElementCallWidgetUrl(
            widgetId = "telecrypt-1",
            parentUrl = "http://127.0.0.1:8080/widget-host.html",
            userId = "@alice:example.org",
            deviceId = "DEV1",
            baseUrl = "https://example.org",
            roomId = roomId,
            roomName = "r",
            displayName = "Alice",
        )
        assertContains(url, "widgetId=telecrypt-1")
        assertContains(url, "clientId=io.element.call")
        assertContains(url, "parentUrl=http%3A%2F%2F127.0.0.1%3A8080%2Fwidget-host.html")
        assertContains(url, "userId=%40alice%3Aexample.org")
        assertContains(url, "deviceId=DEV1")
        assertContains(url, "baseUrl=https%3A%2F%2Fexample.org")
        assertContains(url, "lang=en-US")
        assertContains(url, "intent=join_existing")
    }

    @Test
    fun widgetUrlEncodesAudioVideoFlags() {
        val url = buildElementCallWidgetUrl(
            widgetId = "w", parentUrl = "p", userId = "u", deviceId = "d",
            baseUrl = "b", roomId = roomId, roomName = "r", displayName = "n",
            disableAudio = true, disableVideo = true,
        )
        assertContains(url, "disableAudio=true")
        assertContains(url, "disableVideo=true")
        assertContains(url, "hideScreensharing=true")
    }

    @Test
    fun roomNameAndAliasAreConsistentForSameInput() {
        val a = buildElementCallUrl(roomId, "Same", "n")
        val b = buildElementCallUrl(roomId, "Same", "n")
        assertEquals(a, b)
    }
}

package de.connect2x.tammy.telecryptModules.call

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class CallDeepLinkTest {

    @Test
    fun buildsTelecryptSchemeWithEncodedParams() {
        val link = buildTelecryptCallDeepLink("!abc:example.org", "Team Room", CallMode.VIDEO)
        assertTrue(link.startsWith("com.zendev.telecrypt://call?"), link)
        assertContains(link, "roomId=%21abc%3Aexample.org")
        assertContains(link, "roomName=Team%20Room")
        assertContains(link, "mode=video")
    }

    @Test
    fun modeIsLowercased() {
        val audio = buildTelecryptCallDeepLink("!r:s", "n", CallMode.AUDIO)
        assertContains(audio, "mode=audio")
    }

    @Test
    fun unreservedCharsAreNotEncoded() {
        val link = buildTelecryptCallDeepLink("room-1_a.b~c", "n", CallMode.AUDIO)
        assertContains(link, "roomId=room-1_a.b~c")
    }
}

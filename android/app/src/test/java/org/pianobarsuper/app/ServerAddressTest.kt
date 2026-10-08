package org.pianobarsuper.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pianobarsuper.app.net.ServerAddress

class ServerAddressTest {
    @Test fun acceptsGenericServers() {
        assertEquals("https://radio.example.com/", ServerAddress.normalize(" https://Radio.Example.com "))
        assertEquals("http://192.168.1.4:8765/", ServerAddress.normalize("http://192.168.1.4:8765"))
        assertEquals("http://[::1]:8765/", ServerAddress.normalize("http://[::1]:8765/"))
        assertEquals("http://music.local/", ServerAddress.normalize("http://music.local"))
        assertEquals("http://localhost:18765/", ServerAddress.normalize("http://localhost:18765"))
    }

    @Test fun rejectsUnexpectedDestinationsAndEmbeddedSecrets() {
        for (address in listOf("", "javascript:alert(1)", "file:///etc/passwd", "https://user:password@example.com", "https://example.com/#token",
            "https://example.com/?token=secret", "https://example.com/path", "http://example.com", "http://192.168.999.1",
            "https://example.com:0", "https://example.com:65536", "https://example.com\\@evil.com")) {
            assertThrows(address, IllegalArgumentException::class.java) { ServerAddress.normalize(address) }
        }
    }

    @Test fun sameOriginIsStrict() {
        val origin = "https://radio.example.com/"
        assertTrue(ServerAddress.sameOrigin(origin, "https://radio.example.com/api/state"))
        assertTrue(ServerAddress.sameOrigin(origin, "https://RADIO.example.com:443/api/state"))
        assertFalse(ServerAddress.sameOrigin(origin, "http://radio.example.com/api/state"))
        assertFalse(ServerAddress.sameOrigin(origin, "https://radio.example.com:8443/"))
        assertFalse(ServerAddress.sameOrigin(origin, "https://evil.example.com/"))
        assertFalse(ServerAddress.sameOrigin(origin, "https://user@radio.example.com/"))
    }
}

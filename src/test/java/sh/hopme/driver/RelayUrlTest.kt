package sh.hopme.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * quality-cov: [HopBearer.validateRelayUrl] — the gate that keeps junk out of the persisted pinned
 * relay. The shared RelayBearer dials the pinned value over an OkHttp WebSocket, which accepts only
 * ws/wss/https/http with a non-empty host; a bad string that latched into prefs would be a silent
 * relay outage (the device talks to exactly one relay). These pin: the four accepted schemes, the
 * host:port form, and every rejection path (no scheme, empty host, host-that-is-only-a-port, embedded
 * whitespace, out-of-range/non-numeric port). Pure + host-free (no OkHttp, no radios).
 */
class RelayUrlTest {

    @Test fun acceptsTheFourWebSocketSchemes() {
        assertEquals("wss://relay.hopme.sh/", HopBearer.validateRelayUrl("wss://relay.hopme.sh/"))
        assertEquals("https://relay.example.com", HopBearer.validateRelayUrl("https://relay.example.com"))
        assertEquals("ws://localhost", HopBearer.validateRelayUrl("ws://localhost"))
        assertEquals("http://example.org/x", HopBearer.validateRelayUrl("http://example.org/x"))
    }

    @Test fun acceptsAValidHostPort() {
        assertEquals("wss://relay.example.com:9443", HopBearer.validateRelayUrl("wss://relay.example.com:9443"))
        assertEquals("http://1.2.3.4:8080/path", HopBearer.validateRelayUrl("http://1.2.3.4:8080/path"))
        assertEquals("ws://host:1", HopBearer.validateRelayUrl("ws://host:1"))
        assertEquals("ws://host:65535", HopBearer.validateRelayUrl("ws://host:65535"))
    }

    @Test fun trimsSurroundingWhitespace() {
        assertEquals("wss://relay.hopme.sh/", HopBearer.validateRelayUrl("  wss://relay.hopme.sh/\t"))
    }

    @Test fun rejectsEmptyOrBlank() {
        assertNull(HopBearer.validateRelayUrl(""))
        assertNull(HopBearer.validateRelayUrl("     "))
        assertNull(HopBearer.validateRelayUrl("\n\t"))
    }

    @Test fun rejectsAMissingOrWrongScheme() {
        assertNull("a bare host is not a URL", HopBearer.validateRelayUrl("relay.hopme.sh"))
        assertNull("junk is not a URL", HopBearer.validateRelayUrl("just some words"))
        assertNull("ftp is not a WebSocket scheme", HopBearer.validateRelayUrl("ftp://relay.example.com"))
        assertNull(HopBearer.validateRelayUrl("wssrelay.example.com"))
    }

    @Test fun rejectsAnEmptyHost() {
        assertNull("scheme with no host", HopBearer.validateRelayUrl("wss://"))
        assertNull("scheme then slash — empty host", HopBearer.validateRelayUrl("wss:///path"))
        assertNull("host is only a port", HopBearer.validateRelayUrl("wss://:9443"))
    }

    @Test fun rejectsEmbeddedWhitespace() {
        assertNull(HopBearer.validateRelayUrl("wss://relay .example.com"))
        assertNull(HopBearer.validateRelayUrl("wss://relay.example.com/a b"))
    }

    @Test fun rejectsAnOutOfRangeOrNonNumericPort() {
        assertNull("port 0 is invalid", HopBearer.validateRelayUrl("wss://relay.example.com:0"))
        assertNull("port > 65535 is invalid", HopBearer.validateRelayUrl("wss://relay.example.com:70000"))
        assertNull("non-numeric port", HopBearer.validateRelayUrl("wss://relay.example.com:abc"))
    }
}

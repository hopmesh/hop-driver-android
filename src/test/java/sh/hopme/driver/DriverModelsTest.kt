package sh.hopme.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * quality-cov: the driver's plain app-model value types (HopBearer.Peer / Message / QueueRow +
 * HnsCacheRow / HpsMsg). These are pure Kotlin data holders the UI observes; constructing them exercises
 * the generated constructors, the many defaulted Message fields (the incoming-vs-outgoing metadata), and
 * the derived flags. No node, no Context, no radios. (HpsTopic.id is deliberately not touched here — it
 * calls the native addressBase58, so it lives with the device-gated surface.)
 */
class DriverModelsTest {

    @Test fun peerDefaultsToActiveWithNoPlatform() {
        val p = HopBearer.Peer(address = ByteArray(32) { 7 }, name = "Pixel 7", hops = 1u)
        assertTrue("a freshly-seen peer defaults active", p.active)
        assertEquals("Pixel 7", p.name)
        assertEquals(1.toUByte(), p.hops)
        assertTrue(p.platform.isEmpty())
        assertTrue(p.app.isEmpty())
    }

    @Test fun peerCarriesPlatformAndAppWhenKnown() {
        val p = HopBearer.Peer(ByteArray(32), "iPhone", 2u, active = false, platform = "ios", app = "HopDemo")
        assertFalse(p.active)
        assertEquals("ios", p.platform)
        assertEquals("HopDemo", p.app)
    }

    @Test fun messageDefaultsAreAnOutgoingTextStub() {
        // Minimal construction exercises the whole defaulted metadata block (contentType, hops, relayed,
        // delivered flags, sentAt, ...) that the send path and the refresh() renderer read.
        val m = HopBearer.Message(localId = 5L, peer = "addrB58", text = "hi", incoming = false)
        assertEquals(5L, m.localId)
        assertEquals("hi", m.text)
        assertFalse(m.incoming)
        assertEquals("text/plain", m.contentType)
        assertNull(m.bundleId)
        assertNull(m.imageData)
        assertTrue(m.images.isEmpty())
        assertEquals(0.toUByte(), m.hops)
        assertNull(m.latencyMs)
        assertTrue(m.trace.isEmpty())
        assertNull(m.deliveredAt)
        assertEquals(0.toUInt(), m.relayed)
        assertFalse(m.delivered)
        assertFalse(m.failed)
        assertTrue("sentAt stamps a wall-clock millis", m.sentAt > 0L)
    }

    @Test fun messageCarriesIncomingDeliveryMetadata() {
        val m = HopBearer.Message(
            localId = 9L, peer = "p", text = "yo", incoming = true,
            hops = 3u, latencyMs = 1200uL, trace = listOf("BT", "LAN"),
            delivered = true, deliveryHops = 2u, deliveryMs = 800uL, relayed = 4u,
        )
        assertTrue(m.incoming)
        assertEquals(3.toUByte(), m.hops)
        assertEquals(1200uL, m.latencyMs)
        assertEquals(listOf("BT", "LAN"), m.trace)
        assertTrue(m.delivered)
        assertEquals(2.toUByte(), m.deliveryHops)
        assertEquals(800uL, m.deliveryMs)
        assertEquals(4.toUInt(), m.relayed)
        val flippedFail = m.copy(failed = true, delivered = false)
        assertTrue(flippedFail.failed)
        assertFalse(flippedFail.delivered)
    }

    @Test fun queueRowHoldsRoutingCounters() {
        val r = HopBearer.QueueRow(own = true, to = "addr", priority = 5u, hops = 2u)
        assertTrue(r.own)
        assertEquals("addr", r.to)
        assertEquals(5.toUByte(), r.priority)
        assertEquals(2.toUByte(), r.hops)
    }

    @Test fun hnsCacheRowHoldsDomainAddressTtl() {
        val row = HopBearer.HnsCacheRow(domain = "alice.hop", address = ByteArray(32) { 3 }, ttlSecs = 3600u)
        assertEquals("alice.hop", row.domain)
        assertEquals(32, row.address.size)
        assertEquals(3600.toUInt(), row.ttlSecs)
    }

    @Test fun hpsMsgHoldsSenderAndText() {
        val msg = HopBearer.HpsMsg(id = 1L, path = "room", sender = ByteArray(32) { 9 }, text = "gm")
        assertEquals(1L, msg.id)
        assertEquals("room", msg.path)
        assertEquals("gm", msg.text)
        assertEquals(32, msg.sender.size)
    }
}

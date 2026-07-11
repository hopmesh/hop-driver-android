package sh.hopme.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.hop.MessageStatus
import uniffi.hop.PeerLink
import uniffi.hop.ServiceHit

/** cov/android-driver: refresh() - presence-advert aggregation into the peer list, link-transport
 *  tagging, the forward-secret (lock) set, the offline "seen" list, and outgoing-message delivery
 *  reconciliation via node.messageStatus(). */
class HopBearerRefreshTest : DriverTestBase() {

    private val alice = ByteArray(32) { 20 }
    private fun trigger() { bearer.send("t", HopBearer.Peer(ByteArray(32) { 1 }, "x", 0u)); settle() }

    private fun advert(addr: ByteArray, name: String, summary: String, hops: UByte = 2u, at: ULong = 100u) =
        ServiceHit(publisher = addr, service = "presence", title = name, summary = summary, tags = emptyList(), hops = hops, createdAt = at)

    @Test fun presenceAdvertBecomesAPeer() {
        fake.browseHits = listOf(advert(alice, "Alice", "fg|android|HopDemo"))
        trigger()
        val peer = bearer.peers.firstOrNull { it.name == "Alice" }
        assertTrue("Alice surfaced from her presence advert", peer != null)
        assertEquals("android", peer!!.platform)
        assertEquals("HopDemo", peer.app)
    }

    @Test fun liveLinkForcesOneHopAndTagsTransport() {
        fake.browseHits = listOf(advert(alice, "Alice", "fg|android|HopDemo", hops = 3u))
        fake.peerLinksList = listOf(PeerLink(address = alice, link = 1_000_000uL))
        trigger()
        val peer = bearer.peers.first { it.name == "Alice" }
        assertEquals("a live radio link is a 1-hop direct path", 1.toUByte(), peer.hops)
        assertEquals("BT", bearer.linkTransports[alice.toList()])
    }

    @Test fun securedPeerJoinsTheLockSet() {
        fake.browseHits = listOf(advert(alice, "Alice", "fg|android|HopDemo"))
        fake.securedAddrs = setOf(alice.toList())
        trigger()
        assertTrue(bearer.secured.contains(alice.toList()))
    }

    @Test fun offlineContactBecomesSeen() {
        val bob = ByteArray(32) { 21 }
        bearer.rememberContact(HopBearer.Peer(bob, "Bob", 0u, active = false))
        settle()
        trigger()   // Bob isn't in any live advert -> offline "seen"
        assertTrue(bearer.seen.any { it.name == "Bob" })
    }

    @Test fun deliveredStatusFlipsTheOutgoingMessage() {
        bearer.send("track-me", HopBearer.Peer(ByteArray(32) { 30 }, "Dst", 0u))
        settle()
        val id = bearer.messages.first { it.text == "track-me" }.bundleId!!
        fake.statuses[id.toList()] = MessageStatus(relayed = 2u, delivered = true, deliveryHops = 3u, deliveryMs = 500u)
        trigger()   // refresh() reconciles delivery
        val m = bearer.messages.first { it.text == "track-me" }
        assertTrue(m.delivered)
        assertEquals(3.toUByte(), m.deliveryHops)
        assertTrue(m.deliveredAt != null)
    }

    @Test fun relayedCountUpdatesWithoutDelivery() {
        bearer.send("spreading", HopBearer.Peer(ByteArray(32) { 31 }, "Dst", 0u))
        settle()
        val id = bearer.messages.first { it.text == "spreading" }.bundleId!!
        fake.statuses[id.toList()] = MessageStatus(relayed = 4u, delivered = false, deliveryHops = 0u, deliveryMs = 0u)
        trigger()
        assertEquals(4.toUInt(), bearer.messages.first { it.text == "spreading" }.relayed)
    }
}

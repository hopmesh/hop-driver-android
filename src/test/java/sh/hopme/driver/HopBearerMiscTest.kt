package sh.hopme.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.hop.HnsCacheEntry
import uniffi.hop.QueueItem
import uniffi.hop.addressBase58
import uniffi.hop.serviceIdentify

/** cov/android-driver: the smaller instance helpers + diagnostics - keyFor / displayName / contactList,
 *  the identify-on-add path, and the HNS-cache + relay-queue debug views refreshed off the node. */
class HopBearerMiscTest : DriverTestBase() {

    private fun trigger() { bearer.send("t", HopBearer.Peer(ByteArray(32) { 1 }, "x", 0u)); settle() }

    @Test fun keyForIsTheBase58Address() {
        val addr = ByteArray(32) { 15 }
        assertEquals(addressBase58(addr), bearer.keyFor(HopBearer.Peer(addr, "n", 0u)))
    }

    @Test fun displayNameFallsBackToShortAddress() {
        val addr = ByteArray(32) { 16 }
        assertEquals(addressBase58(addr).take(8), bearer.displayName(addr))
    }

    @Test fun contactListSortsByName() {
        bearer.rememberContact(HopBearer.Peer(ByteArray(32) { 1 }, "Zoe", 0u))
        bearer.rememberContact(HopBearer.Peer(ByteArray(32) { 2 }, "Ann", 0u))
        settle()
        // contactList reads `peers`, which refresh() fills; seed a couple presence adverts instead.
        fake.browseHits = listOf(
            uniffi.hop.ServiceHit(ByteArray(32) { 3 }, "presence", "Zoe", "fg|android|A", emptyList(), 1u, 9u),
            uniffi.hop.ServiceHit(ByteArray(32) { 4 }, "presence", "Ann", "fg|android|A", emptyList(), 1u, 9u),
        )
        trigger()
        val names = bearer.contactList.map { it.name }
        assertTrue(names.indexOf("Ann") < names.indexOf("Zoe"))
    }

    @Test fun addContactWithNoNameQueuesIdentify() {
        val addr58 = addressBase58(ByteArray(32) { 17 })
        assertTrue(bearer.addContact("", addr58))
        settle()
        assertTrue(fake.serviceRequests.any { it.second == serviceIdentify() })
    }

    @Test fun hnsCacheDebugViewIsRefreshed() {
        fake.hnsCacheEntries = listOf(HnsCacheEntry(domain = "alice.hop", address = ByteArray(32) { 8 }, ttlSecs = 3600u))
        trigger()   // drainHns() refreshes the cache view
        assertTrue(bearer.hnsCache.any { it.domain == "alice.hop" })
    }

    @Test fun relayQueueDiagnosticsAreRefreshed() {
        fake.queueItems = listOf(
            QueueItem(id = ByteArray(8) { 1 }, own = true, to = ByteArray(32) { 6 }, priority = 3u, hops = 2u),
            QueueItem(id = ByteArray(8) { 2 }, own = false, to = ByteArray(0), priority = 1u, hops = 0u),
        )
        trigger()   // refresh() -> refreshQueue()
        assertEquals(2, bearer.queue.size)
        assertTrue(bearer.queue.any { it.to == "broadcast" })
    }

    @Test fun browseDiscoverableDefaultsEmpty() {
        assertTrue(bearer.hpsBrowse().isEmpty())
    }
}

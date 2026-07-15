package sh.hopme.driver

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** cov/android-driver: fetchReachRecord() - the host resolver hook (§30). node.takeDnsLookups() hands
 *  the driver a domain; it fetches `https://<domain>/.well-known/hop` (here a MockWebServer via the
 *  hnsResolverBase override) and feeds the decoded reach-record bytes back to node.provideReachRecord.
 *  No real network. */
class HopBearerReachFetchTest : DriverTestBase() {

    // A canned reach record the well-known serves; the driver must base64-decode `reach` and hand these
    // raw bytes to core (core verifies the signature - the fake just records what it received).
    private val recordBytes = ByteArray(24) { it.toByte() }
    private val wellKnownJson = JSONObject()
        .put("address", "abc123")
        .put("endpoint", "wss://x.example/_hop")
        .put("reach", Base64.getEncoder().encodeToString(recordBytes))
        .toString()

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path == "/.well-known/hop")
                    MockResponse().setResponseCode(200)
                        .setHeader("content-type", "application/json").setBody(wellKnownJson)
                else MockResponse().setResponseCode(404)
        }
        start()
    }

    @After fun stopServer() { runCatching { server.shutdown() } }

    private fun awaitReach(fake: FakeHopNode, b: HopBearer, domain: String) {
        val end = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < end && fake.reachRecords.none { it.first == domain }) {
            settleOn(b, 1)
            Thread.sleep(40)
        }
        settleOn(b, 1)
    }

    @Test fun dnsLookupFetchesWellKnownAndFeedsReachRecordBack() {
        val cfg = defaultConfig().copy(hnsResolverBase = server.url("").toString())
        val fake2 = FakeHopNode()
        val b = newBearer(fake2, cfg)
        // node asks the host to resolve "x"; drainHns() runs inside pump() and fires the well-known GET.
        fake2.pendingDnsLookups.add("x")
        b.send("kick", HopBearer.Peer(ByteArray(32) { 1 }, "x", 0u))
        awaitReach(fake2, b, "x")
        assertTrue("driver fetched the well-known over HTTPS", server.requestCount >= 1)
        assertEquals("/.well-known/hop", server.takeRequest().path)
        val handed = fake2.reachRecords.firstOrNull { it.first == "x" }
        assertTrue("decoded reach record handed back to the node", handed != null)
        assertArrayEquals("bytes are the base64-decoded reach field", recordBytes, handed!!.second)
        runCatching { b.teardown() }; settleOn(b, 1)
    }

    @Test fun failedFetchNegativeCachesWithEmptyRecord() {
        // Point at a dead base so the GET fails; the driver must still hand core an (empty) record so it
        // negative-caches instead of hanging the resolve forever.
        val cfg = defaultConfig().copy(hnsResolverBase = "http://127.0.0.1:1")
        val fake2 = FakeHopNode()
        val b = newBearer(fake2, cfg)
        fake2.pendingDnsLookups.add("dead.example")
        b.send("kick", HopBearer.Peer(ByteArray(32) { 1 }, "dead.example", 0u))
        awaitReach(fake2, b, "dead.example")
        val handed = fake2.reachRecords.firstOrNull { it.first == "dead.example" }
        assertTrue("a failed fetch still feeds core a record (negative cache)", handed != null)
        assertEquals("the record is empty on failure", 0, handed!!.second.size)
        runCatching { b.teardown() }; settleOn(b, 1)
    }
}

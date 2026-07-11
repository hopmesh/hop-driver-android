package sh.hopme.driver

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/** cov/android-driver: fetchDnssecChain() - the host DNS hook (§30). node.takeDnsLookups() hands the
 *  driver a domain; it fetches the DNSSEC chain over DoH (here a MockWebServer) and feeds the raw bodies
 *  back to node.provideDnsProof. No real network. */
class HopBearerDnssecTest : DriverTestBase() {

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200).setBody("{\"Status\":0,\"Answer\":[]}")
        }
        start()
    }

    @After fun stopServer() { runCatching { server.shutdown() } }

    @Test fun dnsLookupTriggersDohChainAndFeedsProofBack() {
        val cfg = defaultConfig().copy(dohResolverUrl = server.url("/resolve").toString())
        val fake2 = FakeHopNode()
        val b = newBearer(fake2, cfg)
        // node asks the host to resolve "x"; drainHns() runs inside pump() and fires the DoH GETs.
        fake2.pendingDnsLookups.add("x")
        b.send("kick", HopBearer.Peer(ByteArray(32) { 1 }, "x", 0u))
        // the DoH GETs are async (OkHttp threads); once all return, a core.post calls provideDnsProof.
        val end = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < end && fake2.dnsProofs.isEmpty()) {
            settleOn(b, 1)
            Thread.sleep(40)
        }
        settleOn(b, 1)
        assertTrue("driver fetched the chain over DoH", server.requestCount >= 1)
        assertTrue("raw DoH bodies handed back to the node", fake2.dnsProofs.any { it.first == "x" })
        runCatching { b.teardown() }; settleOn(b, 1)
    }
}

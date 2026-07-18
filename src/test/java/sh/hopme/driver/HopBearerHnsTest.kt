package sh.hopme.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.hop.HnsLookupResult
import uniffi.hop.HnsRecord
import uniffi.hop.HttpResp

/** cov/android-driver: HNS resolution + hops:// fetch (§30) - the text-box openHops path, the WebView
 *  hopsFetch callback path, drainHns (HNS results + HTTP responses), and the 30s WebView-fetch expiry. */
class HopBearerHnsTest : DriverTestBase() {

    private val endpoint = ByteArray(32) { 4 }
    private fun pump() { bearer.send("t", HopBearer.Peer(ByteArray(32) { 1 }, "x", 0u)); settle() }

    @Test fun openHopsCachedFires() {
        fake.resolve = { HnsLookupResult.Cached(endpoint) }
        bearer.openHops("hops://alice.hop/index"); settle()
        assertTrue(fake.hopsRequests.contains("alice.hop"))
        assertEquals("fetching…", bearer.hopsResults["alice.hop"])
    }

    @Test fun openHopsCachedNegativeIsError() {
        fake.resolve = { HnsLookupResult.Cached(ByteArray(0)) }
        bearer.openHops("bob.hop"); settle()
        assertTrue(bearer.hopsResults["bob.hop"]!!.contains("no hops endpoint"))
    }

    @Test fun openHopsNeedsResolverIsOffline() {
        fake.resolve = { HnsLookupResult.NeedsResolver }
        bearer.openHops("carol.hop"); settle()
        assertTrue(bearer.hopsResults["carol.hop"]!!.contains("offline"))
    }

    @Test fun openHopsBadUrlIsError() {
        bearer.openHops("   "); settle()
        assertTrue(bearer.hopsResults["?"]!!.contains("not a hops"))
    }

    @Test fun openHopsCanonicalizesCaseAndRejectsAmbiguousAuthorities() {
        fake.resolve = { HnsLookupResult.Cached(endpoint) }
        bearer.openHops("HOPS://Alice.Hop/a?q=1"); settle()
        assertTrue(fake.hopsRequests.contains("alice.hop"))

        for (url in listOf("https://alice.hop", "hops://user@alice.hop/", "hops://alice.hop:443/", "hops://alice.hop\\@evil/")) {
            bearer.openHops(url); settle()
        }
        assertTrue(bearer.hopsResults["?"]!!.contains("not a hops"))
    }

    @Test fun openHopsPendingThenResolvedFires() {
        fake.resolve = { HnsLookupResult.Pending }
        bearer.openHops("late.hop/p"); settle()
        assertEquals("resolving…", bearer.hopsResults["late.hop"])
        fake.pendingHnsResults.add(HnsRecord(domain = "late.hop", address = endpoint))
        pump()   // drainHns resolves the pending fetch
        assertTrue(fake.hopsRequests.contains("late.hop"))
    }

    @Test fun openHopsHttpResponseRendered() {
        fake.resolve = { HnsLookupResult.Cached(endpoint) }
        bearer.openHops("dave.hop"); settle()
        val reqId = fake.lastHopsReqId
        fake.pendingHttpResponses.add(
            HttpResp(id = ByteArray(32) { 1 }, from = endpoint, forRequestId = reqId, status = 200u, contentType = "text/plain", body = "OK".toByteArray()),
        )
        pump()
        assertTrue(bearer.hopsResults["dave.hop"]!!.contains("200"))
        assertTrue(fake.acceptedHttpResponseIds.any { it.contentEquals(ByteArray(32) { 1 }) })
    }

    // ---- WebView (callback) path ----
    private class Cb {
        var status = -1; var ct = ""; var body = ByteArray(0); var calls = 0
        val fn: (Int, String, ByteArray) -> Unit = { s, c, b -> status = s; ct = c; body = b; calls++ }
    }

    @Test fun hopsFetchCachedThenResponse() {
        fake.resolve = { HnsLookupResult.Cached(endpoint) }
        val cb = Cb()
        var acceptedBeforeCallback = false
        bearer.hopsFetch("hops://web.hop/page") { status, contentType, body ->
            acceptedBeforeCallback = fake.acceptedHttpResponseIds.isNotEmpty()
            cb.fn(status, contentType, body)
        }
        settle()
        assertTrue(fake.hopsRequests.contains("web.hop"))
        fake.pendingHttpResponses.add(
            HttpResp(id = ByteArray(32) { 2 }, from = endpoint, forRequestId = fake.lastHopsReqId, status = 201u, contentType = "text/html", body = "<h1>".toByteArray()),
        )
        pump()
        assertEquals(201, cb.status)
        assertEquals("text/html", cb.ct)
        assertFalse("the core row must remain durable until callback delivery", acceptedBeforeCallback)
        assertTrue(fake.acceptedHttpResponseIds.any { it.contentEquals(ByteArray(32) { 2 }) })
    }

    @Test fun failedHttpAcceptanceRetriesWithoutRepeatingTheCallback() {
        fake.resolve = { HnsLookupResult.Cached(endpoint) }
        val cb = Cb()
        bearer.hopsFetch("hops://retry.hop/page", cb.fn); settle()
        val id = ByteArray(32) { 3 }
        fake.failHttpResponseAcceptance = true
        fake.pendingHttpResponses.add(
            HttpResp(id = id, from = endpoint, forRequestId = fake.lastHopsReqId, status = 200u,
                contentType = "text/plain", body = "once".toByteArray()),
        )
        pump()
        assertEquals(1, cb.calls)
        assertTrue(fake.acceptedHttpResponseIds.isEmpty())

        fake.failHttpResponseAcceptance = false
        pump()
        assertEquals(1, cb.calls)
        assertTrue(fake.acceptedHttpResponseIds.any { it.contentEquals(id) })
    }

    @Test fun hopsFetchBadUrlAndOfflineAndNegative() {
        val bad = Cb(); bearer.hopsFetch("", bad.fn); settle(); assertEquals(400, bad.status)
        fake.resolve = { HnsLookupResult.NeedsResolver }
        val off = Cb(); bearer.hopsFetch("x.hop", off.fn); settle(); assertEquals(503, off.status)
        fake.resolve = { HnsLookupResult.Cached(ByteArray(0)) }
        val neg = Cb(); bearer.hopsFetch("y.hop", neg.fn); settle(); assertEquals(502, neg.status)
    }

    @Test fun hopsFetchPendingThenResolved() {
        fake.resolve = { HnsLookupResult.Pending }
        val cb = Cb()
        bearer.hopsFetch("slow.hop/a", cb.fn); settle()
        fake.pendingHnsResults.add(HnsRecord(domain = "slow.hop", address = endpoint))
        pump()
        assertTrue(fake.hopsRequests.contains("slow.hop"))
    }

    // NOTE: the 30s WebView-fetch expiry (expireHopsWeb) is intentionally NOT unit-tested here. It keys
    // off System.currentTimeMillis(), which Robolectric's paused-looper clock does not advance with
    // ShadowLooper time-travel, so a deterministic JVM assertion isn't reliable. The rest of the hops://
    // WebView path (Cached / Pending / negative / offline / bad-url / HTTP response) is covered above.
}

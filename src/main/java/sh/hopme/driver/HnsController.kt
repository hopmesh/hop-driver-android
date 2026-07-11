package sh.hopme.driver

import android.os.Handler
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uniffi.hop.HnsLookupResult
import uniffi.hop.HopNodeInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * HNS + hops:// concern (DESIGN.md §30), split out of the [HopBearer] god-object into a cohesive
 * controller the driver composes. Owns the hops:// text-box results, the WebView fetch callbacks (with
 * their 30s deadlines), the HNS cache debug view, and the bounded DoH (DNSSEC-chain) HTTP client.
 *
 * The threading model is unchanged: every method here runs on the driver's serial `core` thread (the
 * same Handler the rest of the node work runs on), and every Compose-state mutation is marshaled to main
 * via [onUi]. The driver injects the node seam, the core Handler, onUi, [pump] (drainHns is still called
 * from pump(); the pump()-after-send calls are preserved verbatim), the shared [nameByAddr] map, and the
 * DoH resolver config. Behavior-identical to the code that previously lived inline in HopBearer.
 */
internal class HnsController(
    private val node: HopNodeInterface,
    private val core: Handler,
    private val onUi: (() -> Unit) -> Unit,
    private val pump: () -> Unit,
    private val nameByAddr: ConcurrentHashMap<List<Byte>, String>,
    private val dohResolverUrl: String,
    dohMaxConcurrent: Int,
) {
    // hops:// (DESIGN.md §30): domain → rendered result; pending resolves + outstanding requests.
    val hopsResults = mutableStateMapOf<String, String>()
    private val pendingHops = HashMap<String, String>()              // domain → path awaiting resolve
    private val hopsReqs = HashMap<List<Byte>, String>()             // request id → domain
    // DoH client for DNSSEC-chain fetches (§30). BOUNDED concurrency: fetchDnssecChain enqueues one GET
    // per zone/record, so a burst of resolves (a page that references many hops:// domains, or a flood of
    // takeDnsLookups) could otherwise spawn an unbounded number of in-flight HTTPS requests and exhaust
    // sockets/threads. Cap the dispatcher so excess calls queue instead of all firing at once.
    private val dohClient = OkHttpClient.Builder()
        .dispatcher(okhttp3.Dispatcher().apply {
            maxRequests = dohMaxConcurrent              // total in-flight GETs across all domains
            maxRequestsPerHost = dohMaxConcurrent       // all go to one host (dns.google), so cap it too
        })
        .build()
    // HNS cache debug view: domain → (address, ttl).
    val hnsCache = mutableStateListOf<HopBearer.HnsCacheRow>()

    /// Open `hops://<domain>/<path>` (bare `<domain>` ok): resolve via HNS, then GET over the mesh.
    fun openHops(input: String) = core.post {
        val (domain, path) = parseHops(input)
        if (domain.isEmpty()) { onUi { hopsResults["?"] = "error: not a hops:// url" }; return@post }
        onUi { hopsResults[domain] = "resolving…" }
        when (val r = node.resolveHns(domain)) {
            is HnsLookupResult.Cached ->
                if (r.address.isEmpty()) onUi { hopsResults[domain] = "error: no hops endpoint for $domain" }
                else fireHops(domain, path, r.address)
            is HnsLookupResult.Pending -> pendingHops[domain] = path
            is HnsLookupResult.NeedsResolver ->
                onUi { hopsResults[domain] = "error: offline - no internet or peers to resolve $domain" }
        }
        pump()
    }

    private fun fireHops(domain: String, path: String, endpoint: ByteArray) {
        nameByAddr[endpoint.toList()] = domain // label the endpoint by its domain (endpoints list/traces)
        val id = runCatching {
            node.sendHopsRequest(endpoint, domain, "GET", path, ByteArray(0), 8u * 1024u * 1024u)
        }.getOrNull()
        if (id == null) { onUi { hopsResults[domain] = "error: could not send request to $domain" }; return }
        hopsReqs[id.toList()] = domain
        onUi { hopsResults[domain] = "fetching…" }
        pump()
    }

    // hops:// for the WebView (callback-style, per resource; DESIGN.md §30).
    // android-13: each WebView callback carries a deadline so a resolve/response that never arrives
    // (dead domain, dropped request) is expired instead of latching a dead callback forever. Expiry
    // matches the 30s UI timeout; the entries are touched only on the core thread.
    private val hopsWebPending = HashMap<String, MutableList<Triple<String, (Int, String, ByteArray) -> Unit, Long>>>()
    private val hopsWebReqs = HashMap<List<Byte>, Pair<(Int, String, ByteArray) -> Unit, Long>>()
    private val HOPS_WEB_TTL_MS = 30_000L

    /// Fetch one hops:// resource for the WebView, calling back with (status, contentType, body).
    fun hopsFetch(url: String, cb: (Int, String, ByteArray) -> Unit) {
        core.post {
            val (domain, path) = parseHops(url)
            if (domain.isEmpty()) { cb(400, "text/plain; charset=utf-8", "bad url".toByteArray()); return@post }
            when (val r = node.resolveHns(domain)) {
                is HnsLookupResult.Cached ->
                    if (r.address.isEmpty()) cb(502, "text/plain; charset=utf-8", "no hops endpoint for $domain".toByteArray())
                    else fireHopsWeb(domain, path, r.address, cb)
                is HnsLookupResult.Pending -> hopsWebPending.getOrPut(domain) { mutableListOf() }.add(Triple(path, cb, System.currentTimeMillis() + HOPS_WEB_TTL_MS))
                is HnsLookupResult.NeedsResolver -> cb(503, "text/plain; charset=utf-8", "offline".toByteArray())
            }
            pump()
        }
    }

    /// android-13: expire WebView fetch callbacks whose HNS resolve or hops response never arrived, so a
    /// dead domain / dropped request fails the UI (matching its 30s timeout) instead of latching a dead
    /// callback + its WebView worker forever. Runs on core (same thread that populates the maps).
    fun expireHopsWeb() {
        val now = System.currentTimeMillis()
        val reqIt = hopsWebReqs.entries.iterator()
        while (reqIt.hasNext()) {
            val (cb, deadline) = reqIt.next().value
            if (now >= deadline) { reqIt.remove(); cb(504, "text/plain; charset=utf-8", "timeout".toByteArray()) }
        }
        val penIt = hopsWebPending.entries.iterator()
        while (penIt.hasNext()) {
            val e = penIt.next()
            e.value.removeAll { (_, cb, deadline) ->
                (now >= deadline).also { if (it) cb(504, "text/plain; charset=utf-8", "timeout".toByteArray()) }
            }
            if (e.value.isEmpty()) penIt.remove()
        }
    }

    private fun fireHopsWeb(domain: String, path: String, endpoint: ByteArray, cb: (Int, String, ByteArray) -> Unit) {
        nameByAddr[endpoint.toList()] = domain
        val id = runCatching {
            node.sendHopsRequest(endpoint, domain, "GET", path, ByteArray(0), 8u * 1024u * 1024u)
        }.getOrNull()
        if (id == null) { cb(502, "text/plain; charset=utf-8", "send failed".toByteArray()); return }
        hopsWebReqs[id.toList()] = cb to (System.currentTimeMillis() + HOPS_WEB_TTL_MS)
        pump()
    }

    fun drainHns() {
        for (rec in node.takeHnsResults()) {
            // WebView fetches queued on this domain's resolution take priority.
            hopsWebPending.remove(rec.domain)?.let { queued ->
                for ((path, cb, _) in queued) {
                    if (rec.address.isEmpty()) cb(502, "text/plain; charset=utf-8", "no hops endpoint for ${rec.domain}".toByteArray())
                    else fireHopsWeb(rec.domain, path, rec.address, cb)
                }
            }
            val path = pendingHops.remove(rec.domain) ?: continue // the text-box fetch
            if (rec.address.isEmpty()) { val d = rec.domain; onUi { hopsResults[d] = "error: no hops endpoint for $d" } }
            else fireHops(rec.domain, path, rec.address)
        }
        for (resp in node.takeHttpResponses()) {
            val webCb = hopsWebReqs.remove(resp.forRequestId.toList())?.first
            if (webCb != null) { webCb(resp.status.toInt(), resp.contentType, resp.body); continue }
            val domain = hopsReqs.remove(resp.forRequestId.toList()) ?: continue
            val line = "${resp.status} · ${String(resp.body)}"
            onUi { hopsResults[domain] = line }
        }
        // Host DNS hook (§30): fetch each requested domain's full DNSSEC chain over DoH and hand
        // core the raw bodies, and core validates to the root anchors and decides the address.
        for (domain in node.takeDnsLookups()) fetchDnssecChain(domain)
        // Refresh the cache debug view.
        val cacheRows = runCatching { node.hnsCache() }.getOrDefault(emptyList())
            .map { HopBearer.HnsCacheRow(it.domain, it.address, it.ttlSecs) }
        onUi { hnsCache.clear(); hnsCache.addAll(cacheRows) }
    }

    /// Fetch a domain's DNSSEC chain over DNS-over-HTTPS (TXT _hopaddress + DNSKEY/DS per zone to
    /// root, all do=1), then feed the raw JSON bodies to core (§30). Concurrent GETs.
    private fun fetchDnssecChain(domain: String) {
        val queries = ArrayList<Pair<String, Int>>()
        queries.add("_hopaddress.$domain" to 16) // TXT
        var zone = domain
        while (true) {
            queries.add(zone to 48) // DNSKEY
            if (zone == ".") break
            queries.add(zone to 43) // DS
            zone = if (zone.contains(".")) zone.substringAfter(".") else "."
        }
        val bodies = java.util.Collections.synchronizedList(ArrayList<String>())
        val remaining = java.util.concurrent.atomic.AtomicInteger(queries.size)
        for ((name, qtype) in queries) {
            val req = Request.Builder().url("$dohResolverUrl?name=$name&type=$qtype&do=1").build()
            dohClient.newCall(req).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) { done() }
                override fun onResponse(call: okhttp3.Call, response: Response) {
                    response.use { it.body?.string()?.let { b -> bodies.add(b) } }
                    done()
                }
                private fun done() {
                    if (remaining.decrementAndGet() == 0) core.post {
                        runCatching { node.provideDnsProof(domain, ArrayList(bodies)) }
                        pump()
                    }
                }
            })
        }
    }

    /// Parse a hops:// URL (or bare domain) into (domain, path). The endpoint validates host,
    /// so we pass the bare domain and just the path.
    private fun parseHops(input: String): Pair<String, String> {
        var s = input.trim().removePrefix("hops://").removePrefix("https://").removePrefix("http://")
        val slash = s.indexOf('/')
        val domain = (if (slash >= 0) s.substring(0, slash) else s).lowercase()
        val path = if (slash >= 0) s.substring(slash) else "/"
        return domain to path
    }

    /// Release the DoH client's dispatcher thread pool + connection pool (bounded, but still held).
    /// Mirrors the teardown cleanup that previously lived inline in HopBearer.teardown().
    fun shutdown() {
        runCatching { dohClient.dispatcher.executorService.shutdown() }
        runCatching { dohClient.connectionPool.evictAll() }
    }
}

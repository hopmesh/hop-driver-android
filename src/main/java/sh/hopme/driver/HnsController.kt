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
 * their 30s deadlines), the HNS cache debug view, and the bounded well-known reach-record HTTP client.
 *
 * Name resolution is a single HTTPS GET of `https://<domain>/.well-known/hop`: the domain's own TLS
 * cert proves the domain, and the self-certifying reach record served there binds the domain to a Hop
 * address (verified in core against the address that signed it). No DNSSEC, no DoH resolver.
 *
 * The threading model is unchanged: every method here runs on the driver's serial `core` thread (the
 * same Handler the rest of the node work runs on), and every Compose-state mutation is marshaled to main
 * via [onUi]. The driver injects the node seam, the core Handler, onUi, [pump] (drainHns is still called
 * from pump(); the pump()-after-send calls are preserved verbatim), the shared [nameByAddr] map, and the
 * HNS resolver-base override. Behavior-identical to the code that previously lived inline in HopBearer.
 */
internal class HnsController(
    private val node: HopNodeInterface,
    private val core: Handler,
    private val onUi: (() -> Unit) -> Unit,
    private val pump: () -> Unit,
    private val nameByAddr: ConcurrentHashMap<List<Byte>, String>,
    private val hnsResolverBase: String,
    hnsMaxConcurrent: Int,
) {
    // hops:// (DESIGN.md §30): domain → rendered result; pending resolves + outstanding requests.
    val hopsResults = mutableStateMapOf<String, String>()
    private val pendingHops = HashMap<String, String>()              // domain → path awaiting resolve
    private val hopsReqs = HashMap<List<Byte>, String>()             // request id → domain
    // HTTP client for well-known reach-record fetches (§30). BOUNDED concurrency: a burst of resolves
    // (a page that references many hops:// domains, or a flood of takeDnsLookups) could otherwise spawn
    // an unbounded number of in-flight HTTPS GETs and exhaust sockets/threads. Cap the dispatcher so
    // excess calls queue instead of all firing at once. maxRequestsPerHost stays at the OkHttp default
    // (5) since each domain is its own host now (the GET goes to the domain being resolved).
    private val httpClient = OkHttpClient.Builder()
        .dispatcher(okhttp3.Dispatcher().apply {
            maxRequests = hnsMaxConcurrent              // total in-flight GETs across all domains
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
        // Host resolver hook (§30): fetch each requested domain's `/.well-known/hop` reach record over
        // HTTPS and hand core the raw record bytes; core verifies the self-certifying signature and
        // decides the address (the domain's TLS cert proved the domain).
        for (domain in node.takeDnsLookups()) fetchReachRecord(domain)
        // Refresh the cache debug view.
        val cacheRows = runCatching { node.hnsCache() }.getOrDefault(emptyList())
            .map { HopBearer.HnsCacheRow(it.domain, it.address, it.ttlSecs) }
        onUi { hnsCache.clear(); hnsCache.addAll(cacheRows) }
    }

    /// Fetch a domain's `/.well-known/hop` reach record over HTTPS and feed the raw record bytes to
    /// core (§30). One GET per resolve. The body is JSON `{address, endpoint, reach}` where `reach` is
    /// the base64 (std) postcard reach record; we hand core those decoded bytes, and it verifies the
    /// self-certifying signature. Any failure (network, non-200, malformed body) hands core an empty
    /// record, which it negative-caches. In production the base is `https://<domain>`; a non-empty
    /// [hnsResolverBase] override (tests, or a shared resolver) replaces just the scheme+host.
    private fun fetchReachRecord(domain: String) {
        val base = if (hnsResolverBase.isNotEmpty()) hnsResolverBase.trimEnd('/') else "https://$domain"
        val req = Request.Builder().url("$base/.well-known/hop").build()
        httpClient.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) { deliver(ByteArray(0)) }
            override fun onResponse(call: okhttp3.Call, response: Response) {
                val record = response.use {
                    if (!it.isSuccessful) ByteArray(0)
                    else runCatching { parseReachRecord(it.body?.string().orEmpty()) }.getOrDefault(ByteArray(0))
                }
                deliver(record)
            }
            private fun deliver(record: ByteArray) = core.post {
                runCatching { node.provideReachRecord(domain, record) }
                pump()
            }
        })
    }

    /// Pull the `reach` field out of a `/.well-known/hop` JSON body and base64-decode it to the raw
    /// reach-record bytes. Returns empty if the field is absent or not valid base64.
    private fun parseReachRecord(body: String): ByteArray {
        val reachB64 = org.json.JSONObject(body).optString("reach", "")
        if (reachB64.isEmpty()) return ByteArray(0)
        return runCatching { java.util.Base64.getDecoder().decode(reachB64) }.getOrDefault(ByteArray(0))
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

    /// Release the HTTP client's dispatcher thread pool + connection pool (bounded, but still held).
    /// Mirrors the teardown cleanup that previously lived inline in HopBearer.teardown().
    fun shutdown() {
        runCatching { httpClient.dispatcher.executorService.shutdown() }
        runCatching { httpClient.connectionPool.evictAll() }
    }
}

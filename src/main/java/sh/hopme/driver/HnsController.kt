package sh.hopme.driver

import android.os.Handler
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import uniffi.hop.HnsLookupResult
import uniffi.hop.HopNodeInterface
import java.io.ByteArrayOutputStream
import java.net.URI

/**
 * HNS + hops:// concern (DESIGN.md §30), split out of the [HopBearer] god-object into a cohesive
 * controller the driver composes. Owns the hops:// text-box results, the WebView fetch callbacks (with
 * their 30s deadlines), the HNS cache debug view, and the bounded well-known reach-record HTTP client.
 *
 * Name resolution is a single HTTPS GET of `https://<domain>/.well-known/hop`: the domain's own TLS
 * cert proves the domain, and the self-certifying reach record served there binds the domain to a Hop
 * address (verified in core against the address that signed it). No DoH resolver.
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
    private val rememberName: (List<Byte>, String) -> Unit,
    private val hnsResolverBase: String,
    hnsMaxConcurrent: Int,
) {
    // hops:// (DESIGN.md §30): domain → rendered result; pending resolves + outstanding requests.
    val hopsResults = mutableStateMapOf<String, String>()
    private val pendingHops = HashMap<String, String>()              // domain → path awaiting resolve
    private val hopsReqs = HashMap<List<Byte>, String>()             // request id → domain
    private val dispatchedHttpResponses = HashSet<List<Byte>>()
    private val deliveredHttpResponses = HashMap<List<Byte>, ByteArray>()
    // HTTP client for well-known reach-record fetches (§30). BOUNDED concurrency: a burst of resolves
    // (a page that references many hops:// domains, or a flood of takeDnsLookups) could otherwise spawn
    // an unbounded number of in-flight HTTPS GETs and exhaust sockets/threads. Cap the dispatcher so
    // excess calls queue instead of all firing at once. maxRequestsPerHost stays at the OkHttp default
    // (5) since each domain is its own host now (the GET goes to the domain being resolved).
    private val httpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .dispatcher(okhttp3.Dispatcher().apply {
            maxRequests = hnsMaxConcurrent              // total in-flight GETs across all domains
        })
        .build()
    private val resolverOrigin = hnsResolverBase.takeIf { it.isNotBlank() }?.let(::canonicalResolverOrigin)
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
        rememberName(endpoint.toList(), domain) // label the endpoint by its domain (endpoints list/traces)
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
        rememberName(endpoint.toList(), domain)
        val id = runCatching {
            node.sendHopsRequest(endpoint, domain, "GET", path, ByteArray(0), 8u * 1024u * 1024u)
        }.getOrNull()
        if (id == null) { cb(502, "text/plain; charset=utf-8", "send failed".toByteArray()); return }
        hopsWebReqs[id.toList()] = cb to (System.currentTimeMillis() + HOPS_WEB_TTL_MS)
        pump()
    }

    fun drainHns() {
        for ((key, id) in deliveredHttpResponses.toMap()) {
            if (runCatching { node.acceptHttpResponse(id) }.getOrDefault(false)) {
                deliveredHttpResponses.remove(key)
                dispatchedHttpResponses.remove(key)
            }
        }
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
            val responseKey = resp.id.toList()
            if (responseKey in dispatchedHttpResponses) continue
            val webCb = hopsWebReqs.remove(resp.forRequestId.toList())?.first
            if (webCb != null) {
                dispatchedHttpResponses.add(responseKey)
                onUi {
                    webCb(resp.status.toInt(), resp.contentType, resp.body)
                    core.post {
                        deliveredHttpResponses[responseKey] = resp.id
                        if (runCatching { node.acceptHttpResponse(resp.id) }.getOrDefault(false)) {
                            deliveredHttpResponses.remove(responseKey)
                            dispatchedHttpResponses.remove(responseKey)
                        }
                    }
                }
                continue
            }
            val domain = hopsReqs.remove(resp.forRequestId.toList())
            if (domain != null) {
                val line = "${resp.status} · ${String(resp.body)}"
                dispatchedHttpResponses.add(responseKey)
                onUi {
                    hopsResults[domain] = line
                    core.post {
                        deliveredHttpResponses[responseKey] = resp.id
                        if (runCatching { node.acceptHttpResponse(resp.id) }.getOrDefault(false)) {
                            deliveredHttpResponses.remove(responseKey)
                            dispatchedHttpResponses.remove(responseKey)
                        }
                    }
                }
            } else {
                runCatching { node.acceptHttpResponse(resp.id) }
            }
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
        val url = reachRecordUrl(domain)
        if (url == null) {
            core.post { runCatching { node.provideReachRecord(domain, ByteArray(0)) }; pump() }
            return
        }
        val req = Request.Builder().url(url).get().build()
        httpClient.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) { deliver(ByteArray(0)) }
            override fun onResponse(call: okhttp3.Call, response: Response) {
                val record = response.use {
                    val body = it.body
                    val contentType = body.contentType()
                    if (it.code != 200 || it.request.url != url ||
                        it.headers.byteCount() > HNS_MAX_HEADER_BYTES ||
                        contentType?.let { type -> type.type == "application" && type.subtype == "json" } != true ||
                        body.contentLength() > HNS_MAX_BODY_BYTES) {
                        ByteArray(0)
                    } else {
                        runCatching { readCapped(body.byteStream(), HNS_MAX_BODY_BYTES)?.let(::parseReachRecord) }
                            .getOrNull() ?: ByteArray(0)
                    }
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
    private fun parseReachRecord(body: ByteArray): ByteArray {
        val reachB64 = org.json.JSONObject(String(body, Charsets.UTF_8)).optString("reach", "")
        if (reachB64.isEmpty()) return ByteArray(0)
        return runCatching { java.util.Base64.getDecoder().decode(reachB64) }
            .getOrNull()?.takeIf { it.size <= HNS_MAX_RECORD_BYTES } ?: ByteArray(0)
    }

    private fun reachRecordUrl(domain: String): HttpUrl? {
        val host = canonicalDomain(domain) ?: return null
        val origin = if (hnsResolverBase.isBlank()) {
            HttpUrl.Builder().scheme("https").host(host).build()
        } else {
            resolverOrigin ?: return null
        }
        return origin.newBuilder().encodedPath("/.well-known/hop").query(null).fragment(null).build()
    }

    private fun canonicalResolverOrigin(raw: String): HttpUrl? {
        val parsed = raw.toHttpUrlOrNull() ?: return null
        if (parsed.scheme !in setOf("http", "https") || parsed.username.isNotEmpty() ||
            parsed.password.isNotEmpty() || parsed.query != null || parsed.fragment != null ||
            parsed.encodedPath != "/") return null
        if (parsed.host !in setOf("localhost", "127.0.0.1", "::1")) return null
        return parsed.newBuilder().encodedPath("/").build()
    }

    private fun readCapped(input: java.io.InputStream, maximum: Long): ByteArray? {
        val out = ByteArrayOutputStream(minOf(maximum, 8_192).toInt())
        val buffer = ByteArray(8_192)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maximum) return null
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    /// Parse a hops:// URL (or bare domain) into (domain, path). The endpoint validates host,
    /// so we pass the bare domain and just the path.
    private fun parseHops(input: String): Pair<String, String> {
        val raw = input.trim()
        if (raw.isEmpty() || raw.contains('\\')) return "" to "/"
        val absolute = if (raw.contains("://")) raw else "hops://$raw"
        val uri = runCatching { URI(absolute) }.getOrNull() ?: return "" to "/"
        if (!uri.scheme.equals("hops", ignoreCase = true) || uri.isOpaque || uri.userInfo != null ||
            uri.port != -1 || uri.fragment != null) return "" to "/"
        val domain = canonicalDomain(uri.host ?: return "" to "/") ?: return "" to "/"
        val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") +
            (uri.rawQuery?.let { "?$it" } ?: "")
        return domain to path
    }

    private fun canonicalDomain(raw: String): String? {
        val host = raw.lowercase()
        if (host.length !in 1..253 || host.endsWith('.') || host.any { it.code !in 0x21..0x7e }) return null
        val labels = host.split('.')
        if (labels.any { label ->
                label.length !in 1..63 || label.first() == '-' || label.last() == '-' ||
                    label.any { !it.isLetterOrDigit() && it != '-' }
            }) return null
        return host
    }

    /// Release the HTTP client's dispatcher thread pool + connection pool (bounded, but still held).
    /// Mirrors the teardown cleanup that previously lived inline in HopBearer.teardown().
    fun shutdown() {
        runCatching { httpClient.dispatcher.executorService.shutdown() }
        runCatching { httpClient.connectionPool.evictAll() }
    }

    private companion object {
        const val HNS_MAX_HEADER_BYTES = 32L * 1024L
        const val HNS_MAX_BODY_BYTES = 64L * 1024L
        const val HNS_MAX_RECORD_BYTES = 32 * 1024
    }
}

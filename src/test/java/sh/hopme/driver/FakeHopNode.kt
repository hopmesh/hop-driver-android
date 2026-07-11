package sh.hopme.driver

import uniffi.hop.HnsCacheEntry
import uniffi.hop.HnsLookupResult
import uniffi.hop.HnsRecord
import uniffi.hop.HopNodeInterface
import uniffi.hop.HpsAccess
import uniffi.hop.HpsInvite
import uniffi.hop.HpsKind
import uniffi.hop.HpsMessage
import uniffi.hop.HpsMyTopic
import uniffi.hop.HpsTopicInfo
import uniffi.hop.HpsVisibility
import uniffi.hop.HttpReq
import uniffi.hop.HttpResp
import uniffi.hop.InboxMessage
import uniffi.hop.MessageStatus
import uniffi.hop.OutPacket
import uniffi.hop.PeerLink
import uniffi.hop.QueueItem
import uniffi.hop.ServiceHit
import uniffi.hop.ServiceReq
import uniffi.hop.ServiceResp
import java.util.concurrent.atomic.AtomicInteger

/**
 * cov/android-driver: an in-memory [HopNodeInterface] that stands in for the real libhop node so the
 * whole HopBearer instance can be driven on a plain JVM (Robolectric) with no device, no SQLite, no FFI
 * to the node itself. Injected via HopBearer's internal test constructor.
 *
 * The node's own free functions the driver still calls (addressBase58 / addressFromBase58 /
 * decodeIdentity / serviceIdentify) run against the REAL host libhop (JNA, jna.library.path), so
 * addresses/base58 behave for real; only the stateful node surface is faked here.
 *
 * Every `take*`/`drain*` accessor is drain-on-read (returns the queued items once, then clears), exactly
 * like the real node, so a single pump() consumes each item once. Tests push items into the `pending*`
 * lists and read back what the driver did via the `sent*` records.
 */
class FakeHopNode(
    private val addr: ByteArray = ByteArray(32) { (it + 1).toByte() },
) : HopNodeInterface {

    // ---- knobs the tests set --------------------------------------------------
    var pendingInbox = mutableListOf<InboxMessage>()
    var pendingHpsMessages = mutableListOf<HpsMessage>()
    var pendingHpsInvites = mutableListOf<HpsInvite>()
    var pendingHnsResults = mutableListOf<HnsRecord>()
    var pendingHttpResponses = mutableListOf<HttpResp>()
    var pendingHttpRequests = mutableListOf<HttpReq>()
    var pendingServiceResponses = mutableListOf<ServiceResp>()
    var pendingServiceRequests = mutableListOf<ServiceReq>()
    var pendingDnsLookups = mutableListOf<String>()
    var pendingOutgoing = mutableListOf<OutPacket>()

    var browseHits: List<ServiceHit> = emptyList()
    var discoverable: List<HpsTopicInfo> = emptyList()
    var peerLinksList: List<PeerLink> = emptyList()
    var queueItems: List<QueueItem> = emptyList()
    var hnsCacheEntries: List<HnsCacheEntry> = emptyList()
    var myTopics: List<HpsMyTopic> = emptyList()
    var members: List<ByteArray> = emptyList()
    var pending: List<ByteArray> = emptyList()

    var securedAddrs: Set<List<Byte>> = emptySet()
    var routedAddrs: Set<List<Byte>> = emptySet()
    var internetOn = false
    var persistent = true
    var reach: UInt = 0u
    var pendingCountVal: UInt = 0u
    var nameVal = ""

    /** bundle-id (as a List<Byte> key) -> status, so refresh()'s delivery reconciliation can be driven. */
    var statuses = HashMap<List<Byte>, MessageStatus>()
    /** resolveHns answer, keyed by domain; default = Pending. */
    var resolve: (String) -> HnsLookupResult = { HnsLookupResult.Pending }
    /** when true, sendMessage throws (drives the stampSent "failed" path). */
    var sendMessageThrows = false

    // ---- what the driver did (assertions read these) --------------------------
    data class Sent(val dst: ByteArray, val contentType: String, val body: ByteArray, val ack: Boolean)
    val sentMessages = mutableListOf<Sent>()
    val serviceRequests = mutableListOf<Triple<ByteArray, String, ByteArray>>()   // dst, service, args
    val serviceResponses = mutableListOf<Int>()                                    // status codes
    val hopsRequests = mutableListOf<String>()                                     // host domains
    val publishedServices = mutableListOf<String>()                                // service names
    val subscriptions = mutableListOf<String>()
    val registered = mutableListOf<String>()
    val hpsPublished = mutableListOf<Pair<String, ByteArray>>()
    val hpsSubscribed = mutableListOf<String>()
    val dnsProofs = mutableListOf<Pair<String, List<String>>>()
    val connectedLinks = mutableListOf<ULong>()
    val disconnectedLinks = mutableListOf<ULong>()
    val received = mutableListOf<Pair<ULong, ByteArray>>()
    var prekeysPublished = 0
    var ticks = 0
    var cleared = 0
    var setInternetCalls = mutableListOf<Boolean>()
    /** the bundle id the driver got back from the last sendHopsRequest - tests key an HttpResp to it. */
    var lastHopsReqId: ByteArray = ByteArray(0)
    /** the id from the last sendServiceRequest (queueIdentify) - tests key a ServiceResp to it. */
    var lastServiceReqId: ByteArray = ByteArray(0)

    private val ids = AtomicInteger(1)
    private fun nextId(): ByteArray = ByteArray(32).also { it[0] = 0x77; it[31] = ids.getAndIncrement().toByte() }
    private fun <T> drain(l: MutableList<T>): List<T> { val c = l.toList(); l.clear(); return c }

    // ---- HopNodeInterface -----------------------------------------------------
    override fun address(): ByteArray = addr
    override fun name(): String = nameVal
    override fun setName(name: String) { nameVal = name }
    override fun secret(): ByteArray = ByteArray(32) { 0x5 }
    override fun isInternet(): Boolean = internetOn
    override fun isPersistent(): Boolean = persistent
    override fun rehydrateDropped(): UInt = 0u
    override fun pendingCount(): UInt = pendingCountVal

    override fun setInternet(on: Boolean) { internetOn = on; setInternetCalls.add(on) }
    override fun subscribe(topic: String) { subscriptions.add(topic) }
    override fun tick(nowMs: ULong) { ticks++ }
    override fun publishPrekey(): ByteArray { prekeysPublished++; return nextId() }
    override fun publishService(service: String, title: String, summary: String, tags: List<String>, ttlMs: UInt): ByteArray {
        publishedServices.add(service); return nextId()
    }

    override fun connected(link: ULong, initiator: Boolean) { connectedLinks.add(link) }
    override fun disconnected(link: ULong) { disconnectedLinks.add(link) }
    override fun received(link: ULong, bytes: ByteArray) { received.add(link to bytes) }
    override fun drainOutgoing(): List<OutPacket> = drain(pendingOutgoing)

    override fun sendMessage(dst: ByteArray, contentType: String, body: ByteArray, requestAck: Boolean): ByteArray {
        if (sendMessageThrows) throw RuntimeException("seal failed (test)")
        sentMessages.add(Sent(dst, contentType, body, requestAck))
        return nextId()
    }
    override fun sendMessageTraced(dst: ByteArray, contentType: String, body: ByteArray, requestAck: Boolean): ByteArray {
        sentMessages.add(Sent(dst, contentType, body, requestAck)); return nextId()
    }
    override fun sendTo(address: ByteArray, contentType: String, body: ByteArray, requestAck: Boolean): ByteArray {
        sentMessages.add(Sent(address, contentType, body, requestAck)); return nextId()
    }
    override fun messageStatus(id: ByteArray): MessageStatus =
        statuses[id.toList()] ?: MessageStatus(relayed = 0u, delivered = false, deliveryHops = 0u, deliveryMs = 0u)

    override fun takeInbox(): List<InboxMessage> = drain(pendingInbox)
    override fun browse(service: String, tag: String): List<ServiceHit> = browseHits
    override fun browseDiscoverable(): List<HpsTopicInfo> = discoverable
    override fun peerLinks(): List<PeerLink> = peerLinksList
    override fun peers(): List<ByteArray> = peerLinksList.map { it.address }
    override fun isSecured(address: ByteArray): Boolean = address.toList() in securedAddrs
    override fun knowsRoute(address: ByteArray): Boolean = address.toList() in routedAddrs
    override fun queue(): List<QueueItem> = queueItems
    override fun clearQueue() { cleared++ }
    override fun hnsCache(): List<HnsCacheEntry> = hnsCacheEntries

    // services (§29)
    override fun sendServiceRequest(dst: ByteArray, service: String, method: String, args: ByteArray): ByteArray {
        serviceRequests.add(Triple(dst, service, args)); return nextId().also { lastServiceReqId = it }
    }
    override fun sendServiceResponse(to: ByteArray, forRequestId: ByteArray, status: UShort, body: ByteArray): ByteArray {
        serviceResponses.add(status.toInt()); return nextId()
    }
    override fun takeServiceResponses(): List<ServiceResp> = drain(pendingServiceResponses)
    override fun takeServiceRequests(): List<ServiceReq> = drain(pendingServiceRequests)

    // hps:// (§32)
    override fun registerService(path: String, kind: HpsKind, access: HpsAccess, visibility: HpsVisibility): ByteArray {
        registered.add(path); return nextId()
    }
    override fun hpsSubscribe(host: ByteArray, path: String): ByteArray { hpsSubscribed.add(path); return nextId() }
    override fun hpsPublish(path: String, body: ByteArray): ByteArray { hpsPublished.add(path to body); return nextId() }
    override fun hpsInvite(path: String, dest: ByteArray): ByteArray = nextId()
    override fun hpsAcceptInvite(host: ByteArray, path: String): ByteArray = nextId()
    override fun hpsDeclineInvite(host: ByteArray, path: String) {}
    override fun hpsDeny(path: String, requester: ByteArray) {}
    override fun hpsApprove(path: String, requester: ByteArray): ByteArray = nextId()
    override fun hpsLeave(path: String): ByteArray = nextId()
    override fun hpsMembers(path: String): List<ByteArray> = members
    override fun hpsPending(path: String): List<ByteArray> = pending
    override fun hpsReach(path: String): UInt = reach
    override fun hpsRekey(path: String, newPath: String, remove: List<ByteArray>): List<ByteArray> = listOf(nextId())
    override fun hpsMyTopics(): List<HpsMyTopic> = myTopics
    override fun takeHpsMessages(): List<HpsMessage> = drain(pendingHpsMessages)
    override fun takeHpsInvites(): List<HpsInvite> = drain(pendingHpsInvites)

    // HNS + hops:// (§30)
    override fun resolveHns(domain: String): HnsLookupResult = resolve(domain)
    override fun resolveHnsVia(resolver: ByteArray, domain: String): ByteArray = nextId()
    override fun sendHopsRequest(endpoint: ByteArray, host: String, method: String, url: String, body: ByteArray, maxResp: UInt): ByteArray {
        hopsRequests.add(host); return nextId().also { lastHopsReqId = it }
    }
    override fun sendHttpResponse(to: ByteArray, forRequestId: ByteArray, status: UShort, body: ByteArray) {}
    override fun takeHnsResults(): List<HnsRecord> = drain(pendingHnsResults)
    override fun takeHttpResponses(): List<HttpResp> = drain(pendingHttpResponses)
    override fun takeHttpRequests(): List<HttpReq> = drain(pendingHttpRequests)
    override fun takeDnsLookups(): List<String> = drain(pendingDnsLookups)
    override fun provideDnsProof(domain: String, bodies: List<String>) { dnsProofs.add(domain to bodies) }
}

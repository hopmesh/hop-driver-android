package sh.hopme.driver

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import kotlin.concurrent.thread
import androidx.compose.runtime.mutableStateMapOf
import uniffi.hop.HopNode
import uniffi.hop.HopNodeInterface
import uniffi.hop.TraceHopInfo
import uniffi.hop.addressBase58
import uniffi.hop.addressFromBase58
import uniffi.hop.decodeIdentity
import uniffi.hop.serviceIdentify

/**
 * The Android Hop driver: owns the libhop node, the app-model (messages / peers / hps / chat), and
 * the pump loop, and forms transport links through the shared cross-platform bearers (BLE / LAN /
 * Wi-Fi Direct / cloud relay) multiplexed by one BearerManager. Mirrors the iOS driver; does no
 * protocol work, only shuttles bytes via connected / received / drainOutgoing.
 */
@SuppressLint("MissingPermission")
class HopBearer internal constructor(
    private val context: Context,
    private val config: HopConfig,
    // cov/android-driver test seam: an already-built node injected by the driver's own unit tests,
    // bypassing the [node] field-initializer's HopNode.openKeyed below (which loads libhop.so and can't
    // run on a plain JVM). This constructor is `internal`, so only the driver module can reach it; the
    // app (a separate Gradle module) still MUST go through [shared]. Production passes null (see the
    // 2-arg secondary constructor), so the initializer opens the real keyed node exactly as before.
    injectedNode: HopNodeInterface?,
) {

    data class Peer(
        val address: ByteArray, val name: String, val hops: UByte,
        val active: Boolean = true, val platform: String = "", val app: String = "",
    )
    data class Message(
        val localId: Long, val peer: String, val text: String, val incoming: Boolean,
        val bundleId: ByteArray? = null,
        val contentType: String = "text/plain",
        val imageData: ByteArray? = null,                            // set for a single image/* message
        val images: List<ByteArray> = emptyList(),                   // one+ images of a multipart message
        val hops: UByte = 0u, val latencyMs: ULong? = null,           // incoming metadata
        val trace: List<String> = emptyList(),                        // provenance hop labels (§27)
        val sentAt: Long = System.currentTimeMillis(),               // outgoing tracking
        val deliveredAt: Long? = null, val relayed: UInt = 0u,
        val delivered: Boolean = false, val deliveryHops: UByte = 0u,
        val deliveryMs: ULong? = null, // forward-path (A→B) latency the recipient reported, ms
        val failed: Boolean = false,   // gave up (e.g. the queue was cleared before it sent)
    )

    // Identity derived from the device (stable, storage-independent - §4); the db path
    // persists messages across restarts. Both come from the host-supplied HopConfig.
    // F-25: open SQLCipher-encrypted with the device-derived db key (empty ⇒ plain). Only encrypts
    // when libhop is built `--features sqlcipher` (tools/build-aar.sh, on by default).
    // Typed to the UniFFI-generated HopNodeInterface (not the concrete HopNode) so a unit test can
    // inject an in-memory fake; production passes injectedNode=null and this initializer opens the real
    // keyed node, byte-for-byte the same openKeyed call as before the seam was added.
    val node: HopNodeInterface = injectedNode ?: HopNode.openKeyed(
        config.dbPath,
        config.identitySecret,
        config.appSecret,
        config.dbKey,
    )

    /**
     * Production constructor: no injected node, so the [node] initializer opens the real keyed libhop
     * node (SQLCipher at rest when built `--features sqlcipher`). Behavior-identical to the prior
     * behavior; [shared] uses this path.
     */
    private constructor(context: Context, config: HopConfig) : this(context, config, null)
    val peers = mutableStateListOf<Peer>()
    /// Persistent address book: everyone we've seen, messaged, or been messaged by, keyed by address.
    /// `seen` is the subset NOT currently reachable - so past conversations stay reachable even when
    /// the peer is offline / out of range / after a restart (mirrors iOS).
    private val contacts = HashMap<List<Byte>, Peer>()
    val seen = mutableStateListOf<Peer>()
    /// Directly-linked peer address → the transport carrying it ("BT" / "Relay"); mesh peers
    /// (reached multi-hop) have no entry. Mirrors iOS's link-type indicators.
    val linkTransports = mutableStateMapOf<List<Byte>, String>()
    val messages = mutableStateListOf<Message>()
    /// Unread incoming messages received while backgrounded - mirrored onto the app icon
    /// badge (via the notification) and cleared when the app returns to the foreground.
    val unread = androidx.compose.runtime.mutableIntStateOf(0)
    val secured = mutableStateListOf<List<Byte>>()   // addresses with a forward-secret session

    // HNS + hops:// (DESIGN.md §30) is owned by [HnsController] (composed below, once core/nameByAddr
    // exist). The app-facing state (hopsResults, hnsCache) is re-exposed as delegating getters so
    // `bearer.hopsResults` / `bearer.hnsCache` keep returning the same Compose snapshot instances.
    /// One HNS cache debug row: domain → (address, ttl). App-facing model, kept nested here.
    data class HnsCacheRow(val domain: String, val address: ByteArray, val ttlSecs: UInt)

    // hps:// pub/sub (DESIGN.md §32) is owned by [HpsController] (composed below, once core/mirror exist).
    // The app-facing state (hpsTopics/hpsThreads/hpsUnread/hpsInvites) is re-exposed as delegating getters
    // so `bearer.hps*` keeps returning the same Compose snapshot instances. HpsTopic/HpsMsg stay nested
    // here (the app references them as HopBearer.HpsTopic / HopBearer.HpsMsg).

    // Diagnostics (Status tab) - parity with iOS: service-call log + relay queue.
    val serviceLog = mutableStateListOf<String>()
    val queue = mutableStateListOf<QueueRow>()
    data class QueueRow(val own: Boolean, val to: String, val priority: UByte, val hops: UByte)
    private val identifyAsked = HashSet<List<Byte>>()   // addresses we've sent hop.identify to
    private val identifyReqs = HashSet<List<Byte>>()    // outstanding identify request ids
    data class HpsTopic(
        val host: ByteArray, val path: String, val channel: Boolean, val hosting: Boolean,
        val access: uniffi.hop.HpsAccess = uniffi.hop.HpsAccess.OPEN,
    ) {
        val id: String get() = addressBase58(host) + "/" + path
        val writable: Boolean get() = channel || hosting
    }
    data class HpsMsg(val id: Long, val path: String, val sender: ByteArray, val text: String)
    var myAddress = mutableStateOf("")
    var myName = mutableStateOf("")
    // Core also READS our name/address/private-mode (publishPresence, LAN discovery), so those live
    // in @Volatile backing fields the core thread reads; the mutableStateOf above is the UI mirror,
    // written via onUi. (Keeps "Compose state mutates only on main" without racing core's reads.)
    @Volatile private var myAddressVal = ""
    @Volatile private var myNameVal = ""
    @Volatile private var privateModeVal = false
    /// Privacy: when on, we stop broadcasting our presence advert (name + address). We stay fully
    /// relay-capable and reachable by anyone who already has our address (scanned QR / manual add).
    val privateMode = mutableStateOf(false)
    var status = mutableStateOf("starting…")
    var relayStatus = mutableStateOf("not connected")
    private val prefs get() = context.getSharedPreferences("hop", android.content.Context.MODE_PRIVATE)
    /// A relay the user pinned by direct address (persisted). A device only ever talks to ONE
    /// relay - routing is anycast - so pinning overrides the anycast default for testing a
    /// specific relay, rather than publishing presence to several at once.
    val pinnedRelay = mutableStateOf<String?>(null)
    private val nextMsgId = java.util.concurrent.atomic.AtomicLong(0L)
    /// Notifications concern (message channel + incoming-message notification + badge). Split out of the
    /// god-object into [Notifier]; the driver composes it and posts from pump().
    private val notifier = Notifier(context, CHANNEL_ID, config.notificationIcon)
    /// At-rest mirror store (seal/open + content-addressed media) the three persistence mirrors share.
    /// Split out of the god-object into [MirrorStore]; wraps [MirrorCrypto] with the device db key.
    private val mirror = MirrorStore(config.dbKey, context.filesDir)
    @Volatile private var appActive = true
    private val appName: String =
        context.applicationInfo.loadLabel(context.packageManager).toString()

    // Threading model (DESIGN: Stage C). Every node.* call and all bearer plumbing run on ONE serial
    // background thread (hop.core), so the main thread never does node/SQLite/crypto work - under
    // multi-peer BLE load that path ANR'd ("Skipped N frames"). The node is internally Mutex-guarded
    // (thread-safe), so the rare synchronous UI getters (node.address(), hpsReach/Members/…) can still
    // read it directly from main. Compose snapshot state is UI state: it is WRITTEN ONLY on the main
    // thread via onUi { } - core does the node work, then marshals the visible result to main. The
    // heavy SQLite-backed refresh stays coalesced (~4 Hz, scheduleRefresh); never refresh per-frame.
    private val coreThread = HandlerThread("hop.core").apply { start() }
    private val core = Handler(coreThread.looper)
    private val main = Handler(Looper.getMainLooper())
    /** cov/android-driver test seam: the serial core looper, so a Robolectric test can flush the
     *  background HandlerThread deterministically with ShadowLooper.idle()/idleFor(). No production use. */
    internal val coreLooper: Looper get() = coreThread.looper
    /// Marshal a Compose-state update to the main thread. UI (snapshot) state mutates ONLY here.
    private fun onUi(block: () -> Unit) { main.post(block) }
    private var lastPeerLinkCount = -1
    // Read from the UI thread (displayName) and written on core (refresh/pump), so concurrent-safe.
    private val nameByAddr = java.util.concurrent.ConcurrentHashMap<List<Byte>, String>()

    // HNS + hops:// concern (§30), composed here now that node/core/nameByAddr exist. Every method runs
    // on `core`; drainHns() is still called from pump(), expireHopsWeb() from the tick loop, and the DoH
    // client is released in teardown() via hns.shutdown().
    private val hns = HnsController(node, core, ::onUi, ::pump, nameByAddr, config.dohResolverUrl, DOH_MAX_CONCURRENT)
    /// hops:// text-box results (domain → rendered result). Delegates to [HnsController] so the app keeps
    /// observing the same Compose snapshot map (`bearer.hopsResults`).
    val hopsResults get() = hns.hopsResults
    /// HNS cache debug view. Delegates to [HnsController] (`bearer.hnsCache`).
    val hnsCache get() = hns.hnsCache

    // hps:// pub/sub concern (§32), composed here now that node/core/mirror exist. drainHps() is called
    // from pump(); loadChannels()/loadTopics() from start(). The app-facing state is re-exposed below.
    private val hps = HpsController(node, core, ::onUi, ::pump, { nextMsgId.getAndIncrement() }, mirror, context.filesDir)
    /// Topics we host/subscribe. Delegates to [HpsController] (`bearer.hpsTopics`).
    val hpsTopics get() = hps.hpsTopics
    /// Per-topic message threads (topic id → messages). Delegates to [HpsController] (`bearer.hpsThreads`).
    val hpsThreads get() = hps.hpsThreads
    /// Per-topic unread counts. Delegates to [HpsController] (`bearer.hpsUnread`).
    val hpsUnread get() = hps.hpsUnread
    /// Received channel invites. Delegates to [HpsController] (`bearer.hpsInvites`).
    val hpsInvites get() = hps.hpsInvites

    // ---- shared cross-platform transport layer (ble-lab bearer-core/-ble/-lan/-relay) ----
    // The driver forms pure-L2CAP BLE + LAN + cloud-relay links, all multiplexed by ONE BearerManager
    // and surfaced through bearerSink into the node seam. The manager's global link-id space starts
    // HIGH (1_000_000). (The legacy in-driver BLE/LAN/relay transports and Wi-Fi Direct were removed.)
    private val bearerMgr = sh.hop.BearerManager(baseLinkId = 1_000_000L)
    // One transport id shared by the bearers (the BLE/LAN HELLO id + greater-id dedup tiebreaker);
    // distinct from the Hop node address - Noise is still negotiated over the bearer's DATA frames.
    private val bearerId = sh.hop.randomNodeId()
    // Link ids currently owned by the BearerManager, so refresh() can tag them by transport. CORE-CONFINED:
    // added/removed and read ONLY inside core.post (pump() also runs on core), so it needs no lock.
    private val bearerLinks = HashSet<Long>()
    // quality-net-07: per-link tx/rx byte + packet counters. The tick loop emits one LINKFLOW line per
    // link so the soak's flood-health critic has real per-link throughput to judge (a gossip-flood
    // regression shows as data-pkt/tx climbing fast on an idle link). CORE-CONFINED like bearerLinks
    // (onTx runs in pump(); onRx/up/down hop to core via bearerSink), so it needs no lock.
    private val linkFlow = LinkFlow()
    // Adapts the shared BearerManager to the node seam. Every link from a bearer surfaces here and
    // drives node.connected/received/disconnected (linkId mismatch: the bearer libs use Long, the node
    // uses ULong - convert at this boundary).
    // android-09: the effective relay switch - the caller's config AND'd with a runtime killswitch pref,
    // so the deployed-off fleet stays off without an app update. Set once in start().
    @Volatile private var relaysOnVal = false
    /// android-09: relays run only when the caller opted in AND the runtime killswitch is on. The pref
    /// defaults to the config value, so an operator/remote-config can force relays OFF (fleet torn down)
    /// without shipping a new build; it never force-ENABLES a config that opted out.
    private fun relaysEffectivelyEnabled(): Boolean =
        config.relaysEnabled && prefs.getBoolean("relaysEnabled", config.relaysEnabled)

    // android-11: global link ids currently on the "Relay" transport, so linkDown can recognize a relay
    // drop even though BearerManager forgets the transport mapping BEFORE it calls linkDown(). Touched
    // only from the manager's sink callbacks (one thread), but kept synchronized as a cheap guard.
    private val relayLinks = java.util.Collections.synchronizedSet(HashSet<Long>())
    private val bearerSink = object : sh.hop.LinkSink {
        override fun linkUp(link: Long, role: sh.hop.HopRole, peerId: ByteArray) {
            // android-03: LINKFLOW at the manager seam - the GLOBAL link id + transport + peer, so a soak
            // can correlate a bearer-local "LINKFLOW LINK UP link=<local>" line with the node-facing
            // connected()/disconnected() churn. This is where the previously-missing bearer↔global↔node
            // link correlation gets stitched.
            val t = bearerMgr.transportNameOf(link) ?: "?"
            android.util.Log.i("HOPLOG", "LINKFLOW SEAM UP g=$link xport=$t role=$role peer=${peerId.take(6).joinToString("") { "%02x".format(it) }}")
            // android-11: infer relay status from the manager's link events - the RelayBearer surfaces its
            // one link with transportName "Relay", so a live relay link IS the honest "connected" signal.
            if (t == "Relay") {
                relayLinks.add(link)
                if (relaysOnVal) onUi { relayStatus.value = "connected" }
            }
            core.post {
                if (torndown) return@post   // a late link event after teardown must not touch a closed node
                bearerLinks.add(link)
                linkFlow.linkUp(link, t)   // quality-net-07: start per-link tx/rx counters
                node.connected(link.toULong(), role == sh.hop.HopRole.DIALER)
                pump()   // ship the dialer's queued Noise m1 immediately (mirrors legacy addLink / Apple linkUp)
            }
        }
        override fun linkBytes(link: Long, bytes: ByteArray) {
            core.post { if (torndown) return@post; linkFlow.onRx(link, bytes.size); node.received(link.toULong(), bytes); pump() }
        }
        override fun linkDown(link: Long) {
            // android-11: the manager already dropped the transport mapping, so recognize the relay link
            // from the set we recorded at linkUp instead of transportNameOf().
            val wasRelay = relayLinks.remove(link)
            android.util.Log.i("HOPLOG", "LINKFLOW SEAM DOWN g=$link${if (wasRelay) " xport=Relay" else ""}")
            if (wasRelay && relaysOnVal) onUi { relayStatus.value = "reconnecting…" }
            core.post {
                if (torndown) return@post   // a late linkDown after teardown must not touch a closed node
                bearerLinks.remove(link)
                linkFlow.linkDown(link)   // quality-net-07: stop per-link tx/rx counters
                node.disconnected(link.toULong())
                scheduleRefresh()   // mirrors legacy onClose / Apple linkDown
            }
        }
    }

    @Volatile var appInForeground = false
    private var started = false
    // Set by teardown() so the self-reposting tick loop stops re-arming and so a late core task after
    // shutdown is a no-op. Read on core (the tick loop) and set from teardown (which hops to core).
    @Volatile private var torndown = false

    fun start(name: String = config.deviceName) = core.post {
        if (started) return@post
        started = true
        notifier.ensureChannel()
        loadMessages()      // restore chat history from the previous run
        loadContacts()      // restore the address book so past conversations are reachable when offline
        hps.loadChannels()  // restore channel (hps) message threads
        hps.loadTopics()    // restore hosted/subscribed channels (the node persists them)
        myNameVal = name; onUi { myName.value = name }
        val addr58 = addressBase58(node.address())
        myAddressVal = addr58; onUi { myAddress.value = addr58 }
        // android-r2-05: the self-address targeting line prints our full base58 address; the harness
        // needs it, but a release build must not leak identity to logcat. Debug builds only.
        if (DriverFlags.verboseContentLogs)
            android.util.Log.i("HOPLOG", "HOPAUTO self=$addr58 name=${config.deviceName}")   // test harness reads this for targeting
        // Presence is an app-level service (DESIGN.md §23): publish our name on the
        // "presence" topic and subscribe so discovered records are retained.
        runCatching { node.subscribe(PRESENCE_SERVICE) }
        // Set the node clock to real time BEFORE publishing any adverts. The node starts at
        // now_ms=0 and the first tick runs directory.expire(), so a prekey/presence advert
        // stamped created_at=0 here is judged expired (1970 + TTL) and dropped instantly.
        // Presence re-publishes and recovers; the prekey is published once, so without this no
        // peer ever learns our prekey and every message defers forever ("Sending…"). (§25)
        runCatching { node.tick(nowMs()) }
        publishPresence()
        // Publish our prekey so peers can open forward-secret sessions (§25). Re-published
        // periodically in the tick loop too, so a lapsed/late neighbour can always re-open one.
        runCatching { node.publishPrekey() }
        // Shared transport layer (HopBearers): pure-L2CAP BLE + LAN + cloud relay, multiplexed by ONE
        // BearerManager. The BleBearer owns the peripheral+central roles and the LanBearer owns NSD/TCP.
        // Seed the background flag (foreground-service default false), point the manager at our node
        // adapter, register the bearers, and start. BleBearer is pure-L2CAP by design (the proven
        // clean-room transport). NO Wi-Fi Direct bearer: WifiP2pManager.connect pops a system per-peer
        // approval dialog a passive mesh can't use (BLE + LAN cover Android peers; NSD/LAN is silent).
        sh.hop.appInBackground = !appActive
        bearerMgr.sink = bearerSink
        bearerMgr.register(sh.hopme.bearers.ble.BleBearer(context, bearerId))
        bearerMgr.register(sh.hopme.bearers.lan.LanBearer(context, bearerId))
        // Cloud relay (WebSocket) as a shared bearer - ONE outbound link to the backbone, registered
        // only when relays are enabled and a URL exists. (P2P test mode sets relaysEnabled=false, so
        // this stays unregistered.)
        val relaysOn = relaysEffectivelyEnabled()
        relaysOnVal = relaysOn
        if (relaysOn) {
            val relay = prefs.getString("pinnedRelay", null) ?: config.relayUrl
            if (relay.isNotEmpty())
                bearerMgr.register(sh.hopme.bearers.relay.RelayBearer(relay))
        }
        bearerMgr.start()
        // android-10: the mesh is up, so drive status off "starting…" so the UI reflects the live state
        // (it was previously never written again, leaving Status stuck on "starting…" forever).
        onUi { status.value = "running" }
        android.util.Log.i("HOPLOG", "shared bearers started (BLE+LAN${if (relaysOn) "+Relay" else ""}) id=${bearerId.take(4).joinToString("") { "%02x".format(it) }}")
        // Declare internet reachability so the node resolves HNS itself by servicing
        // takeDnsLookups() (DESIGN.md §30). Track the default network so it stays accurate.
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        runCatching {
            val net = cm?.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            node.setInternet(caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
            cm?.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) { core.post { node.setInternet(true); pump() } }
                override fun onLost(network: android.net.Network) { core.post { node.setInternet(false) } }
            })
        }
        // Check in to the backbone (DESIGN.md §28): dial the anycast relay so we pull any
        // queued mail and stay reachable across the internet. The foreground service keeps
        // this alive; the tick loop below reconnects it if it ever drops.
        val pinned = prefs.getString("pinnedRelay", null)
        val priv = prefs.getBoolean("privateMode", false)
        privateModeVal = priv
        onUi { pinnedRelay.value = pinned; privateMode.value = priv }
        // The shared RelayBearer owns the cloud relay. Surface "disabled" in pure-P2P mode so the UI
        // reflects it (relays off); otherwise seed "connecting…" and let bearerSink flip it to
        // "connected"/"reconnecting…" on the relay link's up/down events (android-09/11).
        onUi { relayStatus.value = if (relaysOn) "connecting…" else "disabled" }
        var ticks = 0
        core.postDelayed(object : Runnable {
            override fun run() {
                if (torndown) return   // teardown() stops the loop re-arming; don't touch a closed node
                node.tick(nowMs())
                if (++ticks % 20 == 0) publishPresence()
                // Re-publish our prekey periodically so a neighbour whose cached copy lapsed
                // (or who arrived later) can always open a forward-secret session to us (§25).
                if (ticks % 120 == 0) runCatching { node.publishPrekey() }
                if (ticks % 15 == 0 && DriverFlags.verboseContentLogs) android.util.Log.i("HOPLOG", "HOPAUTO self=$myAddressVal name=${config.deviceName}")  // periodic, so the harness always finds it (android-r2-05: debug only)
                hns.expireHopsWeb()   // android-13: fail out WebView fetches whose resolve/response never arrived
                pump()
                // DIAG: node link state each tick. peerLinks() maps each Up link to its peer address,
                // so a link that is UP but stuck (no peerLinks) shows the Noise handshake never
                // completed. The BearerManager owns the transports now, so this reads node state only.
                val pls = runCatching { node.peerLinks() }.getOrDefault(emptyList())
                val distinctPeers = pls.map { it.address.toList() }.distinct().size
                if (pls.isNotEmpty()) android.util.Log.i("HOPLOG",
                    "NODESTATE upLinks=${pls.size} peers=$distinctPeers pend=${runCatching { node.pendingCount() }.getOrDefault(0u)} " +
                    pls.joinToString(" ") { p -> "id${p.link}=${p.address.take(3).joinToString(""){ b -> "%02x".format(b) }}" +
                        "[sec=${runCatching { node.isSecured(p.address) }.getOrDefault(false)},rt=${runCatching { node.knowsRoute(p.address) }.getOrDefault(false)}]" })
                // quality-net-07: one LINKFLOW line per bearer link every ~5s. The flood-health critic
                // reads these tx/rx byte + txpkts[hs/data/frag] counters across rounds to spot a link
                // whose data/tx climbs fast on idle (the historical gossip-flood regression) vs a healthy
                // link that grows slowly. Empty (no lines) when there are no bearer links.
                if (ticks % 5 == 0) for (l in linkFlow.lines()) android.util.Log.i("HOPLOG", l)
                if (!torndown) core.postDelayed(this, 1000)
            }
        }, 1000)
    }

    /// Release every long-lived resource this driver owns: the shared bearers (radios/sockets), the DoH
    /// HTTP client, the serial core HandlerThread, and the libhop node handle. This is the cleanup path
    /// for a process-level teardown (e.g. an owning foreground service's onDestroy); the current demo
    /// creates the singleton from an Activity and relies on process death, so it is not yet wired to a
    /// lifecycle callback, but it is kept correct and crash-safe so wiring it is a one-liner.
    /// Idempotent + safe to call from any thread; the actual node close runs on core (its only caller)
    /// AFTER the tick loop and pending work drain, so nothing touches a freed handle. Mirrors the
    /// executor/link cleanup the RelayBearer got.
    fun teardown() {
        if (torndown) return
        torndown = true
        started = false
        // Drop the process singleton under the SAME lock shared() creates it under, and only if it is
        // still THIS instance, so a teardown racing a re-creation cannot null out (orphan) a newer
        // driver (r6-01). After this a shared() builds a FRESH driver rather than handing back this
        // torn-down one (torndown is one-way, so returning it would be a permanent brick). The node
        // handle is closed on `core` below; a shared() in that narrow window would open a second handle
        // on the same db, but Android serializes a foreground service's onDestroy (the teardown site)
        // with the next onCreate/shared(), so that overlap does not occur in the real lifecycle.
        synchronized(Companion) { if (inst === this) inst = null }
        // Stop the radios/sockets first so no new link event races the node close.
        runCatching { bearerMgr.stop() }
        // Release the DoH client's dispatcher thread pool + connection pool (bounded, but still held).
        hns.shutdown()
        // Drain the core queue THEN close the node + quit the thread, so any already-queued node work
        // finishes before the handle is freed (closing under an in-flight node.* call is a use-after-free).
        core.post {
            core.removeCallbacksAndMessages(null)   // drop the self-reposting tick + any pending tasks
            // close() is on the concrete HopNode (AutoCloseable), not on HopNodeInterface (the seam type).
            // The real production node IS AutoCloseable, so this still frees the libhop handle; a unit
            // test's in-memory fake that doesn't implement AutoCloseable is simply a no-op here.
            runCatching { (node as? AutoCloseable)?.close() }
            coreThread.quitSafely()                 // stop the serial core HandlerThread
        }
    }

    /// Re-publish our presence advert so it stays within TTL and renames propagate.
    /// `summary` carries app-level metadata: "state|platform|app".
    private fun publishPresence() {
        if (privateModeVal) return   // private: don't broadcast our name/address
        val meta = "${if (appActive) "fg" else "bg"}|android|$appName"
        runCatching {
            node.publishService(PRESENCE_SERVICE, myNameVal, meta, emptyList(), PRESENCE_TTL_MS)
        }
    }

    /// Toggle private mode (persisted). On → supersede our live presence with a near-instantly-
    /// expiring one so peers drop our name now. Off → start broadcasting again.
    fun setPrivateMode(on: Boolean) {
        privateModeVal = on          // core reads this in publishPresence
        privateMode.value = on       // UI mirror (caller is main)
        prefs.edit().putBoolean("privateMode", on).apply()
        core.post {
            if (on) {
                val meta = "${if (appActive) "fg" else "bg"}|android|$appName"
                runCatching { node.publishService(PRESENCE_SERVICE, myNameVal, meta, emptyList(), 1000u) }
                pump()
            } else publishPresence()
        }
    }

    /// The host activity calls this on resume/pause; we re-publish presence so peers
    /// see our current foreground/background state.
    fun setForeground(fg: Boolean) {
        appActive = fg
        appInForeground = fg
        sh.hop.appInBackground = !fg   // shared bearers: relax liveness deadline when backgrounded
        if (fg) {   // the user is looking at the app → clear the unread badge + notifications
            unread.intValue = 0
            notifier.cancelAll()
        }
        core.post { publishPresence(); pump() }
    }

    /** Stable conversation key for a peer - its base58 ADDRESS, never its display name. Two distinct
     *  peers that happen to share a name (e.g. two "Pixel 7"s) must not collapse into one thread. */
    fun keyFor(p: Peer): String = addressBase58(p.address)

    /** Test/automation hook: send [text] to a base58 ADDRESS, building a minimal Peer (no UI selection
     *  needed). Backs the hopdemo://send deep link so a harness can drive sends without UI taps.
     *
     *  android-r2-03: this is a silent send-as-user primitive, so it is inert unless the automation
     *  surface is enabled (debug builds only) - defense in depth beside the debug-only manifest filter
     *  and the BuildConfig.DEBUG guard in the activity. */
    fun sendTo(addrBase58: String, text: String) {
        if (!DriverFlags.automationSurface) return   // release: refuse driven sends (android-r2-03)
        val addr = runCatching { addressFromBase58(addrBase58.trim()) }.getOrNull() ?: return
        send(text, Peer(addr, contacts[addr.toList()]?.name ?: "", 0u, active = false))
    }

    fun send(text: String, to: Peer) {
        // Optimistic insert: the bubble appears INSTANTLY (next main frame), decoupled from the serial
        // core thread + the blocking node.sendMessage. The node send + the bundleId (for delivery
        // tracking) fill in on core and patch back by localId. (Prior bug: the append ran INSIDE
        // core.post AFTER node.sendMessage, so a busy core stalled the bubble for seconds - felt frozen.)
        val msg = Message(localId = nextMsgId.getAndIncrement(), peer = addressBase58(to.address), text = text, incoming = false, bundleId = null)
        onUi { messages.add(msg) }
        core.post {
            rememberContact(to)   // messaging someone adds them to your address book
            val r = runCatching { node.sendMessage(to.address, "text/plain", text.toByteArray(), true) }
            r.exceptionOrNull()?.let { android.util.Log.w("HOPLOG", "sendMessage threw for ${msg.localId}: ${it.message}") }
            stampSent(msg.localId, r.getOrNull())
            pump()
        }
    }

    /// Patch an optimistically-inserted outgoing message with the bundleId returned by node.sendMessage.
    /// Located by localId; the StateList edit hops to main. A non-null id → track delivery. A NULL id
    /// means node.sendMessage THREW (a real store/seal error, not the peer-unreachable case, which
    /// defers with a valid id) - mark the row `failed` so it stops showing "Sending…" forever and the
    /// "Not sent · tap to retry" affordance engages (F-15). Applies to text/image/multipart alike.
    private fun stampSent(localId: Long, id: ByteArray?) = onUi {
        val i = messages.indexOfFirst { it.localId == localId }
        if (i < 0) return@onUi
        messages[i] = if (id != null) messages[i].copy(bundleId = id) else messages[i].copy(failed = true)
    }

    /// Send an image - large bodies are transparently carrier-chunked + reassembled by core
    /// (DESIGN.md §20), same path as a text message but a binary content type.
    fun sendImage(data: ByteArray, to: Peer) {
        val msg = Message(localId = nextMsgId.getAndIncrement(), peer = addressBase58(to.address), text = "", incoming = false,
            bundleId = null, contentType = "image/jpeg", imageData = data)
        onUi { messages.add(msg) }   // optimistic - instant bubble (see send())
        core.post {
            rememberContact(to)
            val id = runCatching { node.sendMessage(to.address, "image/jpeg", data, true) }.getOrNull()
            stampSent(msg.localId, id)
            pump()
        }
    }

    /// Send text and/or one-or-more images as ONE message (multipart/mixed) - a single sealed
    /// payload (DESIGN.md §20/§32). Wire format shared with iOS:
    /// `[u32 partCount][ per part: u16 ctLen, ct, u32 bodyLen, body ]`.
    fun sendMultipart(text: String, images: List<ByteArray>, to: Peer) {
        val t = text.trim()
        val parts = ArrayList<Pair<String, ByteArray>>()
        if (t.isNotEmpty()) parts.add("text/plain" to t.toByteArray())
        for (img in images) parts.add("image/jpeg" to img)
        if (parts.isEmpty()) return
        val msg = Message(localId = nextMsgId.getAndIncrement(), peer = addressBase58(to.address), text = t, incoming = false,
            bundleId = null, contentType = "multipart/mixed", images = images)
        onUi { messages.add(msg) }   // optimistic - instant bubble (see send())
        core.post {
            rememberContact(to)
            val id = runCatching { node.sendMessage(to.address, "multipart/mixed", encodeMultipart(parts), true) }.getOrNull()
            stampSent(msg.localId, id)
            pump()
        }
    }

    /// Re-send a failed ("Not sent") message in place. Recovery for a message that gave up
    /// (queue cleared, or still unsent at a restart). `to` supplies the address (Message stores
    /// only the peer name).
    fun retry(m: Message, to: Peer) = core.post {
        if (m.incoming) return@post
        val ctBody: Pair<String, ByteArray> = when {
            m.contentType.startsWith("image/") -> {
                val d = m.imageData ?: return@post
                "image/jpeg" to d
            }
            m.contentType == "multipart/mixed" -> {
                val parts = ArrayList<Pair<String, ByteArray>>()
                if (m.text.isNotEmpty()) parts.add("text/plain" to m.text.toByteArray())
                val imgs = if (m.imageData != null) listOf(m.imageData) else m.images
                for (img in imgs) parts.add("image/jpeg" to img)
                if (parts.isEmpty()) return@post
                "multipart/mixed" to encodeMultipart(parts)
            }
            else -> "text/plain" to m.text.toByteArray()
        }
        val id = runCatching { node.sendMessage(to.address, ctBody.first, ctBody.second, true) }.getOrNull()
        onUi {
            val i = messages.indexOfFirst { it.localId == m.localId }
            if (i >= 0) messages[i] = m.copy(failed = false, delivered = false, bundleId = id,
                sentAt = System.currentTimeMillis())
        }
        pump()
    }

    private fun pump() {
        for (pkt in node.drainOutgoing()) {
            // The shared BearerManager (BLE + LAN + Wi-Fi Direct + Relay) owns every link now, so
            // route each outgoing packet to it by link id.
            linkFlow.onTx(pkt.link.toLong(), pkt.bytes.size)   // quality-net-07: per-link tx counters
            bearerMgr.send(pkt.bytes, pkt.link.toLong())
        }
        scheduleRefresh()
        for (m in node.takeInbox()) {
            val who = nameByAddr[m.from.toList()] ?: shortHex(m.from)
            val isImage = m.contentType.startsWith("image/")
            val isMultipart = m.contentType == "multipart/mixed"
            var text = if (isImage) "" else String(m.body)
            var images: List<ByteArray> = emptyList()
            if (isMultipart) {
                val parts = decodeMultipart(m.body)
                text = parts.firstOrNull { it.first.startsWith("text/") }?.let { String(it.second) } ?: ""
                images = parts.filter { it.first.startsWith("image/") }.map { it.second }
            }
            val now = nowMs()
            val latency = if (now >= m.createdAt) now - m.createdAt else 0uL
            val msg = Message(localId = nextMsgId.getAndIncrement(), peer = addressBase58(m.from), text = text,
                incoming = true, contentType = m.contentType,
                imageData = if (isImage) m.body else null, images = images,
                hops = m.hops, latencyMs = latency, trace = m.trace.map { traceLabel(it) })
            // quality-net-06: log inbound receipt to logcat the instant it lands, keyed by text (the
            // harness marker). tk_verify reads this instead of the lagging files/messages.json export.
            // android-r2-05: this prints the sender's full address + cleartext body, so it is DEBUG only
            // (a release build must not leak addresses/message text to logcat).
            if (!isImage && DriverFlags.verboseContentLogs) android.util.Log.i("HOPLOG",
                "HOPAUTO received from=${addressBase58(m.from)} hops=${m.hops} text=$text")
            queueIdentify(m.from)   // learn the sender's display name (§29)
            // Someone who messages us joins the address book, so their conversation is reachable.
            val k = m.from.toList()
            if (!contacts.containsKey(k)) {
                contacts[k] = Peer(m.from, who, 0u, active = false)
                saveContacts(force = true)
            }
            val notifyText = if (isImage) "Photo" else text  // system notifications can't render FA glyphs
            onUi {   // UI-visible result → main thread
                messages.add(msg)
                if (!appInForeground) { unread.intValue += 1; notifier.notify(who, notifyText, unread.intValue) }
            }
        }
        saveMessages()
        hps.drainHps()   // pub/sub messages (§32)
        hns.drainHns()   // HNS lookups + hops:// responses (§30)
        drainServices()  // hop.identify replies + custom service calls (§29)
        // (relay-queue diagnostics now refresh on the coalesced scheduleRefresh path, not per-frame)
    }

    // ---- services & diagnostics (DESIGN.md §29) ----------------------------

    /// hop.identify an address once per session so we learn its display name (its input, or a
    /// relay's domain). Resolves names in traces and the chat list.
    private fun queueIdentify(address: ByteArray) {
        val key = address.toList()
        if (!identifyAsked.add(key)) return
        runCatching { node.sendServiceRequest(address, serviceIdentify(), "", ByteArray(0)) }
            .getOrNull()?.let { identifyReqs.add(it.toList()) }
    }

    /// Resolve a trace hop to a label: us, a known name, else app-label + short id (§27).
    private fun traceLabel(h: TraceHopInfo): String {
        if (h.node.all { it == 0.toByte() }) return h.appLabel   // anonymized device hop (§27)
        val name = nameByAddr[h.node.toList()]
        return name ?: "${h.appLabel} ${shortHex(h.node)}"
    }

    private fun drainServices() {
        val logLines = ArrayList<String>()
        for (resp in node.takeServiceResponses()) {
            val info = if (identifyReqs.remove(resp.forRequestId.toList()) && resp.status == 0u.toUShort())
                runCatching { decodeIdentity(resp.body) }.getOrNull() else null
            if (info != null) {
                val label = info.name.ifEmpty { shortHex(info.address) }
                nameByAddr[info.address.toList()] = label
                logLines.add("identify ← $label (${info.kind})")
                scheduleRefresh()
            } else {
                // Service-response bodies are usually binary (postcard) - a lenient String(bytes)
                // mojibakes them. Match iOS: strict UTF-8 decode, else show a byte count.
                val text = runCatching {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(resp.body)).toString()
                }.getOrNull() ?: "<${resp.body.size} bytes>"
                logLines.add("service ← ${resp.status}: ${text.take(120)}")
            }
        }
        for (req in node.takeServiceRequests()) {
            // No custom services in the demo - reply 501 so the caller isn't left hanging.
            logLines.add("service → ${req.service}/${req.method} (501)")
            runCatching { node.sendServiceResponse(req.from, req.requestId, 501u, ByteArray(0)) }
        }
        if (logLines.isEmpty()) return
        onUi {
            for (line in logLines) serviceLog.add(0, line)
            while (serviceLog.size > 100) serviceLog.removeAt(serviceLog.size - 1)
        }
    }

    private fun refreshQueue() {
        val rows = runCatching { node.queue() }.getOrDefault(emptyList()).map { q ->
            QueueRow(q.own, if (q.to.isEmpty()) "broadcast" else shortHex(q.to), q.priority, q.hops)
        }
        onUi { queue.clear(); queue.addAll(rows) }
    }

    fun clearQueue() = core.post {
        runCatching { node.clearQueue() }
        // Anything of ours still in flight is now abandoned - mark those "not sent" instead of
        // leaving them stuck on "Sending…".
        onUi {
            for (i in messages.indices) {
                val m = messages[i]
                if (!m.incoming && !m.delivered && !m.failed) messages[i] = m.copy(failed = true)
            }
        }
        saveMessages()   // core; debounced write picks up the onUi mutation
        refreshQueue()
    }

    // ---- hps:// pub/sub (DESIGN.md §32) -------------------------------------
    // The concern lives in [HpsController]; HopBearer keeps the public entry points the app calls and
    // delegates to it (drainHps/loadChannels/loadTopics are wired at the pump/start sites above).

    fun hpsRegister(path: String, channel: Boolean,
                    access: uniffi.hop.HpsAccess = uniffi.hop.HpsAccess.OPEN,
                    discoverable: Boolean = false) = hps.hpsRegister(path, channel, access, discoverable)
    fun hpsSubscribe(hostB58: String, path: String) = hps.hpsSubscribe(hostB58, path)
    fun hpsJoin(t: uniffi.hop.HpsTopicInfo) = hps.hpsJoin(t)
    fun hpsPublish(topic: HpsTopic, text: String) = hps.hpsPublish(topic, text)
    fun hpsInvite(topic: HpsTopic, to: ByteArray) = hps.hpsInvite(topic, to)
    fun hpsAcceptInvite(inv: uniffi.hop.HpsInvite) = hps.hpsAcceptInvite(inv)
    fun hpsDeclineInvite(inv: uniffi.hop.HpsInvite) = hps.hpsDeclineInvite(inv)
    fun hpsPending(topic: HpsTopic): List<ByteArray> = hps.hpsPending(topic)
    fun hpsApprove(topic: HpsTopic, who: ByteArray) = hps.hpsApprove(topic, who)
    fun hpsDeny(topic: HpsTopic, who: ByteArray) = hps.hpsDeny(topic, who)
    fun hpsReach(topic: HpsTopic): Int = hps.hpsReach(topic)
    fun hpsMembers(topic: HpsTopic): List<ByteArray> = hps.hpsMembers(topic)
    fun hpsRekey(topic: HpsTopic, remove: List<ByteArray> = emptyList()) = hps.hpsRekey(topic, remove)
    fun hpsBrowse(): List<uniffi.hop.HpsTopicInfo> = hps.hpsBrowse()
    fun hpsLeave(topic: HpsTopic) = hps.hpsLeave(topic)
    fun openTopic(id: String) = hps.openTopic(id)
    fun closeTopic() = hps.closeTopic()

    /// Resolved display name for an address (its set name, or a short base58 prefix).
    fun displayName(addr: ByteArray): String = nameByAddr[addr.toList()] ?: shortHex(addr)
    /// Known peers as an invite-picker list (sorted by name).
    val contactList: List<Peer> get() = peers.sortedBy { it.name.lowercase() }

    // ---- HNS & hops:// (DESIGN.md §30) -------------------------------------
    // The concern lives in [HnsController]; HopBearer keeps the public entry points the app calls and
    // delegates to it (drainHns/expireHopsWeb/shutdown are wired at the pump/tick/teardown sites above).

    /// Open `hops://<domain>/<path>` (bare `<domain>` ok): resolve via HNS, then GET over the mesh.
    fun openHops(input: String) = hns.openHops(input)

    /// Fetch one hops:// resource for the WebView, calling back with (status, contentType, body).
    fun hopsFetch(url: String, cb: (Int, String, ByteArray) -> Unit) = hns.hopsFetch(url, cb)

    // ---- cloud relay bearer (→ hop-relayd) ----------------------------------

    /// Connect to a `hop-relayd`. Accepts a `host:port` (raw TCP, path A) or a
    /// `ws://`/`wss://` URL (WebSocket, path B). The device dials → Noise initiator.
    /// Pin this device to a single relay by direct address (persisted), or pass null to clear the pin
    /// and fall back to the anycast default. The shared RelayBearer reads this pin at start(), so the
    /// pin takes effect on the next launch; we persist it and reflect it in the UI now.
    fun setPinnedRelay(url: String?): Boolean {
        val raw = url?.trim()
        // Clearing the pin (null/blank) is always allowed → fall back to the anycast default.
        if (raw.isNullOrEmpty()) {
            pinnedRelay.value = null
            prefs.edit().remove("pinnedRelay").apply()
            return true
        }
        // Validate BEFORE persisting: junk (or an http:// / random text) would be handed straight to the
        // RelayBearer's OkHttp WebSocket dial at the next launch and fail obscurely. Reject it here so a
        // bad pin never latches into prefs (the device only ever talks to ONE relay - a bad pin is a
        // silent outage). Accepts wss:// / https:// / ws:// URLs and bare host:port (raw-TCP path A).
        val pinned = validateRelayUrl(raw)
        if (pinned == null) {
            android.util.Log.w("HOPLOG", "setPinnedRelay rejected malformed url")
            return false
        }
        pinnedRelay.value = pinned
        prefs.edit().putString("pinnedRelay", pinned).apply()
        return true
    }

    // MARK: chat-history persistence (survives app restart) ----------------------

    private val messagesFile get() = java.io.File(context.filesDir, "messages.json")
    private val contactsFile get() = java.io.File(context.filesDir, "contacts.json")
    private var lastContactSaveMs = 0L

    /// Add a contact by base58 address (manual entry or scanned QR). Returns false if invalid.
    /// An empty name resolves via hop.identify; a provided name is kept as the local alias.
    fun addContact(name: String, base58: String): Boolean {
        val addr = runCatching { addressFromBase58(base58.trim()) }.getOrNull() ?: return false
        if (addr.size != 32 || addr.contentEquals(node.address())) return false
        val alias = name.trim()
        core.post {
            rememberContact(Peer(addr, alias.ifEmpty { shortHex(addr) }, 0u, active = false))
            if (alias.isEmpty()) runCatching { queueIdentify(addr) }
            pump()
        }
        return true
    }

    /// Add a peer to the address book and persist now (e.g. on send) so the conversation is reachable.
    fun rememberContact(p: Peer) {
        contacts[p.address.toList()] = p
        nameByAddr[p.address.toList()] = p.name
        saveContacts(force = true)
    }
    private fun saveContacts(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastContactSaveMs < 4000) return   // throttle (refresh runs often)
        lastContactSaveMs = now
        // quality-net-03: serialize through the pure ContactBook codec (unit-tested round-trip). The
        // base58 conversion is core-owned (uniffi), so it happens here; the JSON shape is ContactBook's.
        val records = contacts.values.map {
            ContactRecord(addressBase58(it.address), it.name, it.platform, it.app)
        }
        val json = ContactBook.encode(records)
        // android-r2-01: seal the address book with the db key so contacts.json (addresses + names) is
        // not plaintext beside the encrypted hop.db. Off the main thread (refresh runs often).
        thread(name = "save-contacts") { runCatching { contactsFile.writeBytes(mirror.seal(json.toByteArray())) } }
    }
    private fun loadContacts() {
        val txt = mirror.read(contactsFile) ?: return
        // quality-net-03: parse through the pure ContactBook codec (unit-tested); the driver then does
        // the core-owned base58 -> bytes conversion + 32-byte validation.
        for (rec in ContactBook.decode(txt)) {
            val addr = runCatching { addressFromBase58(rec.addr58) }.getOrNull() ?: continue
            if (addr.size != 32) continue
            val key = addr.toList()
            if (contacts.containsKey(key)) continue
            val name = rec.name.ifEmpty { shortHex(addr) }
            contacts[key] = Peer(addr, name, 0u, active = false, platform = rec.platform, app = rec.app)
            nameByAddr[key] = name
        }
    }
    private var saveScheduled = false

    /// Coalesce rapid mutations into one disk write (~1/sec) so a burst of messages - or the
    /// per-tick pump() - doesn't re-encode the whole history each time.
    private fun saveMessages() {
        if (saveScheduled) return
        saveScheduled = true
        core.postDelayed({ saveScheduled = false; writeMessages() }, 1000)
    }

    /// Snapshot the message list on the caller (core thread, the only writer of `messages`), externalize
    /// image blobs, then serialize + write the JSON on a background thread. Image bytes are stored as
    /// separate content-addressed files (android-12) and referenced by name, so the mirror stays small
    /// and the debounced rewrite never re-base64s photo bytes on the core/main path.
    private fun writeMessages() {
        val snapshot = messages.toList()
        // Externalize images to disk here (dedupes via writtenMedia); the JSON build below just refs.
        val imgRefs = HashMap<Long, Pair<String?, List<String>>>()
        for (m in snapshot) {
            val single = m.imageData?.let { mirror.putMedia(it) }
            val multi = if (m.images.isNotEmpty()) m.images.map { mirror.putMedia(it) } else emptyList()
            if (single != null || multi.isNotEmpty()) imgRefs[m.localId] = single to multi
        }
        thread(name = "save-messages") {
            // MessageCodec owns the messages.json schema (the pure List<Message> <-> JSON transform);
            // the CPU-bound encode stays on this background thread, exactly as the inline loop did.
            val json = MessageCodec.encode(snapshot, imgRefs)
            // android-r2-01: seal the chat mirror with the db key so messages.json (every body) is not
            // plaintext beside the encrypted hop.db (empty key ⇒ plain, matching an unencrypted db).
            runCatching { messagesFile.writeBytes(mirror.seal(json.toByteArray())) }
        }
    }

    private fun loadMessages() {
        val txt = mirror.read(messagesFile) ?: return
        // MessageCodec parses the schema; the driver supplies fresh local ids (in file order) and the
        // media resolver, then applies the restored rows on main (messages is UI/main-owned).
        val loaded = MessageCodec.decode(txt, { nextMsgId.getAndIncrement() }, mirror::getMedia)
        onUi { messages.addAll(loaded) }
    }

    @Volatile private var refreshScheduled = false

    /// Coalesced UI refresh. `refresh()` does synchronous SQLite work (browse, queue, per-message
    /// status) and is far too costly to run on every `pump()` - which fires on every received packet
    /// across BLE/Wi-Fi/LAN/relay. Per-packet refresh saturates the main thread (sluggish UI, ANRs).
    /// Coalesce to ~4 Hz; pump still drains outgoing + the inbox immediately, only this is throttled.
    private fun scheduleRefresh() {
        if (refreshScheduled) return
        refreshScheduled = true
        core.postDelayed({ refreshScheduled = false; refresh() }, 250)
    }

    /// Runs on core: all node reads happen here; the resulting UI state is applied in ONE onUi block.
    private fun refresh() {
        val mine = node.address().toList()
        // Collapse the many retained presence adverts per publisher: nearest hops for
        // distance, newest advert (max createdAt) for current name/state/platform/app.
        data class Agg(var minHops: UByte, var newestAt: ULong, var peer: Peer)
        val agg = HashMap<List<Byte>, Agg>()
        for (p in node.browse(PRESENCE_SERVICE, "")) {
            val key = p.publisher.toList()
            if (key == mine) continue
            val name = p.title.ifEmpty { shortHex(p.publisher) }
            val parts = p.summary.split("|")
            val active = parts.getOrNull(0) != "bg"
            val platform = parts.getOrNull(1) ?: ""
            val app = parts.getOrNull(2) ?: ""
            val ex = agg[key]
            val hops = if (ex != null && ex.minHops < p.hops) ex.minHops else p.hops
            if (ex == null || p.createdAt >= ex.newestAt) {
                agg[key] = Agg(hops, p.createdAt, Peer(p.publisher, name, hops, active, platform, app))
            } else {
                ex.minHops = hops
            }
            nameByAddr[key] = name
        }
        // Map each directly-linked peer to its transport. Every link is minted by the BearerManager
        // now, so ask the manager for the owning bearer's REAL transport (BT / LAN / Wi-Fi Direct /
        // Relay). (refresh() runs on core, so reading bearerLinks here is safe.)
        val ltLocal = LinkedHashMap<List<Byte>, String>()
        val pls = runCatching { node.peerLinks() }.getOrDefault(emptyList())
        pls.forEach { pl ->
            ltLocal[pl.address.toList()] = bearerMgr.transportNameOf(pl.link.toLong()) ?: "BT"
        }
        if (pls.isNotEmpty() && pls.size != lastPeerLinkCount) {
            lastPeerLinkCount = pls.size
            android.util.Log.i("HOPLOG", "peerLinks=${pls.size}: " +
                pls.joinToString { "${HopBearer.shortHex(it.address)}@${it.link}" })
        }

        // A live local radio link (BLE) IS a 1-hop path, so force hops=1 for those peers even if a
        // stale advert arrived via the relay at 2 hops. Keeps "direct" honest: direct iff hops<=1,
        // so a live-linked peer shows "1 hop · BT" (never "2 hops") and a no-link 2-hop peer is mesh.
        // Sort by the stable address so rows keep their position.
        val list = agg.values.map {
            val key = it.peer.address.toList()
            val t = ltLocal[key]
            // Every shared/local radio link is a 1-hop direct path; "Relay" is the only non-direct tag.
            val hops = if (t == "BT" || t == "LAN" || t == "P2P" || t == "Wi-Fi Direct") 1u.toUByte() else it.minHops
            it.peer.copy(hops = hops)
        }.sortedBy { addressBase58(it.address) }

        // Address book: fold every live peer into the (persisted) contact book; the contacts NOT
        // currently reachable form the offline "seen" list, so past conversations stay reachable
        // even when the peer is offline / out of range / after a restart.
        for (p in list) contacts[p.address.toList()] = p
        val here = list.map { it.address.toList() }.toHashSet()
        val off = contacts.filterKeys { it !in here }.values
            .map { it.copy(hops = 0u, active = false) }
            .sortedBy { it.name.lowercase() }
        saveContacts()

        // Which peers we're talking to over a forward-secret session (lock icon).
        val securedLocal = list.filter { node.isSecured(it.address) }.map { it.address.toList() }

        // Delivery status for our outgoing messages: read the node here (core), apply by localId on
        // main. Each entry is the already-transformed copy (derived from the core-side snapshot).
        val now = System.currentTimeMillis()
        val msgUpdates = ArrayList<Pair<Long, Message>>()
        for (m in messages.toList()) {
            if (m.incoming || m.bundleId == null) continue
            val s = node.messageStatus(m.bundleId)
            if (s.delivered && m.deliveredAt == null) {
                // quality-net-06: emit the end-to-end delivery ACK to logcat the INSTANT the node reports
                // it, keyed by the message text (the harness marker). The test harness reads this
                // logcat line instead of files/messages.json, whose debounced export lags in-memory
                // delivery by >90s, causing false "delivered=false" records past the poll window.
                if (DriverFlags.verboseContentLogs) android.util.Log.i("HOPLOG",   // android-r2-05: debug only (leaks peer + text)
                    "HOPAUTO delivered to=${m.peer} deliveryMs=${s.deliveryMs} hops=${s.deliveryHops} text=${m.text}")
                msgUpdates.add(m.localId to m.copy(relayed = s.relayed, delivered = true,
                    deliveryHops = s.deliveryHops, deliveryMs = s.deliveryMs.toULong(), deliveredAt = now))
            } else if (s.relayed != m.relayed) {
                msgUpdates.add(m.localId to m.copy(relayed = s.relayed))
            }
        }

        onUi {
            linkTransports.clear(); linkTransports.putAll(ltLocal)
            peers.clear(); peers.addAll(list)
            seen.clear(); seen.addAll(off)
            secured.clear(); secured.addAll(securedLocal)
            for ((localId, updated) in msgUpdates) {
                val i = messages.indexOfFirst { it.localId == localId }
                if (i >= 0) messages[i] = updated
            }
        }
        refreshQueue()   // relay-queue diagnostics (was per-frame in pump; now on the coalesced path)
    }

    companion object {
        const val CHANNEL_ID = "hop.messages"
        const val PRESENCE_SERVICE = "presence"
        const val PRESENCE_TTL_MS: UInt = 600_000u
        const val DEFAULT_RELAY = "wss://relay.hopme.sh/"
        /// Cap on simultaneously in-flight DoH (DNSSEC-chain) GETs so a burst of hops:// resolves can't
        /// spawn unbounded concurrent HTTPS requests; excess calls queue on the dispatcher instead.
        const val DOH_MAX_CONCURRENT = 6
        /// Shared app secret for Hop Debug - all our demo devices use it so they interoperate.
        /// A different app (different secret) can't see or join these channels (DESIGN.md §32).
        val APP_SECRET = ByteArray(32) { 0x48 } // "H" ×32 - dev build only (matches iOS)

        @Volatile private var inst: HopBearer? = null

        /// One shared instance, owned by the foreground service and observed by the UI.
        /// Configure-once: the first caller's HopConfig wins; later callers get that same instance.
        /// The host (MainActivity) configures explicitly; a bare service restart falls back to
        /// HopConfig.default(context), preserving the prior behavior.
        fun shared(context: Context, config: HopConfig): HopBearer =
            inst ?: synchronized(this) {
                inst ?: HopBearer(context.applicationContext, config).also { inst = it }
            }

        fun shared(context: Context): HopBearer =
            inst ?: shared(context, HopConfig.default(context.applicationContext))

        fun nowMs(): ULong = System.currentTimeMillis().toULong()

        /// Encode `(contentType, bytes)` parts into the multipart wire format (shared with iOS).
        fun encodeMultipart(parts: List<Pair<String, ByteArray>>): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            fun u32(v: Int) { out.write(v ushr 24); out.write(v ushr 16); out.write(v ushr 8); out.write(v) }
            fun u16(v: Int) { out.write(v ushr 8); out.write(v) }
            u32(parts.size)
            for ((ct, body) in parts) {
                val ctd = ct.toByteArray()
                u16(ctd.size); out.write(ctd)
                u32(body.size); out.write(body)
            }
            return out.toByteArray()
        }

        /// Decode the multipart wire format into `(contentType, bytes)` parts.
        fun decodeMultipart(data: ByteArray): List<Pair<String, ByteArray>> {
            val parts = mutableListOf<Pair<String, ByteArray>>()
            var i = 0
            fun u(n: Int): Int? {
                if (i + n > data.size) return null
                var v = 0; repeat(n) { v = (v shl 8) or (data[i].toInt() and 0xff); i++ }; return v
            }
            val count = u(4) ?: return parts
            repeat(count) {
                val cl = u(2) ?: return parts
                if (i + cl > data.size) return parts
                val ct = String(data, i, cl); i += cl
                val bl = u(4) ?: return parts
                if (i + bl > data.size) return parts
                parts.add(ct to data.copyOfRange(i, i + bl)); i += bl
            }
            return parts
        }

        /// Compact base58 prefix for display (full base58 via `addressBase58`).
        fun shortHex(d: ByteArray): String = addressBase58(d).take(8)

        /// 32-byte Ed25519 identity seed, hardware-backed (android-02 / sec-priv-03). A random secret is
        /// generated ONCE and wrapped by a non-exportable AndroidKeyStore key (StrongBox when available),
        /// so it has full 256-bit entropy and cannot be recomputed by anything that merely learns
        /// ANDROID_ID (on-device code, a backup, forensics). Existing installs are preserved: the old
        /// `SHA-256("hop.identity.v1|ANDROID_ID")` value is adopted as the stored secret on first run,
        /// so the address does not change. See [KeystoreSecret].
        fun deviceSeed(context: Context): ByteArray =
            KeystoreSecret.getOrCreate(context, "identity.v1", legacy = legacyDeviceSeed(context))

        /// 32-byte SQLCipher key for `hop.db` at rest (F-25), hardware-backed the same way as the
        /// identity seed and domain-separated from it (its own Keystore key + pref entry). The key is
        /// NOT in the db file and is unreadable off-device, so a pulled db is useless without this
        /// device's secure element. Existing installs migrate from the old ANDROID_ID-derived key so the
        /// already-encrypted db still opens. Encrypts only when libhop is built `--features sqlcipher`.
        fun dbKey(context: Context): ByteArray =
            KeystoreSecret.getOrCreate(context, "db.key.v1", legacy = legacyDbKey(context))

        /// Legacy (pre-Keystore) identity seed: `SHA-256("hop.identity.v1|ANDROID_ID")`. Kept only as the
        /// one-time migration seed so existing installs keep their address; never the primary source.
        private fun legacyDeviceSeed(context: Context): ByteArray {
            val androidId = android.provider.Settings.Secure.getString(
                context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
            ) ?: "hop-fallback"
            return java.security.MessageDigest.getInstance("SHA-256")
                .digest("hop.identity.v1|$androidId".toByteArray())
        }

        /// Legacy (pre-Keystore) db key: `SHA-256("hop.db.key.v1|ANDROID_ID")`. Migration seed only.
        private fun legacyDbKey(context: Context): ByteArray {
            val androidId = android.provider.Settings.Secure.getString(
                context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
            ) ?: "hop-fallback"
            return java.security.MessageDigest.getInstance("SHA-256")
                .digest("hop.db.key.v1|$androidId".toByteArray())
        }

        /// Compact elapsed-time label: 3s / 5m / 2h / 4d.
        fun compactDuration(ms: ULong): String {
            val s = ms / 1000u
            if (s < 60u) return "${s}s"
            val m = s / 60u
            if (m < 60u) return "${m}m"
            val h = m / 60u
            if (h < 24u) return "${h}h"
            return "${h / 24u}d"
        }

        /// A single link is "direct" (0 relays); ≥2 shows the count.
        fun hopsLabel(h: UByte): String = if (h.toInt() <= 1) "direct" else "$h hops"

        /// Validate + normalize a user-entered relay URL before it is persisted as the pinned relay.
        /// The shared RelayBearer dials this over an OkHttp WebSocket (`Request.url(...)`), which accepts
        /// only ws/wss/https/http schemes and requires a non-empty host. Returns the trimmed URL if it is
        /// a well-formed wss:// / https:// (or ws:// / http://) URL with a host, else null - so junk, a
        /// bare word, or a random string never latches into prefs as a silent relay outage. Pure + host-
        /// free so it is unit-testable on a plain JVM.
        fun validateRelayUrl(input: String): String? {
            val s = input.trim()
            if (s.isEmpty()) return null
            val lower = s.lowercase()
            val scheme = when {
                lower.startsWith("wss://") -> "wss://"
                lower.startsWith("https://") -> "https://"
                lower.startsWith("ws://") -> "ws://"
                lower.startsWith("http://") -> "http://"
                else -> return null   // must be an explicit ws/wss/https/http URL, not a bare host or junk
            }
            // Host is everything after the scheme up to the first '/', '?' or '#'. Reject an empty host
            // ("wss://", "wss:///path"), a host that is only a port (":9443"), and whitespace anywhere.
            if (s.any { it.isWhitespace() }) return null
            val afterScheme = s.substring(scheme.length)
            val hostPort = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
            val host = hostPort.substringBefore(':')
            if (host.isEmpty()) return null
            // A port, if present, must be a valid 1..65535 number.
            val portPart = hostPort.substringAfter(':', "")
            if (portPart.isNotEmpty()) {
                val port = portPart.toIntOrNull() ?: return null
                if (port !in 1..65535) return null
            }
            return s
        }
    }
}

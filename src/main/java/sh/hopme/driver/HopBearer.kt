package sh.hopme.driver

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.File
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import uniffi.hop.HopNode
import uniffi.hop.HnsLookupResult
import uniffi.hop.HpsKind
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
class HopBearer private constructor(private val context: Context, private val config: HopConfig) {

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

    // Identity derived from the device (stable, storage-independent — §4); the db path
    // persists messages across restarts. Both come from the host-supplied HopConfig.
    // F-25: open SQLCipher-encrypted with the device-derived db key (empty ⇒ plain). Only encrypts
    // when libhop is built `--features sqlcipher` (android/build-aar.sh, on by default).
    val node: HopNode = HopNode.openKeyed(
        config.dbPath,
        config.identitySecret,
        config.appSecret,
        config.dbKey,
    )
    val peers = mutableStateListOf<Peer>()
    /// Persistent address book: everyone we've seen, messaged, or been messaged by, keyed by address.
    /// `seen` is the subset NOT currently reachable — so past conversations stay reachable even when
    /// the peer is offline / out of range / after a restart (mirrors iOS).
    private val contacts = HashMap<List<Byte>, Peer>()
    val seen = mutableStateListOf<Peer>()
    /// Directly-linked peer address → the transport carrying it ("BT" / "Relay"); mesh peers
    /// (reached multi-hop) have no entry. Mirrors iOS's link-type indicators.
    val linkTransports = mutableStateMapOf<List<Byte>, String>()
    val messages = mutableStateListOf<Message>()
    /// Unread incoming messages received while backgrounded — mirrored onto the app icon
    /// badge (via the notification) and cleared when the app returns to the foreground.
    val unread = androidx.compose.runtime.mutableIntStateOf(0)
    val secured = mutableStateListOf<List<Byte>>()   // addresses with a forward-secret session

    // hops:// (DESIGN.md §30): domain → rendered result; pending resolves + outstanding requests.
    val hopsResults = mutableStateMapOf<String, String>()
    private val pendingHops = HashMap<String, String>()              // domain → path awaiting resolve
    private val hopsReqs = HashMap<List<Byte>, String>()             // request id → domain
    private val dohClient = OkHttpClient()
    // HNS cache debug view: domain → (address, ttl).
    val hnsCache = mutableStateListOf<HnsCacheRow>()
    data class HnsCacheRow(val domain: String, val address: ByteArray, val ttlSecs: UInt)

    // hps:// pub/sub (DESIGN.md §32): topics we host/subscribe, per-topic threads, unread, invites.
    val hpsTopics = mutableStateListOf<HpsTopic>()
    val hpsThreads = mutableStateMapOf<String, SnapshotStateList<HpsMsg>>() // topic id → messages
    val hpsUnread = mutableStateMapOf<String, Int>()                        // topic id → unread
    val hpsInvites = mutableStateListOf<uniffi.hop.HpsInvite>()         // invites received
    @Volatile var activeTopic: String? = null                              // topic on screen

    // Diagnostics (Status tab) — parity with iOS: service-call log + relay queue.
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
    /// relay — routing is anycast — so pinning overrides the anycast default for testing a
    /// specific relay, rather than publishing presence to several at once.
    val pinnedRelay = mutableStateOf<String?>(null)
    private val nextMsgId = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var appActive = true
    private val appName: String =
        context.applicationInfo.loadLabel(context.packageManager).toString()

    // Threading model (DESIGN: Stage C). Every node.* call and all bearer plumbing run on ONE serial
    // background thread (hop.core), so the main thread never does node/SQLite/crypto work — under
    // multi-peer BLE load that path ANR'd ("Skipped N frames"). The node is internally Mutex-guarded
    // (thread-safe), so the rare synchronous UI getters (node.address(), hpsReach/Members/…) can still
    // read it directly from main. Compose snapshot state is UI state: it is WRITTEN ONLY on the main
    // thread via onUi { } — core does the node work, then marshals the visible result to main. The
    // heavy SQLite-backed refresh stays coalesced (~4 Hz, scheduleRefresh); never refresh per-frame.
    private val coreThread = HandlerThread("hop.core").apply { start() }
    private val core = Handler(coreThread.looper)
    private val main = Handler(Looper.getMainLooper())
    /// Marshal a Compose-state update to the main thread. UI (snapshot) state mutates ONLY here.
    private fun onUi(block: () -> Unit) { main.post(block) }
    private var lastPeerLinkCount = -1
    // Read from the UI thread (displayName) and written on core (refresh/pump), so concurrent-safe.
    private val nameByAddr = java.util.concurrent.ConcurrentHashMap<List<Byte>, String>()

    // ---- shared cross-platform transport layer (ble-lab bearer-core/-ble/-lan/-relay) ----
    // The driver forms pure-L2CAP BLE + LAN + cloud-relay links, all multiplexed by ONE BearerManager
    // and surfaced through bearerSink into the node seam. The manager's global link-id space starts
    // HIGH (1_000_000). (The legacy in-driver BLE/LAN/relay transports and Wi-Fi Direct were removed.)
    private val bearerMgr = sh.hop.BearerManager(baseLinkId = 1_000_000L)
    // One transport id shared by the bearers (the BLE/LAN HELLO id + greater-id dedup tiebreaker);
    // distinct from the Hop node address — Noise is still negotiated over the bearer's DATA frames.
    private val bearerId = sh.hop.randomNodeId()
    // Link ids currently owned by the BearerManager, so refresh() can tag them by transport. CORE-CONFINED:
    // added/removed and read ONLY inside core.post (pump() also runs on core), so it needs no lock.
    private val bearerLinks = HashSet<Long>()
    // Adapts the shared BearerManager to the node seam. Every link from a bearer surfaces here and
    // drives node.connected/received/disconnected (linkId mismatch: the bearer libs use Long, the node
    // uses ULong — convert at this boundary).
    // android-09: the effective relay switch — the caller's config AND'd with a runtime killswitch pref,
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
            // android-03: LINKFLOW at the manager seam — the GLOBAL link id + transport + peer, so a soak
            // can correlate a bearer-local "LINKFLOW LINK UP link=<local>" line with the node-facing
            // connected()/disconnected() churn. This is where the previously-missing bearer↔global↔node
            // link correlation gets stitched.
            val t = bearerMgr.transportNameOf(link) ?: "?"
            android.util.Log.i("HOPLOG", "LINKFLOW SEAM UP g=$link xport=$t role=$role peer=${peerId.take(6).joinToString("") { "%02x".format(it) }}")
            // android-11: infer relay status from the manager's link events — the RelayBearer surfaces its
            // one link with transportName "Relay", so a live relay link IS the honest "connected" signal.
            if (t == "Relay") {
                relayLinks.add(link)
                if (relaysOnVal) onUi { relayStatus.value = "connected" }
            }
            core.post {
                bearerLinks.add(link)
                node.connected(link.toULong(), role == sh.hop.HopRole.DIALER)
                pump()   // ship the dialer's queued Noise m1 immediately (mirrors legacy addLink / Apple linkUp)
            }
        }
        override fun linkBytes(link: Long, bytes: ByteArray) {
            core.post { node.received(link.toULong(), bytes); pump() }
        }
        override fun linkDown(link: Long) {
            // android-11: the manager already dropped the transport mapping, so recognize the relay link
            // from the set we recorded at linkUp instead of transportNameOf().
            val wasRelay = relayLinks.remove(link)
            android.util.Log.i("HOPLOG", "LINKFLOW SEAM DOWN g=$link${if (wasRelay) " xport=Relay" else ""}")
            if (wasRelay && relaysOnVal) onUi { relayStatus.value = "reconnecting…" }
            core.post {
                bearerLinks.remove(link)
                node.disconnected(link.toULong())
                scheduleRefresh()   // mirrors legacy onClose / Apple linkDown
            }
        }
    }

    @Volatile var appInForeground = false
    private var started = false

    fun start(name: String = config.deviceName) = core.post {
        if (started) return@post
        started = true
        ensureNotificationChannel()
        loadMessages()      // restore chat history from the previous run
        loadContacts()      // restore the address book so past conversations are reachable when offline
        loadHpsChannels()   // restore channel (hps) message threads
        loadHpsTopics()     // restore hosted/subscribed channels (the node persists them)
        myNameVal = name; onUi { myName.value = name }
        val addr58 = addressBase58(node.address())
        myAddressVal = addr58; onUi { myAddress.value = addr58 }
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
        // Cloud relay (WebSocket) as a shared bearer — ONE outbound link to the backbone, registered
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
                node.tick(nowMs())
                if (++ticks % 20 == 0) publishPresence()
                // Re-publish our prekey periodically so a neighbour whose cached copy lapsed
                // (or who arrived later) can always open a forward-secret session to us (§25).
                if (ticks % 120 == 0) runCatching { node.publishPrekey() }
                if (ticks % 15 == 0) android.util.Log.i("HOPLOG", "HOPAUTO self=$myAddressVal name=${config.deviceName}")  // periodic, so the harness always finds it
                expireHopsWeb()   // android-13: fail out WebView fetches whose resolve/response never arrived
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
                core.postDelayed(this, 1000)
            }
        }, 1000)
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
            runCatching { NotificationManagerCompat.from(context).cancelAll() }
        }
        core.post { publishPresence(); pump() }
    }

    /** Stable conversation key for a peer — its base58 ADDRESS, never its display name. Two distinct
     *  peers that happen to share a name (e.g. two "Pixel 7"s) must not collapse into one thread. */
    fun keyFor(p: Peer): String = addressBase58(p.address)

    /** Test/automation hook: send [text] to a base58 ADDRESS, building a minimal Peer (no UI selection
     *  needed). Backs the hopdemo://send deep link so a harness can drive sends without UI taps. */
    fun sendTo(addrBase58: String, text: String) {
        val addr = runCatching { addressFromBase58(addrBase58.trim()) }.getOrNull() ?: return
        send(text, Peer(addr, contacts[addr.toList()]?.name ?: "", 0u, active = false))
    }

    fun send(text: String, to: Peer) {
        // Optimistic insert: the bubble appears INSTANTLY (next main frame), decoupled from the serial
        // core thread + the blocking node.sendMessage. The node send + the bundleId (for delivery
        // tracking) fill in on core and patch back by localId. (Prior bug: the append ran INSIDE
        // core.post AFTER node.sendMessage, so a busy core stalled the bubble for seconds — felt frozen.)
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
    /// defers with a valid id) — mark the row `failed` so it stops showing "Sending…" forever and the
    /// "Not sent · tap to retry" affordance engages (F-15). Applies to text/image/multipart alike.
    private fun stampSent(localId: Long, id: ByteArray?) = onUi {
        val i = messages.indexOfFirst { it.localId == localId }
        if (i < 0) return@onUi
        messages[i] = if (id != null) messages[i].copy(bundleId = id) else messages[i].copy(failed = true)
    }

    /// Send an image — large bodies are transparently carrier-chunked + reassembled by core
    /// (DESIGN.md §20), same path as a text message but a binary content type.
    fun sendImage(data: ByteArray, to: Peer) {
        val msg = Message(localId = nextMsgId.getAndIncrement(), peer = addressBase58(to.address), text = "", incoming = false,
            bundleId = null, contentType = "image/jpeg", imageData = data)
        onUi { messages.add(msg) }   // optimistic — instant bubble (see send())
        core.post {
            rememberContact(to)
            val id = runCatching { node.sendMessage(to.address, "image/jpeg", data, true) }.getOrNull()
            stampSent(msg.localId, id)
            pump()
        }
    }

    /// Send text and/or one-or-more images as ONE message (multipart/mixed) — a single sealed
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
        onUi { messages.add(msg) }   // optimistic — instant bubble (see send())
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
                if (!appInForeground) { unread.intValue += 1; notify(who, notifyText) }
            }
        }
        saveMessages()
        drainHps()       // pub/sub messages (§32)
        drainHns()       // HNS lookups + hops:// responses (§30)
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
                // Service-response bodies are usually binary (postcard) — a lenient String(bytes)
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
            // No custom services in the demo — reply 501 so the caller isn't left hanging.
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
        // Anything of ours still in flight is now abandoned — mark those "not sent" instead of
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

    fun hpsRegister(path: String, channel: Boolean,
                    access: uniffi.hop.HpsAccess = uniffi.hop.HpsAccess.OPEN,
                    discoverable: Boolean = false) = core.post {
        val p = path.trim(); if (p.isEmpty()) return@post
        runCatching {
            node.registerService(p, if (channel) HpsKind.CHANNEL else HpsKind.SERVICE, access,
                if (discoverable) uniffi.hop.HpsVisibility.DISCOVERABLE else uniffi.hop.HpsVisibility.PRIVATE)
        }
        val topic = HpsTopic(node.address(), p, channel, hosting = true, access = access)
        onUi { if (hpsTopics.none { it.host.contentEquals(topic.host) && it.path == p }) hpsTopics.add(0, topic) }
    }

    fun hpsSubscribe(hostB58: String, path: String) = core.post {
        val host = runCatching { addressFromBase58(hostB58.trim()) }.getOrNull() ?: return@post
        val p = path.trim(); if (host.size != 32 || p.isEmpty()) return@post
        hpsSubscribeTo(host, p, channel = true)
    }

    private fun hpsSubscribeTo(host: ByteArray, path: String, channel: Boolean) {
        runCatching { node.hpsSubscribe(host, path) }
        val topic = HpsTopic(host, path, channel = channel, hosting = false)
        onUi { if (hpsTopics.none { it.host.contentEquals(host) && it.path == path }) hpsTopics.add(0, topic) }
        pump()
    }

    fun hpsJoin(t: uniffi.hop.HpsTopicInfo) = core.post {
        hpsSubscribeTo(t.host, t.path, t.kind == HpsKind.CHANNEL)
    }

    fun hpsPublish(topic: HpsTopic, text: String) = core.post {
        if (text.isEmpty()) return@post
        runCatching { node.hpsPublish(topic.path, text.toByteArray()) }
        appendThread(topic.id, HpsMsg(nextMsgId.getAndIncrement(), topic.path, node.address(), text)) // echo
        pump()
    }

    fun hpsInvite(topic: HpsTopic, to: ByteArray) = core.post {
        if (!topic.hosting || to.size != 32) return@post
        runCatching { node.hpsInvite(topic.path, to) }; pump()
    }

    fun hpsAcceptInvite(inv: uniffi.hop.HpsInvite) = core.post {
        runCatching { node.hpsAcceptInvite(inv.host, inv.path) }
        val topic = HpsTopic(inv.host, inv.path, inv.kind == HpsKind.CHANNEL, hosting = false)
        onUi {
            hpsInvites.removeAll { it.path == inv.path && it.host.contentEquals(inv.host) }
            if (hpsTopics.none { it.host.contentEquals(inv.host) && it.path == inv.path }) hpsTopics.add(0, topic)
        }
        pump()
    }

    fun hpsDeclineInvite(inv: uniffi.hop.HpsInvite) = core.post {
        runCatching { node.hpsDeclineInvite(inv.host, inv.path) } // durable: won't reappear
        onUi { hpsInvites.removeAll { it.path == inv.path && it.host.contentEquals(inv.host) } }
    }

    fun hpsPending(topic: HpsTopic): List<ByteArray> = runCatching { node.hpsPending(topic.path) }.getOrDefault(emptyList())
    fun hpsApprove(topic: HpsTopic, who: ByteArray) = core.post { runCatching { node.hpsApprove(topic.path, who) }; pump() }
    fun hpsDeny(topic: HpsTopic, who: ByteArray) = core.post { runCatching { node.hpsDeny(topic.path, who) } }
    fun hpsReach(topic: HpsTopic): Int = runCatching { node.hpsReach(topic.path).toInt() }.getOrDefault(0)
    fun hpsMembers(topic: HpsTopic): List<ByteArray> = runCatching { node.hpsMembers(topic.path) }.getOrDefault(emptyList())
    fun hpsRekey(topic: HpsTopic, remove: List<ByteArray> = emptyList()) = core.post { runCatching { node.hpsRekey(topic.path, "", remove) }; pump() }
    fun hpsBrowse(): List<uniffi.hop.HpsTopicInfo> = runCatching { node.browseDiscoverable() }.getOrDefault(emptyList())

    /// Rebuild the channel list from the node's persisted topics (hosted + subscribed) at startup.
    private fun loadHpsTopics() {
        val topics = runCatching { node.hpsMyTopics() }.getOrDefault(emptyList()).map { t ->
            HpsTopic(t.host, t.path, t.kind == HpsKind.CHANNEL, t.hosting, t.access)
        }
        onUi { topics.forEach { topic -> if (hpsTopics.none { it.id == topic.id }) hpsTopics.add(topic) } }
    }

    fun hpsLeave(topic: HpsTopic) = core.post {
        runCatching { node.hpsLeave(topic.path) }
        onUi {
            hpsTopics.removeAll { it.id == topic.id }
            hpsThreads.remove(topic.id); hpsUnread.remove(topic.id)
        }
        pump()
    }

    fun openTopic(id: String) { activeTopic = id; hpsUnread[id] = 0 }
    fun closeTopic() { activeTopic = null }

    /// Resolved display name for an address (its set name, or a short base58 prefix).
    fun displayName(addr: ByteArray): String = nameByAddr[addr.toList()] ?: shortHex(addr)
    /// Known peers as an invite-picker list (sorted by name).
    val contactList: List<Peer> get() = peers.sortedBy { it.name.lowercase() }

    private fun appendThread(id: String, m: HpsMsg) {
        onUi {
            val list = hpsThreads.getOrPut(id) { mutableStateListOf() }
            list.add(m)
            if (list.size > 500) list.removeAt(0)
        }
        saveChannels()
    }

    // ---- channel-thread persistence (survives restart) ----------------------
    private val channelsFile get() = java.io.File(context.filesDir, "channels.json")
    private var channelSaveScheduled = false
    private fun saveChannels() {
        if (channelSaveScheduled) return
        channelSaveScheduled = true
        core.postDelayed({ channelSaveScheduled = false; writeChannels() }, 1000)
    }
    private fun writeChannels() {
        val root = org.json.JSONObject()
        for ((id, msgs) in hpsThreads) {
            val arr = org.json.JSONArray()
            for (m in msgs) {
                arr.put(org.json.JSONObject().apply {
                    put("path", m.path)
                    put("sender", android.util.Base64.encodeToString(m.sender, android.util.Base64.NO_WRAP))
                    put("text", m.text)
                })
            }
            root.put(id, arr)
        }
        runCatching { channelsFile.writeText(root.toString()) }
    }
    private fun loadHpsChannels() {
        val txt = runCatching { channelsFile.readText() }.getOrNull() ?: return
        val root = runCatching { org.json.JSONObject(txt) }.getOrNull() ?: return
        val loaded = LinkedHashMap<String, List<HpsMsg>>()
        for (id in root.keys()) {
            val arr = root.optJSONArray(id) ?: continue
            val msgs = ArrayList<HpsMsg>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                msgs.add(HpsMsg(nextMsgId.getAndIncrement(), o.optString("path", ""),
                    android.util.Base64.decode(o.optString("sender", ""), android.util.Base64.NO_WRAP),
                    o.optString("text", "")))
            }
            loaded[id] = msgs
        }
        onUi { for ((id, msgs) in loaded) hpsThreads[id] = mutableStateListOf<HpsMsg>().apply { addAll(msgs) } }
    }

    private fun drainHps() {
        for (m in node.takeHpsMessages()) {
            val topic = hpsTopics.firstOrNull { it.path == m.path }
            val id = topic?.id ?: m.path
            appendThread(id, HpsMsg(nextMsgId.getAndIncrement(), m.path, m.sender, String(m.body)))
            onUi { if (id != activeTopic) hpsUnread[id] = (hpsUnread[id] ?: 0) + 1 }
        }
        for (inv in node.takeHpsInvites()) {
            onUi {
                if (hpsInvites.none { it.path == inv.path && it.host.contentEquals(inv.host) })
                    hpsInvites.add(inv)
            }
        }
    }

    // ---- HNS & hops:// (DESIGN.md §30) -------------------------------------

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
                onUi { hopsResults[domain] = "error: offline — no internet or peers to resolve $domain" }
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

    // hops:// for the WebView (callback-style, per resource — DESIGN.md §30).
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
    private fun expireHopsWeb() {
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

    private fun drainHns() {
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
        // core the raw bodies — core validates to the root anchors and decides the address.
        for (domain in node.takeDnsLookups()) fetchDnssecChain(domain)
        // Refresh the cache debug view.
        val cacheRows = runCatching { node.hnsCache() }.getOrDefault(emptyList())
            .map { HnsCacheRow(it.domain, it.address, it.ttlSecs) }
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
            val req = Request.Builder().url("https://dns.google/resolve?name=$name&type=$qtype&do=1").build()
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

    // ---- cloud relay bearer (→ hop-relayd) ----------------------------------

    /// Connect to a `hop-relayd`. Accepts a `host:port` (raw TCP, path A) or a
    /// `ws://`/`wss://` URL (WebSocket, path B). The device dials → Noise initiator.
    /// Pin this device to a single relay by direct address (persisted), or pass null to clear the pin
    /// and fall back to the anycast default. The shared RelayBearer reads this pin at start(), so the
    /// pin takes effect on the next launch; we persist it and reflect it in the UI now.
    fun setPinnedRelay(url: String?) {
        val pinned = url?.trim()?.takeIf { it.isNotEmpty() }
        pinnedRelay.value = pinned
        prefs.edit().apply { if (pinned != null) putString("pinnedRelay", pinned) else remove("pinnedRelay") }.apply()
    }

    // MARK: chat-history persistence (survives app restart) ----------------------

    private val messagesFile get() = java.io.File(context.filesDir, "messages.json")
    private val contactsFile get() = java.io.File(context.filesDir, "contacts.json")
    /// Image blobs live here as individual content-addressed files, referenced by name from
    /// messages.json (android-12). This keeps the mirror JSON small so the debounced rewrite doesn't
    /// re-base64 megabytes of photo bytes on every message burst.
    private val mediaDir get() = java.io.File(context.filesDir, "media").apply { mkdirs() }
    /// Content hashes already flushed to [mediaDir]; lets writeMessages skip re-writing unchanged blobs.
    private val writtenMedia = java.util.Collections.synchronizedSet(HashSet<String>())
    private var lastContactSaveMs = 0L

    /// Store [bytes] as a content-addressed file under [mediaDir] and return its reference name. The
    /// same image (by SHA-256) is written at most once; a re-send of the same photo dedupes on disk.
    private fun putMedia(bytes: ByteArray): String {
        val name = mediaName(bytes)
        if (writtenMedia.add(name)) {
            val f = java.io.File(mediaDir, name)
            if (!f.exists()) runCatching { f.writeBytes(bytes) }
        }
        return name
    }

    private fun getMedia(name: String): ByteArray? =
        runCatching { java.io.File(mediaDir, name).readBytes() }.getOrNull()

    private fun mediaName(bytes: ByteArray): String {
        val h = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return android.util.Base64.encodeToString(h, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING)
    }

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
        val arr = org.json.JSONArray()
        for (p in contacts.values) {
            arr.put(org.json.JSONObject()
                .put("addr", addressBase58(p.address)).put("name", p.name)
                .put("platform", p.platform).put("app", p.app))
        }
        val json = arr.toString()
        thread(name = "save-contacts") { runCatching { contactsFile.writeText(json) } }  // off the main thread
    }
    private fun loadContacts() {
        val txt = runCatching { contactsFile.readText() }.getOrNull() ?: return
        val arr = runCatching { org.json.JSONArray(txt) }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val addr = runCatching { addressFromBase58(o.getString("addr")) }.getOrNull() ?: continue
            if (addr.size != 32) continue
            val key = addr.toList()
            if (contacts.containsKey(key)) continue
            val name = o.optString("name", shortHex(addr))
            contacts[key] = Peer(addr, name, 0u, active = false,
                platform = o.optString("platform", ""), app = o.optString("app", ""))
            nameByAddr[key] = name
        }
    }
    private var saveScheduled = false

    /// Coalesce rapid mutations into one disk write (~1/sec) so a burst of messages — or the
    /// per-tick pump() — doesn't re-encode the whole history each time.
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
            val single = m.imageData?.let { putMedia(it) }
            val multi = if (m.images.isNotEmpty()) m.images.map { putMedia(it) } else emptyList()
            if (single != null || multi.isNotEmpty()) imgRefs[m.localId] = single to multi
        }
        thread(name = "save-messages") {
            val arr = org.json.JSONArray()
            for (m in snapshot) {
                val o = org.json.JSONObject()
                o.put("peer", m.peer); o.put("text", m.text); o.put("incoming", m.incoming)
                o.put("contentType", m.contentType)
                val refs = imgRefs[m.localId]
                refs?.first?.let { o.put("imageRef", it) }
                refs?.second?.takeIf { it.isNotEmpty() }?.let { o.put("imageRefs", org.json.JSONArray(it)) }
                o.put("hops", m.hops.toInt())
                m.latencyMs?.let { o.put("latencyMs", it.toLong()) }
                if (m.trace.isNotEmpty()) o.put("trace", org.json.JSONArray(m.trace))
                o.put("sentAt", m.sentAt)
                m.deliveredAt?.let { o.put("deliveredAt", it) }
                o.put("relayed", m.relayed.toLong())
                o.put("delivered", m.delivered); o.put("deliveryHops", m.deliveryHops.toInt())
                m.deliveryMs?.let { o.put("deliveryMs", it.toLong()) } // forward-path (A→B) latency
                o.put("failed", m.failed)
                // Keep the bundleId: the node re-sprays undelivered own-bundles after restart (node.rs
                // rehydrate); persisting it lets refresh() re-query messageStatus and flip to Delivered.
                m.bundleId?.let { o.put("bundleId", android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)) }
                arr.put(o)
            }
            runCatching { messagesFile.writeText(arr.toString()) }
        }
    }

    private fun loadMessages() {
        val txt = runCatching { messagesFile.readText() }.getOrNull() ?: return
        val arr = runCatching { org.json.JSONArray(txt) }.getOrNull() ?: return
        val loaded = ArrayList<Message>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val incoming = o.getBoolean("incoming")
            val delivered = o.optBoolean("delivered", false)
            // New format: images referenced by content-addressed file name (android-12). Legacy format:
            // inline base64 under "imageData"/"images", still read so old mirrors keep working.
            val single: ByteArray? = when {
                o.has("imageRef") -> getMedia(o.getString("imageRef"))
                o.has("imageData") -> android.util.Base64.decode(o.getString("imageData"), android.util.Base64.NO_WRAP)
                else -> null
            }
            val imgs: List<ByteArray> = o.optJSONArray("imageRefs")?.let { a ->
                (0 until a.length()).mapNotNull { getMedia(a.getString(it)) }
            } ?: o.optJSONArray("images")?.let { a ->
                (0 until a.length()).map { android.util.Base64.decode(a.getString(it), android.util.Base64.NO_WRAP) }
            } ?: emptyList()
            val trace = o.optJSONArray("trace")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            loaded.add(Message(
                localId = nextMsgId.getAndIncrement(), peer = o.getString("peer"), text = o.optString("text", ""),
                incoming = incoming, contentType = o.optString("contentType", "text/plain"),
                imageData = single,
                images = imgs, hops = o.optInt("hops", 0).toUByte(),
                latencyMs = if (o.has("latencyMs")) o.getLong("latencyMs").toULong() else null,
                trace = trace, sentAt = o.optLong("sentAt", System.currentTimeMillis()),
                deliveredAt = if (o.has("deliveredAt")) o.getLong("deliveredAt") else null,
                relayed = o.optLong("relayed", 0).toUInt(), delivered = delivered,
                deliveryHops = o.optInt("deliveryHops", 0).toUByte(),
                deliveryMs = if (o.has("deliveryMs")) o.getLong("deliveryMs").toULong() else null,
                // An outgoing message still in flight KEEPS sending after restart — the node re-sprays
                // it until its ACK (node.rs rehydrate). Restore it in-flight with its bundleId so
                // refresh() reconciles to Delivered when it lands, rather than falsely showing "not sent".
                failed = o.optBoolean("failed", false),
                bundleId = if (o.has("bundleId")) android.util.Base64.decode(o.getString("bundleId"), android.util.Base64.NO_WRAP) else null,
            ))
        }
        onUi { messages.addAll(loaded) }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Hop messages", NotificationManager.IMPORTANCE_DEFAULT)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notify(from: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(config.notificationIcon)
            .setContentTitle(from)
            .setContentText(text)
            .setAutoCancel(true)
            .setNumber(unread.intValue)   // drives the launcher icon badge count
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .build()
        NotificationManagerCompat.from(context).notify(text.hashCode(), n)
    }

    @Volatile private var refreshScheduled = false

    /// Coalesced UI refresh. `refresh()` does synchronous SQLite work (browse, queue, per-message
    /// status) and is far too costly to run on every `pump()` — which fires on every received packet
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
        /// Shared app secret for Hop Debug — all our demo devices use it so they interoperate.
        /// A different app (different secret) can't see or join these channels (DESIGN.md §32).
        val APP_SECRET = ByteArray(32) { 0x48 } // "H" ×32 — dev build only (matches iOS)

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
    }
}

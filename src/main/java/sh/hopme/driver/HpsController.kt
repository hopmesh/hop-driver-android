package sh.hopme.driver

import android.os.Handler
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import uniffi.hop.HopNodeInterface
import uniffi.hop.HpsInvite
import uniffi.hop.HpsKind
import uniffi.hop.HpsTopicInfo
import uniffi.hop.addressFromBase58
import java.io.File

/**
 * hps:// pub/sub concern (DESIGN.md §32), split out of the [HopBearer] god-object into a cohesive
 * controller the driver composes. Owns the topic list, the per-topic message threads, the per-topic
 * unread counts, the received invites, the on-screen topic, and the channel-thread mirror persistence.
 *
 * The threading model is unchanged: every method runs on the driver's serial `core` thread, Compose
 * state mutates only on main via [onUi], and pump()-after-op is preserved verbatim. The driver injects
 * the node seam, the core Handler, onUi, [pump], the shared local-id supplier ([newLocalId], the same
 * nextMsgId), the [MirrorStore] (for the sealed channels.json), and the files dir. The app-facing state
 * (hpsTopics/hpsThreads/hpsUnread/hpsInvites) and the [HopBearer.HpsTopic]/[HopBearer.HpsMsg] models stay
 * the same instances/types, so `bearer.hps*` keeps observing them. Behavior-identical to the code that
 * previously lived inline in HopBearer.
 */
internal class HpsController(
    private val node: HopNodeInterface,
    private val core: Handler,
    private val onUi: (() -> Unit) -> Unit,
    private val pump: () -> Unit,
    private val newLocalId: () -> Long,
    private val mirror: MirrorStore,
    private val filesDir: File,
) {
    val hpsTopics = mutableStateListOf<HopBearer.HpsTopic>()
    val hpsThreads = mutableStateMapOf<String, SnapshotStateList<HopBearer.HpsMsg>>() // topic id → messages
    val hpsUnread = mutableStateMapOf<String, Int>()                                  // topic id → unread
    val hpsInvites = mutableStateListOf<HpsInvite>()                                  // invites received
    @Volatile var activeTopic: String? = null                                        // topic on screen

    // Defaults for access/discoverable live on the HopBearer facade the app calls; this internal method
    // is always invoked with explicit args, so it takes none (no dead default-value synthesis here).
    fun hpsRegister(path: String, channel: Boolean,
                    access: uniffi.hop.HpsAccess,
                    discoverable: Boolean) = core.post {
        val p = path.trim(); if (p.isEmpty()) return@post
        runCatching {
            node.registerService(p, if (channel) HpsKind.CHANNEL else HpsKind.SERVICE, access,
                if (discoverable) uniffi.hop.HpsVisibility.DISCOVERABLE else uniffi.hop.HpsVisibility.PRIVATE)
        }
        val topic = HopBearer.HpsTopic(node.address(), p, channel, hosting = true, access = access)
        onUi { if (hpsTopics.none { it.host.contentEquals(topic.host) && it.path == p }) hpsTopics.add(0, topic) }
    }

    fun hpsSubscribe(hostB58: String, path: String) = core.post {
        val host = runCatching { addressFromBase58(hostB58.trim()) }.getOrNull() ?: return@post
        val p = path.trim(); if (host.size != 32 || p.isEmpty()) return@post
        hpsSubscribeTo(host, p, channel = true)
    }

    private fun hpsSubscribeTo(host: ByteArray, path: String, channel: Boolean) {
        runCatching { node.hpsSubscribe(host, path) }
        val topic = HopBearer.HpsTopic(host, path, channel = channel, hosting = false)
        onUi { if (hpsTopics.none { it.host.contentEquals(host) && it.path == path }) hpsTopics.add(0, topic) }
        pump()
    }

    fun hpsJoin(t: HpsTopicInfo) = core.post {
        hpsSubscribeTo(t.host, t.path, t.kind == HpsKind.CHANNEL)
    }

    fun hpsPublish(topic: HopBearer.HpsTopic, text: String) = core.post {
        if (text.isEmpty()) return@post
        runCatching { node.hpsPublish(topic.path, text.toByteArray()) }
        appendThread(topic.id, HopBearer.HpsMsg(newLocalId(), topic.path, node.address(), text)) // echo
        pump()
    }

    fun hpsInvite(topic: HopBearer.HpsTopic, to: ByteArray) = core.post {
        if (!topic.hosting || to.size != 32) return@post
        runCatching { node.hpsInvite(topic.path, to) }; pump()
    }

    fun hpsAcceptInvite(inv: HpsInvite) = core.post {
        runCatching { node.hpsAcceptInvite(inv.host, inv.path) }
        val topic = HopBearer.HpsTopic(inv.host, inv.path, inv.kind == HpsKind.CHANNEL, hosting = false)
        onUi {
            hpsInvites.removeAll { it.path == inv.path && it.host.contentEquals(inv.host) }
            if (hpsTopics.none { it.host.contentEquals(inv.host) && it.path == inv.path }) hpsTopics.add(0, topic)
        }
        pump()
    }

    fun hpsDeclineInvite(inv: HpsInvite) = core.post {
        runCatching { node.hpsDeclineInvite(inv.host, inv.path) } // durable: won't reappear
        onUi { hpsInvites.removeAll { it.path == inv.path && it.host.contentEquals(inv.host) } }
    }

    fun hpsPending(topic: HopBearer.HpsTopic): List<ByteArray> = runCatching { node.hpsPending(topic.path) }.getOrDefault(emptyList())
    fun hpsApprove(topic: HopBearer.HpsTopic, who: ByteArray) = core.post { runCatching { node.hpsApprove(topic.path, who) }; pump() }
    fun hpsDeny(topic: HopBearer.HpsTopic, who: ByteArray) = core.post { runCatching { node.hpsDeny(topic.path, who) } }
    fun hpsReach(topic: HopBearer.HpsTopic): Int = runCatching { node.hpsReach(topic.path).toInt() }.getOrDefault(0)
    fun hpsMembers(topic: HopBearer.HpsTopic): List<ByteArray> = runCatching { node.hpsMembers(topic.path) }.getOrDefault(emptyList())
    fun hpsRekey(topic: HopBearer.HpsTopic, remove: List<ByteArray>) = core.post { runCatching { node.hpsRekey(topic.path, "", remove) }; pump() }
    fun hpsBrowse(): List<HpsTopicInfo> = runCatching { node.browseDiscoverable() }.getOrDefault(emptyList())

    /// Rebuild the channel list from the node's persisted topics (hosted + subscribed) at startup.
    fun loadTopics() {
        val topics = runCatching { node.hpsMyTopics() }.getOrDefault(emptyList()).map { t ->
            HopBearer.HpsTopic(t.host, t.path, t.kind == HpsKind.CHANNEL, t.hosting, t.access)
        }
        onUi { topics.forEach { topic -> if (hpsTopics.none { it.id == topic.id }) hpsTopics.add(topic) } }
    }

    fun hpsLeave(topic: HopBearer.HpsTopic) = core.post {
        runCatching { node.hpsLeave(topic.path) }
        onUi {
            hpsTopics.removeAll { it.id == topic.id }
            hpsThreads.remove(topic.id); hpsUnread.remove(topic.id)
        }
        pump()
    }

    fun openTopic(id: String) { activeTopic = id; hpsUnread[id] = 0 }
    fun closeTopic() { activeTopic = null }

    private fun appendThread(id: String, m: HopBearer.HpsMsg) {
        onUi {
            val list = hpsThreads.getOrPut(id) { mutableStateListOf() }
            list.add(m)
            if (list.size > 500) list.removeAt(0)
        }
        saveChannels()
    }

    // ---- channel-thread persistence (survives restart) ----------------------
    private val channelsFile get() = File(filesDir, "channels.json")
    private var channelSaveScheduled = false
    private fun saveChannels() {
        if (channelSaveScheduled) return
        channelSaveScheduled = true
        core.postDelayed({ channelSaveScheduled = false; writeChannels() }, 1000)
    }
    private fun writeChannels() {
        // ChannelCodec owns the channels.json schema; the seal + write stay here (empty key ⇒ plain,
        // matching an unencrypted db). android-r2-01: the sealed mirror is not plaintext beside hop.db.
        val json = ChannelCodec.encode(hpsThreads)
        runCatching { channelsFile.writeBytes(mirror.seal(json.toByteArray())) }
    }
    fun loadChannels() {
        val txt = mirror.read(channelsFile) ?: return
        val loaded = ChannelCodec.decode(txt) { newLocalId() }
        onUi { for ((id, msgs) in loaded) hpsThreads[id] = mutableStateListOf<HopBearer.HpsMsg>().apply { addAll(msgs) } }
    }

    fun drainHps() {
        for (m in node.takeHpsMessages()) {
            val topic = hpsTopics.firstOrNull { it.path == m.path }
            val id = topic?.id ?: m.path
            appendThread(id, HopBearer.HpsMsg(newLocalId(), m.path, m.sender, String(m.body)))
            onUi { if (id != activeTopic) hpsUnread[id] = (hpsUnread[id] ?: 0) + 1 }
        }
        for (inv in node.takeHpsInvites()) {
            onUi {
                if (hpsInvites.none { it.path == inv.path && it.host.contentEquals(inv.host) })
                    hpsInvites.add(inv)
            }
        }
    }
}

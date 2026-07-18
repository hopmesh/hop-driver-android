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
) {
    val hpsTopics = mutableStateListOf<HopBearer.HpsTopic>()
    val hpsThreads = mutableStateMapOf<String, SnapshotStateList<HopBearer.HpsMsg>>() // topic id → messages
    val hpsUnread = mutableStateMapOf<String, Int>()                                  // topic id → unread
    val hpsInvites = mutableStateListOf<HpsInvite>()                                  // invites received
    @Volatile var activeTopic: String? = null                                        // topic on screen
    private val durableInboxIds = BoundedLruSet<List<Byte>>(
        RetentionPolicy.defaults.globalMessages + RetentionPolicy.defaults.journalRecords,
    )

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
        val retained = hpsThreads.filterKeys { it != topic.id }.mapValues { it.value.toList() }
        if (!writeChannelsNow(retained)) return@post
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
            appendThreadNow(id, m)
        }
        saveChannels()
    }
    private fun appendThreadNow(id: String, m: HopBearer.HpsMsg): Boolean {
        if (id !in hpsThreads && hpsThreads.size >= RetentionPolicy.defaults.conversations) return false
        val list = hpsThreads.getOrPut(id) { mutableStateListOf() }
        list.add(m)
        while (list.size > RetentionPolicy.defaults.conversationMessages ||
            list.sumOf { it.text.toByteArray().size.toLong() } > RetentionPolicy.defaults.conversationMessageBytes) {
            list.removeAt(0)
        }
        while (hpsThreads.values.sumOf { it.size } > RetentionPolicy.defaults.globalMessages ||
            hpsThreads.values.sumOf { rows -> rows.sumOf { it.text.toByteArray().size.toLong() } } >
                RetentionPolicy.defaults.globalMessageBytes) {
            val oldest = hpsThreads.entries.flatMap { (key, rows) -> rows.map { key to it } }
                .minWithOrNull(compareBy<Pair<String, HopBearer.HpsMsg>> { it.second.id }.thenBy { it.first })
                ?: break
            hpsThreads[oldest.first]?.removeAll { it.id == oldest.second.id }
        }
        return hpsThreads[id]?.any { it.id == m.id } == true
    }

    // ---- channel-thread persistence (survives restart) ----------------------
    private val channelsFile get() = mirror.file("channels.json")
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
        val durableIds = hpsThreads.values.flatten().mapNotNull { it.inboxId?.toList() }
        mirror.enqueue("channels") {
            check(mirror.write(channelsFile, json.toByteArray())) { "failed to persist channel mirror" }
            check(mirror.resetDelta("channels")) { "failed to compact channel journal" }
            durableInboxIds.replaceAll(durableIds)
        }
    }
    private fun writeChannelsNow(snapshot: Map<String, List<HopBearer.HpsMsg>>): Boolean {
        val json = ChannelCodec.encode(snapshot)
        val persisted = runCatching {
            mirror.writeNow("channels") {
                mirror.write(channelsFile, json.toByteArray()) && mirror.resetDelta("channels")
            }
        }.getOrDefault(false)
        if (persisted) {
            durableInboxIds.replaceAll(snapshot.values.flatten().mapNotNull { it.inboxId?.toList() })
        }
        return persisted
    }
    fun loadChannels() {
        val txt = mirror.read(channelsFile)
        val loaded: LinkedHashMap<String, MutableList<HopBearer.HpsMsg>> = if (txt == null) {
            linkedMapOf()
        } else {
            ChannelCodec.decodeBounded(txt, newLocalId = { newLocalId() })
                ?.mapValuesTo(linkedMapOf()) { it.value.toMutableList() }
                ?: run {
                    mirror.quarantine(channelsFile)
                    linkedMapOf()
                }
        }
        durableInboxIds.replaceAll(loaded.values.flatten().mapNotNull { it.inboxId?.toList() })
        val replay = mirror.replayDeltas("channels")
        for (record in replay.records) {
            val stableId = record.id.toList()
            durableInboxIds.add(stableId)
            val payload = record.payload ?: continue
            val delta = ChannelCodec.decodeBounded(
                String(payload, Charsets.UTF_8), newLocalId = { newLocalId() }, maximumConversations = 1,
                maximumElements = 1,
            ) ?: continue
            val (id, rows) = delta.entries.singleOrNull() ?: continue
            val row = rows.singleOrNull() ?: continue
            if (loaded.values.none { existing -> existing.any { it.inboxId?.contentEquals(record.id) == true } }) {
                loaded.getOrPut(id) { mutableListOf() }.add(row)
            }
        }
        if (txt == null && replay.records.isEmpty()) return
        writeChannelsNow(loaded)
        onUi {
            for ((id, msgs) in loaded) {
                for (message in msgs) appendThreadNow(id, message)
            }
        }
    }

    fun drainHps() {
        val incoming = node.takeHpsMessages()
        for (m in incoming) {
            val stableId = m.id.toList()
            if (stableId in durableInboxIds) {
                runCatching { node.acceptHpsMessage(m.id) }
                continue
            }
            val topic = hpsTopics.firstOrNull { it.path == m.path }
            val id = topic?.id ?: m.path
            val text = String(m.body, Charsets.UTF_8)
            val keep = text.toByteArray(Charsets.UTF_8).size.toLong() <=
                RetentionPolicy.defaults.conversationMessageBytes &&
                (id in hpsThreads || hpsThreads.size < RetentionPolicy.defaults.conversations)
            val row = HopBearer.HpsMsg(newLocalId(), m.path, m.sender, text, m.id)
            val payload = if (keep) ChannelCodec.encode(mapOf(id to listOf(row))).toByteArray() else null
            val appended = runCatching {
                mirror.writeNow("channels") { mirror.appendDelta("channels", m.id, payload) }
            }.getOrDefault(false)
            if (!appended) continue
            durableInboxIds.add(stableId)
            runCatching { node.acceptHpsMessage(m.id) }
            if (keep) onUi {
                if (appendThreadNow(id, row) && id != activeTopic) {
                    hpsUnread[id] = (hpsUnread[id] ?: 0) + 1
                }
                core.post { saveChannels() }
            } else {
                core.post { saveChannels() }
            }
        }
        for (inv in node.takeHpsInvites()) {
            onUi {
                if (hpsInvites.none { it.path == inv.path && it.host.contentEquals(inv.host) })
                    hpsInvites.add(inv)
            }
        }
    }
}

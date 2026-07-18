package sh.hopme.driver

import org.json.JSONArray
import org.json.JSONObject

/**
 * The hps:// channel-thread mirror's on-disk JSON codec, split out of [HopBearer] so the channels.json
 * schema round-trip is a cohesive, testable unit (mirrors [ContactBook] / [MessageCodec]). The driver
 * keeps the orchestration (the debounced save on core, sealing via [MirrorStore], and applying the
 * restored threads on main); this object owns only the pure `Map<topicId, List<HpsMsg>>` <-> JSON
 * transform. Behavior-identical to the loop that previously lived inline in writeChannels/loadHpsChannels.
 */
internal object ChannelCodec {
    /// Serialize the per-topic message threads to the channels.json object string (topic id -> array of
    /// {path, sender(base64), text}).
    fun encode(threads: Map<String, List<HopBearer.HpsMsg>>): String {
        val root = JSONObject()
        for ((id, msgs) in threads) {
            val arr = JSONArray()
            for (m in msgs) {
                arr.put(JSONObject().apply {
                    put("path", m.path)
                    put("sender", android.util.Base64.encodeToString(m.sender, android.util.Base64.NO_WRAP))
                    put("text", m.text)
                    m.inboxId?.let {
                        put("inboxId", android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP))
                    }
                })
            }
            root.put(id, arr)
        }
        return root.toString()
    }

    /// Parse channels.json back into per-topic threads (insertion-ordered). [newLocalId] supplies a fresh
    /// local id per message in file order (the driver passes nextMsgId.getAndIncrement()). Malformed JSON
    /// yields an empty map (a missing/corrupt mirror is "no channels", never a crash).
    fun decode(text: String, newLocalId: () -> Long): Map<String, List<HopBearer.HpsMsg>> =
        decodeBounded(text, newLocalId) ?: emptyMap()

    fun decodeBounded(
        text: String,
        newLocalId: () -> Long,
        maximumConversations: Int = RetentionPolicy.defaults.conversations,
        maximumElements: Int = RetentionPolicy.defaults.globalMessages,
        maximumAggregateBytes: Long = RetentionPolicy.defaults.globalMessageBytes,
    ): Map<String, List<HopBearer.HpsMsg>>? = runCatching {
        val root = JSONObject(text)
        check(root.length() <= maximumConversations)
        val loaded = LinkedHashMap<String, List<HopBearer.HpsMsg>>()
        var elements = 0
        var aggregate = 0L
        for (id in root.keys()) {
            aggregate += id.toByteArray(Charsets.UTF_8).size
            check(aggregate <= maximumAggregateBytes)
            val arr = root.optJSONArray(id) ?: continue
            check(arr.length() <= maximumElements - elements)
            val msgs = ArrayList<HopBearer.HpsMsg>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val inboxId = o.optString("inboxId", "").takeIf { it.isNotEmpty() }?.let {
                    runCatching { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }.getOrNull()
                }
                val message = HopBearer.HpsMsg(newLocalId(), o.optString("path", ""),
                    android.util.Base64.decode(o.optString("sender", ""), android.util.Base64.NO_WRAP),
                    o.optString("text", ""), inboxId)
                val bytes = message.path.toByteArray(Charsets.UTF_8).size.toLong() +
                    message.sender.size + message.text.toByteArray(Charsets.UTF_8).size +
                    (message.inboxId?.size ?: 0)
                check(bytes <= maximumAggregateBytes - aggregate)
                aggregate += bytes
                elements += 1
                msgs.add(message)
            }
            loaded[id] = msgs
        }
        loaded
    }.getOrNull()
}

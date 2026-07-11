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
                })
            }
            root.put(id, arr)
        }
        return root.toString()
    }

    /// Parse channels.json back into per-topic threads (insertion-ordered). [newLocalId] supplies a fresh
    /// local id per message in file order (the driver passes nextMsgId.getAndIncrement()). Malformed JSON
    /// yields an empty map (a missing/corrupt mirror is "no channels", never a crash).
    fun decode(text: String, newLocalId: () -> Long): Map<String, List<HopBearer.HpsMsg>> {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyMap()
        val loaded = LinkedHashMap<String, List<HopBearer.HpsMsg>>()
        for (id in root.keys()) {
            val arr = root.optJSONArray(id) ?: continue
            val msgs = ArrayList<HopBearer.HpsMsg>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                msgs.add(HopBearer.HpsMsg(newLocalId(), o.optString("path", ""),
                    android.util.Base64.decode(o.optString("sender", ""), android.util.Base64.NO_WRAP),
                    o.optString("text", "")))
            }
            loaded[id] = msgs
        }
        return loaded
    }
}

package sh.hopme.driver

import org.json.JSONArray
import org.json.JSONObject

/**
 * The chat-history mirror's on-disk JSON codec, split out of [HopBearer] so the messages.json schema
 * round-trip is a cohesive, testable unit (mirrors [ContactBook]). The driver keeps the orchestration
 * (snapshotting the message list on core, externalizing image blobs to [MirrorStore], the debounced
 * background write, and sealing the bytes); this object owns only the pure `List<Message>` <-> JSON
 * transform with the exact field names the app already ships. Behavior-identical to the loop that
 * previously lived inline in writeMessages/loadMessages.
 */
internal object MessageCodec {
    /// Serialize a message snapshot to the messages.json array string. [imgRefs] maps a message's
    /// localId to its externalized image references ((single ref or null) to (multi refs)); the caller
    /// externalizes the blobs first, so this only writes the refs.
    fun encode(
        snapshot: List<HopBearer.Message>,
        imgRefs: Map<Long, Pair<String?, List<String>>>,
    ): String {
        val arr = JSONArray()
        for (m in snapshot) {
            val o = JSONObject()
            o.put("peer", m.peer); o.put("text", m.text); o.put("incoming", m.incoming)
            o.put("contentType", m.contentType)
            val refs = imgRefs[m.localId]
            refs?.first?.let { o.put("imageRef", it) }
            refs?.second?.takeIf { it.isNotEmpty() }?.let { o.put("imageRefs", JSONArray(it)) }
            o.put("hops", m.hops.toInt())
            m.latencyMs?.let { o.put("latencyMs", it.toLong()) }
            if (m.trace.isNotEmpty()) o.put("trace", JSONArray(m.trace))
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
        return arr.toString()
    }

    /// Parse messages.json back into [HopBearer.Message] rows. [newLocalId] supplies a fresh local id per
    /// row in file order (the driver passes nextMsgId.getAndIncrement()); [getMedia] resolves a content-
    /// addressed image ref to its bytes (the driver passes [MirrorStore.getMedia]). Malformed JSON yields
    /// an empty list (a missing/corrupt mirror is "no history", never a crash). Legacy inline-base64
    /// image fields ("imageData"/"images") are still read so old mirrors keep working.
    fun decode(
        text: String,
        newLocalId: () -> Long,
        getMedia: (String) -> ByteArray?,
    ): List<HopBearer.Message> {
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        val loaded = ArrayList<HopBearer.Message>(arr.length())
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
            loaded.add(HopBearer.Message(
                localId = newLocalId(), peer = o.getString("peer"), text = o.optString("text", ""),
                incoming = incoming, contentType = o.optString("contentType", "text/plain"),
                imageData = single,
                images = imgs, hops = o.optInt("hops", 0).toUByte(),
                latencyMs = if (o.has("latencyMs")) o.getLong("latencyMs").toULong() else null,
                trace = trace, sentAt = o.optLong("sentAt", System.currentTimeMillis()),
                deliveredAt = if (o.has("deliveredAt")) o.getLong("deliveredAt") else null,
                relayed = o.optLong("relayed", 0).toUInt(), delivered = delivered,
                deliveryHops = o.optInt("deliveryHops", 0).toUByte(),
                deliveryMs = if (o.has("deliveryMs")) o.getLong("deliveryMs").toULong() else null,
                // An outgoing message still in flight KEEPS sending after restart, so the node re-sprays
                // it until its ACK (node.rs rehydrate). Restore it in-flight with its bundleId so
                // refresh() reconciles to Delivered when it lands, rather than falsely showing "not sent".
                failed = o.optBoolean("failed", false),
                bundleId = if (o.has("bundleId")) android.util.Base64.decode(o.getString("bundleId"), android.util.Base64.NO_WRAP) else null,
            ))
        }
        return loaded
    }
}

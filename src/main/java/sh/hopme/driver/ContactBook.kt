package sh.hopme.driver

import org.json.JSONArray
import org.json.JSONObject

// ContactBook: the persistent address book's on-disk JSON codec, split out of HopBearer so the
// serialization round-trip is unit-testable (quality-net-03). Addresses cross this boundary as their
// base58 string form (the driver does the byte<->base58 conversion via uniffi.hop; base58 itself is
// core-owned and needs the native lib, so it stays out of this pure codec). What we CAN pin here: that
// a contact's address, display name, platform, and app survive a save->load round-trip through the
// exact JSON schema the driver writes to contacts.json, with the same field names the app already ships.

/// One persisted contact. `addr58` is the peer's base58 ADDRESS (the stable conversation key, never
/// its display name), plus the display name and app/platform metadata.
internal data class ContactRecord(
    val addr58: String,
    val name: String,
    val platform: String = "",
    val app: String = "",
)

internal object ContactBook {
    /// Serialize contacts to the contacts.json array form the driver writes (saveContacts).
    fun encode(contacts: Collection<ContactRecord>): String {
        val arr = JSONArray()
        for (c in contacts) {
            arr.put(
                JSONObject()
                    .put("addr", c.addr58).put("name", c.name)
                    .put("platform", c.platform).put("app", c.app),
            )
        }
        return arr.toString()
    }

    /// Parse the contacts.json array back into records (loadContacts). Malformed JSON yields an empty
    /// list (the driver treats a missing/corrupt file as "no contacts", never a crash). Entries with a
    /// blank address are dropped. A missing "name" falls back to the address (the driver uses shortHex,
    /// but at this layer the base58 address is the honest fallback key).
    fun decode(text: String): List<ContactRecord> = decodeBounded(text) ?: emptyList()

    fun decodeBounded(
        text: String,
        maximumElements: Int = RetentionPolicy.defaults.contacts,
        maximumAggregateBytes: Long = RetentionPolicy.defaults.contactMirrorBytes,
    ): List<ContactRecord>? = runCatching {
        val arr = JSONArray(text)
        check(arr.length() <= maximumElements)
        val out = ArrayList<ContactRecord>(arr.length())
        var aggregate = 0L
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val addr = o.optString("addr", "")
            if (addr.isEmpty()) continue
            val record = ContactRecord(
                    addr58 = addr,
                    name = o.optString("name", addr),
                    platform = o.optString("platform", ""),
                    app = o.optString("app", ""),
                )
            val bytes = (record.addr58 + record.name + record.platform + record.app)
                .toByteArray(Charsets.UTF_8).size.toLong()
            check(bytes <= maximumAggregateBytes - aggregate)
            aggregate += bytes
            out.add(record)
        }
        out
    }.getOrNull()
}

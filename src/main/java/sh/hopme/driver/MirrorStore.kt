package sh.hopme.driver

import java.io.File

/**
 * At-rest mirror store, split out of [HopBearer] so the sealed-file IO the three persistence mirrors
 * (messages / contacts / channels) share lives in one cohesive unit instead of the god-object. Wraps
 * [MirrorCrypto] with the device db key so callers seal/open by concern, and owns the content-addressed
 * image blob store (android-12). Behavior-identical to the code that previously lived inline in
 * HopBearer: seal/open still route through [MirrorCrypto] with [dbKey], legacy plaintext mirrors still
 * read through transparently, and the same-photo-by-SHA256 dedupe on disk is unchanged.
 */
internal class MirrorStore(private val dbKey: ByteArray, private val filesDir: File) {
    /// Image blobs live here as individual content-addressed files, referenced by name from
    /// messages.json (android-12). This keeps the mirror JSON small so the debounced rewrite doesn't
    /// re-base64 megabytes of photo bytes on every message burst.
    private val mediaDir get() = File(filesDir, "media").apply { mkdirs() }
    /// Content hashes already flushed to [mediaDir]; lets writeMessages skip re-writing unchanged blobs.
    private val writtenMedia = java.util.Collections.synchronizedSet(HashSet<String>())

    /// Seal [plaintext] under the db key (empty key ⇒ plaintext, matching an unencrypted db).
    fun seal(plaintext: ByteArray): ByteArray = MirrorCrypto.seal(dbKey, plaintext)

    /// Store [bytes] as a content-addressed file under [mediaDir] and return its reference name. The
    /// same image (by SHA-256) is written at most once; a re-send of the same photo dedupes on disk.
    fun putMedia(bytes: ByteArray): String {
        val name = mediaName(bytes)
        if (writtenMedia.add(name)) {
            val f = File(mediaDir, name)
            // android-r2-01: seal the photo blob with the db key so media/* is not plaintext at rest.
            if (!f.exists()) runCatching { f.writeBytes(MirrorCrypto.seal(dbKey, bytes)) }
        }
        return name
    }

    fun getMedia(name: String): ByteArray? =
        runCatching { File(mediaDir, name).readBytes() }.getOrNull()
            ?.let { blob -> MirrorCrypto.open(dbKey, blob) ?: blob }   // sealed, or legacy plaintext

    /// android-r2-01: read a sealed mirror file back to its plaintext JSON string. Opens the sealed
    /// (AES-GCM, Keystore-wrapped-key) form; if the bytes are a LEGACY plaintext mirror from before
    /// at-rest encryption (or a plain-SQLite dev build with an empty key), returns them as text so the
    /// old file is read once and rewritten sealed. Returns null if the file is absent/unreadable.
    fun read(f: File): String? {
        val blob = runCatching { f.readBytes() }.getOrNull() ?: return null
        return MirrorCrypto.open(dbKey, blob)?.let { String(it) } ?: String(blob)
    }

    private fun mediaName(bytes: ByteArray): String {
        val h = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return android.util.Base64.encodeToString(h, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING)
    }
}

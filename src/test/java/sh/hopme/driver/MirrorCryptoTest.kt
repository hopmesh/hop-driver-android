package sh.hopme.driver

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * android-r2-01: the at-rest codec for the driver's on-disk chat/contact/channel/media mirrors.
 *
 * Before this change the mirrors (messages.json, contacts.json, channels.json, media blobs) were written
 * as cleartext beside the SQLCipher-encrypted hop.db, so a forensic/backup extract read every message
 * body + the whole address book with NO db key. These pin that a sealed mirror:
 *   - round-trips only with the right key,
 *   - does NOT contain the plaintext (the forensic-extract defeat), and
 *   - is distinguishable from a legacy plaintext file (so an existing install migrates transparently).
 */
class MirrorCryptoTest {

    // A 32-byte key stand-in (the real one is the Keystore-wrapped db key). Non-zero so it's a real key.
    private val key = ByteArray(32) { (it * 7 + 1).toByte() }
    private val other = ByteArray(32) { (it * 3 + 9).toByte() }

    @Test fun sealOpenRoundTrips() {
        val plain = """[{"peer":"abc","text":"meet at the docks at nine"}]""".toByteArray()
        val sealed = MirrorCrypto.seal(key, plain)
        assertArrayEquals(plain, MirrorCrypto.open(key, sealed))
    }

    @Test fun sealedBytesDoNotContainThePlaintext() {
        // The whole point of android-r2-01: the sealed blob on disk must not expose the message body.
        val secret = "meet at the docks at nine"
        val sealed = MirrorCrypto.seal(key, """[{"text":"$secret"}]""".toByteArray())
        val asText = String(sealed, Charsets.ISO_8859_1)
        assertFalse("sealed mirror must not contain the cleartext body", asText.contains(secret))
    }

    @Test fun wrongKeyFailsToOpen() {
        val sealed = MirrorCrypto.seal(key, "address book".toByteArray())
        assertNull("a different device key must not decrypt the mirror", MirrorCrypto.open(other, sealed))
    }

    @Test fun sealedIsRecognizedOpenIsNot() {
        val sealed = MirrorCrypto.seal(key, "x".toByteArray())
        assertTrue(MirrorCrypto.isSealed(sealed))
        // A legacy plaintext JSON mirror (starts with '[' or '{') is NOT mistaken for sealed.
        assertFalse(MirrorCrypto.isSealed("[]".toByteArray()))
        assertFalse(MirrorCrypto.isSealed("""{"a":1}""".toByteArray()))
    }

    @Test fun emptyKeyIsPassthroughForPlainBuilds() {
        // A plain-SQLite dev build (no at-rest key) has nothing to seal WITH; seal is identity and
        // open returns null (so the loader treats it as legacy plaintext), matching the unencrypted db.
        val plain = "no key".toByteArray()
        assertArrayEquals(plain, MirrorCrypto.seal(ByteArray(0), plain))
        assertNull(MirrorCrypto.open(ByteArray(0), plain))
    }

    @Test fun ivIsRandomizedPerSeal() {
        // Two seals of the same plaintext must differ (fresh IV), so identical mirrors aren't linkable.
        val plain = "same".toByteArray()
        val a = MirrorCrypto.seal(key, plain)
        val b = MirrorCrypto.seal(key, plain)
        assertFalse(a.contentEquals(b))
        // ...yet both open to the same plaintext.
        assertArrayEquals(plain, MirrorCrypto.open(key, a))
        assertArrayEquals(plain, MirrorCrypto.open(key, b))
    }

    @Test fun tamperedCiphertextFailsToOpen() {
        val sealed = MirrorCrypto.seal(key, "integrity".toByteArray())
        sealed[sealed.size - 1] = (sealed[sealed.size - 1] + 1).toByte()   // flip a ciphertext/tag byte
        assertNull("GCM auth must reject a tampered mirror", MirrorCrypto.open(key, sealed))
    }

    @Test fun emptyPayloadRoundTrips() {
        val sealed = MirrorCrypto.seal(key, ByteArray(0))
        assertArrayEquals(ByteArray(0), MirrorCrypto.open(key, sealed))
    }
}

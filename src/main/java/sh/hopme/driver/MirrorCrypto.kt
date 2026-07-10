package sh.hopme.driver

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * android-r2-01: AES-256-GCM at-rest encryption for the driver's on-disk chat/contact/channel mirrors,
 * so they are NOT plaintext beside the SQLCipher-encrypted hop.db. The key is the same 32-byte
 * Keystore-wrapped [HopConfig.dbKey] that keys the db, so a forensic/backup extract that F-25 claims to
 * defeat can read neither the db nor these mirrors without the device's secure element.
 *
 * Wire: a 4-byte magic + 1-byte version, then a 12-byte random IV, then the GCM ciphertext (which
 * carries its own auth tag). The magic lets [open] and the driver's loaders distinguish an encrypted
 * blob from a legacy plaintext JSON file (which starts with '[' or '{'), so an existing install's
 * cleartext mirror is transparently read once and rewritten sealed.
 *
 * Pure `javax.crypto`, no Android APIs, so the seal/open round-trip is unit-testable on a plain JVM.
 */
internal object MirrorCrypto {
    private val MAGIC = byteArrayOf('H'.code.toByte(), 'O'.code.toByte(), 'P'.code.toByte(), 'M'.code.toByte())
    private const val VERSION: Byte = 1
    private const val HEADER = 5           // MAGIC(4) + VERSION(1)
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** True if [blob] is one of our sealed mirrors (starts with the magic + version). */
    fun isSealed(blob: ByteArray): Boolean =
        blob.size >= HEADER + IV_BYTES &&
            blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC) &&
            blob[MAGIC.size] == VERSION

    /**
     * Seal [plaintext] under [key] (a 32-byte AES key). Returns MAGIC|VER|IV|GCM-ciphertext. If [key]
     * is empty (a plain, non-SQLCipher dev build — no at-rest key exists), returns [plaintext] as-is so
     * behavior matches the unencrypted db case; there is nothing to protect the mirror WITH.
     */
    fun seal(key: ByteArray, plaintext: ByteArray): ByteArray {
        if (key.isEmpty()) return plaintext
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.copyOf(32), "AES"), GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(plaintext)
        return ByteArray(HEADER + iv.size + ct.size).also {
            MAGIC.copyInto(it, 0)
            it[MAGIC.size] = VERSION
            iv.copyInto(it, HEADER)
            ct.copyInto(it, HEADER + iv.size)
        }
    }

    /**
     * Open a sealed [blob] under [key], returning the plaintext, or null if it is not our sealed format
     * or the key is wrong / the data is corrupt. Callers fall back to a legacy plaintext parse on null.
     */
    fun open(key: ByteArray, blob: ByteArray): ByteArray? {
        if (key.isEmpty() || !isSealed(blob)) return null
        return runCatching {
            val iv = blob.copyOfRange(HEADER, HEADER + IV_BYTES)
            val ct = blob.copyOfRange(HEADER + IV_BYTES, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.copyOf(32), "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(ct)
        }.getOrNull()
    }
}

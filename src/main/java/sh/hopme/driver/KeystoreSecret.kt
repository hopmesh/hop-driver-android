package sh.hopme.driver

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * android-02 / sec-priv-03 (Android half): hardware-backed storage for the two long-term secrets
 * (the Ed25519 identity seed and the SQLCipher db key).
 *
 * The old scheme derived both from `SHA-256("...|ANDROID_ID")`: ANDROID_ID has at most ~64 bits of
 * entropy and any on-device code (or a forensic/backup extract) that learns it can recompute both
 * secrets, so "a pulled db is useless without the device" only held against an attacker who somehow
 * had the file but not ANDROID_ID.
 *
 * Instead we generate a random 32-byte secret ONCE and wrap it with a non-exportable AES/GCM key in
 * the AndroidKeyStore (StrongBox-backed when the hardware supports it, else the TEE). The wrapped
 * ciphertext lives in plain SharedPreferences; the key never leaves the secure element, so the
 * secret is unreadable off-device even by code that knows ANDROID_ID. Each secret gets its own
 * Keystore key and pref entry, keyed by a caller-supplied name, so identity and db-key stay
 * domain-separated.
 *
 * Migration: a legacy ANDROID_ID-derived value can be adopted as the one-time random secret via
 * [getOrCreate]'s `legacy` seed, so existing installs keep their identity/db instead of resetting.
 */
internal object KeystoreSecret {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val PREFS = "sh.hopme.driver.secrets"
    private const val KEY_ALIAS_PREFIX = "hop.wrap."   // one Keystore key per named secret
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12

    /**
     * Return the persisted random secret for [name], generating and wrapping a new one on first use.
     *
     * @param legacy a one-time seed (e.g. the old ANDROID_ID-derived value) adopted as the stored
     *   secret when nothing exists yet, so an existing install keeps its identity/db key. When null,
     *   a fresh 32 random bytes are generated (a one-time identity reset for that secret).
     */
    fun getOrCreate(context: Context, name: String, legacy: ByteArray? = null): ByteArray {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        loadWrapped(prefs, name)?.let { wrapped ->
            runCatching { unwrap(name, wrapped) }.getOrNull()?.let { return it }
            // The Keystore key was lost (e.g. cleared credentials / restore to new hardware): the
            // ciphertext is undecryptable. Fall through and mint a fresh secret so the app recovers
            // instead of crash-looping; this is a one-time identity reset for this device.
        }
        val secret = legacy?.copyOf(32) ?: randomBytes(32)
        runCatching { store(prefs, name, secret) }
        return secret
    }

    private fun store(prefs: SharedPreferences, name: String, secret: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrapKey(name))
        val iv = cipher.iv
        val ct = cipher.doFinal(secret)
        // iv || ciphertext, base64 in a plain pref (the AES key that protects it is in the Keystore).
        val blob = ByteArray(iv.size + ct.size).also {
            iv.copyInto(it); ct.copyInto(it, iv.size)
        }
        prefs.edit().putString(prefKey(name), Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    private fun unwrap(name: String, wrapped: ByteArray): ByteArray {
        require(wrapped.size > GCM_IV_BYTES) { "wrapped secret too short" }
        val iv = wrapped.copyOfRange(0, GCM_IV_BYTES)
        val ct = wrapped.copyOfRange(GCM_IV_BYTES, wrapped.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, existingWrapKey(name), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }

    private fun loadWrapped(prefs: SharedPreferences, name: String): ByteArray? =
        prefs.getString(prefKey(name), null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    /** The Keystore AES key wrapping [name]'s secret, creating it (StrongBox-preferred) if absent. */
    private fun wrapKey(name: String): SecretKey {
        existingWrapKeyOrNull(name)?.let { return it }
        return generateWrapKey(name)
    }

    private fun existingWrapKey(name: String): SecretKey =
        existingWrapKeyOrNull(name) ?: error("Keystore key missing for $name")

    private fun existingWrapKeyOrNull(name: String): SecretKey? {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return (ks.getEntry(alias(name), null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun generateWrapKey(name: String): SecretKey {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
            alias(name),
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
            .build()

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        // Prefer StrongBox (a discrete secure element); gracefully fall back to the TEE when the
        // device has no StrongBox or refuses the request.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                gen.init(spec(strongBox = true))
                return gen.generateKey()
            } catch (_: StrongBoxUnavailableException) {
                // fall through to TEE
            } catch (_: Exception) {
                // some OEMs throw a generic exception instead of StrongBoxUnavailableException
            }
        }
        gen.init(spec(strongBox = false))
        return gen.generateKey()
    }

    private fun randomBytes(n: Int): ByteArray =
        ByteArray(n).also { java.security.SecureRandom().nextBytes(it) }

    private fun alias(name: String) = KEY_ALIAS_PREFIX + name
    private fun prefKey(name: String) = "secret.$name"
}

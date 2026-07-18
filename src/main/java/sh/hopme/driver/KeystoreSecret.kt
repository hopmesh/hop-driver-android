package sh.hopme.driver

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.ProviderException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal sealed interface SecretReadResult {
    data object Missing : SecretReadResult
    data class Found(val bytes: ByteArray) : SecretReadResult
    data class Failed(val cause: Throwable) : SecretReadResult
}

internal interface SecretBackend {
    fun read(name: String): SecretReadResult
    fun store(name: String, secret: ByteArray)
}

internal class SecretStorageException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/** Fail-closed AndroidKeyStore storage for the identity seed and SQLCipher key. */
internal object KeystoreSecret {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val PREFS = "sh.hopme.driver.secrets"
    private const val MODERN_INSTALL_MARKER = "secrets.v2.committed"
    private const val KEY_ALIAS_PREFIX = "hop.wrap."
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12

    data class DeviceSecrets(val identity: ByteArray, val database: ByteArray)

    /** Resolve both secrets as one migration. The modern marker is committed only after both values are durable. */
    @Synchronized
    fun deviceSecrets(context: Context): DeviceSecrets {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val databaseFile = File(app.filesDir, "hop.db")
        val legacyOwner = ownsLegacyEncryptedState(
            modernSecretsCommitted = prefs.getBoolean(MODERN_INSTALL_MARKER, false),
            databaseIsFile = databaseFile.isFile,
            databaseBytes = databaseFile.length(),
        )
        val androidId = if (legacyOwner) {
            android.provider.Settings.Secure.getString(
                app.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID,
            ) ?: throw SecretStorageException("legacy encrypted state has no ANDROID_ID")
        } else null
        val backend = AndroidKeystoreBackend(prefs)
        val identity = resolve(
            backend,
            "identity.v1",
            legacyMaterial("hop.identity.v1", androidId, legacyOwner),
        )
        val database = resolve(
            backend,
            "db.key.v1",
            legacyMaterial("hop.db.key.v1", androidId, legacyOwner),
        )
        if (!prefs.getBoolean(MODERN_INSTALL_MARKER, false) &&
            !prefs.edit().putBoolean(MODERN_INSTALL_MARKER, true).commit()) {
            throw SecretStorageException("failed to commit secret migration marker")
        }
        return DeviceSecrets(identity, database)
    }

    /** Only [SecretReadResult.Missing] may create. Every read, unwrap, and length failure stops startup. */
    internal fun resolve(
        backend: SecretBackend,
        name: String,
        legacy: ByteArray? = null,
        random: (Int) -> ByteArray = ::randomBytes,
    ): ByteArray = when (val existing = backend.read(name)) {
        is SecretReadResult.Found -> validate(name, existing.bytes)
        is SecretReadResult.Failed -> throw SecretStorageException("failed to read $name", existing.cause)
        SecretReadResult.Missing -> {
            val secret = validate(name, legacy?.copyOf() ?: random(32))
            try {
                backend.store(name, secret)
            } catch (error: Throwable) {
                throw SecretStorageException("failed to durably store $name", error)
            }
            when (val committed = backend.read(name)) {
                is SecretReadResult.Found -> {
                    val verified = validate(name, committed.bytes)
                    if (!verified.contentEquals(secret)) throw SecretStorageException("stored $name did not verify")
                    verified
                }
                SecretReadResult.Missing -> throw SecretStorageException("stored $name is missing")
                is SecretReadResult.Failed -> throw SecretStorageException("failed to verify $name", committed.cause)
            }
        }
    }

    internal fun legacyMaterial(label: String, androidId: String?, ownsLegacyEncryptedState: Boolean): ByteArray? {
        if (!ownsLegacyEncryptedState) return null
        val id = androidId ?: throw SecretStorageException("legacy encrypted state has no ANDROID_ID")
        return java.security.MessageDigest.getInstance("SHA-256").digest("$label|$id".toByteArray())
    }

    internal fun ownsLegacyEncryptedState(
        modernSecretsCommitted: Boolean,
        databaseIsFile: Boolean,
        databaseBytes: Long,
    ): Boolean = !modernSecretsCommitted && databaseIsFile && databaseBytes > 0L

    private fun validate(name: String, bytes: ByteArray): ByteArray {
        if (bytes.size != 32) throw SecretStorageException("$name must be exactly 32 bytes")
        return bytes.copyOf()
    }

    private fun randomBytes(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }

    private class AndroidKeystoreBackend(private val prefs: SharedPreferences) : SecretBackend {
        override fun read(name: String): SecretReadResult {
            if (!prefs.contains(prefKey(name))) return SecretReadResult.Missing
            return try {
                val encoded = prefs.getString(prefKey(name), null)
                    ?: throw SecretStorageException("wrapped $name is not a string")
                val wrapped = Base64.decode(encoded, Base64.NO_WRAP)
                if (Base64.encodeToString(wrapped, Base64.NO_WRAP) != encoded) {
                    throw SecretStorageException("wrapped $name is not canonical base64")
                }
                require(wrapped.size > GCM_IV_BYTES) { "wrapped secret too short" }
                val key = existingWrapKey(name)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    GCMParameterSpec(GCM_TAG_BITS, wrapped.copyOfRange(0, GCM_IV_BYTES)),
                )
                SecretReadResult.Found(cipher.doFinal(wrapped.copyOfRange(GCM_IV_BYTES, wrapped.size)))
            } catch (error: Throwable) {
                SecretReadResult.Failed(error)
            }
        }

        override fun store(name: String, secret: ByteArray) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, wrapKey(name))
            val ciphertext = cipher.doFinal(secret)
            val blob = ByteArray(cipher.iv.size + ciphertext.size).also {
                cipher.iv.copyInto(it)
                ciphertext.copyInto(it, cipher.iv.size)
            }
            val encoded = Base64.encodeToString(blob, Base64.NO_WRAP)
            if (!prefs.edit().putString(prefKey(name), encoded).commit()) {
                throw SecretStorageException("SharedPreferences commit failed for $name")
            }
        }

        private fun wrapKey(name: String): SecretKey = existingWrapKeyOrNull(name) ?: generateWrapKey(name)

        private fun existingWrapKey(name: String): SecretKey = existingWrapKeyOrNull(name)
            ?: throw SecretStorageException("Keystore key missing for wrapped $name")

        private fun existingWrapKeyOrNull(name: String): SecretKey? {
            val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!store.containsAlias(alias(name))) return null
            val entry = store.getEntry(alias(name), null)
            return (entry as? KeyStore.SecretKeyEntry)?.secretKey
                ?: throw SecretStorageException("Keystore entry for $name is not a secret key")
        }

        private fun generateWrapKey(name: String): SecretKey {
            fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
                alias(name),
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .apply {
                    if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true)
                }
                .build()

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    generator.init(spec(strongBox = true))
                    return generator.generateKey()
                } catch (_: StrongBoxUnavailableException) {
                    // The documented absence case may fall back to the TEE.
                } catch (error: ProviderException) {
                    if (error.cause !is StrongBoxUnavailableException) throw error
                }
            }
            generator.init(spec(strongBox = false))
            return generator.generateKey()
        }
    }

    private fun alias(name: String) = KEY_ALIAS_PREFIX + name
    private fun prefKey(name: String) = "secret.$name"
}

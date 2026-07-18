package sh.hopme.driver

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KeystoreSecretTest {
    private class FakeBackend(
        var result: SecretReadResult = SecretReadResult.Missing,
        private val failStore: Boolean = false,
    ) : SecretBackend {
        var stores = 0
        override fun read(name: String): SecretReadResult = result
        override fun store(name: String, secret: ByteArray) {
            stores += 1
            if (failStore) error("disk full")
            result = SecretReadResult.Found(secret.copyOf())
        }
    }

    @Test fun freshInstallsUseIndependentRandomSecrets() {
        val first = KeystoreSecret.resolve(FakeBackend(), "identity")
        val second = KeystoreSecret.resolve(FakeBackend(), "identity")
        assertEquals(32, first.size)
        assertNotEquals(first.toList(), second.toList())
    }

    @Test fun verifiedLegacyMigrationPreservesTheOwnedSecret() {
        val expected = KeystoreSecret.legacyMaterial("hop.identity.v1", "legacy-id", true)!!
        val backend = FakeBackend()
        val resolved = KeystoreSecret.resolve(backend, "identity", expected)
        assertArrayEquals(expected, resolved)
        assertEquals(1, backend.stores)
    }

    @Test fun freshInstallNeverReadsAndroidIdDerivedMaterial() {
        assertTrue(!KeystoreSecret.ownsLegacyEncryptedState(false, databaseIsFile = false, databaseBytes = 0))
        assertTrue(!KeystoreSecret.ownsLegacyEncryptedState(false, databaseIsFile = true, databaseBytes = 0))
        assertTrue(!KeystoreSecret.ownsLegacyEncryptedState(true, databaseIsFile = true, databaseBytes = 100))
        assertTrue(KeystoreSecret.ownsLegacyEncryptedState(false, databaseIsFile = true, databaseBytes = 100))
        assertEquals(null, KeystoreSecret.legacyMaterial("hop.identity.v1", "public-id", false))
        assertThrows(SecretStorageException::class.java) {
            KeystoreSecret.legacyMaterial("hop.identity.v1", null, true)
        }
    }

    @Test fun unwrapOrKeystoreReadFailureNeverCreatesAReplacement() {
        val backend = FakeBackend(SecretReadResult.Failed(SecurityException("unwrap failed")))
        assertThrows(SecretStorageException::class.java) {
            KeystoreSecret.resolve(backend, "identity", ByteArray(32) { 9 })
        }
        assertEquals(0, backend.stores)
    }

    @Test fun malformedStoredKeyLengthStopsStartup() {
        val backend = FakeBackend(SecretReadResult.Found(ByteArray(31)))
        assertThrows(SecretStorageException::class.java) {
            KeystoreSecret.resolve(backend, "database")
        }
        assertEquals(0, backend.stores)
    }

    @Test fun durableCommitFailureDoesNotReturnUnpersistedIdentity() {
        val backend = FakeBackend(failStore = true)
        val error = assertThrows(SecretStorageException::class.java) {
            KeystoreSecret.resolve(backend, "identity", ByteArray(32) { 5 })
        }
        assertTrue(error.message!!.contains("durably store"))
    }

    @Test fun restartLoadsTheExactCommittedIdentityWithoutWriting() {
        val backend = FakeBackend()
        val first = KeystoreSecret.resolve(backend, "identity")
        val stores = backend.stores
        val second = KeystoreSecret.resolve(backend, "identity")
        assertArrayEquals(first, second)
        assertEquals(stores, backend.stores)
    }
}

package sh.hopme.driver

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * android-r3-01: pin the invariant the (now-corrected) AndroidManifest comment documents.
 *
 * The old manifest comment claimed the on-disk mirrors were "gated out of release". That was never
 * true: HopBearer writes every mirror (channels/contacts/messages) through [MirrorCrypto.seal]
 * UNCONDITIONALLY, in both debug and release, keyed by the Keystore-wrapped db key. The corrected
 * comment says exactly that. These tests fail-if a future maintainer either (a) build-gates the seal
 * (e.g. only sealing when [DriverFlags] is off, leaving cleartext otherwise) or (b) regresses the
 * on-disk bytes back to plaintext.
 *
 * Pure JVM: this models the write-then-read-back that HopBearer.kt:728/1005/1070 do, without Android.
 */
class MirrorSealAlwaysTest {

    private val key = ByteArray(32) { (it * 5 + 2).toByte() }
    private val secret = "meet at the docks at nine"
    private val plain = """[{"peer":"abc","text":"$secret"}]""".toByteArray()

    @After fun restore() {
        // Never let a flipped flag leak into other tests.
        DriverFlags.verboseContentLogs = BuildConfig.DEBUG
        DriverFlags.automationSurface = BuildConfig.DEBUG
    }

    /** The mirror bytes on disk must be sealed and cleartext-free REGARDLESS of the build flags. */
    @Test fun mirrorSealIsIndependentOfBuildFlags() {
        for (flag in listOf(true, false)) {
            // Simulate BOTH build postures (debug on, release off). The seal must not consult these.
            DriverFlags.verboseContentLogs = flag
            DriverFlags.automationSurface = flag

            val onDisk = MirrorCrypto.seal(key, plain)
            assertTrue("mirror must be sealed in build posture flag=$flag", MirrorCrypto.isSealed(onDisk))
            val asText = String(onDisk, Charsets.ISO_8859_1)
            assertFalse(
                "sealed mirror must never carry the cleartext body (flag=$flag)",
                asText.contains(secret),
            )
            assertArrayEquals(
                "sealed mirror must round-trip back to the original (flag=$flag)",
                plain,
                MirrorCrypto.open(key, onDisk),
            )
        }
    }

    /** A written mirror file's on-disk bytes are the sealed form, not plaintext JSON. */
    @Test fun writtenMirrorFileHoldsSealedBytesNotPlaintext() {
        val f = File.createTempFile("messages", ".json").apply { deleteOnExit() }
        // Mirror the exact write HopBearer does: writeBytes(MirrorCrypto.seal(key, json)).
        f.writeBytes(MirrorCrypto.seal(key, plain))

        val bytes = f.readBytes()
        assertTrue("file must start with the sealed magic, not '[' plaintext JSON", MirrorCrypto.isSealed(bytes))
        assertFalse(
            "on-disk mirror file must not contain the cleartext body",
            String(bytes, Charsets.ISO_8859_1).contains(secret),
        )
        // The loader reads it back transparently.
        assertArrayEquals(plain, MirrorCrypto.open(key, bytes))
    }
}

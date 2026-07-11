package sh.hopme.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.hop.HpsAccess
import uniffi.hop.addressBase58

/** cov/android-driver: the on-disk mirror round-trips - messages.json (+ externalized media),
 *  contacts.json, channels.json - written by one driver and re-read by a "restart" (start()) driver,
 *  in both the plaintext (empty key) and AES-GCM-sealed (32-byte key) forms. */
class HopBearerPersistenceTest : DriverTestBase() {

    private fun restart(cfg: HopConfig): HopBearer {
        val b = newBearer(FakeHopNode(), cfg)
        b.start("Restart")
        settleOn(b)
        return b
    }

    @Test fun messagesAndMediaSurviveARestart() {
        val cfg = defaultConfig()
        val b1 = newBearer(FakeHopNode(), cfg)
        b1.send("persist me", HopBearer.Peer(ByteArray(32) { 2 }, "Bob", 0u))
        b1.sendImage(ByteArray(32) { 5 }, HopBearer.Peer(ByteArray(32) { 3 }, "Cy", 0u))
        settleOn(b1)
        assertTrue(awaitFileContains(filesFile("messages.json"), "persist me"))

        val b2 = restart(cfg)
        assertTrue("text survived", b2.messages.any { it.text == "persist me" })
        assertTrue("image survived via media/", b2.messages.any { it.imageData != null })
    }

    @Test fun contactsSurviveARestart() {
        val cfg = defaultConfig()
        val b1 = newBearer(FakeHopNode(), cfg)
        val addr = ByteArray(32) { 12 }
        b1.rememberContact(HopBearer.Peer(addr, "Dana", 0u, active = false, platform = "android", app = "HopDemo"))
        settleOn(b1)
        assertTrue(awaitFileContains(filesFile("contacts.json"), "Dana"))

        val b2 = restart(cfg)
        assertEquals("Dana", b2.displayName(addr))
    }

    @Test fun channelThreadsSurviveARestart() {
        val cfg = defaultConfig()
        val b1 = newBearer(FakeHopNode(), cfg)
        b1.hpsRegister("townsquare", channel = true, access = HpsAccess.OPEN)
        settleOn(b1)
        val topic = b1.hpsTopics.first()
        b1.hpsPublish(topic, "persisted post")
        settleOn(b1)
        assertTrue(awaitFileContains(filesFile("channels.json"), "persisted post"))

        val b2 = restart(cfg)
        val thread = b2.hpsThreads[topic.id]
        assertTrue("channel post survived", thread != null && thread.any { it.text == "persisted post" })
    }

    @Test fun sealedMirrorsRoundTripWithADbKey() {
        val cfg = defaultConfig(dbKey = ByteArray(32) { 0x7 })
        val b1 = newBearer(FakeHopNode(), cfg)
        b1.send("secret msg", HopBearer.Peer(ByteArray(32) { 4 }, "Eve", 0u))
        settleOn(b1)
        assertTrue(awaitFile(filesFile("messages.json")))
        val bytes = filesFile("messages.json").readBytes()
        assertTrue("mirror is AES-GCM sealed, not plaintext JSON", MirrorCrypto.isSealed(bytes))

        val b2 = restart(cfg)
        assertTrue("sealed history re-read on restart", b2.messages.any { it.text == "secret msg" })
    }

    @Test fun legacyPlaintextMirrorStillReads() {
        // an existing install's cleartext mirror (empty key) must round-trip
        val cfg = defaultConfig()
        val addr58 = addressBase58(ByteArray(32) { 9 })
        assertTrue(addr58.isNotEmpty())
        val b1 = newBearer(FakeHopNode(), cfg)
        b1.send("cleartext", HopBearer.Peer(ByteArray(32) { 9 }, "F", 0u))
        settleOn(b1)
        assertTrue(awaitFileContains(filesFile("messages.json"), "cleartext"))
        val bytes = filesFile("messages.json").readBytes()
        assertTrue("empty key => plaintext JSON array", bytes.isNotEmpty() && bytes[0] == '['.code.toByte())
        val b2 = restart(cfg)
        assertTrue(b2.messages.any { it.text == "cleartext" })
    }
}

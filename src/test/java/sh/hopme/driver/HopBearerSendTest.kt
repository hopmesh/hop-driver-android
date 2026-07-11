package sh.hopme.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.hop.addressBase58

/** cov/android-driver: the outgoing paths - send / sendImage / sendMultipart / retry / stampSent /
 *  sendTo / addContact / clearQueue - driven through the real serial core thread. */
class HopBearerSendTest : DriverTestBase() {

    private fun peer(b: Byte = 9) = HopBearer.Peer(ByteArray(32) { b }, "Bob", 0u)

    @Test fun sendTextOptimisticThenStamped() {
        bearer.send("hi there", peer())
        // the optimistic bubble is posted to main (onUi{}) and appears on the next main-loop turn,
        // BEFORE the core thread finishes node.sendMessage (bundleId still null).
        idleMain()
        assertEquals(1, bearer.messages.size)
        assertFalse(bearer.messages[0].incoming)
        assertNull(bearer.messages[0].bundleId)
        settle()
        assertEquals(1, fake.sentMessages.size)
        assertEquals("text/plain", fake.sentMessages[0].contentType)
        assertEquals("hi there", String(fake.sentMessages[0].body))
        assertTrue(fake.sentMessages[0].ack)
        assertTrue("bundleId patched back", bearer.messages[0].bundleId != null)
    }

    @Test fun sendImageUsesJpegContentType() {
        val img = ByteArray(64) { it.toByte() }
        bearer.sendImage(img, peer())
        settle()
        assertEquals("image/jpeg", fake.sentMessages[0].contentType)
        assertEquals("image/jpeg", bearer.messages[0].contentType)
        assertTrue(bearer.messages[0].imageData!!.contentEquals(img))
    }

    @Test fun sendMultipartPacksTextAndImages() {
        val imgs = listOf(ByteArray(8) { 1 }, ByteArray(8) { 2 })
        bearer.sendMultipart("caption", imgs, peer())
        settle()
        assertEquals("multipart/mixed", fake.sentMessages[0].contentType)
        // the wire body decodes back to text + 2 images
        val parts = HopBearer.decodeMultipart(fake.sentMessages[0].body)
        assertEquals(3, parts.size)
        assertEquals("text/plain", parts[0].first)
        assertEquals("caption", String(parts[0].second))
        assertEquals("multipart/mixed", bearer.messages[0].contentType)
        assertEquals(2, bearer.messages[0].images.size)
    }

    @Test fun sendMultipartWithNothingIsNoOp() {
        bearer.sendMultipart("   ", emptyList(), peer())
        settle()
        assertTrue(fake.sentMessages.isEmpty())
        assertTrue(bearer.messages.isEmpty())
    }

    @Test fun sendThatThrowsMarksFailed() {
        fake.sendMessageThrows = true
        bearer.send("doomed", peer())
        settle()
        assertTrue(fake.sentMessages.isEmpty())          // node.sendMessage threw
        assertTrue("row flips to failed", bearer.messages[0].failed)
        assertNull(bearer.messages[0].bundleId)
    }

    @Test fun retryReSendsAFailedTextMessage() {
        fake.sendMessageThrows = true
        bearer.send("first", peer())
        settle()
        val failed = bearer.messages[0]
        assertTrue(failed.failed)
        fake.sendMessageThrows = false
        bearer.retry(failed, peer())
        settle()
        assertEquals(1, fake.sentMessages.size)
        assertEquals("first", String(fake.sentMessages[0].body))
        assertFalse(bearer.messages[0].failed)
        assertTrue(bearer.messages[0].bundleId != null)
    }

    @Test fun retryReSendsImageAndMultipart() {
        val img = ByteArray(4) { 7 }
        val m = HopBearer.Message(localId = 99, peer = "p", text = "", incoming = false,
            contentType = "image/jpeg", imageData = img, failed = true)
        bearer.messages.add(m)
        bearer.retry(m, peer())
        settle()
        assertEquals("image/jpeg", fake.sentMessages.last().contentType)

        val mp = HopBearer.Message(localId = 100, peer = "p", text = "hey", incoming = false,
            contentType = "multipart/mixed", images = listOf(img), failed = true)
        bearer.messages.add(mp)
        bearer.retry(mp, peer())
        settle()
        assertEquals("multipart/mixed", fake.sentMessages.last().contentType)
    }

    @Test fun retryIgnoresIncoming() {
        val incoming = HopBearer.Message(localId = 1, peer = "p", text = "in", incoming = true)
        bearer.retry(incoming, peer())
        settle()
        assertTrue(fake.sentMessages.isEmpty())
    }

    @Test fun sendToHonorsAutomationFlag() {
        val addr58 = addressBase58(ByteArray(32) { 5 })
        DriverFlags.automationSurface = true
        bearer.sendTo(addr58, "driven")
        settle()
        assertEquals(1, fake.sentMessages.size)

        DriverFlags.automationSurface = false
        bearer.sendTo(addr58, "blocked")
        settle()
        assertEquals("release build refuses driven sends", 1, fake.sentMessages.size)
        DriverFlags.automationSurface = true
    }

    @Test fun addContactValidatesAddress() {
        val good = addressBase58(ByteArray(32) { 4 })
        assertTrue(bearer.addContact("Alice", good))
        settle()
        assertFalse("garbage base58 rejected", bearer.addContact("x", "not-base58!!"))
        assertFalse("own address rejected", bearer.addContact("me", addressBase58(fake.address())))
    }

    @Test fun clearQueueMarksInflightFailed() {
        bearer.send("inflight", peer())
        settle()
        assertFalse(bearer.messages[0].failed)
        bearer.clearQueue()
        settle()
        assertTrue(fake.cleared >= 1)
        assertTrue("undelivered own message marked not-sent", bearer.messages[0].failed)
    }
}

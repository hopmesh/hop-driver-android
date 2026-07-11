package sh.hopme.driver

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicLong

/**
 * The chat-history mirror codec (messages.json). Split out of HopBearer so the schema round-trip is a
 * cohesive unit; these pin that a Message's fields survive save->load and that image blobs are
 * externalized by ref (not inlined). Runs under Robolectric because the codec base64s the bundleId via
 * android.util.Base64. (base58 addresses are opaque strings to the codec, so no libhop is needed.)
 */
@RunWith(RobolectricTestRunner::class)
class MessageCodecTest {

    private val ids = AtomicLong(100)
    private fun newId(): Long = ids.getAndIncrement()
    private val noMedia: (String) -> ByteArray? = { null }

    private fun msg(
        peer: String, text: String, incoming: Boolean,
        contentType: String = "text/plain",
        imageData: ByteArray? = null, images: List<ByteArray> = emptyList(),
        bundleId: ByteArray? = null, hops: UByte = 0u, delivered: Boolean = false,
        failed: Boolean = false, trace: List<String> = emptyList(),
    ) = HopBearer.Message(
        localId = newId(), peer = peer, text = text, incoming = incoming, bundleId = bundleId,
        contentType = contentType, imageData = imageData, images = images, hops = hops,
        trace = trace, delivered = delivered, failed = failed,
    )

    @Test fun textMessageRoundTrips() {
        val orig = msg("HpAddrPeerA", "hello world", incoming = true, hops = 3u, trace = listOf("A", "B"))
        val json = MessageCodec.encode(listOf(orig), emptyMap())
        val back = MessageCodec.decode(json, ::newId, noMedia).single()
        assertEquals("HpAddrPeerA", back.peer)
        assertEquals("hello world", back.text)
        assertTrue(back.incoming)
        assertEquals(3u.toUByte(), back.hops)
        assertEquals(listOf("A", "B"), back.trace)
    }

    @Test fun outgoingBundleIdSurvivesRoundTrip() {
        // The bundleId must persist so refresh() can reconcile a restart-restored in-flight message.
        val id = ByteArray(32) { (it + 1).toByte() }
        val orig = msg("HpAddrPeerB", "sent", incoming = false, bundleId = id)
        val json = MessageCodec.encode(listOf(orig), emptyMap())
        val back = MessageCodec.decode(json, ::newId, noMedia).single()
        assertArrayEquals(id, back.bundleId)
        assertFalse(back.incoming)
    }

    @Test fun imagesAreStoredByRefNotInlined() {
        // The single + multi image refs are externalized; the encoded JSON carries the ref names, and
        // decode resolves them through the supplied media loader.
        val single = byteArrayOf(1, 2, 3)
        val multiA = byteArrayOf(4, 5)
        val orig = msg("HpAddrPeerC", "", incoming = true, contentType = "multipart/mixed",
            imageData = single, images = listOf(multiA))
        val refs = mapOf(orig.localId to ("ref-single" to listOf("ref-multi-0")))
        val json = MessageCodec.encode(listOf(orig), refs)
        assertTrue("ref written", json.contains("ref-single"))
        assertTrue("multi ref written", json.contains("ref-multi-0"))
        assertFalse("raw image bytes must not be inlined", json.contains("imageData"))

        val media = mapOf("ref-single" to single, "ref-multi-0" to multiA)
        val back = MessageCodec.decode(json, ::newId) { media[it] }
        assertArrayEquals(single, back.single().imageData)
        assertArrayEquals(multiA, back.single().images.single())
    }

    @Test fun localIdsAreAssignedFreshInFileOrder() {
        val orig = listOf(
            msg("p", "one", true), msg("p", "two", true), msg("p", "three", true),
        )
        val json = MessageCodec.encode(orig, emptyMap())
        val start = ids.get()
        val back = MessageCodec.decode(json, ::newId, noMedia)
        // Each decoded row gets a fresh id from the supplier, in order.
        assertEquals(listOf(start, start + 1, start + 2), back.map { it.localId })
        assertEquals(listOf("one", "two", "three"), back.map { it.text })
    }

    @Test fun malformedJsonYieldsEmptyNotCrash() {
        assertEquals(emptyList<HopBearer.Message>(), MessageCodec.decode("not json", ::newId, noMedia))
        assertEquals(emptyList<HopBearer.Message>(), MessageCodec.decode("", ::newId, noMedia))
    }

    @Test fun legacyInlineImageDataStillReads() {
        // Old mirrors inlined base64 under "imageData"; decode must still honor them.
        val raw = byteArrayOf(9, 8, 7, 6)
        val b64 = android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP)
        val json = "[{\"peer\":\"p\",\"text\":\"\",\"incoming\":true,\"contentType\":\"image/jpeg\",\"imageData\":\"$b64\"}]"
        val back = MessageCodec.decode(json, ::newId, noMedia).single()
        assertArrayEquals(raw, back.imageData)
    }

    @Test fun legacyInlineImagesArrayStillReads() {
        // Old mirrors inlined a multi-image message as a base64 array under "images"; decode must honor it.
        val a = byteArrayOf(1, 1, 1); val b = byteArrayOf(2, 2)
        val ea = android.util.Base64.encodeToString(a, android.util.Base64.NO_WRAP)
        val eb = android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
        val json = "[{\"peer\":\"p\",\"text\":\"\",\"incoming\":true,\"contentType\":\"multipart/mixed\",\"images\":[\"$ea\",\"$eb\"]}]"
        val back = MessageCodec.decode(json, ::newId, noMedia).single()
        assertEquals(2, back.images.size)
        assertArrayEquals(a, back.images[0])
        assertArrayEquals(b, back.images[1])
    }

    @Test fun missingOptionalFieldsDefaultCleanly() {
        val json = "[{\"peer\":\"p\",\"text\":\"hi\",\"incoming\":false}]"
        val back = MessageCodec.decode(json, ::newId, noMedia).single()
        assertEquals("hi", back.text)
        assertEquals("text/plain", back.contentType)
        assertEquals(0u.toUByte(), back.hops)
        assertNull(back.bundleId)
        assertFalse(back.delivered)
    }
}

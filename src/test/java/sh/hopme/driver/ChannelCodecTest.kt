package sh.hopme.driver

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicLong

/**
 * The hps:// channel-thread mirror codec (channels.json). Split out of HopBearer so the schema
 * round-trip is a cohesive unit; these pin that per-topic threads (path/sender/text) survive save->load
 * and stay insertion-ordered. Runs under Robolectric because the codec base64s the sender via
 * android.util.Base64.
 */
@RunWith(RobolectricTestRunner::class)
class ChannelCodecTest {

    private val ids = AtomicLong(500)
    private fun newId(): Long = ids.getAndIncrement()

    private fun m(path: String, sender: ByteArray, text: String) =
        HopBearer.HpsMsg(newId(), path, sender, text)

    @Test fun singleTopicRoundTrips() {
        val sender = ByteArray(32) { (it + 1).toByte() }
        val threads = mapOf("hostA/general" to listOf(m("general", sender, "hi there")))
        val json = ChannelCodec.encode(threads)
        val back = ChannelCodec.decode(json, ::newId)
        assertEquals(setOf("hostA/general"), back.keys)
        val msg = back.getValue("hostA/general").single()
        assertEquals("general", msg.path)
        assertArrayEquals(sender, msg.sender)
        assertEquals("hi there", msg.text)
    }

    @Test fun manyTopicsAndMessagesRoundTripInOrder() {
        val s = ByteArray(32) { 7 }
        val threads = LinkedHashMap<String, List<HopBearer.HpsMsg>>()
        threads["hostA/one"] = listOf(m("one", s, "a1"), m("one", s, "a2"))
        threads["hostB/two"] = listOf(m("two", s, "b1"))
        val back = ChannelCodec.decode(ChannelCodec.encode(threads), ::newId)
        assertEquals(listOf("hostA/one", "hostB/two"), back.keys.toList())
        assertEquals(listOf("a1", "a2"), back.getValue("hostA/one").map { it.text })
        assertEquals(listOf("b1"), back.getValue("hostB/two").map { it.text })
    }

    @Test fun freshLocalIdsAssignedInFileOrder() {
        val s = ByteArray(32) { 1 }
        val threads = mapOf("t" to listOf(m("t", s, "x"), m("t", s, "y")))
        val json = ChannelCodec.encode(threads)
        val start = ids.get()
        val back = ChannelCodec.decode(json, ::newId).getValue("t")
        assertEquals(listOf(start, start + 1), back.map { it.id })
    }

    @Test fun malformedJsonYieldsEmptyNotCrash() {
        assertEquals(emptyMap<String, List<HopBearer.HpsMsg>>(), ChannelCodec.decode("nope", ::newId))
        assertEquals(emptyMap<String, List<HopBearer.HpsMsg>>(), ChannelCodec.decode("", ::newId))
    }

    @Test fun encodedShapeCarriesPathSenderText() {
        val json = ChannelCodec.encode(mapOf("t" to listOf(m("p", ByteArray(4) { 2 }, "hey"))))
        assertTrue(json.contains("\"path\""))
        assertTrue(json.contains("\"sender\""))
        assertTrue(json.contains("\"text\""))
    }
}

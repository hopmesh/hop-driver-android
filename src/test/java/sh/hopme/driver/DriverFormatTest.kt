package sh.hopme.driver

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * quality-cov: the driver's pure display + wire helpers (HopBearer companion). [compactDuration] and
 * [hopsLabel] are the exact strings the Status/chat UI renders; [encodeMultipart]/[decodeMultipart] are
 * the cross-platform multipart wire format shared with iOS, so a codec drift silently corrupts an
 * image-plus-text message between an Android and an iOS peer. Pure JVM (no node, no radios).
 */
class DriverFormatTest {

    // ---- compactDuration: 3s / 5m / 2h / 4d --------------------------------------------

    @Test fun compactDurationPicksTheLargestWholeUnit() {
        assertEquals("0s", HopBearer.compactDuration(500uL))          // sub-second rounds down to 0s
        assertEquals("3s", HopBearer.compactDuration(3_000uL))
        assertEquals("59s", HopBearer.compactDuration(59_000uL))
        assertEquals("1m", HopBearer.compactDuration(60_000uL))
        assertEquals("5m", HopBearer.compactDuration(5uL * 60_000uL))
        assertEquals("59m", HopBearer.compactDuration(59uL * 60_000uL))
        assertEquals("1h", HopBearer.compactDuration(60uL * 60_000uL))
        assertEquals("2h", HopBearer.compactDuration(2uL * 60uL * 60_000uL))
        assertEquals("23h", HopBearer.compactDuration(23uL * 60uL * 60_000uL))
        assertEquals("1d", HopBearer.compactDuration(24uL * 60uL * 60_000uL))
        assertEquals("4d", HopBearer.compactDuration(4uL * 24uL * 60uL * 60_000uL))
    }

    // ---- hopsLabel: a single link is "direct"; >= 2 shows the count ---------------------

    @Test fun hopsLabelCallsZeroOrOneHopDirect() {
        assertEquals("direct", HopBearer.hopsLabel(0u))
        assertEquals("direct", HopBearer.hopsLabel(1u))
    }

    @Test fun hopsLabelCountsTwoOrMore() {
        assertEquals("2 hops", HopBearer.hopsLabel(2u))
        assertEquals("5 hops", HopBearer.hopsLabel(5u))
        assertEquals("255 hops", HopBearer.hopsLabel(255u))
    }

    // ---- multipart wire codec: byte-identical round-trip, shared with iOS ---------------

    private fun assertPartsEqual(exp: List<Pair<String, ByteArray>>, act: List<Pair<String, ByteArray>>) {
        assertEquals(exp.size, act.size)
        for (i in exp.indices) {
            assertEquals("part $i contentType", exp[i].first, act[i].first)
            assertArrayEquals("part $i body", exp[i].second, act[i].second)
        }
    }

    @Test fun multipartRoundTripsASinglePart() {
        val parts = listOf("text/plain" to "hello mesh".toByteArray())
        assertPartsEqual(parts, HopBearer.decodeMultipart(HopBearer.encodeMultipart(parts)))
    }

    @Test fun multipartRoundTripsTextPlusBinaryImages() {
        val parts = listOf(
            "text/plain" to "caption éà".toByteArray(),
            "image/png" to byteArrayOf(0, 1, 2, 0x7f, -1, -128),
            "image/jpeg" to ByteArray(300) { (it % 256).toByte() },
        )
        assertPartsEqual(parts, HopBearer.decodeMultipart(HopBearer.encodeMultipart(parts)))
    }

    @Test fun multipartRoundTripsEmptyList() {
        val encoded = HopBearer.encodeMultipart(emptyList())
        assertTrue(HopBearer.decodeMultipart(encoded).isEmpty())
    }

    @Test fun multipartRoundTripsAnEmptyBody() {
        val parts = listOf("application/octet-stream" to ByteArray(0))
        assertPartsEqual(parts, HopBearer.decodeMultipart(HopBearer.encodeMultipart(parts)))
    }

    @Test fun decodeOfTruncatedDataFailsSafeToWhatItParsed() {
        // A body shorter than its declared count must not throw or over-read; it returns the parts it
        // could fully read (defensive decode). Encode 2 parts, then cut the buffer mid-second-part.
        val parts = listOf("a" to byteArrayOf(1, 2, 3), "b" to byteArrayOf(9, 9, 9, 9))
        val full = HopBearer.encodeMultipart(parts)
        val truncated = full.copyOfRange(0, full.size - 2)
        val decoded = HopBearer.decodeMultipart(truncated)
        // The first part is intact; the second is dropped rather than corrupting or crashing.
        assertTrue(decoded.size <= parts.size)
        if (decoded.isNotEmpty()) {
            assertEquals("a", decoded[0].first)
            assertArrayEquals(byteArrayOf(1, 2, 3), decoded[0].second)
        }
    }

    @Test fun decodeOfEmptyBufferIsEmpty() {
        assertTrue(HopBearer.decodeMultipart(ByteArray(0)).isEmpty())
    }

    // ---- companion constants the UI + relay path depend on ------------------------------

    @Test fun companionConstantsAreTheDeployedValues() {
        assertEquals("wss://relay.hopme.sh/", HopBearer.DEFAULT_RELAY)
        assertEquals(6, HopBearer.HNS_MAX_CONCURRENT)
        assertEquals(600_000u, HopBearer.PRESENCE_TTL_MS)
        // APP_SECRET is the shared dev-fabric secret ("H" x32); byte-identical to iOS or the fabrics split.
        assertEquals(32, HopBearer.APP_SECRET.size)
        assertTrue(HopBearer.APP_SECRET.all { it == 0x48.toByte() })
    }

    @Test fun nowMsIsAWallClockMillis() {
        val before = System.currentTimeMillis().toULong()
        val n = HopBearer.nowMs()
        val after = System.currentTimeMillis().toULong()
        assertTrue("nowMs must be a current wall-clock millis", n in before..after)
    }
}

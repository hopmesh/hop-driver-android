package sh.hopme.driver

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

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

    private fun decodeSuccess(data: ByteArray): List<Pair<String, ByteArray>> {
        val result = HopBearer.decodeMultipart(data)
        assertTrue("expected success, got $result", result is MultipartDecodeResult.Success)
        return (result as MultipartDecodeResult.Success).parts
    }

    private fun assertDecodeFailure(data: ByteArray, reason: MultipartDecodeFailure) {
        assertEquals(MultipartDecodeResult.Failure(reason), HopBearer.decodeMultipart(data))
    }

    private fun writeU32(out: ByteArrayOutputStream, value: Long) {
        out.write((value ushr 24).toInt())
        out.write((value ushr 16).toInt())
        out.write((value ushr 8).toInt())
        out.write(value.toInt())
    }

    private fun writePart(out: ByteArrayOutputStream, bodyLength: Long, body: ByteArray = ByteArray(0)) {
        out.write(0)
        out.write(1)
        out.write('x'.code)
        writeU32(out, bodyLength)
        out.write(body)
    }

    private fun declaredBodyLength(length: Long): ByteArray = ByteArrayOutputStream().also { out ->
        writeU32(out, 1)
        writePart(out, length)
    }.toByteArray()

    @Test fun multipartRoundTripsASinglePart() {
        val parts = listOf("text/plain" to "hello mesh".toByteArray())
        assertPartsEqual(parts, decodeSuccess(HopBearer.encodeMultipart(parts)))
    }

    @Test fun multipartRoundTripsTextPlusBinaryImages() {
        val parts = listOf(
            "text/plain" to "caption éà".toByteArray(),
            "image/png" to byteArrayOf(0, 1, 2, 0x7f, -1, -128),
            "image/jpeg" to ByteArray(300) { (it % 256).toByte() },
        )
        assertPartsEqual(parts, decodeSuccess(HopBearer.encodeMultipart(parts)))
    }

    @Test fun multipartRoundTripsEmptyList() {
        val encoded = HopBearer.encodeMultipart(emptyList())
        assertTrue(decodeSuccess(encoded).isEmpty())
    }

    @Test fun multipartRoundTripsAnEmptyBody() {
        val parts = listOf("application/octet-stream" to ByteArray(0))
        assertPartsEqual(parts, decodeSuccess(HopBearer.encodeMultipart(parts)))
    }

    @Test fun unsignedHighBitAndMaxCountsAreRejectedBeforeIteration() {
        for (count in listOf(0x8000_0000L, 0xffff_ffffL)) {
            val out = ByteArrayOutputStream()
            writeU32(out, count)
            assertDecodeFailure(out.toByteArray(), MultipartDecodeFailure.TOO_MANY_PARTS)
        }
    }

    @Test fun unsignedHighBitAndMaxPartLengthsAreRejectedBeforeSlicing() {
        for (length in listOf(0x8000_0000L, 0xffff_ffffL)) {
            assertDecodeFailure(declaredBodyLength(length), MultipartDecodeFailure.PART_TOO_LARGE)
        }
    }

    @Test fun everyTruncationPointReturnsTypedFailure() {
        val full = HopBearer.encodeMultipart(listOf("x" to byteArrayOf(1, 2, 3)))
        for (length in listOf(0, 4, 5, 6, full.size - 1)) {
            assertDecodeFailure(full.copyOf(length), MultipartDecodeFailure.TRUNCATED)
        }
    }

    @Test fun overflowCombinationAfterAValidPartFailsWithoutIntConversion() {
        val out = ByteArrayOutputStream()
        writeU32(out, 2)
        writePart(out, 1, byteArrayOf(7))
        writePart(out, 0xffff_ffffL)
        assertDecodeFailure(out.toByteArray(), MultipartDecodeFailure.PART_TOO_LARGE)
    }

    @Test fun eachCapPlusOneIsRejected() {
        val count = ByteArrayOutputStream().also { writeU32(it, HopBearer.MULTIPART_MAX_PARTS.toLong() + 1) }
        assertDecodeFailure(count.toByteArray(), MultipartDecodeFailure.TOO_MANY_PARTS)
        assertDecodeFailure(
            declaredBodyLength(HopBearer.MULTIPART_MAX_PART_BYTES.toLong() + 1),
            MultipartDecodeFailure.PART_TOO_LARGE,
        )
        val contentType = ByteArrayOutputStream()
        writeU32(contentType, 1)
        val contentTypeLength = HopBearer.MULTIPART_MAX_CONTENT_TYPE_BYTES + 1
        contentType.write(contentTypeLength ushr 8)
        contentType.write(contentTypeLength)
        assertDecodeFailure(contentType.toByteArray(), MultipartDecodeFailure.CONTENT_TYPE_TOO_LARGE)

        val aggregate = ByteArrayOutputStream()
        writeU32(aggregate, 2)
        val first = ByteArray(HopBearer.MULTIPART_MAX_PART_BYTES)
        writePart(aggregate, first.size.toLong(), first)
        val secondLength = HopBearer.MULTIPART_MAX_AGGREGATE_BYTES.toLong() - first.size - 2 + 1
        writePart(aggregate, secondLength)
        assertDecodeFailure(aggregate.toByteArray(), MultipartDecodeFailure.AGGREGATE_TOO_LARGE)
    }

    @Test fun exactCountPartAndAggregateCapsAreAccepted() {
        val exactCount = List(HopBearer.MULTIPART_MAX_PARTS) { "x" to ByteArray(0) }
        assertEquals(HopBearer.MULTIPART_MAX_PARTS, decodeSuccess(HopBearer.encodeMultipart(exactCount)).size)

        val exactContentType = "x".repeat(HopBearer.MULTIPART_MAX_CONTENT_TYPE_BYTES)
        assertEquals(
            exactContentType,
            decodeSuccess(HopBearer.encodeMultipart(listOf(exactContentType to ByteArray(0)))).single().first,
        )

        val exactPart = ByteArray(HopBearer.MULTIPART_MAX_PART_BYTES)
        assertEquals(
            exactPart.size,
            decodeSuccess(HopBearer.encodeMultipart(listOf("x" to exactPart))).single().second.size,
        )

        val secondSize = HopBearer.MULTIPART_MAX_AGGREGATE_BYTES - exactPart.size - 2
        val exactAggregate = listOf("x" to exactPart, "y" to ByteArray(secondSize))
        val decoded = decodeSuccess(HopBearer.encodeMultipart(exactAggregate))
        assertEquals(
            HopBearer.MULTIPART_MAX_AGGREGATE_BYTES,
            decoded.sumOf { it.first.toByteArray(Charsets.UTF_8).size + it.second.size },
        )
    }

    @Test fun trailingBytesAreMalformedRatherThanPartiallyAccepted() {
        val valid = HopBearer.encodeMultipart(listOf("x" to byteArrayOf(1)))
        assertDecodeFailure(valid + byteArrayOf(0), MultipartDecodeFailure.TRAILING_BYTES)
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

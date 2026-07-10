package sh.hopme.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * quality-net-07: the per-link LINKFLOW counters + log line the soak's flood-health critic reads.
 *
 * The critic was told to read "LINKFLOW ... tx=.. rx=.. txpkts[hs=.. data=.. frag=..]" lines that no
 * code emitted, so that lens was permanently blind. These pin the counter arithmetic and the exact
 * line grammar the critic parses, and that a link's counters vanish on linkDown.
 */
class LinkFlowTest {

    @Test fun classifiesTxBySize() {
        assertEquals(TxKind.HS, classifyTx(1))
        assertEquals(TxKind.HS, classifyTx(LINKFLOW_HS_MAX_BYTES))
        assertEquals(TxKind.DATA, classifyTx(LINKFLOW_HS_MAX_BYTES + 1))
        assertEquals(TxKind.DATA, classifyTx(LINKFLOW_FRAG_MIN_BYTES - 1))
        assertEquals(TxKind.FRAG, classifyTx(LINKFLOW_FRAG_MIN_BYTES))
        assertEquals(TxKind.FRAG, classifyTx(4096))
    }

    @Test fun accumulatesTxBytesAndBucketsPerLink() {
        val f = LinkFlow()
        f.linkUp(1_000_000L, "BT")
        f.onTx(1_000_000L, 48)    // hs
        f.onTx(1_000_000L, 300)   // data
        f.onTx(1_000_000L, 1200)  // frag
        f.onTx(1_000_000L, 64)    // hs
        val c = f.counters(1_000_000L)!!
        assertEquals(48L + 300 + 1200 + 64, c.txBytes)
        assertEquals(2L, c.txHs)
        assertEquals(1L, c.txData)
        assertEquals(1L, c.txFrag)
    }

    @Test fun accumulatesRxBytesAndPackets() {
        val f = LinkFlow()
        f.linkUp(5L, "Relay")
        f.onRx(5L, 200)
        f.onRx(5L, 55)
        val c = f.counters(5L)!!
        assertEquals(255L, c.rxBytes)
        assertEquals(2L, c.rxPkts)
    }

    @Test fun lineMatchesCriticGrammar() {
        val f = LinkFlow()
        f.linkUp(1_000_007L, "LAN")
        f.onTx(1_000_007L, 40)    // hs
        f.onTx(1_000_007L, 512)   // data
        f.onRx(1_000_007L, 128)
        val line = f.line(1_000_007L)!!
        // The exact tokens testkit/soak.workflow.js flood-health lens scans for.
        assertTrue(line.startsWith("LINKFLOW "))
        assertTrue(line.contains("link=1000007"))
        assertTrue(line.contains("xport=LAN"))
        assertTrue(line.contains("tx=552"))
        assertTrue(line.contains("rx=128"))
        assertTrue(line.contains("txpkts[hs=1 data=1 frag=0]"))
        assertTrue(line.contains("rxpkts=1"))
    }

    @Test fun perLinkIsolation() {
        val f = LinkFlow()
        f.linkUp(1L, "BT"); f.linkUp(2L, "LAN")
        f.onTx(1L, 100); f.onRx(2L, 200)
        assertEquals(100L, f.counters(1L)!!.txBytes)
        assertEquals(0L, f.counters(1L)!!.rxBytes)
        assertEquals(0L, f.counters(2L)!!.txBytes)
        assertEquals(200L, f.counters(2L)!!.rxBytes)
        assertEquals(2, f.lines().size)   // one line per known link
    }

    @Test fun linkDownForgetsCounters() {
        val f = LinkFlow()
        f.linkUp(9L, "BT"); f.onTx(9L, 100)
        f.linkDown(9L)
        assertNull(f.counters(9L))
        assertNull(f.line(9L))
        assertTrue(f.lines().isEmpty())
    }

    @Test fun noLinksMeansNoLinesSoTheCriticSeesAbsenceNotGarbage() {
        // quality-net-07: when there are no bearer links the emit is empty (the critic is told to
        // tolerate absence), never a spurious/zeroed line.
        assertTrue(LinkFlow().lines().isEmpty())
    }

    @Test fun countersImplyRatesTheCriticCanJudge() {
        // A gossip-flood regression is "data pkts climbing fast on an idle link". Model two 5s samples:
        // healthy (a few data pkts) vs flooding (hundreds), and confirm the delta the critic would
        // compute is meaningful (monotonic, per-bucket).
        val f = LinkFlow()
        f.linkUp(1L, "BT")
        repeat(3) { f.onTx(1L, 300) }        // healthy idle: 3 data pkts
        val healthy = f.counters(1L)!!.txData
        repeat(500) { f.onTx(1L, 300) }      // flood burst
        val flooding = f.counters(1L)!!.txData
        assertEquals(3L, healthy)
        assertEquals(503L, flooding)
        assertTrue("data-pkt count must be monotonic so a rate delta is well-defined", flooding > healthy)
    }
}

package sh.hopme.driver

// LinkFlow: per-link tx/rx byte + packet counters and the LINKFLOW log line the soak's flood-health
// critic reads (quality-net-07). Deliberately Android-free (pure Kotlin) so the counter + formatter
// are unit-testable on a plain JVM; the driver owns wiring it to the bearer seam and the tick loop.
//
// The line format the critic parses (testkit/soak.workflow.js flood-health lens):
//   LINKFLOW link=<g> xport=<T> tx=<bytes> rx=<bytes> txpkts[hs=<n> data=<n> frag=<n>] rxpkts=<n>
// A HEALTHY idle link's data/tx grow slowly; a gossip-flood regression makes data-pkt or tx climb
// fast. Emitting per-link counters gives that lens real input (previously it read a line no code wrote).
//
// Packet classification is a coarse heuristic on the FIRST byte of a node packet at the driver seam,
// enough to separate handshake churn from data throughput and fragment spray without decoding the wire:
//   - a Noise handshake message (m1/m2/m3) is small; we bucket sub-HS_MAX_BYTES tx packets as "hs".
//   - a fragment of a carrier-chunked large body is bucketed as "frag" when it exceeds FRAG_MIN_BYTES.
//   - everything else is "data". These buckets exist to spot a re-handshake loop or a fragment storm,
//     not to be a protocol decoder.

internal const val LINKFLOW_HS_MAX_BYTES = 96      // Noise m1/m2/m3 are well under this
internal const val LINKFLOW_FRAG_MIN_BYTES = 900   // near the BLE MTU-ish fragment ceiling

internal data class LinkCounters(
    var txBytes: Long = 0, var rxBytes: Long = 0,
    var txHs: Long = 0, var txData: Long = 0, var txFrag: Long = 0,
    var rxPkts: Long = 0,
)

/// Classify an outbound node packet into a coarse tx bucket by size (see file header).
internal enum class TxKind { HS, DATA, FRAG }

internal fun classifyTx(size: Int): TxKind = when {
    size in 1..LINKFLOW_HS_MAX_BYTES -> TxKind.HS
    size >= LINKFLOW_FRAG_MIN_BYTES -> TxKind.FRAG
    else -> TxKind.DATA
}

/// Thread-confined accumulator (the driver touches it only on the core thread). Records tx/rx per
/// GLOBAL link id and renders one LINKFLOW line per known link.
internal class LinkFlow {
    private val byLink = LinkedHashMap<Long, LinkCounters>()
    private val xportOf = HashMap<Long, String>()

    fun linkUp(link: Long, xport: String) {
        byLink.getOrPut(link) { LinkCounters() }
        xportOf[link] = xport
    }

    fun linkDown(link: Long) {
        byLink.remove(link)
        xportOf.remove(link)
    }

    /// Record an outbound node packet of [size] bytes on [link].
    fun onTx(link: Long, size: Int) {
        val c = byLink.getOrPut(link) { LinkCounters() }
        c.txBytes += size
        when (classifyTx(size)) {
            TxKind.HS -> c.txHs++
            TxKind.DATA -> c.txData++
            TxKind.FRAG -> c.txFrag++
        }
    }

    /// Record an inbound node packet of [size] bytes on [link].
    fun onRx(link: Long, size: Int) {
        val c = byLink.getOrPut(link) { LinkCounters() }
        c.rxBytes += size
        c.rxPkts++
    }

    fun counters(link: Long): LinkCounters? = byLink[link]

    /// The LINKFLOW line for one link (null if unknown), in the exact grammar the critic parses.
    fun line(link: Long): String? {
        val c = byLink[link] ?: return null
        val t = xportOf[link] ?: "?"
        return "LINKFLOW link=$link xport=$t tx=${c.txBytes} rx=${c.rxBytes} " +
            "txpkts[hs=${c.txHs} data=${c.txData} frag=${c.txFrag}] rxpkts=${c.rxPkts}"
    }

    /// One LINKFLOW line per known link (for the periodic tick emit). Empty when there are no links.
    fun lines(): List<String> = byLink.keys.mapNotNull { line(it) }
}

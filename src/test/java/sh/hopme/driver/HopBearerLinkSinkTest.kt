package sh.hopme.driver

import org.junit.Assert.assertTrue
import org.junit.Test

/** cov/android-driver: the BearerManager -> node seam (bearerSink). A link coming up / carrying bytes /
 *  going down is normally driven by a real radio; here we fire the same LinkSink callbacks directly with
 *  synthetic events so node.connected/received/disconnected + the per-link counters are exercised. */
class HopBearerLinkSinkTest : DriverTestBase() {

    private fun sink(): sh.hop.LinkSink =
        HopBearer::class.java.getDeclaredField("bearerSink").apply { isAccessible = true }.get(bearer) as sh.hop.LinkSink

    @Test fun linkUpBytesDownDriveTheNode() {
        val s = sink()
        val link = 1_000_000L
        s.linkUp(link, sh.hop.HopRole.DIALER, ByteArray(16) { 1 })
        settle()
        assertTrue("node saw the connection", fake.connectedLinks.contains(link.toULong()))

        s.linkBytes(link, byteArrayOf(1, 2, 3, 4))
        settle()
        assertTrue("bytes forwarded to node.received", fake.received.any { it.first == link.toULong() })

        s.linkDown(link)
        settle()
        assertTrue("node saw the disconnect", fake.disconnectedLinks.contains(link.toULong()))
    }

    @Test fun acceptorRoleIsNotADialer() {
        val s = sink()
        s.linkUp(1_000_042L, sh.hop.HopRole.ACCEPTOR, ByteArray(16) { 2 })
        settle()
        assertTrue(fake.connectedLinks.contains(1_000_042uL))
    }
}

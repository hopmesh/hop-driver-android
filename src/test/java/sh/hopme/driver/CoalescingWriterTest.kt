package sh.hopme.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class CoalescingWriterTest {
    @Test fun burstCoalescesToTheLatestPendingSnapshot() {
        val writer = CoalescingWriter()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writes = Collections.synchronizedList(mutableListOf<Int>())
        writer.submit("messages") {
            started.countDown()
            release.await()
            writes.add(0)
        }
        started.await()
        for (value in 1..100) writer.submit("messages") { writes.add(value) }
        release.countDown()
        writer.flush()
        writer.close()
        assertEquals(listOf(0, 100), writes)
    }

    @Test fun allMirrorTypesShareOneSerializedWriter() {
        val writer = CoalescingWriter()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        for (key in listOf("messages", "contacts", "channels")) {
            writer.submit(key) {
                val now = active.incrementAndGet()
                maximum.updateAndGet { maxOf(it, now) }
                Thread.sleep(10)
                active.decrementAndGet()
            }
        }
        writer.flush()
        writer.close()
        assertTrue(maximum.get() == 1)
    }

    @Test fun continuousUpdatesCannotMoveCompactionPastTheFirstDirtyDeadline() {
        var now = 10_000L
        val deadline = CompactionDeadline { now }
        assertEquals(1_000L, deadline.nextDelayMs(1_000, 5_000))
        now = 12_000
        assertEquals(1_000L, deadline.nextDelayMs(1_000, 5_000))
        now = 14_750
        assertEquals(250L, deadline.nextDelayMs(1_000, 5_000))
        now = 15_000
        assertEquals(0L, deadline.nextDelayMs(1_000, 5_000))
        deadline.clear()
        now = 20_000
        assertEquals(1_000L, deadline.nextDelayMs(1_000, 5_000))
    }
}

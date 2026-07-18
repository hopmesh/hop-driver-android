package sh.hopme.driver

import java.util.LinkedHashMap
import java.util.concurrent.Executors

/** Tracks a coalesced write's deadline from its first dirty update, not its latest replacement. */
internal class CompactionDeadline(private val clockMs: () -> Long = System::currentTimeMillis) {
    private var firstDirtyMs: Long? = null

    @Synchronized
    fun nextDelayMs(debounceMs: Long, maximumDelayMs: Long): Long {
        val now = clockMs()
        val first = firstDirtyMs ?: now.also { firstDirtyMs = it }
        return minOf(debounceMs, (maximumDelayMs - (now - first)).coerceAtLeast(0L))
    }

    @Synchronized fun clear() { firstDirtyMs = null }
}

/** One bounded serial writer. Pending work is last-write-wins per mirror key. */
internal class CoalescingWriter(private val maximumKeys: Int = 4) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "hop-mirror-writer").apply { isDaemon = true }
    }
    private val pending = LinkedHashMap<String, () -> Unit>()
    private var draining = false

    fun submit(key: String, action: () -> Unit) {
        synchronized(pending) {
            require(key in pending || pending.size < maximumKeys) { "mirror writer key limit exceeded" }
            pending[key] = action
            if (!draining) {
                draining = true
                executor.execute(::drain)
            }
        }
    }

    fun <T> runNow(key: String, action: () -> T): T {
        synchronized(pending) { pending.remove(key) }
        return executor.submit<T> { action() }.get()
    }

    fun flush() {
        executor.submit {}.get()
    }

    private fun drain() {
        while (true) {
            val action = synchronized(pending) {
                val entry = pending.entries.firstOrNull()
                if (entry == null) {
                    draining = false
                    null
                } else {
                    pending.remove(entry.key)
                    entry.value
                }
            } ?: return
            runCatching(action)
        }
    }

    override fun close() {
        flush()
        executor.shutdown()
    }
}

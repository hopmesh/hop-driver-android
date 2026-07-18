package sh.hopme.driver

/** Small synchronized access-order maps for sender-controlled metadata. */
internal class BoundedLruMap<K, V>(private val maximum: Int) {
    private val values = LinkedHashMap<K, V>(16, 0.75f, true)

    @Synchronized operator fun get(key: K): V? = values[key]

    @Synchronized
    operator fun set(key: K, value: V) {
        values[key] = value
        while (values.size > maximum) values.entries.iterator().run { next(); remove() }
    }

    @Synchronized fun size(): Int = values.size
    @Synchronized fun snapshot(): Map<K, V> = LinkedHashMap(values)
}

internal class BoundedLruSet<K>(private val maximum: Int) {
    private val values = LinkedHashMap<K, Unit>(16, 0.75f, true)

    @Synchronized
    fun add(value: K): Boolean {
        if (values.remove(value) != null) {
            values[value] = Unit
            return false
        }
        values[value] = Unit
        while (values.size > maximum) values.entries.iterator().run { next(); remove() }
        return true
    }

    @Synchronized fun remove(value: K): Boolean = values.remove(value) != null
    @Synchronized operator fun contains(value: K): Boolean = values[value] != null
    @Synchronized fun replaceAll(replacement: Iterable<K>) {
        values.clear()
        replacement.forEach(::add)
    }
    @Synchronized fun size(): Int = values.size
}

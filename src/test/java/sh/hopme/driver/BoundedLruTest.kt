package sh.hopme.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoundedLruTest {
    @Test fun uniqueSendersCannotGrowMetadataPastTheCapAndRecentEntriesSurvive() {
        val map = BoundedLruMap<Int, String>(1_000)
        repeat(10_000) { map[it] = "peer-$it" }
        assertEquals(1_000, map.size())
        assertNull(map[0])
        assertEquals("peer-9999", map[9_999])

        val touched = BoundedLruMap<Int, String>(3)
        touched[1] = "one"; touched[2] = "two"; touched[3] = "three"
        assertEquals("one", touched[1])
        touched[4] = "four"
        assertEquals(3, touched.size())
        assertNull(touched[2])
        assertEquals(setOf(1, 3, 4), touched.snapshot().keys)

        val set = BoundedLruSet<Int>(2)
        set.add(1); set.add(2); set.add(1); set.add(3)
        assertEquals(2, set.size())
        assertEquals(false, set.remove(2))
        assertEquals(true, set.remove(1))
        set.replaceAll(0 until 10_000)
        assertEquals(2, set.size())
        assertEquals(false, 0 in set)
        assertEquals(true, 9_999 in set)
    }
}

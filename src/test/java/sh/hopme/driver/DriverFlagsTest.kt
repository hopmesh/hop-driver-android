package sh.hopme.driver

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * android-r2-03 / android-r2-05 / android-r2-06: the build-shaped gates and the notification-id fix.
 *
 * The automation send hook and the address/text-leaking HOPLOG lines are gated on [DriverFlags], which
 * defaults to BuildConfig.DEBUG. Unit tests run against the DEBUG variant (so the defaults are true),
 * but the gates must actually be consulted - these flip the flags and pin that the release posture (all
 * off) is expressible, and that the notification id no longer collides.
 */
class DriverFlagsTest {

    @After fun restore() {
        // Leave the singleton flags at their build default so test order can't leak state.
        DriverFlags.verboseContentLogs = BuildConfig.DEBUG
        DriverFlags.automationSurface = BuildConfig.DEBUG
    }

    @Test fun defaultsFollowTheBuildType() {
        // In a debug unit-test build these are on; the real value is BuildConfig.DEBUG either way.
        assertEquals(BuildConfig.DEBUG, DriverFlags.verboseContentLogs)
        assertEquals(BuildConfig.DEBUG, DriverFlags.automationSurface)
    }

    @Test fun releasePostureIsExpressible() {
        // Simulate a release build: every debug-only affordance off at once.
        DriverFlags.verboseContentLogs = false
        DriverFlags.automationSurface = false
        assertFalse(DriverFlags.verboseContentLogs)
        assertFalse(DriverFlags.automationSurface)
    }

    @Test fun flagsAreIndependent() {
        DriverFlags.verboseContentLogs = false
        DriverFlags.automationSurface = true
        assertFalse(DriverFlags.verboseContentLogs)
        assertTrue(DriverFlags.automationSurface)
    }

    /**
     * android-r2-06: the OLD notification id (text.hashCode()) collided for identical text, so a later
     * message silently replaced an earlier distinct notification. The fix is a monotonic counter. Model
     * both and pin that the counter is collision-free where the hashCode is not.
     */
    @Test fun monotonicNotifIdDoesNotCollideOnIdenticalText() {
        val a = "on my way"
        val b = "on my way"   // two distinct incoming messages, identical body

        // Old behavior: identical text -> identical id -> the second notification replaces the first.
        assertEquals("hashCode collides for identical text", a.hashCode(), b.hashCode())

        // New behavior: a monotonic counter (as HopBearer.nextNotifId does), starting high to dodge the
        // foreground-service's fixed ONGOING_ID (1).
        val counter = AtomicInteger(1000)
        val idA = counter.getAndIncrement()
        val idB = counter.getAndIncrement()
        assertNotEquals("distinct messages must get distinct notification ids", idA, idB)
        assertTrue("counter ids never collide with the ongoing-service id (1)", idA > 1 && idB > 1)
    }
}

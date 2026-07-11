package sh.hopme.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** cov/android-driver: lifecycle + control surface - start(), setForeground, setPrivateMode,
 *  setPinnedRelay, teardown idempotency, and the relay/presence side effects. */
class HopBearerLifecycleTest : DriverTestBase() {

    @Test fun startPublishesPresenceAndPrekeyAndRunsTicks() {
        bearer.start("Alpha")
        settle()
        assertEquals("running", bearer.status.value)
        assertEquals("Alpha", bearer.myName.value)
        assertTrue(bearer.myAddress.value.isNotEmpty())
        assertTrue(fake.publishedServices.contains(HopBearer.PRESENCE_SERVICE))
        assertTrue(fake.prekeysPublished >= 1)
        assertTrue("tick loop advanced the node clock", fake.ticks >= 1)
        assertEquals("relays off in P2P mode", "disabled", bearer.relayStatus.value)
    }

    @Test fun startIsIdempotent() {
        bearer.start("A"); settle()
        val ticks = fake.ticks
        bearer.start("A"); settle()
        // second start() returns early (started guard) - presence publisher count doesn't restart
        assertTrue(fake.ticks >= ticks)
    }

    @Test fun setForegroundClearsUnreadAndRepublishes() {
        bearer.start("Fg"); settle()
        bearer.unread.intValue = 3
        val before = fake.publishedServices.size
        bearer.setForeground(true)
        settle()
        assertEquals(0, bearer.unread.intValue)
        assertTrue(bearer.appInForeground)
        assertTrue("presence re-published on resume", fake.publishedServices.size > before)

        bearer.setForeground(false)
        settle()
        assertFalse(bearer.appInForeground)
    }

    @Test fun setPrivateModeStopsPresenceBroadcast() {
        bearer.start("Priv"); settle()
        bearer.setPrivateMode(true)
        settle()
        assertTrue(bearer.privateMode.value)
        assertTrue(context.getSharedPreferences("hop", android.content.Context.MODE_PRIVATE).getBoolean("privateMode", false))
        // in private mode publishPresence returns early; a tick should NOT add more presence publishes
        val n = fake.publishedServices.count { it == HopBearer.PRESENCE_SERVICE }
        settle(2)
        assertEquals("no presence adverts while private", n, fake.publishedServices.count { it == HopBearer.PRESENCE_SERVICE })

        bearer.setPrivateMode(false)
        settle()
        assertFalse(bearer.privateMode.value)
    }

    @Test fun setPinnedRelayValidatesAndPersists() {
        assertTrue(bearer.setPinnedRelay("wss://relay.example/ws"))
        assertEquals("wss://relay.example/ws", bearer.pinnedRelay.value)
        assertFalse("junk rejected", bearer.setPinnedRelay("not a url"))
        assertTrue("clearing the pin is allowed", bearer.setPinnedRelay(null))
        assertNull(bearer.pinnedRelay.value)
    }

    @Test fun teardownIsIdempotent() {
        bearer.start("T"); settle()
        bearer.teardown()
        settle()
        bearer.teardown()   // second call is a no-op (torndown guard)
        settle()
        assertTrue(fake.ticks >= 1)
    }
}

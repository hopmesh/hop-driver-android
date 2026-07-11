package sh.hopme.driver

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * quality-cov: the host-supplied [HopConfig] value + its relay-flag single-source-of-truth pref name.
 * The pure resolution rule ([HopConfig.resolveRelaysEnabled]) is pinned separately (RelayFlagTest);
 * here we cover the config value itself: the deployed-off relay default (android-09), the DEFAULT_RELAY
 * fallback, and that the explicit fields survive construction. Pure JVM (no Context).
 */
class HopConfigTest {

    private fun cfg(
        relayUrl: String = HopBearer.DEFAULT_RELAY,
        relaysEnabled: Boolean = false,
        dbKey: ByteArray = ByteArray(0),
    ) = HopConfig(
        dbPath = "/data/hop.db",
        identitySecret = ByteArray(32) { 1 },
        appSecret = ByteArray(32) { 2 },
        deviceName = "Pixel 7",
        relayUrl = relayUrl,
        relaysEnabled = relaysEnabled,
        notificationIcon = 123,   // explicit so the default (android.R.drawable...) is not read under the stub
        dbKey = dbKey,
    )

    @Test fun defaultsAreTheDeployedOffFleetPosture() {
        val c = cfg()
        assertFalse("relays default OFF to match relays_enabled=false (android-09)", c.relaysEnabled)
        assertEquals("wss://relay.hopme.sh/", c.relayUrl)
        assertEquals(0, c.dbKey.size)   // empty dbKey => open plain unless the host supplies one
    }

    @Test fun explicitFieldsSurviveConstruction() {
        val key = ByteArray(32) { (it * 3).toByte() }
        val c = cfg(relayUrl = "wss://pin.example.com/", relaysEnabled = true, dbKey = key)
        assertEquals("/data/hop.db", c.dbPath)
        assertEquals("Pixel 7", c.deviceName)
        assertEquals("wss://pin.example.com/", c.relayUrl)
        assertTrue(c.relaysEnabled)
        assertArrayEquals(key, c.dbKey)
        assertArrayEquals(ByteArray(32) { 1 }, c.identitySecret)
        assertArrayEquals(ByteArray(32) { 2 }, c.appSecret)
        assertEquals(123, c.notificationIcon)
    }

    @Test fun copyOverridesOneFieldAndKeepsTheRest() {
        val base = cfg()
        val flipped = base.copy(relaysEnabled = true)
        assertTrue(flipped.relaysEnabled)
        assertEquals(base.dbPath, flipped.dbPath)
        assertEquals(base.relayUrl, flipped.relayUrl)
    }

    @Test fun relaysEnabledPrefKeyIsTheSingleSource() {
        // The one persisted key both init paths read; a rename would silently split activity vs service.
        assertEquals("relaysEnabled", HopConfig.RELAYS_ENABLED_PREF)
    }
}

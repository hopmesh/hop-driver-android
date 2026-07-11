package sh.hopme.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** cov/android-driver: HopConfig.Companion - the single-source-of-truth relay flag (android-r2-02) and
 *  device-name resolution. HopConfig.default() is deliberately NOT exercised: it calls
 *  HopBearer.deviceSeed/dbKey -> KeystoreSecret (AndroidKeyStore), the device-gated path. */
class HopConfigCompanionTest : DriverTestBase() {

    @Test fun resolveRelaysEnabledPrefersStoredOverFallback() {
        assertTrue(HopConfig.resolveRelaysEnabled(stored = true, fallback = false))
        assertFalse(HopConfig.resolveRelaysEnabled(stored = false, fallback = true))
        assertTrue("null stored falls back", HopConfig.resolveRelaysEnabled(stored = null, fallback = true))
        assertFalse(HopConfig.resolveRelaysEnabled(stored = null, fallback = false))
    }

    @Test fun relaysEnabledRoundTripsThroughPrefs() {
        assertFalse("defaults to the (false) fallback", HopConfig.relaysEnabled(context))
        HopConfig.persistRelaysEnabled(context, true)
        assertTrue("persisted choice wins", HopConfig.relaysEnabled(context))
        HopConfig.persistRelaysEnabled(context, false)
        assertFalse(HopConfig.relaysEnabled(context))
    }

    @Test fun deviceNameFallsBackToModel() {
        // Robolectric leaves Settings.Global.DEVICE_NAME unset, so it falls back to Build.MODEL.
        val name = HopConfig.deviceName(context)
        assertTrue("a non-blank device name is always produced", name.isNotBlank())
    }

    @Test fun bothInitPathsAgreeByConstruction() {
        // the historical bug was two hardcoded literals; now both read the SAME persisted value.
        HopConfig.persistRelaysEnabled(context, true)
        val a = HopConfig.relaysEnabled(context, fallback = false)
        val b = HopConfig.relaysEnabled(context, fallback = true)
        assertEquals(a, b)
        assertTrue(a)
    }
}

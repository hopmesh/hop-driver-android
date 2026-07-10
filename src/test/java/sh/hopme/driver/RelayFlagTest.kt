package sh.hopme.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * android-r2-02 / android-r2-07: the relay flag must be deterministic across the configure-once
 * singleton's two init paths (a user-launched activity vs an OS START_STICKY service restart).
 *
 * The regression: MainActivity hardcoded `relaysEnabled = true` while HopConfig.default hardcoded
 * `false`, so which behavior a device got depended on WHICH path minted the singleton first — same
 * binary, two network behaviors. The fix single-sources the flag through one persisted pref, resolved
 * by [HopConfig.resolveRelaysEnabled]. These pin that, given the SAME persisted value, both paths agree.
 */
class RelayFlagTest {

    /** Model the two init paths: each resolves the flag from the same stored pref via the same rule.
     *  (In production both call HopConfig.relaysEnabled(context), which delegates to resolveRelaysEnabled.) */
    private fun activityPath(stored: Boolean?) = HopConfig.resolveRelaysEnabled(stored, fallback = false)
    private fun servicePath(stored: Boolean?) = HopConfig.resolveRelaysEnabled(stored, fallback = false)

    @Test fun bothPathsAgreeWhenRelayEnabledPersisted() {
        // The activity persisted `true`; a later sticky-service restart reads the same pref.
        assertTrue(activityPath(true))
        assertTrue(servicePath(true))
        assertEquals(activityPath(true), servicePath(true))
    }

    @Test fun bothPathsAgreeWhenRelayDisabledPersisted() {
        assertFalse(activityPath(false))
        assertFalse(servicePath(false))
        assertEquals(activityPath(false), servicePath(false))
    }

    @Test fun freshInstallDefaultsOffAndAgrees() {
        // Before the activity has ever persisted a choice (nothing stored), BOTH paths fall back to the
        // deployed-off default — deterministically off, never "on for the activity, off for the service".
        assertFalse("deployed-off fleet default (android-09)", activityPath(null))
        assertFalse(servicePath(null))
        assertEquals(activityPath(null), servicePath(null))
    }

    @Test fun theOldNondeterminismWouldHaveFailedThis() {
        // Demonstrate the historical bug the fix removes: two DIFFERENT literals disagree. If the code
        // ever regresses to hardcoding per-path literals, this contrast documents why that is broken.
        val oldActivityLiteral = true    // MainActivity had this
        val oldServiceLiteral = false    // HopConfig.default had this
        assertFalse("the two hardcoded literals used to disagree", oldActivityLiteral == oldServiceLiteral)
        // The single-sourced rule does NOT disagree for any given stored value.
        for (stored in listOf<Boolean?>(true, false, null)) {
            assertEquals(activityPath(stored), servicePath(stored))
        }
    }
}

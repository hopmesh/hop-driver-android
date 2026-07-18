package sh.hopme.driver

import android.content.Context
import android.os.Build

/**
 * Host-supplied configuration for the Hop runtime. The driver owns the bearer/transports; the
 * embedding app owns identity, storage, the backbone endpoint, and presentation (notification
 * icon, display name). [default] reproduces the demo app's existing sources so a bare
 * `HopBearer.shared(context)` keeps its prior behavior.
 */
data class HopConfig(
    val dbPath: String,
    val identitySecret: ByteArray,
    val appSecret: ByteArray,
    val deviceName: String,
    val relayUrl: String = HopBearer.DEFAULT_RELAY,
    /** When false the bearer never dials the backbone relay - pure P2P (BLE/LAN/Wi-Fi Direct) only.
     *  android-09: defaults FALSE to match the deployed-off fleet (relays_enabled=false in infra), so a
     *  bare-service restart never wakes the radio every ~31s to dial a dead endpoint. A caller that
     *  wants the relay must opt in explicitly, and [HopBearer] still ANDs this with a runtime killswitch
     *  (the `relaysEnabled` pref) so the fleet can be turned off without an app update. */
    val relaysEnabled: Boolean = false,
    val notificationIcon: Int = android.R.drawable.ic_dialog_email,
    /** 32-byte SQLCipher key for `hop.db` at rest (F-25). Empty = open plain. Only encrypts when
     *  libhop is built `--features sqlcipher`. Defaults to the device-derived [HopBearer.dbKey]. */
    val dbKey: ByteArray = ByteArray(0),
    /** Optional base-URL override for the HNS reach-record fetch (§30). Empty = production: resolve a
     *  name by fetching `https://<domain>/.well-known/hop`, where the domain's own TLS cert proves the
     *  domain and the served reach record self-certifies the address. Set it (e.g. to a MockWebServer,
     *  loopback server) to fetch `<base>/.well-known/hop` instead, so the unit suite exercises the
     *  fetch path without real network / a real cert. Non-loopback overrides are rejected. */
    val hnsResolverBase: String = "",
) {
    companion object {
        /// android-r2-02: the ONE persisted pref both init paths read for the relay flag, so a
        /// user-launched activity and an OS-driven START_STICKY service restart agree on whether relays
        /// run. Lives in the "hop" prefs (the same file [HopBearer] reads the runtime killswitch from).
        const val RELAYS_ENABLED_PREF = "relaysEnabled"

        /// android-r2-02: the single source of truth for the relay flag. Both [MainActivity] and
        /// [default] resolve it here instead of hardcoding conflicting literals (the activity had `true`,
        /// [default] had `false`), which made relay behavior depend on WHICH path minted the configure-
        /// once singleton first. Defaults to [fallback] (false = deployed-off fleet) until the app
        /// persists an explicit choice via [persistRelaysEnabled].
        fun relaysEnabled(context: Context, fallback: Boolean = false): Boolean {
            val prefs = context.getSharedPreferences("hop", Context.MODE_PRIVATE)
            val stored = if (prefs.contains(RELAYS_ENABLED_PREF)) prefs.getBoolean(RELAYS_ENABLED_PREF, fallback) else null
            return resolveRelaysEnabled(stored, fallback)
        }

        /// Pure resolution rule for the relay flag (android-r2-02), split out so the "both init paths
        /// agree" invariant is unit-testable without a Context: use the persisted choice when present,
        /// else the fallback. Because BOTH paths feed the SAME persisted value in, they agree by
        /// construction - the historical bug was two different hardcoded literals, not this rule.
        fun resolveRelaysEnabled(stored: Boolean?, fallback: Boolean): Boolean = stored ?: fallback

        /// android-r2-02: persist the app's relay choice so BOTH init paths pick it up. Call this before
        /// building the config so the sticky-service restart sees the same value the activity chose.
        fun persistRelaysEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences("hop", Context.MODE_PRIVATE)
                .edit().putBoolean(RELAYS_ENABLED_PREF, enabled).apply()
        }

        /// Build a config from the values the demo app previously hard-coded: the device-derived
        /// identity seed (§4), the shared dev app secret, the user-assigned device name (falling
        /// back to the marketing model), and on-device storage for chat history.
        /// android-r2-02: relaysEnabled comes from the shared pref (single source of truth), so a
        /// bare-service restart agrees with the activity instead of defaulting to a different literal.
        fun default(context: Context): HopConfig = HopConfig(
            dbPath = java.io.File(context.filesDir, "hop.db").absolutePath,
            identitySecret = HopBearer.deviceSeed(context),
            appSecret = HopBearer.APP_SECRET,
            deviceName = deviceName(context),
            relaysEnabled = relaysEnabled(context),
            dbKey = HopBearer.dbKey(context),
        )

        /// Prefer the user-assigned device name (e.g. "Jason's Pixel") - gettable on Android via
        /// Settings.Global.DEVICE_NAME - over the generic marketing model (Build.MODEL).
        fun deviceName(context: Context): String {
            val userName = runCatching {
                android.provider.Settings.Global.getString(
                    context.contentResolver, android.provider.Settings.Global.DEVICE_NAME
                )
            }.getOrNull()
            return userName?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Android"
        }
    }
}

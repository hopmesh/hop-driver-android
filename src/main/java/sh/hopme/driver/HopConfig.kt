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
    /** When false the bearer never dials the backbone relay — pure P2P (BLE/LAN/Wi-Fi Direct) only.
     *  android-09: defaults FALSE to match the deployed-off fleet (relays_enabled=false in infra), so a
     *  bare-service restart never wakes the radio every ~31s to dial a dead endpoint. A caller that
     *  wants the relay must opt in explicitly, and [HopBearer] still ANDs this with a runtime killswitch
     *  (the `relaysEnabled` pref) so the fleet can be turned off without an app update. */
    val relaysEnabled: Boolean = false,
    val notificationIcon: Int = android.R.drawable.ic_dialog_email,
    /** 32-byte SQLCipher key for `hop.db` at rest (F-25). Empty = open plain. Only encrypts when
     *  libhop is built `--features sqlcipher`. Defaults to the device-derived [HopBearer.dbKey]. */
    val dbKey: ByteArray = ByteArray(0),
) {
    companion object {
        /// Build a config from the values the demo app previously hard-coded: the device-derived
        /// identity seed (§4), the shared dev app secret, the user-assigned device name (falling
        /// back to the marketing model), and on-device storage for chat history.
        fun default(context: Context): HopConfig = HopConfig(
            dbPath = java.io.File(context.filesDir, "hop.db").absolutePath,
            identitySecret = HopBearer.deviceSeed(context),
            appSecret = HopBearer.APP_SECRET,
            deviceName = deviceName(context),
            dbKey = HopBearer.dbKey(context),
        )

        /// Prefer the user-assigned device name (e.g. "Jason's Pixel") — gettable on Android via
        /// Settings.Global.DEVICE_NAME — over the generic marketing model (Build.MODEL).
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

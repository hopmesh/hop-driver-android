package sh.hopme.driver

/**
 * Build-shaped feature flags for the driver, split out of [HopBearer] so the gating logic is pure and
 * unit-testable on a plain JVM (android-r2-01/-05/-07). Nothing here touches Android APIs.
 *
 * The flags below default to [BuildConfig.DEBUG]. In a RELEASE build both are false, so:
 *   - android-r2-05: no verbose HOPLOG lines that print full base58 addresses or cleartext message text.
 *   - android-r2-03: the send-as-user automation hook is inert (defense in depth beside the manifest).
 *
 * (android-r2-01's plaintext-mirror defect is fixed by ENCRYPTING the mirrors at rest with the
 * Keystore-wrapped db key via [MirrorCrypto], NOT by a debug flag — so history survives in both debug
 * and release while a forensic/backup extract can no longer read cleartext bodies or the address book.)
 *
 * These are `@JvmStatic var` on a singleton so a JVM unit test can flip them to exercise BOTH the
 * release (off) and debug (on) code paths without an instrumented device.
 */
object DriverFlags {
    /** android-r2-05: emit HOPLOG lines carrying full addresses + cleartext message text. Debug only. */
    @JvmStatic var verboseContentLogs: Boolean = BuildConfig.DEBUG

    /** android-r2-03: honor the hopdemo:// automation send hook. Debug only (mirrors iOS `#if DEBUG`). */
    @JvmStatic var automationSurface: Boolean = BuildConfig.DEBUG
}

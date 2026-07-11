package sh.hopme.driver

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Duration

/**
 * cov/android-driver: shared Robolectric scaffolding for the HopBearer instance suites. Builds a driver
 * around a [FakeHopNode] via HopBearer's internal test constructor, and drives its serial core
 * HandlerThread + the main looper deterministically with ShadowLooper (no device, no real radios).
 */
@RunWith(RobolectricTestRunner::class)
abstract class DriverTestBase {
    protected lateinit var context: Context
    protected lateinit var fake: FakeHopNode
    protected lateinit var bearer: HopBearer
    private val extraBearers = mutableListOf<HopBearer>()

    @Before fun baseSetUp() {
        // Both flags are BuildConfig.DEBUG-defaulted; pin them ON so the suite behaves the same whether
        // it runs under the debug or release unit-test variant.
        DriverFlags.automationSurface = true
        DriverFlags.verboseContentLogs = true
        context = ApplicationProvider.getApplicationContext()
        // A clean slate so persisted mirrors from a prior test don't bleed in.
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        context.getSharedPreferences("hop", Context.MODE_PRIVATE).edit().clear().commit()
        fake = FakeHopNode()
        bearer = newBearer(fake)
    }

    @After fun baseTearDown() {
        // teardown() stops the bearers synchronously and posts a quitSafely to the core looper; drain it
        // tolerantly (idling a looper mid-quit throws "Looper is quitting", which is expected here). Every
        // bearer a test built is torn down so no BleBearer status-executor thread leaks and wedges the JVM.
        extraBearers.distinct().forEach { b ->
            runCatching { b.teardown() }
            repeat(2) {
                runCatching { shadowOf(b.coreLooper).idle() }
                runCatching { shadowOf(Looper.getMainLooper()).idle() }
            }
        }
    }

    /** Build a driver (the base one, or a "restart" reading the same mirrors) and register it so every
     *  bearer - hence every BleBearer status-executor thread - is torn down at the end of the test. */
    protected fun newBearer(node: FakeHopNode, cfg: HopConfig = defaultConfig()): HopBearer =
        HopBearer(context, cfg, node).also { extraBearers.add(it) }

    protected fun defaultConfig(dbKey: ByteArray = ByteArray(0)): HopConfig = HopConfig(
        dbPath = File(context.filesDir, "hop-test.db").absolutePath,
        identitySecret = ByteArray(32) { 1 },
        appSecret = ByteArray(32) { 0x48 },
        deviceName = "TestDroid",
        relaysEnabled = false,
        dbKey = dbKey,
    )

    /** Run any main-looper posts (the driver marshals Compose-state updates - messages.add, stampSent,
     *  peers/queue updates - to main via onUi{}). */
    protected fun idleMain() { runCatching { shadowOf(Looper.getMainLooper()).idle() } }

    /** Flush [bearer]'s core (background) + the main looper, advancing time enough to run the delayed
     *  refresh (250ms) and persistence (1000ms) posts. Drains main BEFORE each core time-step so a
     *  main-posted messages.add lands before the delayed writeMessages on core snapshots the list (else
     *  the mirror is written empty). */
    protected fun settle(rounds: Int = 8) = settleOn(bearer, rounds)

    protected fun settleOn(b: HopBearer, rounds: Int = 8) {
        repeat(rounds) {
            // Tolerant of a looper that a teardown() is quitting (idling mid-quit throws by design).
            runCatching { shadowOf(Looper.getMainLooper()).idle() }
            // Commit Compose snapshot writes made on main so the core thread's messages.toList() sees them
            // (no Compose runtime pumps notifications in a unit test).
            runCatching { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
            runCatching { shadowOf(b.coreLooper).idleFor(Duration.ofMillis(400)) }
        }
        runCatching { shadowOf(Looper.getMainLooper()).idle() }
        runCatching { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
    }

    /** Wait for a mirror file that a background `thread { ... }` writes (save-messages/-contacts). */
    protected fun awaitFile(f: File, timeoutMs: Long = 4000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (f.exists() && f.length() > 0) return true
            Thread.sleep(20)
        }
        return f.exists() && f.length() > 0
    }

    /** Wait until a PLAINTEXT mirror file actually contains [needle] - deterministic against the
     *  debounced writeMessages/save-thread timing (a bare exists() can catch an as-yet-empty write). */
    protected fun awaitFileContains(f: File, needle: String, timeoutMs: Long = 4000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (f.exists() && runCatching { String(f.readBytes()).contains(needle) }.getOrDefault(false)) return true
            Thread.sleep(20)
        }
        return false
    }

    protected fun filesFile(name: String) = File(context.filesDir, name)
}

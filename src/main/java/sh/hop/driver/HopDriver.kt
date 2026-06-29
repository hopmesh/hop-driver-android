// HopDriver — thin Android glue: composes a libhop node + HopRuntime + the bearers this build wants,
// and runs the pump loop. Mirror of drivers/apple/HopDriver. No transport/beacon code — just wiring;
// every radio (incl. the BLE iBeacon emit) lives in its own bearer module. The app owns identity/db
// config + the UI; this owns the node+bearer wiring + libhop.so is loaded by sh.hop.HopNode.

package sh.hop.driver

import android.content.Context
import sh.hop.HopNode
import sh.hop.HopRuntime
import sh.hop.randomNodeId
import net.waldrip.hop.bearers.ble.BleBearer
import net.waldrip.hop.bearers.lan.LanBearer
import net.waldrip.hop.bearers.relay.RelayBearer
import net.waldrip.hop.bearers.wifidirect.WifiDirectBearer
import java.util.Timer
import kotlin.concurrent.timerTask

class HopDriver(context: Context, config: Config) {
    data class Config(
        val dbPath: String,
        val secret: ByteArray = ByteArray(0),
        val appSecret: ByteArray = ByteArray(0),
        val relayUrl: String? = null,
        val enableBle: Boolean = true,
        val enableLan: Boolean = true,
        val enableWifiDirect: Boolean = true,
    )

    /** One transport-layer id shared by every bearer (the HELLO id + dedup tiebreaker). */
    private val bearerId = randomNodeId()
    val runtime: HopRuntime
    private var pump: Timer? = null

    init {
        val node = HopNode.open(config.dbPath, config.secret, config.appSecret) ?: HopNode.ephemeral()
        runtime = HopRuntime(node)
        if (config.enableBle) runtime.register(BleBearer(context, bearerId))
        if (config.enableLan) runtime.register(LanBearer(context, bearerId))
        if (config.enableWifiDirect) runtime.register(WifiDirectBearer(context, bearerId))
        config.relayUrl?.let { runtime.register(RelayBearer(it)) }
    }

    /** The node, for messaging (send/inbox/hops://) + identity. */
    val node get() = runtime.node

    /** Start the bearers + a ~10 Hz pump that advances the clock and drains the node to the radios. */
    fun start() {
        runtime.start()
        pump = Timer("hop-pump", true).also {
            it.scheduleAtFixedRate(timerTask {
                runtime.tick(System.currentTimeMillis())
                runtime.pump()
            }, 0, 100)
        }
    }

    fun stop() {
        pump?.cancel(); pump = null
        runtime.stop()
    }
}

package sh.hopme.driver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Keeps Hop's BLE bearer alive in the background (DESIGN.md §22). A foreground
 * service with a persistent notification avoids background-scan throttling, so the
 * device keeps relaying and can receive while the app isn't on screen.
 */
class HopService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ONGOING_ID, ongoingNotification())
        // Prefer the user-assigned device name (e.g. "Jason's Pixel") — gettable on Android via
        // Settings.Global.DEVICE_NAME — over the generic marketing model (Build.MODEL).
        val userName = runCatching {
            android.provider.Settings.Global.getString(contentResolver, android.provider.Settings.Global.DEVICE_NAME)
        }.getOrNull()
        HopBearer.shared(this).start(userName?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Android")
        return START_STICKY
    }

    private fun ongoingNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ONGOING_CHANNEL, "Hop relay", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, ONGOING_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Hop is relaying nearby")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val ONGOING_ID = 1
        private const val ONGOING_CHANNEL = "hop.relay"

        fun start(context: Context) {
            val intent = Intent(context, HopService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

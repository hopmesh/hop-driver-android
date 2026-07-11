package sh.hopme.driver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.atomic.AtomicInteger

/**
 * Notifications concern, split out of [HopBearer] so message-notification posting + the icon badge live
 * in one cohesive unit instead of the god-object. Owns the message NotificationChannel, the incoming-
 * message notification, and the badge cancel. The driver composes this and calls [notify] from pump()
 * (background) with the already-updated unread count; every method is behavior-identical to the code
 * that previously lived inline in HopBearer.
 */
internal class Notifier(
    private val context: Context,
    private val channelId: String,
    private val icon: Int,
) {
    // android-r2-06: monotonic notification id so two messages with identical text (same or different
    // sender) don't collide on text.hashCode() and silently replace each other. Starts high to avoid
    // colliding with the foreground-service's fixed ONGOING_ID (1).
    private val nextNotifId = AtomicInteger(1000)

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Hop messages", NotificationManager.IMPORTANCE_DEFAULT)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    /// Post an incoming-message notification. [badgeCount] is the current unread count (the caller
    /// increments unread BEFORE calling this, matching the prior inline behavior) and drives the launcher
    /// icon badge.
    fun notify(from: String, text: String, badgeCount: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(icon)
            .setContentTitle(from)
            .setContentText(text)
            .setAutoCancel(true)
            .setNumber(badgeCount)   // drives the launcher icon badge count
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .build()
        // android-r2-06: unique per-notification id (was text.hashCode(), which collided across
        // messages with identical text, silently replacing an earlier distinct notification).
        NotificationManagerCompat.from(context).notify(nextNotifId.getAndIncrement(), n)
    }

    /// Clear every posted notification + the icon badge (the app returned to the foreground).
    fun cancelAll() { runCatching { NotificationManagerCompat.from(context).cancelAll() } }
}

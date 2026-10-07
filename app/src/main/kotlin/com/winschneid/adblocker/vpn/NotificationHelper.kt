package com.winschneid.adblocker.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.winschneid.adblocker.R
import com.winschneid.adblocker.ui.MainActivity

object NotificationHelper {
    const val CHANNEL_STATUS = "vpn_status"
    const val NOTIFICATION_ID = 1

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_STATUS,
            context.getString(R.string.notification_channel_status),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        manager.createNotificationChannel(channel)
    }

    fun buildStatusNotification(context: Context, stats: VpnStats, running: Boolean): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val openApp = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), flags)
        val stopIntent = Intent(context, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_STOP)
        val stop = PendingIntent.getService(context, 1, stopIntent, flags)
        val icon = Icon.createWithResource(context, R.drawable.ic_stat_shield)
        return Notification.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_shield)
            .setContentTitle(context.getString(if (running) R.string.notification_running else R.string.notification_starting))
            .setContentText(context.getString(R.string.notification_stats, stats.blockedQueries, stats.totalQueries))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(icon, context.getString(R.string.action_stop), stop).build())
            .build()
    }

    fun update(context: Context, notification: Notification) {
        context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Whether the status notification is currently on screen. It is gone once the user has swiped it away
     * (possible since Android 13) or has turned notifications off; posting an update would bring it back.
     */
    fun isShown(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return try {
            manager.activeNotifications.any { it.id == NOTIFICATION_ID }
        } catch (e: RuntimeException) {
            true // cannot tell: keep the previous behaviour of updating it
        }
    }
}

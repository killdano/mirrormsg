package com.dano.mirrormsg

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * Errors and status the watch should hear about, as a notification the Pebble app shows
 * on the watch: Pebble OS draws it, so the watch app keeps no screens of its own for
 * them. One at a time (a new one replaces the last), short, and never a message's text.
 */
object WatchNotice {
    private const val TAG = "WatchNotice"
    private const val CHANNEL_ID = "mirrormsg_watch_notices"
    private const val NOTIF_ID = 0x4400

    fun post(ctx: Context, title: String, text: String) {
        try {
            val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                // Default importance, so the Pebble app shows it; no sound on the phone.
                val ch = NotificationChannel(CHANNEL_ID, "Notices on the watch",
                    NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Why something picked on the watch didn't go"
                    setSound(null, null)
                }
                mgr.createNotificationChannel(ch)
            }
            val n = Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(ctx.resources.getIdentifier("ic_notification", "drawable", ctx.packageName)
                    .takeIf { it != 0 } ?: android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setTimeoutAfter(5 * 60_000L)
                .build()
            mgr.notify(NOTIF_ID, n)
            LogStore.i(TAG, "Notice: $title")
        } catch (e: Exception) {
            LogStore.e(TAG, "Couldn't post a watch notice", e)
        }
    }
}

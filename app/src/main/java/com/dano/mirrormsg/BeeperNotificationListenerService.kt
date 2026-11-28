package com.dano.mirrormsg

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlin.concurrent.thread

/**
 * Listens for notifications posted by the Beeper app and hands them to
 * [BeeperImages] to capture any attached image. This is the only on-device way
 * to get real image bytes for Beeper-bridged messages — the content provider
 * exposes only unresolvable `mxc://` handles.
 *
 * Lifecycle: Android binds this automatically once the user grants Notification
 * Access (the row in Settings → Permissions → "Notification access", which
 * deep-links to the system grant screen). Until granted, [onListenerConnected]
 * never fires and no callbacks arrive. We filter strictly to the Beeper package
 * and ignore everything else, with one exception: Calls from the watch (Calls.kt)
 * looks for the ongoing call notification of a call it started, only to press its
 * hang-up (Hang up from the watch) and to know when that call ended.
 *
 * Threading: callbacks arrive on the system binder thread; image decode + file
 * I/O is pushed onto a worker thread so we never block the binder.
 *
 * Modelled on MirrorMap Dash's MapsNotificationListenerService.
 */
class BeeperNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "MirrorMsgSpike"
        private const val BEEPER_PACKAGE = "com.beeper.android"

        // True while Android has this listener bound (i.e. access granted and
        // service connected). Lets the rest of the app know capture is live.
        @Volatile
        var connected: Boolean = false
            private set

        // The bound listener (null when not connected): Calls reads the active
        // notifications through it.
        @Volatile
        var instance: BeeperNotificationListenerService? = null
            private set
    }

    override fun onListenerConnected() {
        connected = true
        instance = this
        LogStore.i(TAG, "Beeper-Notif: listener connected")
    }

    override fun onListenerDisconnected() {
        connected = false
        instance = null
        LogStore.i(TAG, "Beeper-Notif: listener disconnected")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        Calls.onNotificationRemoved(Mirror.appContext ?: applicationContext, n)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (n.packageName != BEEPER_PACKAGE) return
        val ctx = Mirror.appContext ?: applicationContext
        thread(name = "beeper-notif") {
            BeeperImages.onBeeperNotification(ctx, n)
        }
    }
}

package com.dano.mirrormsg

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager

/**
 * Calls from the watch. The watch's reply menu shows a Call row on a chat [target] says
 * can be called (Schema.KEY_THREAD_CALL), and picking it (Schema.KEY_REQ_CALL) starts the
 * call here, on the phone: an ordinary phone call for a one-to-one text chat
 * (TelecomManager), or the network's own call screen for WhatsApp, Signal and Telegram
 * (the "voice call" entry each of those apps adds to a contact in the phone's contacts).
 * The sound stays on the phone, or a headset connected to it.
 *
 * Nothing about it is drawn on the watch: how it went is a notification, which the
 * Pebble app shows there (calling, with Hang up; or why it couldn't). Hang up presses
 * the hang-up of the call's own ongoing notification, which needs notification access.
 *
 * Off until switched on (Settings > Watch settings > Calls from the watch), which asks
 * for the phone-call and contacts permissions.
 */
object Calls {
    private const val TAG = "MirrorMsgCalls"
    private const val CHANNEL_ID = "mirrormsg_calls"
    private const val NOTIF_ID = 0x4300
    const val ACTION_HANG_UP = "com.dano.mirrormsg.action.CALL_HANG_UP"
    const val EXTRA_SETUP = "com.dano.mirrormsg.CALL_SETUP"
    const val EXTRA_ROW = "com.dano.mirrormsg.CALL_ROW"
    const val EXTRA_KIND = "com.dano.mirrormsg.CALL_KIND"

    enum class Kind { PHONE, WHATSAPP, SIGNAL, TELEGRAM }

    /** A chat that can be called: how, and who (a number, or the network's id for them). */
    data class Target(val kind: Kind, val key: String)

    /** A network app's contact entry for a voice call: its package and the entry's type. */
    private class App(val pkg: String, val mime: String, val label: String)

    private val APPS = mapOf(
        Kind.WHATSAPP to App("com.whatsapp",
            "vnd.android.cursor.item/vnd.com.whatsapp.voip.call", "WhatsApp"),
        Kind.SIGNAL to App(SignalService.SIGNAL_PACKAGE,
            "vnd.android.cursor.item/vnd.org.thoughtcrime.securesms.call", "Signal"),
        Kind.TELEGRAM to App("org.telegram.messenger",
            "vnd.android.cursor.item/vnd.org.telegram.messenger.android.call", "Telegram"),
    )

    /** The call this started that may still be under way (for Hang up), or null. */
    @Volatile private var live: Kind? = null

    private fun digits(s: String) = s.filter { it.isDigit() }

    /**
     * How chat [convoId] of account [serviceId] can be called, or null: only one-to-one
     * chats, and only with the switch on. A text chat needs the other person's number;
     * a WhatsApp chat its phone id; a Signal chat a number (Signal can hide it); a
     * Telegram chat is a person's (tg:user:<id>).
     */
    fun target(convoId: String, serviceId: String?): Target? {
        if (!Mirror.watchCalls || Mirror.isFeed(convoId)) return null
        val addr = Mirror.convoTargets[convoId]?.address.orEmpty().trim()
        return when {
            serviceId == "google" && digits(addr).length >= 7 -> Target(Kind.PHONE, addr)
            serviceId == "whatsapp" && convoId.endsWith("@s.whatsapp.net") ->
                Target(Kind.WHATSAPP, convoId.substringBefore('@'))
            serviceId == "signalnative" && addr.startsWith("+") && digits(addr).length >= 7 ->
                Target(Kind.SIGNAL, addr)
            convoId.startsWith("tg:user:") && digits(convoId).isNotEmpty() ->
                Target(Kind.TELEGRAM, convoId.removePrefix("tg:user:"))
            else -> null
        }
    }

    /** The watch picked Call on chat [convoId] (already checked to be the one on its screen). */
    fun start(ctx: Context, convoId: String, serviceId: String?) {
        val t = target(convoId, serviceId)
        if (t == null) {
            LogStore.w(TAG, "Call: this chat can't be called (${serviceId ?: "?"})")
            tell(ctx, "Can't call", "This chat can't be called from the watch.")
            return
        }
        if (t.kind == Kind.PHONE) placePhoneCall(ctx, t.key) else startAppCall(ctx, t)
    }

    private fun granted(ctx: Context, perm: String) =
        ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")   // checked just below
    private fun placePhoneCall(ctx: Context, number: String) {
        if (!granted(ctx, Manifest.permission.CALL_PHONE)) {
            LogStore.w(TAG, "Call: no phone-call permission")
            notifySetup(ctx, "Open MirrorMsg on your phone and allow phone calls.")
            return
        }
        try {
            val tm = ctx.getSystemService(TelecomManager::class.java)
            tm.placeCall(Uri.fromParts("tel", number, null), Bundle())
            live = Kind.PHONE
            LogStore.i(TAG, "Call: phone call placed")
            notifyCalling(ctx, "Calling on your phone")
        } catch (e: SecurityException) {
            LogStore.e(TAG, "Call: phone call refused", e)
            notifySetup(ctx, "Open MirrorMsg on your phone and allow phone calls.")
        } catch (e: Exception) {
            LogStore.e(TAG, "Call: phone call failed", e)
            tell(ctx, "Can't call", "The phone didn't start the call.")
        }
    }

    private fun installed(ctx: Context, pkg: String): Boolean =
        try { ctx.packageManager.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }

    /**
     * The network app's voice-call entry for [key] in the phone's contacts (its
     * ContactsContract.Data row id), or null. Each app keeps its own id for the person in
     * the entry's DATA1: WhatsApp "<number>@s.whatsapp.net", Signal the number, Telegram
     * the user id. Compared by digits, so a formatted number still matches.
     */
    private fun findRow(ctx: Context, mime: String, key: String): Long? {
        val want = digits(key.substringBefore('@'))
        if (want.isEmpty()) return null
        val proj = arrayOf(ContactsContract.Data._ID, ContactsContract.Data.DATA1)
        ctx.contentResolver.query(ContactsContract.Data.CONTENT_URI, proj,
            "${ContactsContract.Data.MIMETYPE} = ?", arrayOf(mime), null)?.use { c ->
            while (c.moveToNext()) {
                val d1 = c.getString(1) ?: continue
                if (digits(d1.substringBefore('@')) == want) return c.getLong(0)
            }
        }
        return null
    }

    private fun callIntent(app: App, row: Long): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, row), app.mime)
            setPackage(app.pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    private fun startAppCall(ctx: Context, t: Target) {
        val app = APPS[t.kind] ?: return
        if (!installed(ctx, app.pkg)) {
            LogStore.w(TAG, "Call: ${app.label} isn't installed")
            tell(ctx, "Can't call", "${app.label} isn't on this phone.")
            return
        }
        if (!granted(ctx, Manifest.permission.READ_CONTACTS)) {
            LogStore.w(TAG, "Call: no contacts permission")
            notifySetup(ctx, "Open MirrorMsg on your phone and allow contacts, for ${app.label} calls.")
            return
        }
        val row = try { findRow(ctx, app.mime, t.key) } catch (e: Exception) {
            LogStore.e(TAG, "Call: contacts lookup failed", e); null
        }
        if (row == null) {
            LogStore.w(TAG, "Call: no ${app.label} call entry for this chat in the contacts")
            tell(ctx, "Can't call",
                "${app.label} calls start from your phone's contacts. Save this person there and turn on contact sync in ${app.label}.")
            return
        }
        // Starting another app's screen from the background needs "Display over other
        // apps". Without it, the notification's Call action starts it instead (one more
        // press, on the watch or the phone).
        if (Settings.canDrawOverlays(ctx)) {
            try {
                ctx.startActivity(callIntent(app, row))
                live = t.kind
                LogStore.i(TAG, "Call: ${app.label} call started")
                notifyCalling(ctx, "Calling on ${app.label}")
                return
            } catch (e: Exception) {
                LogStore.e(TAG, "Call: ${app.label} refused the call", e)
            }
        }
        LogStore.i(TAG, "Call: ${app.label} call waits for a tap (no background start)")
        notifyTapToCall(ctx, t.kind, app, row)
    }

    // ---- Hang up --------------------------------------------------------------------

    /** The ongoing call notification of a call of [kind] (any app's for a phone call). */
    private fun isCall(ctx: Context, sbn: StatusBarNotification, kind: Kind?): Boolean {
        if (sbn.packageName == ctx.packageName) return false
        val pkg = kind?.let { APPS[it]?.pkg }
        if (pkg != null && sbn.packageName != pkg) return false
        val n = sbn.notification
        val callStyle = n.extras?.getString(Notification.EXTRA_TEMPLATE)?.endsWith("\$CallStyle") == true
        return (n.category == Notification.CATEGORY_CALL || callStyle) &&
            (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
    }

    private val HANG_UP_WORDS = listOf("hang up", "hangup", "end call", "end", "leave")

    // Notification.CallStyle keeps its hang-up under this key.
    @Suppress("DEPRECATION")
    private fun hangUpExtra(x: Bundle): PendingIntent? =
        if (Build.VERSION.SDK_INT >= 33) x.getParcelable("android.hangUpIntent", PendingIntent::class.java)
        else x.getParcelable<PendingIntent>("android.hangUpIntent")

    /** A call notification's hang-up: the call style's own, else an action named like one. */
    private fun hangUpIntent(n: Notification): PendingIntent? {
        n.extras?.let { hangUpExtra(it) }?.let { return it }
        return n.actions?.firstOrNull { a ->
            val t = a.title?.toString()?.trim()?.lowercase() ?: ""
            HANG_UP_WORDS.any { t == it || t.startsWith("$it ") }
        }?.actionIntent
    }

    /** Hang up, from the calling notification's action (on the watch or the phone). */
    fun hangUp(ctx: Context) {
        val listener = BeeperNotificationListenerService.instance
        if (listener == null) {
            LogStore.w(TAG, "Hang up: no notification access")
            tell(ctx, "Can't hang up", "End the call on your phone. To hang up from the watch, allow MirrorMsg notification access.")
            return
        }
        val kind = live
        val call = try {
            listener.activeNotifications?.firstOrNull { isCall(ctx, it, kind) }
        } catch (e: Exception) { LogStore.e(TAG, "Hang up: couldn't read the notifications", e); null }
        val pi = call?.let { hangUpIntent(it.notification) }
        if (pi == null) {
            LogStore.w(TAG, "Hang up: no hang-up found (${if (call == null) "no call notification" else "no action"})")
            tell(ctx, "Can't hang up", "End the call on your phone.")
            return
        }
        try {
            pi.send()
            LogStore.i(TAG, "Hang up: sent")
            live = null
            cancel(ctx)
        } catch (e: Exception) {
            LogStore.e(TAG, "Hang up: failed", e)
            tell(ctx, "Can't hang up", "End the call on your phone.")
        }
    }

    /** A notification went: the call's own one means it ended, so ours goes too. */
    fun onNotificationRemoved(ctx: Context, sbn: StatusBarNotification) {
        val kind = live ?: return
        if (!isCall(ctx, sbn, kind)) return
        LogStore.i(TAG, "Call ended")
        live = null
        cancel(ctx)
    }

    // ---- Notifications (the Pebble app shows them on the watch) ---------------------

    private fun ensureChannel(ctx: Context): NotificationManager {
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            // Default importance, so the Pebble app shows it; no sound on the phone.
            val ch = NotificationChannel(CHANNEL_ID, "Calls from the watch",
                NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Calls started on the watch: calling, hang up, or why not"
                setSound(null, null)
            }
            mgr.createNotificationChannel(ch)
        }
        return mgr
    }

    // Our own screens may be started by the Pebble app firing these from the background
    // (as Notifier's are): the creator's grant lets them through on Android 14+.
    private fun balOptions(): Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return android.app.ActivityOptions.makeBasic()
            .setPendingIntentCreatorBackgroundActivityStartMode(
                android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            .toBundle()
    }

    private fun setupIntent(ctx: Context): PendingIntent =
        PendingIntent.getActivity(ctx, NOTIF_ID + 1,
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_SETUP, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, balOptions())

    private fun hangUpPending(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(ctx, NOTIF_ID + 2,
            Intent(ctx, CallReceiver::class.java).setAction(ACTION_HANG_UP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun builder(ctx: Context, title: String, text: String): Notification.Builder {
        ensureChannel(ctx)
        return Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(ctx.resources.getIdentifier("ic_notification", "drawable", ctx.packageName)
                .takeIf { it != 0 } ?: android.R.drawable.sym_action_call)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
    }

    // Pebble shows a notification's actions from its wearable extension (see Notifier).
    private fun withAction(b: Notification.Builder, title: String, pi: PendingIntent): Notification.Builder {
        @Suppress("DEPRECATION")
        val a = Notification.Action.Builder(android.R.drawable.sym_action_call, title, pi).build()
        return b.addAction(a).extend(Notification.WearableExtender().addAction(a))
    }

    private fun post(ctx: Context, b: Notification.Builder) {
        try { ensureChannel(ctx).notify(NOTIF_ID, b.build()) }
        catch (e: Exception) { LogStore.e(TAG, "Call: couldn't post the notification", e) }
    }

    private fun tell(ctx: Context, title: String, text: String) =
        post(ctx, builder(ctx, title, text).setTimeoutAfter(5 * 60_000L))

    private fun notifySetup(ctx: Context, text: String) {
        val pi = setupIntent(ctx)
        post(ctx, withAction(builder(ctx, "Can't call", text).setContentIntent(pi), "Open on phone", pi)
            .setTimeoutAfter(15 * 60_000L))
    }

    private fun notifyCalling(ctx: Context, text: String) {
        val b = builder(ctx, "Call", text).setAutoCancel(false).setTimeoutAfter(2 * 60 * 60_000L)
        // Hang up works only with notification access (it presses the call's own).
        post(ctx, if (BeeperNotificationListenerService.instance != null)
            withAction(b, "Hang up", hangUpPending(ctx)) else b)
    }

    private fun notifyTapToCall(ctx: Context, kind: Kind, app: App, row: Long) {
        val pi = PendingIntent.getActivity(ctx, NOTIF_ID + 3,
            Intent(ctx, CallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
                putExtra(EXTRA_ROW, row)
                putExtra(EXTRA_KIND, kind.name)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, balOptions())
        post(ctx, withAction(builder(ctx, "Call", "Press Call to start the ${app.label} call on your phone.")
            .setContentIntent(pi), "Call", pi).setTimeoutAfter(5 * 60_000L))
    }

    private fun cancel(ctx: Context) {
        try { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID) }
        catch (_: Exception) {}
    }

    /** From [CallActivity]: start the network's call from the foreground. */
    internal fun startFromTap(act: Activity, kindName: String?, row: Long) {
        val kind = try { Kind.valueOf(kindName ?: "") } catch (_: Exception) { null }
        val app = kind?.let { APPS[it] }
        if (app == null || row < 0) return
        try {
            act.startActivity(callIntent(app, row))
            live = kind
            LogStore.i(TAG, "Call: ${app.label} call started (tap)")
            notifyCalling(act.applicationContext, "Calling on ${app.label}")
        } catch (e: Exception) {
            LogStore.e(TAG, "Call: ${app.label} refused the call", e)
            tell(act.applicationContext, "Can't call", "${app.label} didn't start the call.")
        }
    }

    /** The permissions the switch asks for: phone calls, and contacts for the networks' calls. */
    val PERMISSIONS = arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS)
}

/**
 * Invisible trampoline for a network call started from the notification's Call action
 * (when MirrorMsg may not start it from the background): our own screen is allowed to
 * open, and from the foreground it starts the network's call screen.
 */
class CallActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Mirror.appContext = applicationContext
        Calls.startFromTap(this, intent?.getStringExtra(Calls.EXTRA_KIND),
            intent?.getLongExtra(Calls.EXTRA_ROW, -1L) ?: -1L)
        finish()
    }
}

/** The calling notification's Hang up. */
class CallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Calls.ACTION_HANG_UP) Calls.hangUp(ctx.applicationContext)
    }
}

package com.dano.mirrormsg

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import java.util.concurrent.Executors

/**
 * Sends a Dash Cam link on MirrorMap Dash's request (bound by action
 * [CamRules.ACTION_SEND], over a [Messenger]).
 *
 * Request: `what` [CamRules.MSG_SEND], data `kind`, `slot`, `linkKind`, `token`, `sport`,
 * `firstName`, `actId`. Reply to `replyTo`: `what` [CamRules.MSG_RESULT], data `sent`
 * (chats reached by this request) and `error` (null, or one of not_allowed, bad_request,
 * not_set_up, limit, failed).
 *
 * Only Dash with a pinned certificate is served ([CamCallers]); every field must match
 * its pattern ([CamRules.parse]); the text is a fixed template ([CamRules.text]) and goes
 * only to the chats the user picked in [CamPickActivity]. Each (activity, kind, slot) is
 * sent once, and at most [CamRules.MAX_ACTIVITIES_PER_DAY] activities send in a day.
 */
class CamSendService : Service() {

    companion object {
        private const val TAG = "CamSend"
        private const val CHANNEL_ID = "dashcam"
        private const val NOTIF_ID_LINK = 0x4443   // "DC"
        private const val NOTIF_ID_HELP = 0x4444
        /** Gap between two chats of one request, so a list doesn't go out in a burst. */
        private const val GAP_MS = 1500L

        // One request at a time, for the process's life: the limits stay consistent, and
        // a send carries on when Dash unbinds before it's done.
        private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "cam-send") }
    }

    private val messenger by lazy {
        Messenger(Handler(Looper.getMainLooper()) { msg -> onMessage(msg); true })
    }

    override fun onBind(intent: Intent?): IBinder? = messenger.binder

    private fun onMessage(msg: Message) {
        if (msg.what != CamRules.MSG_SEND) return
        val replyTo = msg.replyTo
        val app = applicationContext
        // The caller first, before its data is read at all.
        if (!CamCallers.isDashUid(app, msg.sendingUid)) {
            LogStore.w(TAG, "Refused a request from uid ${msg.sendingUid}")
            reply(replyTo, 0, CamRules.ERR_NOT_ALLOWED)
            return
        }
        val req = try {
            val data = msg.data
            CamRules.parse { k -> data.getString(k) }
        } catch (e: Exception) {
            null
        }
        if (req == null) {
            LogStore.w(TAG, "Bad request")
            reply(replyTo, 0, CamRules.ERR_BAD_REQUEST)
            return
        }
        worker.execute { handle(app, req, replyTo) }
    }

    private fun handle(app: Context, req: CamRules.Request, replyTo: Messenger?) {
        try {
            CamChats.boot(app)
            val chosen = CamChats.load(app, req.listSlot)
            val ledger = CamChats.ledger(app)
            when (val d = ledger.decide(req, chosen.map { it.key }, System.currentTimeMillis())) {
                is CamLedger.Decision.Refuse -> {
                    LogStore.i(TAG, "${req.kind}: ${d.error}")
                    reply(replyTo, 0, d.error)
                }
                is CamLedger.Decision.Send -> {
                    if (d.chatKeys.isEmpty()) {
                        LogStore.i(TAG, "${req.kind}: already sent for this activity")
                        reply(replyTo, 0, null)
                        return
                    }
                    val text = CamRules.text(req)
                    var sent = 0
                    for (chat in chosen.distinctBy { it.key }.filter { it.key in d.chatKeys }) {
                        if (sent > 0) Thread.sleep(GAP_MS)
                        if (CamChats.send(app, chat, text)) {
                            ledger.record(req, chat.key, System.currentTimeMillis())
                            CamChats.saveLedger(app, ledger)
                            sent++
                        }
                    }
                    LogStore.i(TAG, "${req.kind}: sent to $sent of ${d.chatKeys.size} chats")
                    if (sent == 0) {
                        reply(replyTo, 0, CamRules.ERR_FAILED)
                    } else {
                        notifySent(app, req.kind, ledger.sentCount(req.actId, req.kind))
                        reply(replyTo, sent, null)
                    }
                }
            }
        } catch (e: Throwable) {
            LogStore.e(TAG, "${req.kind} failed", e)
            reply(replyTo, 0, CamRules.ERR_FAILED)
        }
    }

    private fun reply(to: Messenger?, sent: Int, error: String?) {
        if (to == null) return
        val m = Message.obtain(null, CamRules.MSG_RESULT)
        m.data = Bundle().apply {
            putInt(CamRules.KEY_SENT, sent)
            putString(CamRules.KEY_ERROR, error)
        }
        try {
            to.send(m)
        } catch (e: Exception) {
            LogStore.w(TAG, "Reply not delivered: ${e.javaClass.simpleName}")
        }
    }

    /** "Dash Cam link sent to N chats" (this activity so far), quietly. */
    private fun notifySent(ctx: Context, kind: String, n: Int) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Dash Cam", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "When MirrorMsg sends your Dash Cam link for MirrorMap Dash."
                        setShowBadge(false)
                    })
            }
            val chats = if (n == 1) "1 chat" else "$n chats"
            val help = kind == CamRules.KIND_HELP
            val title = if (help) "Need help sent to $chats" else "Dash Cam link sent to $chats"
            val tap = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(ctx, CHANNEL_ID)
                    else @Suppress("DEPRECATION") Notification.Builder(ctx)
            val icon = ctx.resources.getIdentifier("ic_notification", "drawable", ctx.packageName)
                .takeIf { it != 0 } ?: android.R.drawable.ic_dialog_email
            nm.notify(if (help) NOTIF_ID_HELP else NOTIF_ID_LINK, b.setContentTitle(title)
                .setSmallIcon(icon)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build())
        } catch (e: Throwable) {
            LogStore.w(TAG, "Notification failed: ${e.javaClass.simpleName}")
        }
    }
}

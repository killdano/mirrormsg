package com.dano.mirrormsg

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The message card link to MirrorMap Dash (the rules and numbers are [MapCardRules]).
 *
 * - [show]: binds Dash's service ([MapCardRules.ACTION_MSG_CARD]), sends one SHOW and
 *   waits for that token's RESULT, then unbinds. The whole exchange gets
 *   [MapCardRules.SHOW_TIMEOUT_MS]; no answer is [MapCardRules.RESULT_TIMEOUT].
 * - Dash answers, and later sends the card's EVENTs, to [replies]: one Messenger for the
 *   process's life, so it still works after the SHOW's binding has ended. Anything it
 *   receives from a caller that isn't Dash with a pinned certificate is dropped
 *   ([CamCallers.isDashUid]) before a field is read.
 * - An "opened" EVENT opens that chat on the watch ([LiveRouter.openFromCard]) and marks
 *   it: when the watch says that chat closed ([onThreadClosed]), [returnTo] asks Dash to
 *   bring the map app back.
 *
 * Only the sender's name and the text go to Dash, in memory, for the card. Nothing about
 * the message goes into a log line: tokens, results, lengths and timings only.
 */
object MapCard {
    private const val TAG = "MapCard"

    /**
     * A chat opened from a card closed: wait this long before bringing the map app back.
     * Leaving the watchapp closes the chat too; the watchapp's own close (which clears
     * the return) normally lands within it, so an exit doesn't relaunch the map app.
     */
    private const val RETURN_SETTLE_MS = 400L

    /** The last cards' tokens and their chats. */
    val tokens = CardTokens()
    private val mark = ReturnMarkSlot()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Main thread only: the exchanges waiting for their answer. A SHOW waits under its
    // token; a RETURN (whose answer carries none) under 0.
    private val waiting = HashMap<Int, (Bundle) -> Unit>()

    /** Where Dash answers. Process-lifetime: Dash keeps it to send a card's events later. */
    private val replies: Messenger by lazy {
        Messenger(Handler(Looper.getMainLooper()) { m -> onReply(m); true })
    }

    private fun serviceIntent(): Intent =
        Intent(MapCardRules.ACTION_MSG_CARD).setPackage(CamRules.DASH_PACKAGE)

    /**
     * Dash is installed, new enough to show cards, and signed with a pinned certificate
     * (the text goes to no one else, and an unpinned Dash's answers would be dropped).
     */
    fun canShow(ctx: Context): Boolean = try {
        ctx.packageManager.resolveService(serviceIntent(), 0) != null &&
            CamCallers.isDash(ctx, CamRules.DASH_PACKAGE)
    } catch (e: Exception) {
        false
    }

    /**
     * Asks Dash to show a card for [token]; returns Dash's result, or
     * [MapCardRules.RESULT_TIMEOUT] / [MapCardRules.RESULT_UNAVAILABLE] /
     * [MapCardRules.RESULT_FAILED] when it couldn't be asked or didn't answer in time.
     */
    suspend fun show(ctx: Context, token: Int, sender: String, text: String, color: Int, buzz: Boolean): String {
        val t0 = SystemClock.elapsedRealtime()
        val s = MapCardRules.clip(sender, MapCardRules.SENDER_MAX_CHARS)
        val t = MapCardRules.clip(text, MapCardRules.TEXT_MAX_CHARS)
        val data = Bundle().apply {
            putInt(MapCardRules.KEY_TOKEN, token)
            putString(MapCardRules.KEY_SENDER, s)
            putString(MapCardRules.KEY_TEXT, t)
            putInt(MapCardRules.KEY_COLOR, color and 0xFF)
            putInt(MapCardRules.KEY_BUZZ, if (buzz) 1 else 0)
        }
        val reply = withContext(Dispatchers.Main) {
            withTimeoutOrNull(MapCardRules.SHOW_TIMEOUT_MS) {
                exchange(ctx.applicationContext, MapCardRules.MSG_SHOW, data, token)
            }
        }
        val result = if (reply == null) MapCardRules.RESULT_TIMEOUT
            else reply.error ?: reply.data?.getString(MapCardRules.KEY_RESULT) ?: MapCardRules.RESULT_FAILED
        val app = reply?.data?.getString(MapCardRules.KEY_APP)?.takeIf { MapCardRules.validApp(it) }
        if (app != null) tokens.get(token)?.app = app
        LogStore.i(TAG, "Card $token: ${MapCardRules.logWord(result)}" + (if (app != null) " on $app" else "") +
            " in ${SystemClock.elapsedRealtime() - t0} ms (sender ${s.length}, text ${t.length} chars," +
            " colour 0x${Integer.toHexString(color and 0xFF)}, buzz ${if (buzz) 1 else 0})")
        return result
    }

    /**
     * Asks Dash to relaunch [app] on the watch (Back from a chat opened from its card).
     * True when Dash says the launch was handed to the Pebble app.
     */
    suspend fun returnTo(ctx: Context, app: String): Boolean {
        if (!MapCardRules.validApp(app)) return false
        val t0 = SystemClock.elapsedRealtime()
        val data = Bundle().apply { putString(MapCardRules.KEY_APP, app) }
        val reply = withContext(Dispatchers.Main) {
            withTimeoutOrNull(MapCardRules.RETURN_TIMEOUT_MS) {
                exchange(ctx.applicationContext, MapCardRules.MSG_RETURN, data, 0)
            }
        }
        val ok = reply?.data?.getBoolean(MapCardRules.KEY_OK, false) == true
        val how = if (reply == null) "no answer" else reply.error ?: "ok=$ok"
        LogStore.i(TAG, "Back to $app: $how in ${SystemClock.elapsedRealtime() - t0} ms")
        return ok
    }

    /** The chat opened from a card: bring [app] back when it closes (for a while). */
    fun markReturn(convoId: String, app: String) {
        mark.set(ReturnMark(convoId, app, SystemClock.elapsedRealtime()))
        LogStore.i(TAG, "Back to $app when this chat closes (for ${MapCardRules.MARK_TTL_MS / 60_000} min)")
    }

    /** A force-open is starting for [convoId]: a return marked for another chat ends. */
    fun onForceOpen(convoId: String) {
        mark.clearUnless(convoId)?.let { LogStore.i(TAG, "Another chat opened: no return to ${it.app}") }
    }

    /** The MirrorMsg watchapp closed: no return. */
    fun onWatchAppClosed() {
        mark.clear()?.let { LogStore.i(TAG, "MirrorMsg closed on the watch: no return to ${it.app}") }
    }

    /**
     * The watch's chat window closed (KEY_THREAD_CLOSED); [closedConvoId] is the chat the
     * phone had open until then. When it's the one opened from a card, bring that map
     * app back, unless the watchapp closed or another chat opened meanwhile.
     */
    fun onThreadClosed(ctx: Context, closedConvoId: String?) {
        val m = mark.take(closedConvoId, SystemClock.elapsedRealtime()) ?: return
        val app = ctx.applicationContext
        scope.launch {
            delay(RETURN_SETTLE_MS)
            if (!Mirror.watchAppOpen || Mirror.currentOpenConvoId != null) {
                LogStore.i(TAG, "Chat closed, then MirrorMsg closed or another chat opened: no return to ${m.app}")
                return@launch
            }
            LogStore.i(TAG, "The chat opened from the card closed: back to ${m.app}")
            try {
                returnTo(app, m.app)
            } catch (e: Exception) {
                LogStore.w(TAG, "Back to ${m.app} failed: ${e.javaClass.simpleName}")
            }
        }
    }

    // ---- The exchange --------------------------------------------------------------

    private class Reply(val data: Bundle?, val error: String?)

    /** Binds, sends one request, waits for its answer (under [key]), unbinds. Main thread. */
    private suspend fun exchange(ctx: Context, what: Int, data: Bundle, key: Int): Reply =
        suspendCancellableCoroutine { cont ->
            var done = false
            var conn: ServiceConnection? = null
            lateinit var waiter: (Bundle) -> Unit

            fun finish(r: Reply) {
                if (done) return
                done = true
                if (waiting[key] === waiter) waiting.remove(key)
                conn?.let {
                    try {
                        ctx.unbindService(it)
                    } catch (_: Exception) {
                    }
                }
                if (cont.isActive) cont.resume(r)
            }

            waiter = { b -> finish(Reply(b, null)) }
            waiting[key] = waiter
            cont.invokeOnCancellation {
                Handler(Looper.getMainLooper()).post { finish(Reply(null, MapCardRules.RESULT_TIMEOUT)) }
            }
            val c = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (binder == null) {
                        finish(Reply(null, MapCardRules.RESULT_UNAVAILABLE))
                        return
                    }
                    try {
                        val msg = Message.obtain(null, what)
                        msg.data = data
                        msg.replyTo = replies
                        Messenger(binder).send(msg)
                    } catch (e: Exception) {
                        LogStore.w(TAG, "Send to Dash failed: ${e.javaClass.simpleName}")
                        finish(Reply(null, MapCardRules.RESULT_FAILED))
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    finish(Reply(null, MapCardRules.RESULT_FAILED))
                }

                override fun onNullBinding(name: ComponentName?) {
                    finish(Reply(null, MapCardRules.RESULT_UNAVAILABLE))
                }

                override fun onBindingDied(name: ComponentName?) {
                    finish(Reply(null, MapCardRules.RESULT_FAILED))
                }
            }
            conn = c
            val bound = try {
                ctx.bindService(serviceIntent(), c, Context.BIND_AUTO_CREATE)
            } catch (e: Exception) {
                LogStore.w(TAG, "Bind refused: ${e.javaClass.simpleName}")
                false
            }
            // Unbound in finish() even when this is false, as bindService asks.
            if (!bound) finish(Reply(null, MapCardRules.RESULT_UNAVAILABLE))
        }

    /** Everything Dash sends to [replies]. Main thread. */
    private fun onReply(m: Message) {
        val ctx = Mirror.appContext ?: return
        // The caller first, before its data is read at all.
        if (!CamCallers.isDashUid(ctx, m.sendingUid)) {
            LogStore.w(TAG, "Dropped a message (what=${m.what}) from uid ${m.sendingUid}")
            return
        }
        try {
            val b = m.data ?: return
            when (m.what) {
                MapCardRules.MSG_RESULT -> {
                    val token = b.getInt(MapCardRules.KEY_TOKEN, 0)
                    val w = if (token > 0) waiting[token] else null
                    if (w != null) w(b)
                    else LogStore.i(TAG, "Card $token: late answer ${MapCardRules.logWord(b.getString(MapCardRules.KEY_RESULT))}")
                }
                MapCardRules.MSG_EVENT -> onEvent(
                    b.getInt(MapCardRules.KEY_TOKEN, 0),
                    b.getString(MapCardRules.KEY_EVENT),
                    b.getString(MapCardRules.KEY_APP),
                )
                MapCardRules.MSG_RETURNED -> {
                    val w = waiting[0]
                    if (w != null) w(b) else LogStore.i(TAG, "Late answer to a return")
                }
                else -> {}   // an unknown message is ignored
            }
        } catch (e: Exception) {
            LogStore.w(TAG, "Unreadable message from Dash (what=${m.what}): ${e.javaClass.simpleName}")
        }
    }

    private fun onEvent(token: Int, event: String?, app: String?) {
        val e = tokens.get(token)
        LogStore.i(TAG, "Card $token: ${MapCardRules.logWord(event)} on ${MapCardRules.logWord(app)}" + if (e == null) " (unknown card)" else "")
        if (event != MapCardRules.EVENT_OPENED) return
        // Once per card: a repeated "opened" must not open the chat twice.
        val first = tokens.firstOpen(token) ?: return
        val to = app?.takeIf { MapCardRules.validApp(it) } ?: first.app
        LiveRouter.openFromCard(first.convoId, first.ownerServiceId, to)
    }
}

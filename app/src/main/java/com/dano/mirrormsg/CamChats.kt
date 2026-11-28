package com.dano.mirrormsg

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The chats picked for Dash Cam, kept on this phone only (MirrorMap Dash learns how many
 * and on which network, never who): one chat per Dash slot (a person), and a standing
 * list for the public link. Also lists the chats to pick from, and sends to a picked
 * chat through its network's own send path.
 */
object CamChats {
    private const val TAG = "CamChats"
    private const val PREFS = "cam"
    private const val KEY_LEDGER = "ledger"
    private const val LIST_COUNT = 100

    /**
     * A picked chat: its network ([serviceId]), its id there, its name when picked (to
     * show it again in the picker), and for Google Messages the SIM route as the reply
     * token that network's send expects ("" where none is needed).
     */
    data class Chat(val serviceId: String, val convoId: String, val name: String, val route: String) {
        val key: String get() = CamRules.chatKey(serviceId, convoId)
        override fun toString(): String = "Chat($serviceId)"
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun storeKey(slot: String?) = if (slot == null) "list" else "slot:$slot"

    /** The chats picked for [slot] (null: the standing list for the public link). */
    fun load(ctx: Context, slot: String?): List<Chat> {
        val raw = prefs(ctx).getString(storeKey(slot), null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val s = o.optString("s"); val c = o.optString("c")
                if (s.isEmpty() || c.isEmpty()) null else Chat(s, c, o.optString("n"), o.optString("r"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Keep [chats] for [slot] (null: the standing list); none forgets it. */
    fun save(ctx: Context, slot: String?, chats: List<Chat>) {
        val e = prefs(ctx).edit()
        if (chats.isEmpty()) {
            e.remove(storeKey(slot))
        } else {
            val arr = JSONArray()
            for (c in chats) arr.put(JSONObject().put("s", c.serviceId).put("c", c.convoId).put("n", c.name).put("r", c.route))
            e.putString(storeKey(slot), arr.toString())
        }
        e.apply()
    }

    fun ledger(ctx: Context): CamLedger = CamLedger.decode(prefs(ctx).getString(KEY_LEDGER, null))

    fun saveLedger(ctx: Context, l: CamLedger) {
        prefs(ctx).edit().putString(KEY_LEDGER, l.encode()).commit()
    }

    /**
     * What the app needs before using a network when nothing else in this process has
     * run yet (Dash can start it): the app context, the settings, and every logged-in
     * network brought up (a no-op when the app is already running).
     */
    fun boot(ctx: Context) {
        val app = ctx.applicationContext
        if (Mirror.appContext == null) {
            Mirror.appContext = app
            try { Mirror.loadSettings(app) } catch (e: Throwable) { LogStore.e(TAG, "loadSettings failed", e) }
        }
        LogStore.init(app)
        MirrorConnections.ensureConnected(app)
    }

    /**
     * The chats [svc] can send to, newest first: its direct chats and groups (a
     * hierarchical network's "dms" level: Discord DMs, every Telegram chat, X and
     * Instagram chats), never a bucket, server or feed. Blocking; off the main thread.
     */
    fun chatsOf(svc: MessagingService): List<Chat> {
        val json = try {
            val first = if (svc.hierarchical) svc.listContext(LIST_COUNT, listOf("dms")) else svc.listConversations(LIST_COUNT)
            if (svc.hierarchical && rows(first).length() == 0) svc.listConversations(LIST_COUNT) else first
        } catch (e: Throwable) {
            LogStore.w(TAG, "List ${svc.id} failed: ${e.javaClass.simpleName}")
            return emptyList()
        }
        val arr = rows(json)
        val out = ArrayList<Chat>()
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            if (c.optString("navKind") == "ENTER") continue
            val id = c.optString("conversationID")
            if (id.isEmpty() || Mirror.isFeed(id) || !seen.add(id)) continue
            out.add(Chat(svc.id, id, c.optString("name").ifEmpty { "(no name)" }, routeOf(c)))
        }
        return out
    }

    private fun rows(json: String): JSONArray =
        try { JSONObject(json).optJSONArray("conversations") } catch (e: Exception) { null } ?: JSONArray()

    // Google Messages sends with the chat's SIM route, read from its row as the watch's
    // list does (PebbleListenerService.rebuildConvoIndex). Other networks need none.
    private fun routeOf(c: JSONObject): String {
        if (!c.has("defaultOutgoingID")) return ""
        val sim = c.optJSONObject("simCard")?.optJSONObject("SIMData")?.optJSONObject("SIMPayload")
        return JSONObject()
            .put("p", c.optString("defaultOutgoingID", "2"))
            .put("t", (sim?.optInt("two", 2) ?: 2).toLong())
            .put("n", (sim?.optInt("SIMNumber", 2) ?: 2).toLong())
            .toString()
    }

    /**
     * Send [text] to [chat] through its network's own send (the path a reply from the
     * watch or a notification takes). Waits up to 20 s for the network to be up when the
     * app has just started, and tries twice. True when the network took it. Blocking.
     */
    fun send(ctx: Context, chat: Chat, text: String): Boolean {
        val svc = ServiceRegistry.byId(ctx, chat.serviceId)
        if (svc == null) {
            LogStore.w(TAG, "Send: ${chat.serviceId} isn't in this build")
            return false
        }
        waitUntilUp(svc)
        for (attempt in 1..2) {
            val token = chat.route.ifEmpty { runCatching { svc.replyTokenFor(chat.convoId) }.getOrDefault("") }
            val result = try {
                svc.sendMessage(chat.convoId, text, token)
            } catch (e: Throwable) {
                LogStore.w(TAG, "Send via ${svc.id} threw ${e.javaClass.simpleName}")
                null
            }
            // As the watch's reply path judges it: an "error" field means not sent.
            val refused = result == null || try { JSONObject(result).has("error") } catch (_: Exception) { false }
            if (!refused) return true
            LogStore.w(TAG, "Send via ${svc.id} refused (attempt $attempt)")
            if (attempt < 2) Thread.sleep(4000)
        }
        return false
    }

    private fun waitUntilUp(svc: MessagingService) {
        val until = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < until) {
            val up = try {
                svc.isLoggedIn() && (svc.id != "google" || Mirror.bridge != null)
            } catch (e: Throwable) { false }
            if (up) return
            Thread.sleep(1000)
        }
        LogStore.w(TAG, "${svc.id} not up after 20 s; trying anyway")
    }
}

package com.dano.mirrormsg

import android.app.Activity
import android.content.Context
import android.content.Intent
import fi.mirrormsg.whatsappbridge.Bridge as WaBridge
import fi.mirrormsg.whatsappbridge.EventSink as WaEventSink
import fi.mirrormsg.whatsappbridge.Whatsappbridge
import java.io.File

/**
 * WhatsAppService — native WhatsApp via the whatsmeow Go bridge (the same library
 * Beeper uses under the hood), wrapping the whatsappbridge package that's bound
 * into mirrorbridge.aar. Modeled on GoogleMessagesService: a thin adapter over a
 * phone-resident Go bridge, behind the [MessagingService] interface, with nothing
 * WhatsApp-privileged in the core.
 *
 * The one structural difference from Google: this adapter OWNS its bridge instead
 * of borrowing Mirror.bridge. whatsmeow manages its own paired-device session in
 * a SQLite store at filesDir/wa.db, so the bridge is created lazily here and held
 * for the app's lifetime (ServiceRegistry caches this instance). It is the SINGLE
 * owner — the pairing screen reuses this same bridge via the instance, because two
 * whatsmeow clients on one store corrupt it.
 *
 * The bridge emits events through one sink; this adapter fans them out:
 *   MESSAGE                  -> LiveRouter.ingestLiveMessage("whatsapp", json)
 *   QR / PAIRED / PAIR_*     -> a transient pairing listener (the pairing screen)
 *   CONNECTED / LOGGED_OUT   -> connection state (+ relayed to the pairing screen)
 *
 * Build status: receive (live messages) + presence as native account #3 are live.
 * The chat-list, send, and media surfaces are stubbed (next milestones); they
 * return empty/error so the core degrades gracefully, exactly like a network that
 * is logged in but has nothing to show yet.
 *
 * Discovered by [ServiceRegistry] via its no-arg constructor.
 */
class WhatsAppService : MessagingService, ReactionRemover {

    override val id = "whatsapp"
    override val displayName = "WhatsApp"
    override val conflictGroup = "whatsapp"   // mutual-exclusion with Beeper WhatsApp (id "wabeeper")

    // Native reply-to + emoji reactions from the watch detail view (whatsmeow
    // ContextInfo / BuildReaction). The interface defaults both to false.
    override val supportsReplyTo = true
    override val supportsReactionSend = true

    // WhatsApp (whatsmeow) sends read receipts; read state is a per-chat high-water
    // mark, so marking the newest message read clears the thread. Opt into the seam.
    override val supportsMarkRead = true

    // WhatsApp's brand green/teal family, kept under ~140 luma so the watch draws
    // white initials (matching the other accounts' branded look).
    override val avatarPalette = listOf("#128C7E", "#1EA362", "#075E54", "#25803D")

    @Volatile private var waBridge: WaBridge? = null
    @Volatile private var connected = false
    @Volatile private var pairingListener: ((String, String) -> Unit)? = null
    private val lock = Any()

    // The bridge's single sink. Fans events out to the right consumer.
    private val sink = object : WaEventSink {
        override fun onEvent(eventType: String, jsonPayload: String) {
            when (eventType) {
                "MESSAGE" -> LiveRouter.ingestLiveMessage("whatsapp", jsonPayload)
                "REACTION" -> {
                    ReactionStore.handleBridgeEvent(jsonPayload)
                    try {
                        val o = org.json.JSONObject(jsonPayload)
                        LogStore.i(TAG, "WA reaction ${o.optString("op")} msg=..${o.optString("msg").takeLast(8)}")
                    } catch (_: Exception) {}
                    // The reaction is now in the store, but it rides on no message, so
                    // nothing re-pushes the thread. If a WhatsApp thread is open, refresh
                    // it in place so the badge appears without re-opening. (The reaction
                    // came through OUR sink, so an open thread under the active WhatsApp
                    // account is the one it belongs to.)
                    val open = Mirror.currentOpenConvoId
                    if (open != null && Mirror.activeServiceId == id) {
                        PebbleListenerService.instance?.refreshOpenThread(open)
                    }
                }
                "CONNECTED" -> {
                    connected = true
                    pairingListener?.invoke(eventType, jsonPayload)
                    // Fetch + cache group subjects so the list shows group names.
                    try { waBridge?.ensureGroupNames() } catch (_: Throwable) {}
                    // Merge any 1:1 chat that split across a contact's @lid and
                    // phone JID back into a single conversation.
                    try { waBridge?.consolidateLidChats() } catch (_: Throwable) {}
                }
                "LOGGED_OUT" -> {
                    connected = false
                    LogStore.w(TAG, "WhatsApp session logged out server-side")
                    pairingListener?.invoke(eventType, jsonPayload)
                }
                "QR", "PAIRED", "PAIR_TIMEOUT", "PAIR_ERROR" ->
                    pairingListener?.invoke(eventType, jsonPayload)
                "CHATS_UPDATED" -> {
                    // History/live folded new chats into the bridge index — drop the
                    // cached list so the next watch read picks them up.
                    Mirror.invalidateConvoList()
                    // A group created/seen after the connect-time sweep needs its
                    // subject fetched too. The bridge tries each group at most once
                    // per session, so this won't re-spam already-handled groups.
                    try { waBridge?.ensureGroupNames() } catch (_: Throwable) {}
                    // Re-run LID consolidation: a contact's @lid->phone mapping may
                    // only become known after more sync, so fold late-resolving
                    // split chats together too (idempotent once merged).
                    try { waBridge?.consolidateLidChats() } catch (_: Throwable) {}
                }
                // Diagnostic strings from the bridge -> exportable log.
                "DIAG" -> LogStore.i(TAG, jsonPayload)
                else -> { /* EVENT goType=… traffic — ignored in normal operation */ }
            }
        }
    }

    /**
     * The single process-wide whatsmeow bridge, created lazily at filesDir/wa.db.
     * Returns null only if there's no app context yet or the store fails to open.
     * NOTE: first call opens the SQLite store (a quick GetFirstDevice query); a
     * later refinement can move that off the main thread, but it's one-time and
     * the result is cached.
     */
    fun ensureBridge(): WaBridge? {
        waBridge?.let { return it }
        val c = Mirror.appContext ?: return null
        return synchronized(lock) {
            waBridge ?: run {
                try {
                    // The encrypted-store build kept its key here; that build is gone.
                    try { c.deleteSharedPreferences("whatsapp_store") } catch (_: Throwable) {}
                    val storePath = File(c.filesDir, "wa.db").absolutePath
                    Whatsappbridge.newBridge(storePath, sink).also { waBridge = it }
                } catch (e: Throwable) {
                    LogStore.e(TAG, "WhatsApp newBridge failed", e); null
                }
            }
        }
    }

    // ---- Identity & state --------------------------------------------------

    override fun isLoggedIn(): Boolean =
        try { ensureBridge()?.isPaired() ?: false } catch (e: Throwable) { false }

    override fun accountLabel(): String =
        try {
            ensureBridge()?.selfNumber()?.takeIf { it.isNotEmpty() }?.let { "+$it" } ?: ""
        } catch (_: Throwable) { "" }

    // ---- Lifecycle ---------------------------------------------------------

    override fun connectIfPossible(): Boolean {
        val b = ensureBridge() ?: return false
        return try {
            if (!b.isPaired()) return false
            if (connected) return true
            b.connect()   // throws on hard failure; "already connected" is benign
            true
        } catch (e: Throwable) {
            LogStore.w(TAG, "WhatsApp connect: ${e.message}")
            connected
        }
    }

    override fun disconnect() {
        try { waBridge?.disconnect() } catch (_: Throwable) {}
        connected = false
    }

    override fun logout(context: Context) {
        // Local sign-out: drop the connection and delete the whatsmeow store so the
        // next launch is unpaired. (The linked-device entry remains on WhatsApp's
        // side until removed there; a server-side unlink can be added later.)
        try { waBridge?.close() } catch (_: Throwable) {}
        waBridge = null
        connected = false
        for (n in listOf("wa.db", "wa.db-wal", "wa.db-shm", "wa.db-journal", "wa.db.chats.json", "wa.db.avatars.json")) {
            try { File(context.filesDir, n).delete() } catch (_: Throwable) {}
        }
        // Per-chat message history now lives in a directory; clear it too so a
        // re-link starts from a clean, empty store.
        try { File(context.filesDir, "wa.db.msgs").deleteRecursively() } catch (_: Throwable) {}
        LogStore.i(TAG, "WhatsApp logged out (local session cleared)")
    }

    // ---- Login -------------------------------------------------------------

    override fun startLogin(activity: Activity) {
        // WhatsApp links a device by QR. The pairing screen renders it and reuses
        // THIS adapter's bridge (never builds its own).
        activity.startActivity(Intent(activity, WhatsAppPairActivity::class.java))
    }

    // ---- Pairing-screen hooks (used by WhatsAppPairActivity) ---------------

    /** Register a transient listener for QR/PAIRED/CONNECTED/… events. */
    fun setPairingListener(l: ((String, String) -> Unit)?) { pairingListener = l }

    /**
     * Generate an 8-character linking code for [number] (full international format).
     * The bridge must already be connecting (beginPairing called + the pairing flow
     * live) — the pairing screen requests this only after the first QR event. Throws
     * on failure (e.g. malformed number, or flow not ready yet).
     */
    fun requestPairCode(number: String): String {
        val b = ensureBridge() ?: throw IllegalStateException("WhatsApp bridge unavailable")
        return b.requestPairCode(number)
    }

    /** Current paired/connected state, for the pairing screen's initial render. */
    fun pairedNow(): Boolean = try { ensureBridge()?.isPaired() ?: false } catch (_: Throwable) { false }
    fun connectedNow(): Boolean = connected

    /**
     * Ensure the bridge is connecting — drives the QR flow if unpaired, or just
     * connects an existing session. Call off the main thread (connect blocks
     * briefly). Safe to call when already connected.
     */
    fun beginPairing(): Boolean {
        val b = ensureBridge() ?: return false
        if (connected) { pairingListener?.invoke("CONNECTED", "{}"); return true }
        return try { b.connect(); true } catch (e: Throwable) {
            LogStore.w(TAG, "WhatsApp beginPairing: ${e.message}"); connected
        }
    }

    // ---- Universal data surface --------------------------------------------
    // Stubs for now: live receive works through the sink -> LiveRouter above.
    // The chat list + thread fetch arrive next (built from whatsmeow history),
    // then send + media. Each returns an empty/error JSON so the core degrades
    // cleanly until then.

    override fun listConversations(count: Int): String =
        try {
            ensureBridge()?.listConversations(count.toLong()) ?: "{\"conversations\":[]}"
        } catch (e: Throwable) {
            LogStore.e(TAG, "WhatsApp listConversations failed", e)
            "{\"conversations\":[]}"
        }

    override fun fetchMessages(conversationId: String, count: Long, cursor: String): String {
        val resp = try {
            ensureBridge()?.fetchMessages(conversationId, count, cursor) ?: "{\"messages\":[]}"
        } catch (e: Throwable) {
            LogStore.e(TAG, "WhatsApp fetchMessages failed", e)
            Mirror.backfillExhausted[conversationId] = true
            return "{\"messages\":[]}"
        }
        // Capture the pagination cursor so the generic backfill can page older
        // history. A cursor with a real anchor => older messages remain; its absence
        // => we've reached the oldest stored message (clean stop). WhatsApp history
        // is bounded (whatever the last FULL sync delivered), so backfill walks the
        // local store and then stops here — no runaway.
        try {
            val cur = org.json.JSONObject(resp).optJSONObject("cursor")
            if (cur != null && cur.optString("beforeTs").isNotEmpty()) {
                Mirror.backfillCursor[conversationId] = cur.toString()
                Mirror.backfillExhausted[conversationId] = false
            } else {
                Mirror.backfillExhausted[conversationId] = true
            }
        } catch (_: Throwable) {
            Mirror.backfillExhausted[conversationId] = true
        }
        return resp
    }

    // ---- New chat (People.kt) -------------------------------------------------
    // The address-book contacts WhatsApp synced. A person's id is their chat's JID, so
    // their chat is that id (the first send makes it on WhatsApp).
    override val supportsNewChat = true

    override fun listPeople(): String =
        ensureBridge()?.listPeople() ?: "{\"people\":[]}"

    override fun openPerson(personId: String): String =
        org.json.JSONObject().put("conversationID", personId).toString()

    override fun sendMessage(conversationId: String, text: String, replyToken: String): String {
        // conversationId IS the chat JID. The send returns {"messageId":...};
        // a hard failure throws.
        val result = try {
            ensureBridge()?.sendMessage(conversationId, text)
                ?: return "{\"error\":\"whatsapp bridge unavailable\"}"
        } catch (e: Throwable) {
            LogStore.e(TAG, "WhatsApp send failed", e)
            return "{\"error\":\"${e.message}\"}"
        }
        // whatsmeow does not loop our OWN sent message back as a live event, so the
        // store never learns of it and the watch keeps showing only history.
        // Optimistically insert it through LiveRouter (the same path a received
        // message takes): it stores the message, pushes the thread to the watch,
        // and stays silent because it's from us. We use the real messageId from the
        // response so that if a late echo ever does arrive, the store dedups on the
        // id instead of showing it twice. NOTE: WhatsApp timestamps are
        // MICROSECONDS-as-string (UnixMicro), unlike FB's milliseconds.
        try {
            val sentId = org.json.JSONObject(result).optString("messageId")
            if (sentId.isNotEmpty()) {
                val sp = org.json.JSONObject()
                    .put("isMe", true).put("firstName", "").put("fullName", "")
                    .put("ID", org.json.JSONObject().put("number", ""))
                val info = org.json.JSONArray().put(
                    org.json.JSONObject().put(
                        "messageContent", org.json.JSONObject().put("content", text)
                    )
                )
                val msg = org.json.JSONObject()
                    .put("conversationID", conversationId)
                    .put("messageID", sentId)
                    .put("timestamp", (System.currentTimeMillis() * 1000L).toString())
                    .put("senderParticipant", sp)
                    .put("messageStatus", org.json.JSONObject().put("status", "OUTGOING_DELIVERED"))
                    .put("messageInfo", info)
                    .put("isMe", true)
                    .put("fromMe", true)
                LiveRouter.ingestLiveMessage("whatsapp", msg.toString())
            }
        } catch (e: Throwable) {
            LogStore.e(TAG, "optimistic echo of sent WhatsApp message failed", e)
        }
        return result
    }

    // Detail-view reply: send text quoting targetMessageId. whatsmeow won't loop our
    // own send back, so we optimistically insert it — tagged with replyTo.id so the
    // phone's thread builder resolves the quoted sender/text from the store and shows
    // the "You replied to X" title. The real messageId dedups any late echo.
    override fun sendReplyTo(
        conversationId: String, targetMessageId: String,
        targetFromMe: Boolean, text: String
    ): String {
        val result = try {
            ensureBridge()?.sendReply(conversationId, targetMessageId, text)
                ?: return "{\"error\":\"whatsapp bridge unavailable\"}"
        } catch (e: Throwable) {
            LogStore.e(TAG, "WhatsApp sendReplyTo failed", e)
            return "{\"error\":\"${e.message}\"}"
        }
        val sentId = try {
            org.json.JSONObject(result).optString("messageId")
        } catch (_: Throwable) {
            ""
        }
        optimisticEcho(conversationId, text, sentId, targetMessageId)
        return result
    }

    // Detail-view emoji reply: a WhatsApp reaction on targetMessageId. No optimistic
    // insert — the reaction isn't a thread message; its badge surfaces through the
    // normal reaction path if/when whatsmeow reflects it. targetFromMe lets the bridge
    // resolve the reaction key's sender when the message is outside the stored window.
    override fun sendReaction(
        conversationId: String, targetMessageId: String,
        targetFromMe: Boolean, emoji: String
    ): String {
        val result = try {
            ensureBridge()?.sendReaction(conversationId, targetMessageId, emoji, targetFromMe)
                ?: return "{\"error\":\"whatsapp bridge unavailable\"}"
        } catch (e: Throwable) {
            LogStore.e(TAG, "WhatsApp sendReaction failed", e)
            return "{\"error\":\"${e.message}\"}"
        }
        // whatsmeow doesn't loop our own sends back. The shared send path stores the
        // user's reaction under selfActorKey() ("wa:self"), so nothing is ingested here
        // (doing both counted a watch-sent reaction twice).
        return result
    }

    // The user's reactions are stored under this key by the shared send path.
    override fun selfActorKey(): String? = WA_SELF_ACTOR

    // Sending the same emoji again takes it back: on WhatsApp an empty reaction
    // removes the user's reaction from the message.
    override fun removeReaction(
        conversationId: String, targetMessageId: String,
        targetFromMe: Boolean, emoji: String
    ): String = sendReaction(conversationId, targetMessageId, targetFromMe, "")

    // Mark the thread read on WhatsApp up through messageId. whatsmeow marks by a
    // per-chat high-water mark, so the newest message clears the whole thread; the
    // bridge resolves the group sender it needs. Fire-and-forget from the caller's
    // view — errors just log.
    override fun markRead(conversationId: String, messageId: String): String {
        return try {
            ensureBridge()?.markThreadRead(conversationId, messageId)
                ?: "{\"error\":\"whatsapp bridge unavailable\"}"
        } catch (e: Throwable) {
            LogStore.e(TAG, "WhatsApp markRead failed", e)
            "{\"error\":\"${e.message}\"}"
        }
    }

    // Optimistically insert a just-sent message through LiveRouter (the received-
    // message path: stores it, pushes the thread, stays silent because it's from us).
    // replyToId, when non-empty, tags it as a reply so buildTrimmed composes the title.
    // WhatsApp timestamps are MICROSECONDS-as-string (UnixMicro), unlike FB's millis.
    private fun optimisticEcho(
        conversationId: String, text: String, sentId: String, replyToId: String
    ) {
        if (sentId.isEmpty()) return
        try {
            val sp = org.json.JSONObject()
                .put("isMe", true).put("firstName", "").put("fullName", "")
                .put("ID", org.json.JSONObject().put("number", ""))
            val info = org.json.JSONArray().put(
                org.json.JSONObject().put(
                    "messageContent", org.json.JSONObject().put("content", text)
                )
            )
            val msg = org.json.JSONObject()
                .put("conversationID", conversationId)
                .put("messageID", sentId)
                .put("timestamp", (System.currentTimeMillis() * 1000L).toString())
                .put("senderParticipant", sp)
                .put("messageStatus", org.json.JSONObject().put("status", "OUTGOING_DELIVERED"))
                .put("messageInfo", info)
                .put("isMe", true)
                .put("fromMe", true)
            if (replyToId.isNotEmpty()) {
                msg.put("replyTo", org.json.JSONObject().put("id", replyToId))
            }
            LiveRouter.ingestLiveMessage("whatsapp", msg.toString())
        } catch (e: Throwable) {
            LogStore.e(TAG, "optimistic echo (reply) failed", e)
        }
    }

    override fun replyTokenFor(conversationId: String): String = ""   // JID is the convoId; no routing token

    // Contact/group photos. With a row (packing the chat list) the bridge answers
    // from its cache only and looks unknown chats up in the background; with null
    // (a notification, off the main thread) it may ask WhatsApp now.
    override fun avatarId(conversationId: String, row: org.json.JSONObject?): String? =
        try {
            ensureBridge()?.avatarID(conversationId, row == null)?.takeIf { it.isNotEmpty() }
        } catch (e: Throwable) {
            null
        }

    // WhatsApp's picture addresses lapse, so the bridge gets a fresh one each time.
    // "" = no photo (null); a failure throws, so the phone tries again later.
    override fun avatarBytes(avatarId: String): ByteArray? {
        val b = ensureBridge() ?: throw IllegalStateException("whatsapp bridge unavailable")
        val url = b.avatarURL(avatarId)
        if (url.isEmpty()) return null
        return Avatars.httpGet(url)
    }

    // Video and voice notes: the bridge fetches + decrypts the file for the phone.
    // mediaId is the message ID; the key lives in the stored media handle.
    override fun downloadMediaToFile(mediaId: String, decryptionKeyB64: String, path: String): Long =
        try {
            ensureBridge()?.downloadMediaToFile(mediaId, path) ?: -1L
        } catch (e: Throwable) {
            LogStore.e(TAG, "WhatsApp downloadMediaToFile failed", e)
            -1L
        }

    override fun downloadImage(
        mediaId: String, decryptionKeyB64: String,
        maxW: Int, maxH: Int, maxBytes: Int, quality: Int
    ): String =
        try {
            // WhatsApp ignores decryptionKeyB64 — the key lives in the stored
            // ImageMessage handle, which the bridge looks up by mediaId (= msg ID).
            ensureBridge()?.downloadImageForWatch(
                mediaId, maxW.toLong(), maxH.toLong(), maxBytes.toLong(), quality.toLong()
            ) ?: "{\"error\":\"whatsapp bridge unavailable\"}"
        } catch (e: Throwable) {
            LogStore.e(TAG, "WhatsApp downloadImage failed", e)
            "{\"error\":\"${e.message}\"}"
        }

    // WhatsApp media is never inline — the message carries an encrypted URL+keys,
    // so it always goes through downloadImage above. (Method kept for the interface.)
    override fun processInlineImage(
        mediaDataB64: String,
        maxW: Int, maxH: Int, maxBytes: Int, quality: Int
    ): String = "{\"error\":\"whatsapp has no inline images\"}"

    companion object {
        private const val TAG = "MirrorMsgSpike"
        // Stable actor key for our own optimistically-reflected reactions. Namespaced
        // so it can't collide with a real WhatsApp actor (those are phone-number users).
        private const val WA_SELF_ACTOR = "wa:self"
    }
}

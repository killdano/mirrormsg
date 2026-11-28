package com.dano.mirrormsg

import android.app.Activity
import android.content.Context
import android.content.Intent
import java.io.File
import fi.mirrormsg.telegrambridge.Bridge as TgBridge
import fi.mirrormsg.telegrambridge.Telegrambridge
import fi.mirrormsg.telegrambridge.LiveSink

/**
 * TelegramService — native Telegram DMs over gotd/td (pure-Go MTProto), wrapped by
 * the `telegrambridge` AAR. Same combined-go/gomobile shape as the other native
 * bridges, and independent of Beeper: a real Telegram client session, no second
 * app required.
 *
 * MILESTONE STATE: AUTH ONLY. Login (phone -> code -> 2FA) and session persistence
 * are implemented; listConversations is live; the rest of the data surface (fetchMessages /
 * sendMessage / images) is intentionally stubbed until the read/send milestones.
 *
 * The id is "tgnative", NOT "telegram": Beeper's Telegram twin (BeeperTelegramService)
 * already owns "telegram". conflictGroup "telegram" makes the native and Beeper
 * Telegram mutually exclusive, so only one "Telegram" is ever active in the switcher
 * (same twin pattern as native "whatsapp" vs Beeper "wabeeper"). Users only ever see
 * the displayName "Telegram"; the id is internal.
 *
 * isLoggedIn() must be cheap (it's read during UI render), so it reads a persisted
 * flag set on successful login — never a blocking bridge call. connectIfPossible()
 * is where the actual gotd connect happens, on a background thread.
 */
class TelegramService : MessagingService, ReactionRemover {

    override val id = "tgnative"
    override val displayName = "Telegram"
    override val conflictGroup = "telegram"

    // Telegram brand blues (under ~140 luma -> white initials on the watch).
    override val avatarPalette = listOf("#0088CC", "#229ED9", "#1C7FC0", "#2775A8")

    override fun isLoggedIn(): Boolean =
        Mirror.appContext?.let { prefs(it).getBoolean(KEY_AUTHED, false) } ?: false

    override fun accountLabel(): String =
        Mirror.appContext?.let { Vault.getString(prefs(it), KEY_PHONE) } ?: ""

    /**
     * Bring Telegram online: creating the bridge starts the gotd run loop and loads
     * any saved session file. Blocks (interface contract: safe on a background
     * thread). Refreshes the persisted auth flag from the real connection result.
     */
    override fun connectIfPossible(): Boolean {
        val status = bridge().connectStatus()
        val ok = status == "authorized"
        Mirror.appContext?.let { prefs(it).edit().putBoolean(KEY_AUTHED, ok).apply() }
        if (!ok) LogStore.i(TAG, "TG connectIfPossible: $status")
        return ok
    }

    override fun disconnect() {
        synchronized(lock) {
            tgBridge?.close()
            tgBridge = null
        }
    }

    override fun logout(context: Context) {
        disconnect()
        prefs(context).edit().remove(KEY_AUTHED).remove(KEY_PHONE).apply()
        File(context.filesDir, SESSION_FILE).delete()
        LogStore.i(TAG, "TG logged out")
    }

    override fun startLogin(activity: Activity) {
        activity.startActivity(Intent(activity, TelegramLoginActivity::class.java))
    }

    // ---- Universal data surface: STUBBED until read/send milestones ---------

    override fun listConversations(count: Int): String {
        // A folder sub-account is active -> show only that folder's chats.
        val folder = Mirror.activeTgFolder
        if (folder > 0) {
            val fjson = try {
                bridge().listFolderConversations(folder.toLong(), count.toLong())
            } catch (e: Throwable) {
                LogStore.w(TAG, "TG folder $folder list failed: ${e.message}")
                return "{\"conversations\":[]}"
            }
            return if (fjson.startsWith("{")) fjson else "{\"conversations\":[]}"
        }
        val json = try {
            bridge().listConversations(count.toLong())
        } catch (e: Throwable) {
            LogStore.w(TAG, "TG listConversations failed: ${e.message}")
            return "{\"conversations\":[]}"
        }
        return if (json.startsWith("{")) json else "{\"conversations\":[]}"
    }

    // ---- Hierarchical navigation: folders (like Discord servers) + Direct Messages -

    // Telegram is a hierarchy on the watch: root -> each chat FOLDER (drillable) plus a
    // "Direct Messages" bucket -> that level's chats. The watch drills via KEY_REQ_ENTER;
    // the phone tracks the path in Mirror.navStack and asks each level here. This is the
    // same generic nav the Discord account uses — no watch changes, just this mapping.
    override val hierarchical = true

    override fun listContext(count: Int, context: List<String>): String {
        // Resolve against the CURRENT (deepest) context token; each is self-identifying.
        val cur = context.lastOrNull()
        return when {
            cur == null -> telegramRootJson()          // root: folders + Direct Messages
            cur == "dms" -> {                          // the default all-chats list (OPEN)
                // Bridge-direct on purpose: bypasses listConversations' legacy
                // activeTgFolder branch so a previously-active folder can't leak in here.
                val json = try { bridge().listConversations(count.toLong()) }
                catch (e: Throwable) { LogStore.w(TAG, "TG DMs failed: ${e.message}"); "{\"conversations\":[]}" }
                if (json.startsWith("{")) json else "{\"conversations\":[]}"
            }
            cur.startsWith("folder:") -> {             // a folder's chats (OPEN)
                val fid = cur.removePrefix("folder:").toLongOrNull() ?: return "{\"conversations\":[]}"
                val json = try { bridge().listFolderConversations(fid, count.toLong()) }
                catch (e: Throwable) { LogStore.w(TAG, "TG folder chats failed: ${e.message}"); "{\"conversations\":[]}" }
                if (json.startsWith("{")) json else "{\"conversations\":[]}"
            }
            else -> "{\"conversations\":[]}"
        }
    }

    // The Telegram root: the "Direct Messages" bucket on top, then an ENTER row per
    // chat folder below it. Folder titles/ids come from the gotd session immediately
    // (no gateway wait, unlike Discord's guilds).
    private fun telegramRootJson(): String {
        val convos = org.json.JSONArray()
        convos.put(enterEntry("dms", "Direct Messages"))
        try {
            val folders = org.json.JSONObject(bridge().listFolders()).optJSONArray("folders")
            if (folders != null) for (i in 0 until folders.length()) {
                val f = folders.optJSONObject(i) ?: continue
                val fid = f.optInt("id", -1); if (fid < 0) continue
                val title = f.optString("title").ifEmpty { "Folder" }
                convos.put(enterEntry("folder:$fid", title))
            }
        } catch (e: Throwable) { LogStore.w(TAG, "TG root build failed: ${e.message}") }
        return org.json.JSONObject().put("conversations", convos).toString()
    }

    /**
     * Restore the nav path when a notification force-opens a thread, so backing out
     * climbs correctly. A foldered chat backs out to its folder (folder chat lists carry
     * the same convoIds, so we can resolve it); everything else backs out to Direct
     * Messages, where every chat is reachable. A chat can sit in several folders — the
     * first match is a fine back-out target.
     */
    override fun forceOpenNavContext(convoId: String): List<String> {
        val folderId = folderForConvo(convoId)
        return if (folderId >= 0) listOf("folder:$folderId") else listOf("dms")
    }

    private fun folderForConvo(convoId: String): Int {
        return try {
            val folders = org.json.JSONObject(bridge().listFolders()).optJSONArray("folders") ?: return -1
            for (i in 0 until folders.length()) {
                val f = folders.optJSONObject(i) ?: continue
                val chats = f.optJSONArray("chats") ?: continue
                for (j in 0 until chats.length()) if (chats.optString(j) == convoId) return f.optInt("id", -1)
            }
            -1
        } catch (e: Throwable) { -1 }
    }

    private fun enterEntry(context: String, name: String): org.json.JSONObject =
        org.json.JSONObject()
            .put("conversationID", context)   // unused for ENTER, but keeps the shape
            .put("name", name)
            .put("navKind", "ENTER")
            .put("navContext", context)

    override fun fetchMessages(conversationId: String, count: Long, cursor: String): String {
        // cursor is the offset_id anchor: empty/unparseable = 0 = newest page; a real
        // message id = the batch strictly older than it. gomobile maps the Go `int`
        // offsetID param to a Long on this call.
        val offsetId = cursor.toLongOrNull() ?: 0L
        val json = try {
            bridge().fetchMessages(conversationId, count, offsetId)
        } catch (e: Throwable) {
            LogStore.w(TAG, "TG fetchMessages failed: ${e.message}")
            return "{\"messages\":[]}"
        }
        // Capture the bridge's cursor (= oldest message id in this batch) so the generic
        // backfill can page older. A non-empty cursor that ADVANCED past the one we sent
        // means more history exists; an empty cursor (true top) or a non-advancing one
        // means stop. The "advanced" guard is the safety that stops a stuck bridge from
        // silently re-fetching the same batch forever (the failure the brief warns about).
        val nextCursor = try {
            org.json.JSONObject(json).optString("cursor", "")
        } catch (e: Throwable) { "" }
        if (nextCursor.isNotEmpty() && nextCursor != cursor) {
            Mirror.backfillCursor[conversationId] = nextCursor
            Mirror.backfillExhausted[conversationId] = false
        } else {
            Mirror.backfillExhausted[conversationId] = true
        }
        return if (json.startsWith("{")) json else "{\"messages\":[]}"
    }

    // ---- New chat (People.kt) -------------------------------------------------
    // The account's Telegram contacts (the ones written to most ranked first). A
    // person's id is their 1:1 chat's (tg:user:<id>), its address kept by the bridge.
    override val supportsNewChat = true

    override fun listPeople(): String = bridge().listPeople()

    override fun openPerson(personId: String): String =
        org.json.JSONObject().put("conversationID", personId).toString()

    override fun sendMessage(conversationId: String, text: String, replyToken: String): String {
        val result = try {
            bridge().sendMessage(conversationId, text)
        } catch (e: Throwable) {
            LogStore.e(TAG, "TG send failed", e)
            return "{\"error\":\"${e.message}\"}"
        }
        // gotd doesn't loop our own send back promptly, so optimistically insert it
        // (the received-message path: stores + pushes the thread, stays silent since
        // it's from us). The real messageId dedups any later update echo.
        optimisticEcho(conversationId, text, sentIdOf(result), "")
        return result
    }

    // Native reply-to + emoji reactions from the watch detail view (gotd
    // reply_to / sendReaction). The interface defaults both to false.
    override val supportsReplyTo = true
    override val supportsReactionSend = true

    // Telegram marks read via messages/channels.readHistory (a per-dialog high-water
    // mark), so the newest message clears the thread. Opt into the mark-read seam.
    override val supportsMarkRead = true

    override fun sendReplyTo(
        conversationId: String, targetMessageId: String,
        targetFromMe: Boolean, text: String
    ): String {
        val result = try {
            bridge().sendReply(conversationId, targetMessageId.toLongOrNull() ?: 0L, text)
        } catch (e: Throwable) {
            LogStore.e(TAG, "TG sendReplyTo failed", e)
            return "{\"error\":\"${e.message}\"}"
        }
        // Tag the optimistic echo with replyTo.id so the thread builder composes the
        // "You replied to X" title from the stored quoted message.
        optimisticEcho(conversationId, text, sentIdOf(result), targetMessageId)
        return result
    }

    override fun sendReaction(
        conversationId: String, targetMessageId: String,
        targetFromMe: Boolean, emoji: String
    ): String {
        val result = try {
            bridge().sendReaction(conversationId, targetMessageId.toLongOrNull() ?: 0L, emoji)
        } catch (e: Throwable) {
            LogStore.e(TAG, "TG sendReaction failed", e)
            return "{\"error\":\"${e.message}\"}"
        }
        // The optimistic badge is applied once by the shared send path
        // (PebbleListenerService.sendTargeted) for EVERY network. Doing it again here
        // under a different actor key ("tg:self" vs the shared path's "self")
        // double-counted the reaction on the watch. Telegram's own reaction feedback
        // reconciles the badge to the true count shortly after.
        return result
    }

    // Sending the same reaction again from the watch takes it back. MTProto's
    // messages.sendReaction with no reaction clears the user's reaction on the message;
    // the bridge answers {"messageId"} when done (also when there was nothing to clear).
    override fun removeReaction(
        conversationId: String, targetMessageId: String,
        targetFromMe: Boolean, emoji: String
    ): String = sendReaction(conversationId, targetMessageId, targetFromMe, "")

    override fun replyTokenFor(conversationId: String): String = ""   // convoId is the routing

    // Mark the thread read on Telegram up through messageId. The bridge routes
    // channel/supergroup vs DM/basic-group to the right readHistory RPC and parses
    // the id (Telegram ids are ints). Errors just log — fire-and-forget.
    override fun markRead(conversationId: String, messageId: String): String {
        return try {
            bridge().markThreadRead(conversationId, messageId)
        } catch (e: Throwable) {
            LogStore.e(TAG, "TG markRead failed", e)
            "{\"error\":\"${e.message}\"}"
        }
    }

    private fun sentIdOf(result: String): String =
        try { org.json.JSONObject(result).optString("messageId") } catch (_: Throwable) { "" }

    // Optimistically insert a just-sent message through LiveRouter (the received-
    // message path: stores it, pushes the thread, stays silent because it's ours).
    // replyToId, when set, tags it as a reply. Telegram timestamps are MICROSECONDS.
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
            LiveRouter.ingestLiveMessage("tgnative", msg.toString())
        } catch (e: Throwable) {
            LogStore.e(TAG, "TG optimistic echo failed", e)
        }
    }

    override fun downloadImage(
        mediaId: String, decryptionKeyB64: String,
        maxW: Int, maxH: Int, maxBytes: Int, quality: Int
    ): String =
        try {
            // Telegram ignores decryptionKeyB64 — the download ref lives in the bridge,
            // keyed by mediaId (a photo's "tgi-" id, or a video poster's "tgp-" id).
            bridge().downloadImageForWatch(
                mediaId, maxW.toLong(), maxH.toLong(), maxBytes.toLong(), quality.toLong()
            )
        } catch (e: Throwable) {
            LogStore.e(TAG, "TG downloadImage failed", e)
            "{\"error\":\"${e.message}\"}"
        }

    // Video ("tgv-") and voice notes / audio ("tga-"): the bridge writes the whole
    // playable file to path; no key needed. Returns the byte count, or -1 on failure.
    override fun downloadMediaToFile(mediaId: String, decryptionKeyB64: String, path: String): Long =
        try {
            bridge().downloadMediaToFile(mediaId, path)
        } catch (e: Throwable) {
            LogStore.e(TAG, "TG downloadMediaToFile failed", e)
            -1L
        }

    // Contact photos. A chat row carries its photo's id ("<convoId>|<photo id>") from the
    // bridge, so the row path never touches the network; ENTER rows (Direct Messages,
    // folders) have none and keep their letter. Without a row (a notification) the bridge
    // answers from the photos its lists and live updates have shown it.
    override fun avatarId(conversationId: String, row: org.json.JSONObject?): String? {
        if (row != null) return row.optString("avatarID").ifEmpty { null }
        return try {
            bridge().avatarID(conversationId).ifEmpty { null }
        } catch (e: Throwable) {
            LogStore.w(TAG, "TG avatarId failed: ${e.message}")
            null
        }
    }

    // The bridge throws when the photo can't be had right now, so the phone retries
    // rather than remembering "no photo".
    override fun avatarBytes(avatarId: String): ByteArray? {
        val bytes = bridge().avatarBytes(avatarId)
        return if (bytes == null || bytes.isEmpty()) null else bytes
    }

    // Telegram photos are never inline — they always download via downloadImage.
    override fun processInlineImage(
        mediaDataB64: String,
        maxW: Int, maxH: Int, maxBytes: Int, quality: Int
    ): String = "{\"error\":\"telegram has no inline images\"}"

    companion object {
        private const val TAG = "MirrorMsgSpike"
        private const val PREFS = "mirrormsg"
        private const val KEY_AUTHED = "tg_authed"
        private const val KEY_PHONE = "tg_phone"
        private const val KEY_SESSION_KEY = "tg_session_key"
        private const val SESSION_FILE = "tg_session.json"

        private val lock = Any()
        @Volatile private var tgBridge: TgBridge? = null

        /**
         * The single shared bridge — its gotd run loop lives process-wide, so login
         * and (later) the live pipeline share one connection/session. Lazily created
         * against <filesDir>/tg_session.json.
         */
        fun bridge(): TgBridge {
            tgBridge?.let { return it }
            return synchronized(lock) {
                tgBridge ?: run {
                    val ctx = Mirror.appContext!!
                    val path = File(ctx.filesDir, SESSION_FILE).absolutePath
                    Telegrambridge.newTelegramBridge(path, sessionKey(ctx)).also { b ->
                        // Live events from the gotd update dispatcher. MESSAGE routes
                        // into the shared pipeline (store + notify); REACTION updates
                        // the badge store; DIAG surfaces bridge diagnostics.
                        b.setLiveSink(object : LiveSink {
                            override fun onEvent(eventType: String, jsonPayload: String) {
                                when (eventType) {
                                    "MESSAGE" -> LiveRouter.ingestLiveMessage("tgnative", jsonPayload)
                                    "REACTION" -> {
                                        ReactionStore.handleBridgeEvent(jsonPayload)
                                        val open = Mirror.currentOpenConvoId
                                        if (open != null && Mirror.activeServiceId == "tgnative") {
                                            PebbleListenerService.instance?.refreshOpenThread(open)
                                        }
                                    }
                                    "DIAG" -> LogStore.i(TAG, jsonPayload)
                                    else -> {}
                                }
                            }
                        })
                        tgBridge = b
                    }
                }
            }
        }

        // The key that seals tg_session.json (the login), made once and kept in the
        // Keystore vault. The bridge seals an older plain session on first load.
        private fun sessionKey(ctx: Context): String {
            val p = prefs(ctx)
            Vault.getString(p, KEY_SESSION_KEY).let { if (it.isNotEmpty()) return it }
            val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            val key = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            Vault.putString(p, KEY_SESSION_KEY, key)
            return key
        }

        private fun prefs(ctx: Context) =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** Sub-accounts (Telegram folders) as JSON, for the Networks-page rows. */
        fun foldersJson(): String = try { bridge().listFolders() } catch (e: Throwable) {
            LogStore.w(TAG, "TG foldersJson failed: ${e.message}"); "{\"folders\":[]}"
        }

        /** Called by TelegramLoginActivity once login reports "authorized". */
        fun markLoggedIn(ctx: Context, phone: String) {
            prefs(ctx).edit().putBoolean(KEY_AUTHED, true).apply()
            Vault.putString(prefs(ctx), KEY_PHONE, phone)
        }
    }
}

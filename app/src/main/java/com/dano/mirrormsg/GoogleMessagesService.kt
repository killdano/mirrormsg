package com.dano.mirrormsg

import android.app.Activity
import android.content.Context
import org.json.JSONObject

/**
 * GoogleMessagesService is the FIRST implementation of [MessagingService] —
 * Google Messages (RCS/SMS), wrapping the existing libgm/mirrorbridge AAR. It
 * proves the framework pattern: the core talks to this through the interface,
 * exactly as it will talk to Facebook, with nothing Google-privileged.
 *
 * This adapter is intentionally THIN — it delegates to the same Mirror.bridge
 * and the same flows the app already uses, just behind the interface. The one
 * piece of real translation is the reply routing: Google needs SIM info
 * (participantID/simTwo/simNumber) to send, but the interface's [sendMessage]
 * takes only an opaque token. So this adapter encodes the SIM route into that
 * token in [replyTokenFor] and decodes it in [sendMessage] — keeping all
 * SMS/RCS-specific routing inside the Google module, invisible to the core.
 *
 * Discovered by [ServiceRegistry] via its no-arg constructor.
 */
class GoogleMessagesService : MessagingService, ReactionRemover {

    override val id = "google"
    override val displayName = "Google Messages"

    // Mutual-exclusion group for a Beeper Google-Messages twin (Beeper protocol
    // "gmessages"/"googlechat"): the twin declares conflictGroup = "google" too,
    // and presence-gated isActive() keeps only one of the pair active on the watch
    // at a time. A Beeper-only user has no native GM present, so this never blocks
    // their Beeper twin; a both-configured user gets native as the active side.
    override val conflictGroup = "google"

    // RCS supports both reply-to and tapback reactions, so this adapter opts into
    // the targeted-send capabilities; the watch's detail-view reply routes here.
    override val supportsReplyTo = true
    override val supportsReactionSend = true

    // Google Messages can mark a thread read on the source (libgm read receipt),
    // so the "Mark as read" notification action is offered for this network.
    override val supportsMarkRead = true

    private val ctx: Context get() = Mirror.appContext!!

    // ---- Identity & state --------------------------------------------------

    override fun isLoggedIn(): Boolean {
        // Logged in iff we have a saved session. (The bridge being connected is
        // a stronger condition handled by connectIfPossible.)
        val prefs = ctx.getSharedPreferences("mirrormsg", Context.MODE_PRIVATE)
        return (prefs.getString("session", "") ?: "").isNotEmpty()
    }

    override fun accountLabel(): String = Mirror.accountNumber

    // ---- Lifecycle ---------------------------------------------------------

    override fun connectIfPossible(): Boolean {
        val b = Mirror.bridge ?: return false
        return try {
            if (b.isLoggedIn) { b.connect(); true } else false
        } catch (e: Exception) {
            LogStore.e("GoogleMessagesService", "connect failed", e); false
        }
    }

    override fun disconnect() {
        try { Mirror.bridge?.disconnect() } catch (_: Exception) {}
    }

    override fun logout(context: Context) {
        // Mirror of the existing performLogout: clear session + cached number.
        context.getSharedPreferences("mirrormsg", Context.MODE_PRIVATE)
            .edit().remove("session").apply()
        Mirror.setAccountNumber(context, "")
        try { Mirror.closeSender() } catch (_: Exception) {}
        Mirror.bridge = null
    }

    // ---- Login -------------------------------------------------------------

    override fun startLogin(activity: Activity) {
        // The Google login flow lives in MainActivity (the WebView cookie harvest
        // + Gaia pairing). The interface just needs to trigger it; MainActivity
        // exposes startWebViewLogin() for exactly this. We call it via the
        // activity so the core never needs to know the mechanism.
        (activity as? MainActivity)?.startWebViewLogin()
    }

    // ---- Universal data surface --------------------------------------------

    override fun listConversations(count: Int): String {
        val json = Mirror.bridge?.listConversations(count.toLong()) ?: "{\"conversations\":[]}"
        learnAvatars(json)
        return json
    }

    // ---- Contact photos (steps 90, 91c) ---------------------------------------
    // A 1:1 chat's photo is the other person's (the participant's thumbnail, which the
    // Google Messages app on the phone serves from your contacts, as it does for the web
    // page); a group's is its icon (groupAvatarURL). Ids: "p:<participantID>" /
    // "g:<address>". Learnt from every list the bridge gives, for the notifications.
    // Which chats and people, as mautrix-gmessages decides (chatinfo.go): a group is
    // isGroupChat; the other person of a 1:1 is the one participant that isn't you, is
    // visible (a participant with isVisible false is a stand-in Google adds, with no
    // photo) and has a number. Asked whether or not the list marks them a saved contact
    // (it rarely does); a chat without a photo is answered "none", kept a day.
    private val avatarIds = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var lastAvSummary = ""

    override fun avatarId(conversationId: String, row: JSONObject?): String? {
        if (row != null) return avatarIdOf(row).also { avatarIds[conversationId] = it ?: "" }
        if (!avatarIds.containsKey(conversationId)) {
            listConversations(100)                         // off the main thread
            avatarIds.putIfAbsent(conversationId, "")      // not in the inbox: asked once
        }
        return avatarIds[conversationId]?.ifEmpty { null }
    }

    private fun learnAvatars(json: String) {
        try {
            val arr = JSONObject(json).optJSONArray("conversations") ?: return
            var oneToOne = 0; var groups = 0; var icons = 0; var none = 0
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val id = c.optString("conversationID")
                val av = avatarIdOf(c)
                if (id.isNotEmpty()) avatarIds[id] = av ?: ""
                when {
                    c.optBoolean("isGroupChat", false) -> { groups++; if (av != null) icons++ }
                    av == null -> none++
                    else -> oneToOne++
                }
            }
            // What the list gave, once per change (counts only: no names or numbers).
            val sum = "${arr.length()} chats: $oneToOne 1:1 asked, $groups groups " +
                "($icons with an icon), $none 1:1 with no one to ask"
            if (sum != lastAvSummary) {
                lastAvSummary = sum
                LogStore.i("GoogleMessagesService", "Contact photos: $sum")
            }
        } catch (_: Exception) {}
    }

    private fun avatarIdOf(c: JSONObject): String? {
        if (c.optBoolean("isGroupChat", false))
            return c.optString("groupAvatarURL").takeIf { it.isNotEmpty() }?.let { "g:$it" }
        val parts = c.optJSONArray("participants") ?: return null
        val numbers = HashSet<String>()
        var pid = ""
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            if (p.optBoolean("isMe", false) || !p.optBoolean("isVisible", false)) continue
            val id = p.optJSONObject("ID") ?: continue
            val num = id.optString("number")
            if (num.isEmpty()) continue
            numbers.add(num.filter { it.isDigit() || it == '+' }.ifEmpty { num })
            if (pid.isEmpty()) pid = id.optString("participantID")
        }
        return if (numbers.size == 1 && pid.isNotEmpty()) "p:$pid" else null
    }

    // The bridge's errors go up (a timeout: asked again later); no photo comes back null.
    override fun avatarBytes(avatarId: String): ByteArray? {
        val b = Mirror.bridge ?: throw IllegalStateException("no bridge")
        return when {
            avatarId.startsWith("p:") -> b.participantThumbnail(avatarId.substring(2))
            avatarId.startsWith("g:") -> b.groupAvatar(avatarId.substring(2))
            else -> null
        }?.takeIf { it.isNotEmpty() }
    }

    override fun fetchMessages(conversationId: String, count: Long, cursor: String): String {
        val resp = Mirror.bridge?.fetchMessages(conversationId, count, cursor) ?: "{\"messages\":[]}"
        // Capture the pagination cursor for backfill (3b). The response carries a
        // top-level "cursor" object ({lastItemID,lastItemTimestamp}) anchoring the
        // OLDEST message in this batch; pass it back to fetch the next-older batch.
        // Store it keyed by convo; an absent/empty cursor means we've reached the
        // true start of the thread -> mark exhausted so backfill stops.
        try {
            val o = org.json.JSONObject(resp)
            val cur = o.optJSONObject("cursor")
            // Key by the EXACT conversationId string this method received — the same
            // string openThread/the span handler pass (currentOpenConvoId), so the
            // backfill lookup matches guaranteed, whatever the id format.
            if (cur != null && cur.optString("lastItemID").isNotEmpty()) {
                Mirror.backfillCursor[conversationId] = cur.toString()
                Mirror.backfillExhausted[conversationId] = false
            } else {
                Mirror.backfillExhausted[conversationId] = true   // no cursor = true top
            }
        } catch (_: Exception) { /* non-fatal: backfill just won't advance */ }
        return resp
    }

    // ---- New chat (People.kt) -------------------------------------------------
    // The contacts as Messages for web lists them, the top ones first; picking one gets
    // the conversation with their number (Google Messages makes one if there's none).
    override val supportsNewChat = true

    override fun listPeople(): String = Mirror.bridge?.listPeople() ?: "{\"people\":[]}"

    override fun openPerson(personId: String): String =
        try {
            Mirror.bridge?.openPerson(personId) ?: ""
        } catch (e: Throwable) {
            LogStore.e("GoogleMessagesService", "openPerson failed", e)
            ""
        }

    override fun sendMessage(conversationId: String, text: String, replyToken: String): String {
        val route = decodeRoute(replyToken)
        return Mirror.bridge?.sendMessage(
            conversationId, text,
            route.participantID, route.simTwo, route.simNumber
        ) ?: "{\"error\":\"no bridge\"}"
    }

    // Detail-view reply: an RCS reply to targetMessageId. Same SIM routing as a
    // normal send (resolved from the conversation), with the quoted message id
    // handed to the bridge. On a bridge error we return an error JSON so the
    // caller falls back to a plain message rather than dropping the reply.
    override fun sendReplyTo(
        conversationId: String, targetMessageId: String,
        targetFromMe: Boolean, text: String
    ): String {
        val route = routeFor(conversationId)
        return try {
            Mirror.bridge?.sendReply(
                conversationId, text, route.participantID, targetMessageId,
                route.simTwo, route.simNumber
            ) ?: "{\"error\":\"no bridge\"}"
        } catch (e: Throwable) {
            LogStore.e("GoogleMessagesService", "sendReplyTo failed", e)
            JSONObject().put("error", e.message ?: "reply send failed").toString()
        }
    }

    // Detail-view emoji reply: an RCS tapback on targetMessageId. No SIM routing
    // needed. The sent reaction surfaces back via the message's embedded reactions
    // on the next update (same path received reactions use), so no optimistic echo.
    override fun sendReaction(
        conversationId: String, targetMessageId: String,
        targetFromMe: Boolean, emoji: String
    ): String {
        return try {
            Mirror.bridge?.sendReaction(targetMessageId, emoji, false)
                ?: "{\"error\":\"no bridge\"}"
        } catch (e: Throwable) {
            LogStore.e("GoogleMessagesService", "sendReaction failed", e)
            JSONObject().put("error", e.message ?: "reaction send failed").toString()
        }
    }

    // The user's own reaction [emoji] taken back (the same emoji sent again, step 101):
    // libgm's REMOVE names the reaction.
    override fun removeReaction(
        conversationId: String, targetMessageId: String,
        targetFromMe: Boolean, emoji: String
    ): String {
        return try {
            Mirror.bridge?.sendReaction(targetMessageId, emoji, true)
                ?: "{\"error\":\"no bridge\"}"
        } catch (e: Throwable) {
            LogStore.e("GoogleMessagesService", "removeReaction failed", e)
            JSONObject().put("error", e.message ?: "reaction removal failed").toString()
        }
    }

    // Mark a conversation read on Google Messages itself: libgm sends a read
    // receipt up through messageId, clearing the thread's unread state on the
    // source (not just dismissing the local notification). Errors return an error
    // JSON so the receiver can log without crashing.
    override fun markRead(conversationId: String, messageId: String): String {
        return try {
            Mirror.bridge?.markRead(conversationId, messageId)
                ?: "{\"error\":\"no bridge\"}"
        } catch (e: Throwable) {
            LogStore.e("GoogleMessagesService", "markRead failed", e)
            JSONObject().put("error", e.message ?: "mark-read failed").toString()
        }
    }

    // The SIM route for a conversation (cached per-conversation in Mirror.simRoutes
    // via convoOrder), or a default route when unknown. Shared by replyTokenFor and
    // sendReplyTo so both route identically.
    private fun routeFor(conversationId: String): Mirror.SimRoute {
        val idx = Mirror.convoOrder.indexOf(conversationId)
        return if (idx >= 0 && idx < Mirror.simRoutes.size) Mirror.simRoutes[idx]
        else Mirror.SimRoute("2", 2L, 2L)
    }

    override fun replyTokenFor(conversationId: String): String {
        // Encode this conversation's SIM route as the opaque token sendMessage decodes.
        val route = routeFor(conversationId)
        return JSONObject().apply {
            put("p", route.participantID)
            put("t", route.simTwo)
            put("n", route.simNumber)
        }.toString()
    }

    private fun decodeRoute(token: String): Mirror.SimRoute {
        return try {
            if (token.isEmpty()) Mirror.SimRoute("2", 2L, 2L)
            else {
                val o = JSONObject(token)
                Mirror.SimRoute(
                    o.optString("p", "2"),
                    o.optLong("t", 2L),
                    o.optLong("n", 2L)
                )
            }
        } catch (e: Exception) {
            Mirror.SimRoute("2", 2L, 2L)
        }
    }

    override fun downloadImage(
        mediaId: String, decryptionKeyB64: String,
        maxW: Int, maxH: Int, maxBytes: Int, quality: Int
    ): String =
        Mirror.bridge?.downloadImageForWatch(
            mediaId, decryptionKeyB64,
            maxW.toLong(), maxH.toLong(), maxBytes.toLong(), quality.toLong()
        ) ?: "{\"error\":\"no bridge\"}"

    override fun processInlineImage(
        mediaDataB64: String,
        maxW: Int, maxH: Int, maxBytes: Int, quality: Int
    ): String =
        Mirror.bridge?.processInlineImageForWatch(
            mediaDataB64,
            maxW.toLong(), maxH.toLong(), maxBytes.toLong(), quality.toLong()
        ) ?: "{\"error\":\"no bridge\"}"

    // ---- Unified media (still OR animation, decided from the bytes) ---------
    // One download, one answer. See MessagingService.downloadMediaForWatch for
    // why this can't be split into "ask, then fetch".

    override fun downloadMediaForWatch(
        mediaId: String, decryptionKeyB64: String,
        maxW: Int, maxH: Int, maxBytes: Int, quality: Int,
        fpsFloor: Int, maxFrames: Int, gifBytes: Int, startRung: Int
    ): String =
        try {
            Mirror.bridge?.downloadMediaForWatch(
                mediaId, decryptionKeyB64,
                maxW.toLong(), maxH.toLong(), maxBytes.toLong(), quality.toLong(),
                fpsFloor.toLong(), maxFrames.toLong(), gifBytes.toLong(), startRung.toLong()
            ) ?: ""
        } catch (e: Throwable) {
            LogStore.w("GoogleMessagesService", "downloadMediaForWatch failed: ${e.message}")
            ""   // empty = caller falls back to the still path
        }

    override fun downloadMediaToFile(mediaId: String, decryptionKeyB64: String, path: String): Long =
        try {
            Mirror.bridge?.downloadMediaToFile(mediaId, decryptionKeyB64, path) ?: -1L
        } catch (e: Throwable) {
            LogStore.w("GoogleMessagesService", "downloadMediaToFile failed: ${e.message}")
            -1L
        }

    override fun processInlineMediaForWatch(
        mediaDataB64: String,
        maxW: Int, maxH: Int, maxBytes: Int, quality: Int,
        fpsFloor: Int, maxFrames: Int, gifBytes: Int, startRung: Int
    ): String =
        try {
            Mirror.bridge?.processInlineMediaForWatch(
                mediaDataB64,
                maxW.toLong(), maxH.toLong(), maxBytes.toLong(), quality.toLong(),
                fpsFloor.toLong(), maxFrames.toLong(), gifBytes.toLong(), startRung.toLong()
            ) ?: ""
        } catch (e: Throwable) {
            LogStore.w("GoogleMessagesService", "processInlineMediaForWatch failed: ${e.message}")
            ""
        }
}

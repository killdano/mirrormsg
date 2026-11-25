package com.dano.mirrormsg

import java.security.MessageDigest

/**
 * Dash Cam hand-off: what MirrorMap Dash may ask MirrorMsg to send, the text MirrorMsg
 * writes for it, and how often. Plain Kotlin (no Android types) so the JVM unit tests
 * cover it; CamPickActivity and CamSendService are the Android halves.
 *
 * The caller never supplies text or a recipient: only a link token, the link kind, a
 * sport, a first name and an activity id, each checked against an exact pattern. The
 * message is one of three fixed templates below, sent to the chats the user picked in
 * MirrorMsg's own picker.
 */
object CamRules {
    const val DASH_PACKAGE = "com.mirrormap.dash"
    const val ACTION_PICK = "com.dano.mirrormsg.action.CAM_PICK_CHAT"
    const val ACTION_SEND = "com.dano.mirrormsg.action.CAM_SEND"

    // Picker extras (in) and result extras (out).
    const val EXTRA_SLOT = "slot"
    const val EXTRA_MULTI = "multi"
    const val EXTRA_CLEAR = "clear"
    const val EXTRA_COUNT = "count"
    const val EXTRA_NETWORK = "network"

    // Messenger codes and fields.
    const val MSG_SEND = 1
    const val MSG_RESULT = 2
    const val KEY_KIND = "kind"
    const val KEY_SLOT = "slot"
    const val KEY_LINK_KIND = "linkKind"
    const val KEY_TOKEN = "token"
    const val KEY_SPORT = "sport"
    const val KEY_FIRST_NAME = "firstName"
    const val KEY_ACT_ID = "actId"
    const val KEY_SENT = "sent"
    const val KEY_ERROR = "error"

    const val ERR_NOT_ALLOWED = "not_allowed"
    const val ERR_BAD_REQUEST = "bad_request"
    const val ERR_NOT_SET_UP = "not_set_up"
    const val ERR_LIMIT = "limit"
    const val ERR_FAILED = "failed"

    const val KIND_PERSON = "person"
    const val KIND_PUBLIC = "public"
    const val KIND_HELP = "help"
    const val LINK_CAM = "cam"
    const val LINK_LIVE = "live"

    /** Distinct activities that may send in any 24 hours. */
    const val MAX_ACTIVITIES_PER_DAY = 5
    const val DAY_MS = 24L * 60 * 60 * 1000
    /** How long a sent (activity, kind, slot) is remembered, so it is never sent twice. */
    const val KEEP_SENT_MS = 14 * DAY_MS
    /** Most chats in the standing list for the public link. */
    const val MAX_LIST_CHATS = 20

    private val SLOT = Regex("[A-Za-z0-9_-]{1,40}")
    private val TOKEN = Regex("[A-Za-z0-9_-]{22}")
    private val ACT_ID = Regex("[A-Za-z0-9_-]{1,64}")
    private val FIRST_NAME = Regex("[\\p{L} '\\-]{1,20}")
    private val SPORTS = setOf("run", "ride", "walk", "hike", "activity")

    fun validSlot(s: String?): Boolean = s != null && SLOT.matches(s)

    /** A checked request. [slot] is "" for the public link (it has none). */
    data class Request(
        val kind: String,
        val slot: String,
        val linkKind: String,
        val token: String,
        val sport: String,
        val firstName: String,
        val actId: String,
    ) {
        /** Sent at most once per this key. */
        val key: String get() = "$actId|$kind|$slot"
        /** The picked chats it goes to: the slot's, or the standing list (null). */
        val listSlot: String? get() = if (kind == KIND_PUBLIC) null else slot

        // Never the token in a log line: the link is the key to the live map.
        override fun toString(): String = "Request(kind=$kind, sport=$sport)"
    }

    /**
     * The request whose string fields [get] returns by key, or null when any field is
     * missing or doesn't match its pattern. A person's and a help message's link is
     * always a /cam/ link, the public one always /live/.
     */
    fun parse(get: (String) -> String?): Request? {
        val kind = get(KEY_KIND)
        val wantLink = when (kind) {
            KIND_PERSON, KIND_HELP -> LINK_CAM
            KIND_PUBLIC -> LINK_LIVE
            else -> return null
        }
        val slot = if (kind == KIND_PUBLIC) "" else get(KEY_SLOT)?.takeIf { SLOT.matches(it) } ?: return null
        val linkKind = get(KEY_LINK_KIND)?.takeIf { it == wantLink } ?: return null
        val token = get(KEY_TOKEN)?.takeIf { TOKEN.matches(it) } ?: return null
        val sport = get(KEY_SPORT)?.takeIf { it in SPORTS } ?: return null
        val firstName = get(KEY_FIRST_NAME)?.takeIf { FIRST_NAME.matches(it) } ?: return null
        val actId = get(KEY_ACT_ID)?.takeIf { ACT_ID.matches(it) } ?: return null
        return Request(kind, slot, linkKind, token, sport, firstName, actId)
    }

    /** The message for [r]: one of the three fixed templates, nothing else. */
    fun text(r: Request): String = when (r.kind) {
        KIND_HELP ->
            "${r.firstName} pressed Need help on Dash Cam. See where: https://mirrormap.app/cam/${r.token}"
        KIND_PUBLIC ->
            "${r.firstName} is out on a ${r.sport}. Watch my Dash Cam: https://mirrormap.app/live/${r.token}"
        else ->
            "${r.firstName} is out on a ${r.sport}. Watch my Dash Cam: https://mirrormap.app/cam/${r.token}"
    }

    /** A picked chat's short, stable key (its network and id, hashed). */
    fun chatKey(serviceId: String, convoId: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest("$serviceId\u0000$convoId".toByteArray())
        return d.take(8).joinToString("") { "%02x".format(it) }
    }

    /** A SHA-256 fingerprint written as hex (colons and spaces allowed) as its 32 bytes, or null. */
    fun hexBytes(fp: String): ByteArray? {
        val h = fp.filter { it != ':' && !it.isWhitespace() }
        if (h.length != 64 || h.any { Character.digit(it, 16) < 0 }) return null
        return ByteArray(32) { i -> ((Character.digit(h[2 * i], 16) shl 4) or Character.digit(h[2 * i + 1], 16)).toByte() }
    }
}

/**
 * What has been sent: each activity's first send (for the daily limit) and, per
 * (activity, kind, slot), the chats that already have it (so nothing goes twice, and a
 * retry after a partial failure reaches only the chats still missing it).
 */
class CamLedger {
    private val activities = LinkedHashMap<String, Long>()      // actId -> first send
    private val sent = LinkedHashMap<String, Sent>()            // Request.key -> chats sent

    private class Sent(val atMs: Long, val chats: LinkedHashSet<String>)

    sealed class Decision {
        data class Refuse(val error: String) : Decision()
        /** The chats still to send to (empty: every picked chat already has it). */
        data class Send(val chatKeys: List<String>) : Decision()
    }

    /** What to do with [r], going to the picked chats [chosen] (their chat keys), at [now]. */
    fun decide(r: CamRules.Request, chosen: List<String>, now: Long): Decision {
        if (chosen.isEmpty()) return Decision.Refuse(CamRules.ERR_NOT_SET_UP)
        prune(now)
        val done = sent[r.key]?.chats ?: emptySet<String>()
        val todo = chosen.distinct().filter { it !in done }
        if (todo.isEmpty()) return Decision.Send(emptyList())
        if (r.actId !in activities && activities.size >= CamRules.MAX_ACTIVITIES_PER_DAY) {
            return Decision.Refuse(CamRules.ERR_LIMIT)
        }
        return Decision.Send(todo)
    }

    /** [r] reached chat [chatKey] at [now]. */
    fun record(r: CamRules.Request, chatKey: String, now: Long) {
        if (r.actId !in activities) activities[r.actId] = now
        sent.getOrPut(r.key) { Sent(now, LinkedHashSet()) }.chats.add(chatKey)
    }

    /** Chats reached for activity [actId] and [kind], every slot together. */
    fun sentCount(actId: String, kind: String): Int =
        sent.entries.filter { it.key.startsWith("$actId|$kind|") }.sumOf { it.value.chats.size }

    /** Activities counted against the daily limit at [now]. */
    fun activitiesToday(now: Long): Int { prune(now); return activities.size }

    private fun prune(now: Long) {
        activities.entries.removeAll { now - it.value >= CamRules.DAY_MS || it.value > now + CamRules.DAY_MS }
        sent.entries.removeAll { now - it.value.atMs >= CamRules.KEEP_SENT_MS || it.value.atMs > now + CamRules.DAY_MS }
        while (sent.size > MAX_SENT) sent.remove(sent.keys.first())
    }

    /** One line per entry: "A<tab>actId<tab>ms", "S<tab>key<tab>ms<tab>chat,chat". */
    fun encode(): String = buildString {
        for ((a, t) in activities) append("A\t").append(a).append('\t').append(t).append('\n')
        for ((k, s) in sent) {
            append("S\t").append(k).append('\t').append(s.atMs).append('\t')
            append(s.chats.joinToString(",")).append('\n')
        }
    }

    companion object {
        private const val MAX_SENT = 500

        /** The ledger [s] encodes; lines it can't read are dropped. */
        fun decode(s: String?): CamLedger {
            val l = CamLedger()
            if (s.isNullOrEmpty()) return l
            for (line in s.split('\n')) {
                val f = line.split('\t')
                when {
                    f.size == 3 && f[0] == "A" -> f[2].toLongOrNull()?.let { l.activities[f[1]] = it }
                    f.size == 4 && f[0] == "S" -> f[2].toLongOrNull()?.let { t ->
                        val chats = f[3].split(',').filter { it.isNotEmpty() }
                        if (chats.isNotEmpty()) l.sent[f[1]] = Sent(t, LinkedHashSet(chats))
                    }
                }
            }
            return l
        }
    }
}

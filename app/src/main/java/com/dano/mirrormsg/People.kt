package com.dano.mirrormsg

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
 * New chat on the watch: the people one account can start a chat with, shown as a
 * level of the watch's list (navStack's [CTX], a BACK row above them), filtered by the
 * screen's own draft ([DRAFT_ID] in DraftStore, which the watch's compose bar shows
 * and its reply menu's Voice and Keyboard fill). Select on a person opens the chat
 * with them (the network's openPerson). The filter is never a message: it's never
 * sent, and it's cleared when the screen starts and when it ends.
 *
 * Who comes first: the people the account has a chat with, in the chat list's order,
 * then the ones the network says are written to most, then by name. With a filter,
 * the closest matches first ([score]): the name's first word, then any word of it
 * starting with what was said or typed, then any part of a word, ignoring case and
 * accents; a number or username also matches its detail line.
 */
internal object People {
    const val CTX = "mirrormsg:people"            // its navStack entry
    const val MORE_CTX = "mirrormsg:people-more"  // its "type more" and "none" rows
    const val DRAFT_ID = "mirrormsg:people"       // the filter's DraftStore key
    const val ROW_PREFIX = "mirrormsg:person:"    // a person row's conversationID (+ its row)
    const val MAX_MATCHES = 40                    // with a filter: the closest, then "type more"
    const val MAX_ALL = 199                       // with none (the list's cap less that row)

    /** [rank]: lower comes first; Int.MAX_VALUE: not known. */
    data class Person(val id: String, val name: String, val detail: String,
                      val rank: Int, val color: String = "")

    @Volatile var all: List<Person> = emptyList()
    /** The people on the watch's rows, in row order (the last list streamed). */
    @Volatile var shown: List<Person> = emptyList()
    @Volatile var loadFailed: Boolean = false

    // ---- Matching (pure: unit-tested) ------------------------------------------------

    /** Lowercase, without accents ("Zoë" -> "zoe"). */
    fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "").lowercase()

    private val MARKS = Regex("\\p{M}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val SPACES = Regex("\\s+")

    private fun digits(s: String): String = s.filter { it.isDigit() }

    /**
     * How well a person matches [query] (0 = not at all). Every word of the query
     * has to be found: as the start of the name's first word (4), of any of its words
     * (3), anywhere in the name (2), or in the detail line (1: a username, or 3+
     * digits of a number). The whole query starting the name adds 4, being it 8.
     */
    fun score(name: String, detail: String, query: String): Int {
        val q = fold(query).trim()
        if (q.isEmpty()) return 1
        val n = fold(name).trim()
        val words = n.split(NON_WORD).filter { it.isNotEmpty() }
        val d = fold(detail)
        val dd = digits(d)
        var total = 0
        for (w0 in q.split(SPACES)) {
            val w = w0.trim()
            if (w.isEmpty()) continue
            val num = digits(w).length == w.length       // all digits: a piece of a number
            total += when {
                words.firstOrNull()?.startsWith(w) == true -> 4
                words.any { it.startsWith(w) } -> 3
                n.contains(w) -> 2
                !num && d.contains(w) -> 1
                num && w.length >= 3 && dd.contains(w) -> 1
                else -> return 0
            }
        }
        if (n.startsWith(q)) total += 4
        if (n == q) total += 8
        return total
    }

    /** Everyone in order: who comes first, then by name. */
    fun ordered(list: List<Person>): List<Person> =
        list.sortedWith(compareBy<Person> { it.rank }.thenBy { fold(it.name) })

    /** The people matching [filter], best first; a blank filter: everyone, in order. */
    fun match(list: List<Person>, filter: String): List<Person> {
        if (filter.isBlank()) return ordered(list)
        return list.map { it to score(it.name, it.detail, filter) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Person, Int>> { it.second }
                .thenBy { it.first.rank }.thenBy { fold(it.first.name) })
            .map { it.first }
    }

    /**
     * Who comes first: [chatPos] (the person's chat's place in the account's chat
     * list), else the network's own rank (1 = written to most), else not known.
     */
    fun rankOf(chatPos: Int?, netRank: Int): Int = when {
        chatPos != null && chatPos >= 0 -> chatPos
        netRank > 0 -> 100_000 + netRank
        else -> Int.MAX_VALUE
    }

    /** One entry per id (the one that comes first), and none without a name. */
    fun dedupe(list: List<Person>): List<Person> {
        val best = LinkedHashMap<String, Person>()
        for (p in list) {
            if (p.id.isEmpty() || p.name.isBlank()) continue
            val had = best[p.id]
            if (had == null || p.rank < had.rank) best[p.id] = p
        }
        return best.values.toList()
    }

    // ---- The account's people and the list the watch shows ---------------------------

    /**
     * Take in a network's listPeople JSON ([MessagingService.listPeople]). [chatIds]:
     * the account's chats in list order, which put the people they're with first.
     * Unreadable: nobody, and the screen says it couldn't load them.
     */
    fun load(json: String, chatIds: List<String>) {
        val pos = HashMap<String, Int>()
        chatIds.forEachIndexed { i, id -> if (id.isNotEmpty() && id !in pos) pos[id] = i }
        val out = ArrayList<Person>()
        val arr = try { JSONObject(json).optJSONArray("people") } catch (_: Exception) { null }
        loadFailed = arr == null
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val chat = o.optString("chat").ifEmpty { id }
            out.add(Person(id, o.optString("name").trim(), o.optString("detail").trim(),
                rankOf(pos[chat], o.optInt("rank", 0)), o.optString("color")))
        }
        all = dedupe(out)
        shown = emptyList()
    }

    fun clear() {
        all = emptyList()
        shown = emptyList()
        loadFailed = false
    }

    /**
     * The list for the watch, as the networks give theirs ({"conversations":[...]}):
     * the people matching [filter] (each row's conversationID names its place in
     * [shown]), then a row to type more when there are more than fit, or one saying
     * none match (or that they couldn't be loaded). Those two are folder rows (their
     * context [MORE_CTX]): picking one streams the list again (and loads it again
     * after a failure), never a chat.
     */
    fun listJson(filter: String): String {
        val m = match(all, filter)
        val cap = if (filter.isBlank()) MAX_ALL else MAX_MATCHES
        val rows = m.take(cap)
        shown = rows
        val arr = JSONArray()
        rows.forEachIndexed { i, p ->
            arr.put(JSONObject()
                .put("conversationID", ROW_PREFIX + i)
                .put("name", p.name)
                .put("type", "PEOPLE")
                .put("avatarHexColor", p.color.ifEmpty { colorFor(p.id) })
                .put("latestMessage", JSONObject().put("displayContent", p.detail)))
        }
        val note = when {
            loadFailed && all.isEmpty() -> "Couldn't load people"
            m.isEmpty() && filter.isBlank() -> "No contacts here"
            m.isEmpty() -> "No one matches"
            m.size > cap -> if (filter.isBlank()) "Type a name for more" else "Type more of the name"
            else -> null
        }
        if (note != null) arr.put(JSONObject()
            .put("conversationID", "$ROW_PREFIX-1")
            .put("name", note)
            .put("type", "PEOPLE")
            .put("navKind", "ENTER")
            .put("navContext", MORE_CTX)
            .put("avatarHexColor", "#616161"))
        return JSONObject().put("conversations", arr).toString()
    }

    private val PALETTE = arrayOf("#6A1B9A", "#2E7D32", "#1565C0", "#EF6C00", "#AD1457",
                                  "#00838F", "#4527A0", "#558B2F", "#C62828", "#4E342E")

    /** A person's circle colour, the same every time. */
    fun colorFor(id: String): String {
        var h = 0x811C9DC5.toInt()
        for (c in id) h = (h xor c.code) * 0x01000193
        return PALETTE[(h ushr 1) % PALETTE.size]
    }
}

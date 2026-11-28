package com.dano.mirrormsg

import java.text.BreakIterator

/**
 * Universal emoji substitution.
 *
 * The Pebble Time Steel font renders only a fixed set of emoji; anything else
 * shows as a "tofu" box. This filter rewrites text so the emoji we DO have a
 * good substitute for get swapped to a drawable glyph — on every path that
 * sends text to the watch or to a Pebble-mirrored notification:
 *
 *   - emoji we have a substitute for -> replaced with the closest supported glyph
 *   - everything else                -> passed through UNCHANGED
 *
 * "Everything else" deliberately includes unknown/unsupported emoji: we let them
 * through (showing tofu for now) rather than dropping them, so that when Pebble's
 * font later gains them they render correctly with no code change. Supported
 * emoji and ordinary text are likewise never touched.
 *
 * It is a pure text transform — the watch has no idea anything changed. Every
 * network gets it for free because callers funnel their outbound text through
 * [processText] (messages, conversation snippets/names, full text, and the
 * notifications Notifier posts).
 *
 * Gated by [enabled], which mirrors the user's "Emoji substitution" setting
 * (Mirror flips it when settings load), and by [watchDrawsAll] (a watch wider
 * than the Time Steel draws every emoji). When off, [processText] is a no-op.
 *
 * Reactions reuse the SAME table via [reactionGlyph] / [substitute] so the chat
 * badge and inline text never disagree about what an emoji maps to.
 *
 * The supported set + substitution map are device-confirmed.
 */
object EmojiSub {

    /** Mirrors the user's "Emoji substitution" setting. Set from Mirror on load. */
    @Volatile var enabled: Boolean = false

    /** True when the connected watch draws every emoji (wider than the Time Steel's
     *  144: Time 2, Round 2). Set from Mirror. The filter is off there whatever the
     *  setting says, since its switch is hidden for those watches. */
    @Volatile var watchDrawsAll: Boolean = false

    private fun active(): Boolean = enabled && !watchDrawsAll

    private const val VS16 = "\uFE0F"  // emoji variation selector
    // Match on a normalized key so "❤️" (with VS16) and "❤" compare equal.
    private fun norm(s: String): String = s.replace(VS16, "")

    /** The emoji the Pebble font renders (device-confirmed). Stored normalized. */
    private val SUPPORTED: Set<String> = (
        "😂 ❤️ 👍 😭 🙏 😘 😍 😊 🎉 😁 💕 😅 🔥 ☺️ 😆 😉 👏 🙂 😳 😎 👌 💜 😔 💪 " +
        "💖 😋 😏 😢 👉 💗 😩 💞 💙 😃 😡 😜 🙈 😄 🙌 😀 💋 👇 💔 😌 💓 😬 😱 😴 " +
        "😐 😒 😇 😈 ✌️ 😞 💚 😚 🎁 😑 😠 😮 😷 👎 💩 🍺"
    ).trim().split(" ").map { norm(it) }.toSet()

    /**
     * Unsupported emoji -> closest supported substitute. Keys normalized; values
     * kept in display form (so e.g. the heart keeps its variation selector).
     * Curated to convey ~80% of the original's intent; emoji with no good match
     * are deliberately absent here — those pass through untouched (tofu for now).
     */
    private val SUBS: Map<String, String> = buildMap {
        fun s(src: String, tgt: String) { put(norm(src), tgt) }
        // --- strong ---
        s("🤣", "😂"); s("🥰", "😍"); s("♥️", "❤️"); s("🙄", "😒")
        s("🎂", "🎉"); s("🥳", "🎉"); s("🎈", "🎉"); s("🎊", "🎉")
        s("🤤", "😋"); s("🤪", "😜"); s("❣️", "❤️"); s("🤩", "😍")
        s("🖤", "❤️"); s("☹️", "😞")
        // --- borderline (Dan-approved) ---
        s("🥺", "😔"); s("🤦", "🙈"); s("🤗", "😊"); s("✨", "🔥")
        s("💯", "🔥"); s("🌹", "❤️"); s("💐", "🎉"); s("🤞", "🙏")
        s("🙃", "🙂"); s("🤭", "🙈"); s("🌞", "😊"); s("🥵", "🔥")
        s("☀️", "😊"); s("💥", "🔥"); s("🚀", "🔥"); s("💀", "😂")
        s("🤙", "👌"); s("🧸", "💕")
        // --- new batch ---
        s("🪅", "🎉"); s("🦾", "💪")
    }

    /** True if the watch font can draw this emoji as-is. */
    fun isSupported(cluster: String): Boolean = norm(cluster) in SUPPORTED

    /** The mapped substitute for an unsupported emoji, or null if none. */
    fun substitute(cluster: String): String? = SUBS[norm(cluster)]

    /**
     * What a reaction emoji should DRAW in the watch badge: its substitute if we
     * have one (and subs are on), otherwise the emoji as-is. Supported emoji draw
     * normally; unknown emoji tofu now and render later once Pebble adds them.
     */
    fun reactionGlyph(cluster: String): String =
        (if (active()) substitute(cluster) else null) ?: cluster

    /**
     * Rewrite text, swapping only the emoji we have substitutes for; supported
     * emoji, unknown emoji, and ordinary text all pass through unchanged. No-op
     * when the setting is off or the string is plain.
     */
    fun processText(text: String): String {
        if (!active() || text.isEmpty()) return text
        // Fast path: if there's no high codepoint at all, there's no emoji to map.
        if (!mightContainEmoji(text)) return text
        return transform(text)
    }

    private fun transform(text: String): String {
        val bi = BreakIterator.getCharacterInstance()
        bi.setText(text)
        val out = StringBuilder(text.length)
        var start = bi.first()
        var end = bi.next()
        while (end != BreakIterator.DONE) {
            val cluster = text.substring(start, end)
            val sub = SUBS[norm(cluster)]
            out.append(sub ?: cluster)   // substitute if mapped, else pass through
            start = end
            end = bi.next()
        }
        return out.toString()
    }

    /** Cheap gate so plain ASCII/Latin text skips grapheme segmentation. Returns
     *  true on anything that could be an emoji; false only when clearly none. */
    private fun mightContainEmoji(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (cp >= 0x2000) return true   // emoji + symbols live well above this
            i += Character.charCount(cp)
        }
        return false
    }
}

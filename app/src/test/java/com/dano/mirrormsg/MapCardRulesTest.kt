package com.dano.mirrormsg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MapCardRulesTest {

    // ---- Dash's answer -> what MirrorMsg does ----------------------------------------

    @Test fun shownAndQueuedAreDone() {
        assertEquals(MapCardRules.Action.DONE, MapCardRules.actionFor("shown"))
        assertEquals(MapCardRules.Action.DONE, MapCardRules.actionFor("queued"))
    }

    @Test fun notShownIsTheNotification() {
        assertEquals(MapCardRules.Action.NOTIFY, MapCardRules.actionFor("not_shown"))
    }

    @Test fun everythingElseForceOpens() {
        for (r in listOf("not_open", "not_allowed", "bad_request", "failed", "timeout", "unavailable",
                "", "SHOWN", "shown ", "anything")) {
            assertEquals(r, MapCardRules.Action.FORCE_OPEN, MapCardRules.actionFor(r))
        }
        assertEquals(MapCardRules.Action.FORCE_OPEN, MapCardRules.actionFor(null))
    }

    @Test fun ownResultsAreNotDashs() {
        // MirrorMsg's own "no answer" and "no service" mean force-open too.
        assertEquals(MapCardRules.Action.FORCE_OPEN, MapCardRules.actionFor(MapCardRules.RESULT_TIMEOUT))
        assertEquals(MapCardRules.Action.FORCE_OPEN, MapCardRules.actionFor(MapCardRules.RESULT_UNAVAILABLE))
    }

    @Test fun contractNumbersAndNames() {
        assertEquals("com.mirrormap.dash.action.MSG_CARD", MapCardRules.ACTION_MSG_CARD)
        assertEquals(1, MapCardRules.MSG_SHOW)
        assertEquals(2, MapCardRules.MSG_RESULT)
        assertEquals(3, MapCardRules.MSG_EVENT)
        assertEquals(4, MapCardRules.MSG_RETURN)
        assertEquals(5, MapCardRules.MSG_RETURNED)
        assertEquals(4000L, MapCardRules.SHOW_TIMEOUT_MS)
        assertEquals(30L * 60 * 1000, MapCardRules.MARK_TTL_MS)
        assertEquals(8, MapCardRules.MAX_TOKENS)
    }

    @Test fun onlyTheMapAppsAreValid() {
        assertTrue(MapCardRules.validApp("mirrormap"))
        assertTrue(MapCardRules.validApp("mirrormov"))
        assertFalse(MapCardRules.validApp(null))
        assertFalse(MapCardRules.validApp(""))
        assertFalse(MapCardRules.validApp("MirrorMap"))
        assertFalse(MapCardRules.validApp("dash"))
    }

    // ---- Colours -----------------------------------------------------------------------

    private val main = listOf("google", "facebook", "whatsapp", "tgnative", "discord", "signalnative",
        "instagram", "xnative")
    private val all = main + listOf("fbmessenger", "wabeeper", "tgbeeper", "dcbeeper", "signal", "igbeeper",
        "x", "imessage", "linkedin", "slack", "googlechat", "line")

    @Test fun unknownNetworkIsTheDefault() {
        assertEquals(0, MapCardRules.colorFor(null))
        assertEquals(0, MapCardRules.colorFor(""))
        assertEquals(0, MapCardRules.colorFor("gvoice"))
        assertEquals(0, MapCardRules.colorFor("Google"))
    }

    @Test fun knownNetworksAreOpaqueGColor8() {
        for (id in all) {
            val c = MapCardRules.colorFor(id)
            assertTrue("$id: 0x${Integer.toHexString(c)}", c in 0xC0..0xFF)
        }
    }

    @Test fun nativeAndBeeperShareTheColour() {
        for ((a, b) in listOf("facebook" to "fbmessenger", "whatsapp" to "wabeeper", "tgnative" to "tgbeeper",
                "discord" to "dcbeeper", "signalnative" to "signal", "instagram" to "igbeeper", "xnative" to "x")) {
            assertEquals("$a / $b", MapCardRules.colorFor(a), MapCardRules.colorFor(b))
        }
    }

    @Test fun mainNetworksTellApart() {
        val colours = main.map { MapCardRules.colorFor(it) }
        assertEquals(colours.toString(), main.size, colours.toSet().size)
    }

    @Test fun brandColours() {
        assertEquals(0xC7, MapCardRules.colorFor("google"))     // blue
        assertEquals(0xC9, MapCardRules.colorFor("whatsapp"))   // green
        assertEquals(0xCB, MapCardRules.colorFor("tgnative"))   // sky blue
        assertEquals(0xD7, MapCardRules.colorFor("discord"))    // blurple
        assertEquals(0xC0, MapCardRules.colorFor("xnative"))    // black
    }

    // ---- Strings for Dash and for the log ------------------------------------------------

    @Test fun clipKeepsShortStrings() {
        assertEquals("", MapCardRules.clip("", 5))
        assertEquals("abc", MapCardRules.clip("abc", 5))
        assertEquals("abcde", MapCardRules.clip("abcde", 5))
        assertEquals("abcd", MapCardRules.clip("abcdef", 4))
    }

    @Test fun clipNeverSplitsASurrogatePair() {
        val s = "ab\uD83D\uDE00cd"            // a, b, one emoji (two chars), c, d
        assertEquals("ab", MapCardRules.clip(s, 3))
        assertEquals("ab\uD83D\uDE00", MapCardRules.clip(s, 4))
    }

    @Test fun logWordKeepsOnlyContractWords() {
        assertEquals("not_shown", MapCardRules.logWord("not_shown"))
        assertEquals("opened", MapCardRules.logWord("opened"))
        assertEquals("?", MapCardRules.logWord(null))
        assertEquals("?", MapCardRules.logWord(""))
        assertEquals("?", MapCardRules.logWord("Hello there"))
        assertEquals("?", MapCardRules.logWord("abcdefghijklmnopq"))   // 17 letters
    }

    // ---- Tokens --------------------------------------------------------------------------

    @Test fun tokensArePositiveAndCountUp() {
        val t = CardTokens()
        val a = t.add("c1", "google")
        val b = t.add("c2", "whatsapp")
        assertEquals(1, a)
        assertEquals(2, b)
        assertEquals("c1", t.get(a)!!.convoId)
        assertEquals("google", t.get(a)!!.ownerServiceId)
        assertEquals("c2", t.get(b)!!.convoId)
        assertNull(t.get(0))
        assertNull(t.get(-1))
        assertNull(t.get(3))
    }

    @Test fun onlyTheLastEightAreKept() {
        val t = CardTokens()
        val tokens = (1..10).map { t.add("c$it", "google") }
        assertEquals(8, t.size())
        assertNull(t.get(tokens[0]))
        assertNull(t.get(tokens[1]))
        for (i in 2 until 10) assertEquals("c${i + 1}", t.get(tokens[i])!!.convoId)
    }

    @Test fun tokensWrapToOne() {
        val t = CardTokens(start = Int.MAX_VALUE - 1)
        assertEquals(Int.MAX_VALUE, t.add("a", "google"))
        assertEquals(1, t.add("b", "google"))
        assertEquals(2, t.add("c", "google"))
        assertEquals("a", t.get(Int.MAX_VALUE)!!.convoId)
    }

    @Test fun aCardOpensOnce() {
        val t = CardTokens()
        val a = t.add("c1", "google")
        val e = t.firstOpen(a)
        assertNotNull(e)
        assertEquals("c1", e!!.convoId)
        assertNull(t.firstOpen(a))
        assertNull(t.firstOpen(99))
    }

    @Test fun entriesHideTheChat() {
        val t = CardTokens()
        val a = t.add("secret-chat-id", "google")
        assertFalse(t.get(a).toString().contains("secret"))
    }

    // ---- The return mark -----------------------------------------------------------------

    private val ttl = MapCardRules.MARK_TTL_MS

    @Test fun markMatchesItsChatBeforeItExpires() {
        val slot = ReturnMarkSlot()
        slot.set(ReturnMark("c1", "mirrormap", 1000L))
        val m = slot.take("c1", 1000L + ttl - 1)
        assertNotNull(m)
        assertEquals("mirrormap", m!!.app)
        assertNull(slot.peek())                      // taken once
        assertNull(slot.take("c1", 1000L + 5))
    }

    @Test fun markExpires() {
        val slot = ReturnMarkSlot()
        slot.set(ReturnMark("c1", "mirrormov", 1000L))
        assertNull(slot.take("c1", 1000L + ttl))
        assertNull(slot.peek())                      // an expired mark is dropped
    }

    @Test fun markFromTheFutureIsNotLive() {
        val m = ReturnMark("c1", "mirrormap", 5000L)
        assertFalse(m.liveAt(4999L))
        assertTrue(m.liveAt(5000L))
    }

    @Test fun anotherChatClosingKeepsTheMark() {
        val slot = ReturnMarkSlot()
        val mark = ReturnMark("c1", "mirrormap", 0L)
        slot.set(mark)
        assertNull(slot.take("c2", 10L))
        assertNull(slot.take(null, 10L))
        assertSame(mark, slot.peek())
    }

    @Test fun anotherChatForceOpenedClearsIt() {
        val slot = ReturnMarkSlot()
        val mark = ReturnMark("c1", "mirrormap", 0L)
        slot.set(mark)
        assertNull(slot.clearUnless("c1"))           // the same chat again: kept
        assertSame(mark, slot.peek())
        assertSame(mark, slot.clearUnless("c2"))
        assertNull(slot.peek())
    }

    @Test fun watchappClosingClearsIt() {
        val slot = ReturnMarkSlot()
        assertNull(slot.clear())
        slot.set(ReturnMark("c1", "mirrormap", 0L))
        assertNotNull(slot.clear())
        assertNull(slot.take("c1", 1L))
    }

    @Test fun aNewMarkReplacesTheOld() {
        val slot = ReturnMarkSlot()
        slot.set(ReturnMark("c1", "mirrormap", 0L))
        slot.set(ReturnMark("c2", "mirrormov", 10L))
        assertNull(slot.take("c1", 20L))
        assertEquals("mirrormov", slot.take("c2", 20L)!!.app)
    }

    @Test fun markHidesTheChat() {
        assertFalse(ReturnMark("secret-chat-id", "mirrormap", 0L).toString().contains("secret"))
    }
}

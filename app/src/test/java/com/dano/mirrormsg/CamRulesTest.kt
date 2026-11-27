package com.dano.mirrormsg

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CamRulesTest {

    private val token = "AbCdEfGhIjKlMnOpQr_-09"   // 22 characters

    private fun fields(vararg over: Pair<String, String?>): Map<String, String?> = linkedMapOf<String, String?>(
        "kind" to "person", "slot" to "p_1-x", "linkKind" to "cam", "token" to token,
        "sport" to "ride", "firstName" to "Ana", "actId" to "act-1",
    ).apply { for ((k, v) in over) put(k, v) }

    private fun parse(m: Map<String, String?>) = CamRules.parse { m[it] }

    // ---- Validation ----------------------------------------------------------

    @Test fun validPersonRequest() {
        val r = parse(fields())
        assertNotNull(r)
        assertEquals("person", r!!.kind)
        assertEquals("p_1-x", r.slot)
        assertEquals("act-1|person|p_1-x", r.key)
        assertEquals("p_1-x", r.listSlot)
    }

    @Test fun publicNeedsNoSlotAndIgnoresOne() {
        val r = parse(fields("kind" to "public", "linkKind" to "live", "slot" to null))
        assertNotNull(r)
        assertEquals("", r!!.slot)
        assertNull(r.listSlot)
        val bad = parse(fields("kind" to "public", "linkKind" to "live", "slot" to "bad slot!"))
        assertEquals("", bad!!.slot)
    }

    @Test fun helpIsACamLink() {
        assertNotNull(parse(fields("kind" to "help")))
        assertNull(parse(fields("kind" to "help", "linkKind" to "live")))
    }

    @Test fun linkKindMustMatchKind() {
        assertNull(parse(fields("linkKind" to "live")))
        assertNull(parse(fields("kind" to "public", "linkKind" to "cam")))
        assertNull(parse(fields("linkKind" to "CAM")))
        assertNull(parse(fields("linkKind" to null)))
    }

    @Test fun unknownKindRefused() {
        assertNull(parse(fields("kind" to "everyone")))
        assertNull(parse(fields("kind" to null)))
    }

    @Test fun slotPattern() {
        assertNull(parse(fields("slot" to null)))
        assertNull(parse(fields("slot" to "")))
        assertNull(parse(fields("slot" to "a".repeat(41))))
        assertNotNull(parse(fields("slot" to "a".repeat(40))))
        assertNull(parse(fields("slot" to "a b")))
        assertNull(parse(fields("slot" to "a/b")))
        assertNull(parse(fields("slot" to "a|b")))
        assertNull(parse(fields("slot" to "abc\n")))
        assertNull(parse(fields("kind" to "help", "slot" to null)))
    }

    @Test fun tokenPattern() {
        assertNull(parse(fields("token" to token.dropLast(1))))
        assertNull(parse(fields("token" to token + "a")))
        assertNull(parse(fields("token" to token.dropLast(1) + "=")))
        assertNull(parse(fields("token" to token.dropLast(1) + "/")))
        assertNull(parse(fields("token" to token.dropLast(1) + "?")))
        assertNull(parse(fields("token" to "$token\n")))
        assertNull(parse(fields("token" to null)))
    }

    @Test fun wordTokens() {
        val person = "airplane-sandwich-river-candle"
        val pub = "airplane-sandwich-river"
        assertEquals(person, parse(fields("token" to person))!!.token)
        assertEquals(pub, parse(fields("kind" to "public", "linkKind" to "live", "token" to pub))!!.token)
        assertEquals(
            "Ana is out on a ride. Watch my Dash Cam: https://mirrormap.app/cam/$person",
            CamRules.text(parse(fields("token" to person))!!))
        assertNull(parse(fields("token" to "airplane-sandwich")))
        assertNull(parse(fields("token" to "airplane-sandwich-river-candle-otter")))
        assertNull(parse(fields("token" to "Airplane-Sandwich-River")))
        assertNull(parse(fields("token" to "airplane_sandwich_river")))
        assertNull(parse(fields("token" to "airplane sandwich river")))
        assertNull(parse(fields("token" to "ab-sandwich-river")))
        assertNull(parse(fields("token" to "airplanes-sandwich-river")))
        assertNull(parse(fields("token" to "airplane-sandwich-river/")))
        assertNull(parse(fields("token" to "airplane-sandwich-river\n")))
        assertFalse(parse(fields("token" to person))!!.toString().contains(person))
    }

    @Test fun sportMustBeKnown() {
        for (s in listOf("run", "ride", "walk", "hike", "activity")) assertNotNull(s, parse(fields("sport" to s)))
        assertNull(parse(fields("sport" to "swim")))
        assertNull(parse(fields("sport" to "Run")))
        assertNull(parse(fields("sport" to "run. Click https://example.com")))
    }

    @Test fun firstNamePattern() {
        assertNotNull(parse(fields("firstName" to "Zoë")))
        assertNotNull(parse(fields("firstName" to "Mary-Jane O'Neil")))
        assertNotNull(parse(fields("firstName" to "Иван")))
        assertNotNull(parse(fields("firstName" to "a".repeat(20))))
        assertNull(parse(fields("firstName" to "a".repeat(21))))
        assertNull(parse(fields("firstName" to "")))
        assertNull(parse(fields("firstName" to "Ana2")))
        assertNull(parse(fields("firstName" to "Ana.")))
        assertNull(parse(fields("firstName" to "a:b")))
        assertNull(parse(fields("firstName" to "Ana\nBob")))
        assertNull(parse(fields("firstName" to "x.co/y")))
        assertNull(parse(fields("firstName" to null)))
    }

    @Test fun actIdPattern() {
        assertNotNull(parse(fields("actId" to "a".repeat(64))))
        assertNull(parse(fields("actId" to "a".repeat(65))))
        assertNull(parse(fields("actId" to "")))
        assertNull(parse(fields("actId" to "a|b")))
        assertNull(parse(fields("actId" to "a\tb")))
        assertNull(parse(fields("actId" to null)))
    }

    @Test fun slotHelper() {
        assertTrue(CamRules.validSlot("abc_DEF-123"))
        assertFalse(CamRules.validSlot(null))
        assertFalse(CamRules.validSlot(""))
        assertFalse(CamRules.validSlot("a b"))
    }

    // ---- Templates -----------------------------------------------------------

    @Test fun personTemplate() {
        assertEquals(
            "Ana is out on a ride. Watch my Dash Cam: https://mirrormap.app/cam/$token",
            CamRules.text(parse(fields())!!))
    }

    @Test fun publicTemplate() {
        assertEquals(
            "Ana is out on a run. Watch my Dash Cam: https://mirrormap.app/live/$token",
            CamRules.text(parse(fields("kind" to "public", "linkKind" to "live", "sport" to "run"))!!))
    }

    @Test fun activityTakesAn() {
        assertEquals(
            "Ana is out on an activity. Watch my Dash Cam: https://mirrormap.app/cam/$token",
            CamRules.text(parse(fields("sport" to "activity"))!!))
    }

    @Test fun helpTemplate() {
        assertEquals(
            "Ana pressed Need help on Dash Cam. See where: https://mirrormap.app/cam/$token",
            CamRules.text(parse(fields("kind" to "help"))!!))
    }

    @Test fun requestNeverPrintsItsToken() {
        val r = parse(fields())!!
        assertFalse(r.toString().contains(token))
    }

    // ---- Limits --------------------------------------------------------------

    private val hour = 60L * 60 * 1000
    private fun req(act: String, kind: String = "person", slot: String = "s1") = parse(fields(
        "actId" to act, "kind" to kind, "slot" to slot,
        "linkKind" to if (kind == "public") "live" else "cam"))!!

    private fun sendList(d: CamLedger.Decision) = (d as CamLedger.Decision.Send).chatKeys

    @Test fun nothingPickedIsNotSetUp() {
        assertEquals(CamLedger.Decision.Refuse("not_set_up"), CamLedger().decide(req("a"), emptyList(), 0))
    }

    @Test fun oncePerActivityKindAndSlot() {
        val l = CamLedger()
        val r = req("a1")
        assertEquals(listOf("c1"), sendList(l.decide(r, listOf("c1"), 1000)))
        l.record(r, "c1", 1000)
        // The same request again: nothing left to send.
        assertEquals(emptyList<String>(), sendList(l.decide(r, listOf("c1"), 2000)))
        // Another slot, another kind or another activity still goes.
        assertEquals(listOf("c1"), sendList(l.decide(req("a1", slot = "s2"), listOf("c1"), 2000)))
        assertEquals(listOf("c1"), sendList(l.decide(req("a1", kind = "help"), listOf("c1"), 2000)))
        assertEquals(listOf("c1"), sendList(l.decide(req("a2"), listOf("c1"), 2000)))
    }

    @Test fun retryReachesOnlyTheChatsStillMissing() {
        val l = CamLedger()
        val r = req("a1", kind = "public", slot = "")
        assertEquals(listOf("c1", "c2", "c3"), sendList(l.decide(r, listOf("c1", "c2", "c3", "c2"), 0)))
        l.record(r, "c1", 0)
        l.record(r, "c3", 0)
        assertEquals(listOf("c2"), sendList(l.decide(r, listOf("c1", "c2", "c3"), 1000)))
        assertEquals(2, l.sentCount("a1", "public"))
    }

    @Test fun oncePerActivityHeldForTwoWeeks() {
        val l = CamLedger()
        val r = req("a1")
        l.record(r, "c1", 0)
        assertEquals(emptyList<String>(), sendList(l.decide(r, listOf("c1"), 13 * 24 * hour)))
    }

    @Test fun fiveActivitiesADay() {
        val l = CamLedger()
        for (i in 1..5) {
            val r = req("a$i")
            assertEquals(listOf("c1"), sendList(l.decide(r, listOf("c1"), i * hour)))
            l.record(r, "c1", i * hour)
        }
        assertEquals(5, l.activitiesToday(6 * hour))
        // A sixth activity is refused...
        assertEquals(CamLedger.Decision.Refuse("limit"), l.decide(req("a6"), listOf("c1"), 6 * hour))
        // ...but the five already counted keep sending (another person, a help message).
        assertEquals(listOf("c1"), sendList(l.decide(req("a3", slot = "s2"), listOf("c1"), 6 * hour)))
        assertEquals(listOf("c9"), sendList(l.decide(req("a5", kind = "help"), listOf("c9"), 6 * hour)))
        // 24 hours after the first one counted, there's room again.
        assertEquals(listOf("c1"), sendList(l.decide(req("a6"), listOf("c1"), 25 * hour)))
        assertEquals(4, l.activitiesToday(25 * hour))
    }

    @Test fun failedSendsDontUseTheDailyLimit() {
        val l = CamLedger()
        for (i in 1..10) assertEquals(listOf("c1"), sendList(l.decide(req("a$i"), listOf("c1"), hour)))
        assertEquals(0, l.activitiesToday(hour))
    }

    @Test fun ledgerSurvivesEncoding() {
        val l = CamLedger()
        l.record(req("a1"), "c1", 1000)
        l.record(req("a1"), "c2", 1000)
        l.record(req("a2", kind = "public", slot = ""), "c3", 2000)
        val back = CamLedger.decode(l.encode())
        assertEquals(l.encode(), back.encode())
        assertEquals(emptyList<String>(), sendList(back.decide(req("a1"), listOf("c1", "c2"), 3000)))
        assertEquals(2, back.activitiesToday(3000))
        assertEquals(1, back.sentCount("a2", "public"))
    }

    @Test fun ledgerDecodeSkipsJunk() {
        val l = CamLedger.decode("junk\nA\tx\tnotanumber\nS\tk\t1\t\nA\ta1\t5\n")
        assertEquals(1, l.activitiesToday(10))
        assertEquals(0, CamLedger.decode(null).activitiesToday(0))
        assertEquals(0, CamLedger.decode("").activitiesToday(0))
    }

    // ---- Keys and fingerprints ------------------------------------------------

    @Test fun chatKeysAreStableAndDistinct() {
        assertEquals(CamRules.chatKey("whatsapp", "123"), CamRules.chatKey("whatsapp", "123"))
        assertNotEquals(CamRules.chatKey("whatsapp", "123"), CamRules.chatKey("wabeeper", "123"))
        assertNotEquals(CamRules.chatKey("a", "bc"), CamRules.chatKey("ab", "c"))
        assertEquals(16, CamRules.chatKey("google", "1").length)
    }

    @Test fun fingerprintHex() {
        val colons = "55:2D:9D:59:A0:64:C3:BA:F1:21:E0:21:5F:13:47:4C:50:B4:74:F9:E8:6D:AD:9D:66:7F:E5:CA:37:84:66:9C"
        val b = CamRules.hexBytes(colons)!!
        assertEquals(32, b.size)
        assertEquals(0x55.toByte(), b[0])
        assertEquals(0x9C.toByte(), b[31])
        assertArrayEquals(b, CamRules.hexBytes(colons.replace(":", "").lowercase()))
        assertNull(CamRules.hexBytes("55:2D"))
        assertNull(CamRules.hexBytes("zz" + colons.replace(":", "").drop(2)))
    }
}

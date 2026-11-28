package com.dano.mirrormsg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// New chat's filter (People.kt). Made-up names only.
class PeopleTest {
    private fun p(id: String, name: String, detail: String = "", rank: Int = Int.MAX_VALUE) =
        People.Person(id, name, detail, rank)

    private val crowd = listOf(
        p("1", "Alex Rivera", "+1 555 0100"),
        p("2", "Sam Alexander", "@samalex"),
        p("3", "Zoë Quinn"),
        p("4", "Jordan Lee", "+1 555 0199"),
        p("5", "Maxine Hall"),
        p("6", "Lee Jordan")
    )

    private fun names(list: List<People.Person>) = list.map { it.name }

    @Test fun foldDropsCaseAndAccents() {
        assertEquals("zoe quinn", People.fold("Zoë Quinn"))
        assertEquals("jose", People.fold("JOSÉ"))
    }

    @Test fun firstNameBeatsLastNameBeatsPartOfAWord() {
        // "alex": Alex's first name, Sam's last name, and part of nobody else's word.
        assertEquals(listOf("Alex Rivera", "Sam Alexander"), names(People.match(crowd, "alex")))
        // "ax": only inside words.
        assertEquals(listOf("Maxine Hall"), names(People.match(crowd, "ax")))
    }

    @Test fun everyWordHasToMatch() {
        assertEquals(listOf("Jordan Lee", "Lee Jordan"), names(People.match(crowd, "jordan lee")))
        assertEquals(listOf("Lee Jordan", "Jordan Lee"), names(People.match(crowd, "lee jordan")))
        assertTrue(People.match(crowd, "jordan smith").isEmpty())
    }

    @Test fun accentsAndCaseDontMatter() {
        assertEquals(listOf("Zoë Quinn"), names(People.match(crowd, "ZOE")))
        assertEquals(listOf("Zoë Quinn"), names(People.match(crowd, "zoë q")))
    }

    @Test fun detailLineMatchesUsernamesAndNumbers() {
        assertEquals(listOf("Sam Alexander"), names(People.match(crowd, "samalex")))
        assertEquals(listOf("Jordan Lee"), names(People.match(crowd, "0199")))
        // Two digits are too few to mean a number.
        assertTrue(People.match(crowd, "99").isEmpty())
    }

    @Test fun blankFilterKeepsEveryoneInOrder() {
        val ranked = listOf(p("a", "Bea", rank = 5), p("b", "Ann"), p("c", "Cal", rank = 0), p("d", "Abe"))
        assertEquals(listOf("Cal", "Bea", "Abe", "Ann"), names(People.match(ranked, "  ")))
    }

    @Test fun tiesGoToWhoComesFirst() {
        val two = listOf(p("a", "Chris Park", rank = 9), p("b", "Chris Bell", rank = 2))
        assertEquals(listOf("Chris Bell", "Chris Park"), names(People.match(two, "chris")))
    }

    @Test fun rankPutsChatsFirstThenTheNetworksOrder() {
        assertEquals(3, People.rankOf(3, 1))
        assertEquals(100_001, People.rankOf(null, 1))
        assertEquals(Int.MAX_VALUE, People.rankOf(null, 0))
        assertTrue(People.rankOf(150, 0) < People.rankOf(null, 1))
    }

    @Test fun dedupeKeepsTheBestRankAndDropsNameless() {
        val d = People.dedupe(listOf(p("x", "Kim", rank = 7), p("x", "Kim", rank = 1), p("y", " "), p("", "Nobody")))
        assertEquals(1, d.size)
        assertEquals(1, d[0].rank)
    }

    @Test fun colourIsStable() {
        assertEquals(People.colorFor("42"), People.colorFor("42"))
        assertTrue(People.colorFor("42").matches(Regex("^#[0-9A-F]{6}$")))
    }
}

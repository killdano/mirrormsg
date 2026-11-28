package com.dano.mirrormsg

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Which chats get a Call row on the watch (Calls.target). Made-up ids and numbers only. */
class CallsTest {
    @Before fun on() { Mirror.watchCalls = true }
    @After fun off() { Mirror.watchCalls = false; Mirror.convoTargets.clear() }

    private fun chat(id: String, protocol: String, address: String): String {
        Mirror.convoTargets[id] = ConvoTarget.from(protocol, address)
        return id
    }

    @Test fun textChatWithANumber() {
        val id = chat("101", "SMS", "+1 555-0100")
        assertEquals(Calls.Target(Calls.Kind.PHONE, "+1 555-0100"), Calls.target(id, "google"))
    }

    @Test fun textGroupOrShortCodeIsNot() {
        assertNull(Calls.target(chat("102", "SMS", ""), "google"))        // a group: no address
        assertNull(Calls.target(chat("103", "SMS", "12345"), "google"))   // a short code
    }

    @Test fun whatsAppPersonNotGroup() {
        val one = chat("15550100@s.whatsapp.net", "WA", "15550100")
        assertEquals(Calls.Target(Calls.Kind.WHATSAPP, "15550100"), Calls.target(one, "whatsapp"))
        assertNull(Calls.target(chat("120363000000000000@g.us", "WA", "120363000000000000"), "whatsapp"))
        assertNull(Calls.target(chat("100000000000001@lid", "WA", "100000000000001"), "whatsapp"))
    }

    @Test fun signalNeedsTheNumber() {
        assertEquals(Calls.Target(Calls.Kind.SIGNAL, "+15550100"),
            Calls.target(chat("0f0f0f0f-0000-4000-8000-000000000001", "SIGNAL", "+15550100"), "signalnative"))
        assertNull(Calls.target(chat("0f0f0f0f-0000-4000-8000-000000000002", "SIGNAL",
            "0f0f0f0f-0000-4000-8000-000000000002"), "signalnative"))
    }

    @Test fun telegramPersonNotGroup() {
        assertEquals(Calls.Target(Calls.Kind.TELEGRAM, "12345"),
            Calls.target(chat("tg:user:12345", "TG", "12345"), "tgnative"))
        assertNull(Calls.target(chat("tg:chat:678", "TG", "678"), "tgnative"))
        assertNull(Calls.target(chat("tg:channel:910", "TG", "910"), "tgnative"))
    }

    @Test fun otherNetworksAndTheSwitch() {
        assertNull(Calls.target(chat("200", "FB", "200"), "facebook"))
        val id = chat("104", "SMS", "+15550100")
        Mirror.watchCalls = false
        assertNull(Calls.target(id, "google"))
    }
}

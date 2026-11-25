package com.autodeck.protocol.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneContactMatcherTest {

    private val sampleContacts = listOf(
        PhoneContact(
            id = "1",
            name = "Maria Aparecida",
            phone = "(62) 99111-0001",
            normalizedPhone = "+5562991110001",
            hasWhatsApp = true,
            whatsAppVoipDataId = 101L,
            nicknames = listOf("Mãe", "Dona Maria"),
            phones = listOf(
                PhoneContactNumber("(62) 99111-0001", "+5562991110001", "Celular", hasWhatsApp = true, whatsAppVoipDataId = 101L),
                PhoneContactNumber("3321-1000", "+556233211000", "Casa", hasWhatsApp = false)
            ),
            starred = true,
            timesContacted = 85
        ),
        PhoneContact(
            id = "2",
            name = "Juliana Costa",
            phone = "(62) 99222-0002",
            normalizedPhone = "+5562992220002",
            hasWhatsApp = true,
            nicknames = listOf("Amor", "Ju"),
            starred = true,
            timesContacted = 120
        ),
        PhoneContact(
            id = "3",
            name = "Ana Silva",
            phone = "(62) 99333-0003",
            normalizedPhone = "+5562993330003",
            hasWhatsApp = true,
            timesContacted = 15
        ),
        PhoneContact(
            id = "4",
            name = "Ana Paula Oliveira",
            phone = "(62) 99444-0004",
            normalizedPhone = "+5562994440004",
            hasWhatsApp = true,
            timesContacted = 10
        ),
        PhoneContact(
            id = "5",
            name = "Ana Clara Souza",
            phone = "(62) 99555-0005",
            normalizedPhone = "+5562995550005",
            hasWhatsApp = false,
            timesContacted = 4
        ),
        PhoneContact(
            id = "6",
            name = "José Henrique",
            phone = "(62) 99666-0006",
            normalizedPhone = "+5562996660006",
            hasWhatsApp = true,
            nicknames = listOf("Zé", "Mecânico")
        )
    )

    @Test
    fun `busca tolerante sem acento e por apelidos encontra contatos corretamente`() {
        val mae = PhoneContactMatcher.resolve(sampleContacts, "mae")
        assertTrue(mae is ContactMatchResult.Exact)
        assertEquals("Maria Aparecida", (mae as ContactMatchResult.Exact).contact.name)

        // Sinônimo afetivo ("mamãe" -> apelido "Mãe")
        val mamae = PhoneContactMatcher.resolve(sampleContacts, "mamãe")
        assertTrue(mamae is ContactMatchResult.Exact)
        assertEquals("Maria Aparecida", (mamae as ContactMatchResult.Exact).contact.name)

        // Com artigo falado ("meu amor")
        val amor = PhoneContactMatcher.resolve(sampleContacts, "meu amor")
        assertTrue(amor is ContactMatchResult.Exact)
        assertEquals("Juliana Costa", (amor as ContactMatchResult.Exact).contact.name)

        // Sem acento no nome ("jose") e apelido ("ze", "mecanico")
        val jose = PhoneContactMatcher.resolve(sampleContacts, "jose")
        assertTrue(jose is ContactMatchResult.Exact)
        assertEquals("José Henrique", (jose as ContactMatchResult.Exact).contact.name)

        val ze = PhoneContactMatcher.resolve(sampleContacts, "ze")
        assertTrue(ze is ContactMatchResult.Exact)
        assertEquals("José Henrique", (ze as ContactMatchResult.Exact).contact.name)

        val mec = PhoneContactMatcher.resolve(sampleContacts, "mecanico")
        assertTrue(mec is ContactMatchResult.Exact)
        assertEquals("José Henrique", (mec as ContactMatchResult.Exact).contact.name)
    }

    @Test
    fun `detecta ambiguidade quando ha varias Anas e resolve quando nome completo e informado`() {
        val amb = PhoneContactMatcher.resolve(sampleContacts, "Ana")
        assertTrue("Esperado Ambiguous para 'Ana', veio $amb", amb is ContactMatchResult.Ambiguous)
        val candidates = (amb as ContactMatchResult.Ambiguous).candidates
        assertEquals(3, candidates.size)
        assertEquals(listOf("Ana Silva", "Ana Paula Oliveira", "Ana Clara Souza"), candidates.map { it.name })

        val toolText = PhoneContactMatcher.formatToolSearchResult("Ana", sampleContacts)
        assertTrue(toolText.contains("AMBIGUIDADE"))
        assertTrue(toolText.contains("Ana Silva"))
        assertTrue(toolText.contains("Ana Paula Oliveira"))
        assertTrue(toolText.contains("Ana Clara Souza"))

        // Nome mais específico resolve exatamente
        val exact = PhoneContactMatcher.resolve(sampleContacts, "Ana Paula")
        assertTrue(exact is ContactMatchResult.Exact)
        assertEquals("Ana Paula Oliveira", (exact as ContactMatchResult.Exact).contact.name)
    }

    @Test
    fun `seleciona contatos frequentes e favoritos no topo e serializa campos estendidos no protocolo`() {
        val top = PhoneContactMatcher.topFrequentContacts(sampleContacts, limit = 3)
        assertEquals(3, top.size)
        assertEquals("Juliana Costa", top[0].name) // starred + 120x
        assertEquals("Maria Aparecida", top[1].name) // starred + 85x
        assertEquals("Ana Silva", top[2].name) // 15x

        val prompt = PhoneContactMatcher.formatForSystemPrompt(sampleContacts, limit = 3)
        assertTrue(prompt.contains("Juliana Costa (apelido: Amor, Ju)"))
        assertTrue(prompt.contains("[WhatsApp]"))

        // Serialização completa de PhoneContact estendido + evento de calendário + app
        val rich = sampleContacts.first().copy(photoBase64 = "AAAA")
        val encoded = PhoneLinkCodec.encode(
            PhoneLinkMessage.Event(
                event = PhoneLinkProtocol.EVENT_CONTACTS_UPDATED,
                data = linkedMapOf(
                    "syncedAtMs" to 1_760_000_000_000L,
                    "contacts" to listOf(PhoneLinkCodec.contactToMap(rich))
                )
            )
        )
        val decoded = PhoneLinkCodec.decode(encoded) as? PhoneLinkMessage.Event
        assertNotNull(decoded)
        val list = decoded!!.data["contacts"] as? List<*>
        val parsed = PhoneLinkCodec.mapToContact(list?.firstOrNull())
        assertEquals(rich, parsed)

        val calEv = PhoneCalendarEvent(
            id = "phone_1",
            title = "Dentista",
            startMs = 1_760_100_000_000L,
            endMs = 1_760_103_600_000L,
            location = "Centro",
            dateIso = "2026-11-26",
            endDateIso = "2026-11-26"
        )
        val decodedCal = PhoneLinkCodec.mapToCalendarEvent(
            (PhoneLinkCodec.decode(
                PhoneLinkCodec.encode(
                    PhoneLinkMessage.Response(
                        id = "c1",
                        ok = true,
                        result = linkedMapOf("events" to listOf(PhoneLinkCodec.calendarEventToMap(calEv)))
                    )
                )
            ) as PhoneLinkMessage.Response).result["events"].let { (it as List<*>).first() }
        )
        assertEquals(calEv, decodedCal)

        val app = PhoneAppEntry("com.waze", "Waze")
        assertEquals(app, PhoneLinkCodec.mapToAppEntry(PhoneLinkCodec.appEntryToMap(app)))
    }
}

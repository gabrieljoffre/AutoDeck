package com.autodeck.headunit.agent

import android.app.Application
import com.autodeck.headunit.agent.copilot.ActionJsonParser
import com.autodeck.headunit.agent.copilot.CopilotIntent
import com.autodeck.headunit.agent.copilot.CopilotIntentParser
import com.autodeck.headunit.agent.live.GeminiLiveTools
import com.autodeck.headunit.agent.live.LiveFunctionCall
import com.autodeck.headunit.data.HeadUnitConfig
import com.autodeck.headunit.data.VoiceAssistantMode
import com.autodeck.headunit.media.MediaPlaybackState
import com.autodeck.headunit.media.WhatsAppNotificationItem
import com.autodeck.headunit.phone.PhoneContactsCache
import com.autodeck.protocol.coordinator.HeadUnitState
import com.autodeck.protocol.coordinator.MainTab
import com.autodeck.protocol.phone.PhoneContact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Phase13ContactsAgendaAppsTest {

    private class FakePhase13Host : CarAgentHostBridge {
        val contacts = listOf(
            PhoneContact(
                id = "1",
                name = "Maria Silva",
                phone = "(62) 99111-1111",
                normalizedPhone = "+5562991111111",
                hasWhatsApp = true,
                nicknames = listOf("Mãe"),
                starred = true,
                timesContacted = 50
            ),
            PhoneContact(
                id = "2",
                name = "Ana Paula",
                phone = "(62) 99222-2222",
                normalizedPhone = "+5562992222222",
                hasWhatsApp = true
            ),
            PhoneContact(
                id = "3",
                name = "Ana Beatriz",
                phone = "(62) 99333-3333",
                normalizedPhone = "+5562993333333",
                hasWhatsApp = true
            )
        )
        var lastCreatedEvent: Triple<String, Long, String>? = null
        var lastReminder: Pair<String, Long>? = null
        var lastOpenedTabletApp: String? = null
        var lastOpenedPhoneApp: String? = null
        var lastAgendaRange: String? = null

        override fun getConfig(): HeadUnitConfig = HeadUnitConfig(voiceAssistantMode = VoiceAssistantMode.LOCAL_ONLY)
        override fun getHeadUnitState(): HeadUnitState = HeadUnitState()
        override fun getMediaState(): MediaPlaybackState = MediaPlaybackState()
        override fun getWhatsAppMessages(): List<WhatsAppNotificationItem> = emptyList()
        override fun controlMusic(action: String, query: String?): String = action
        override fun navigateTo(destination: String?): String = "Mapa"
        override fun switchTab(tab: MainTab): String = tab.name
        override fun toggleReverseCamera(): String = "Cam"
        override fun replyLatestWhatsApp(replyText: String): String = "Ok"
        override fun markMessagesRead() = Unit

        override fun getTopFrequentContacts(limit: Int): List<PhoneContact> = contacts.take(limit)

        override suspend fun getAgendaForRange(rangeQuery: String?, startDateIso: String?, endDateIso: String?): String {
            lastAgendaRange = rangeQuery ?: startDateIso
            return "Sua agenda para ${rangeQuery ?: "hoje"} tem 1 compromisso: 09:00: Revisão Civic."
        }

        override suspend fun createCalendarEvent(
            title: String,
            startMs: Long,
            endMs: Long,
            location: String,
            reminderMinutes: Int?
        ): String {
            lastCreatedEvent = Triple(title, startMs, location)
            return "Evento \"$title\" criado na agenda."
        }

        override fun scheduleLocalReminder(text: String, triggerAtMs: Long): String {
            lastReminder = text to triggerAtMs
            return "Lembrete criado: $text."
        }

        override fun openTabletApp(appName: String): String {
            lastOpenedTabletApp = appName
            return "Abrindo $appName."
        }

        override fun listTabletAppNames(limit: Int): List<String> = listOf("YouTube Music", "Waze", "Câmera")

        override suspend fun openPhoneApp(appName: String): String {
            lastOpenedPhoneApp = appName
            return "Abrindo $appName no celular."
        }
    }

    @Test
    fun `cache local de contatos persiste e recarrega contatos com apelidos`() {
        val tmp = File.createTempFile("contacts_cache_test", ".json")
        try {
            val cache1 = PhoneContactsCache(tmp)
            val contact = PhoneContact(
                id = "c9",
                name = "Carlos Eduardo",
                phone = "62 98888-7777",
                normalizedPhone = "+5562988887777",
                hasWhatsApp = true,
                nicknames = listOf("Amor", "Cadu"),
                starred = true,
                timesContacted = 42
            )
            cache1.save(listOf(contact), syncedAtMs = 1_760_000_000_000L)

            val cache2 = PhoneContactsCache(tmp)
            assertEquals(1, cache2.count())
            assertEquals(1_760_000_000_000L, cache2.syncedAtMs())
            val found = cache2.search("cadu")
            assertEquals(1, found.size)
            assertEquals("Carlos Eduardo", found.first().name)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `GeminiLiveTools declara e executa buscar_contato, agenda, criar_evento com confirmacao, lembrete e apps`() = runBlocking {
        val decls = GeminiLiveTools.buildFunctionDeclarations()
        val names = (0 until decls.length()).map { decls.getJSONObject(it).getString("name") }.toSet()
        for (req in listOf("buscar_contato", "agenda", "criar_evento", "lembrete", "abrir_app", "abrir_no_celular")) {
            assertTrue("Ferramenta '$req' ausente em GeminiLiveTools", req in names)
        }

        val host = FakePhase13Host()
        val sysPrompt = GeminiLiveTools.buildSystemInstruction(host, emptyList(), oneShot = false)
        assertTrue("systemInstruction deve incluir contatos frequentes", sysPrompt.contains("Maria Silva (apelido: Mãe)"))
        assertTrue("systemInstruction deve mencionar capacidades", sysPrompt.contains("O QUE VOCÊ CONSEGUE FAZER"))

        // buscar_contato com ambiguidade
        val amb = GeminiLiveTools.executeTool(LiveFunctionCall("1", "buscar_contato", """{"nome":"Ana"}"""), host)
        assertTrue(amb.result.contains("AMBIGUIDADE"))
        assertTrue(amb.result.contains("Ana Paula"))
        assertTrue(amb.result.contains("Ana Beatriz"))

        // buscar_contato por apelido
        val mae = GeminiLiveTools.executeTool(LiveFunctionCall("2", "buscar_contato", """{"nome":"mae"}"""), host)
        assertTrue(mae.result.contains("Maria Silva"))

        // agenda com intervalo (aceita tanto 'dia' quanto 'intervalo')
        val ag = GeminiLiveTools.executeTool(LiveFunctionCall("3", "agenda", """{"dia":"sexta"}"""), host)
        assertEquals("sexta", host.lastAgendaRange)
        assertTrue(ag.result.contains("Revisão Civic"))

        val agIntervalo = GeminiLiveTools.executeTool(LiveFunctionCall("3b", "agenda", """{"intervalo":"essa semana"}"""), host)
        assertEquals("essa semana", host.lastAgendaRange)
        assertTrue(agIntervalo.result.contains("Revisão Civic"))

        // criar_evento exige confirmação antes de criar
        val askCreate = GeminiLiveTools.executeTool(
            LiveFunctionCall("4", "criar_evento", """{"titulo":"Dentista","data_hora":"amanhã às 3 da tarde","duracao_minutos":45,"local":"Centro"}"""),
            host
        )
        assertNull("Não pode criar evento sem confirmado=true", host.lastCreatedEvent)
        assertTrue(askCreate.result.contains("CONFIRMAÇÃO OBRIGATÓRIA"))
        assertTrue(askCreate.result.contains("Dentista"))

        val created = GeminiLiveTools.executeTool(
            LiveFunctionCall("5", "criar_evento", """{"titulo":"Dentista","data_hora":"amanhã às 3 da tarde","duracao_minutos":45,"local":"Centro","confirmado":true}"""),
            host
        )
        assertNotNull(host.lastCreatedEvent)
        assertEquals("Dentista", host.lastCreatedEvent!!.first)
        assertTrue(created.result.contains("Dentista"))

        // lembrete
        val rem = GeminiLiveTools.executeTool(
            LiveFunctionCall("6", "lembrete", """{"texto":"Calibrar pneus","quando":"daqui a 20 minutos"}"""),
            host
        )
        assertNotNull(host.lastReminder)
        assertEquals("Calibrar pneus", host.lastReminder!!.first)
        assertTrue(rem.result.contains("Calibrar pneus"))

        // abrir_app e abrir_no_celular
        GeminiLiveTools.executeTool(LiveFunctionCall("7", "abrir_app", """{"nome":"YouTube Music"}"""), host)
        assertEquals("YouTube Music", host.lastOpenedTabletApp)

        GeminiLiveTools.executeTool(LiveFunctionCall("8", "abrir_no_celular", """{"app":"Waze"}"""), host)
        assertEquals("Waze", host.lastOpenedPhoneApp)
    }

    @Test
    fun `CopilotIntentParser e fluxo offline cobrem apps, lembretes, eventos com confirmacao e capacidades`() = runBlocking {
        assertEquals(CopilotIntent.OpenTabletApp("youtube music"), CopilotIntentParser.parse("abre o YouTube Music"))
        assertEquals(CopilotIntent.OpenTabletApp("camera"), CopilotIntentParser.parse("abre a câmera"))
        assertEquals(CopilotIntent.OpenTabletApp("waze"), CopilotIntentParser.parse("abre o Waze"))
        assertEquals(CopilotIntent.OpenPhoneApp("instagram"), CopilotIntentParser.parse("abre o Instagram no celular"))
        assertEquals(CopilotIntent.ListCapabilities, CopilotIntentParser.parse("o que você consegue fazer?"))
        assertTrue(CopilotIntentParser.parse("me lembra de comprar leite daqui a 15 minutos") is CopilotIntent.SetReminder)
        assertTrue(CopilotIntentParser.parse("criar evento dentista amanhã às 3 da tarde") is CopilotIntent.CreateCalendarEvent)
        assertTrue(CopilotIntentParser.parse("buscar contato da mãe") is CopilotIntent.SearchContact)

        assertTrue(ActionJsonParser.isValidAction("SEARCH_CONTACT"))
        assertTrue(ActionJsonParser.isValidAction("AGENDA"))
        assertTrue(ActionJsonParser.isValidAction("CREATE_EVENT"))
        assertTrue(ActionJsonParser.isValidAction("SET_REMINDER"))
        assertTrue(ActionJsonParser.isValidAction("OPEN_APP"))
        assertTrue(ActionJsonParser.isValidAction("OPEN_PHONE_APP"))

        val host = FakePhase13Host()
        val agent = GeminiCarAgent(
            appContext = Application(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            host = host,
            mainDispatcher = Dispatchers.Unconfined
        )

        // Capacidades
        agent.sendUserPrompt("o que você consegue fazer?", speakReply = false)
        val caps = agent.state.value.messages.last().text
        assertTrue(caps.contains("Spotify"))
        assertTrue(caps.contains("WhatsApp"))
        assertTrue(caps.contains("agenda"))
        assertTrue(caps.contains("lembretes"))
        assertTrue(caps.contains("aplicativos"))

        // Criar evento pede confirmação e executa após "sim"
        agent.sendUserPrompt("criar evento revisão civic sexta 9h", speakReply = false)
        assertNull(host.lastCreatedEvent)
        agent.sendUserPrompt("sim", speakReply = false)
        assertNotNull(host.lastCreatedEvent)
        assertEquals("revisao civic", host.lastCreatedEvent!!.first)

        // Abrir app no tablet e no celular
        agent.sendUserPrompt("abre o YouTube Music", speakReply = false)
        assertEquals("youtube music", host.lastOpenedTabletApp)
        agent.sendUserPrompt("abre o Waze no celular", speakReply = false)
        assertEquals("waze", host.lastOpenedPhoneApp)
    }
}

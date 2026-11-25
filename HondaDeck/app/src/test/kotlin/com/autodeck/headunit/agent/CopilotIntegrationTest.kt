package com.autodeck.headunit.agent

import android.app.Application
import com.autodeck.headunit.agent.copilot.FavoriteRef
import com.autodeck.headunit.agent.copilot.HttpGetter
import com.autodeck.headunit.agent.copilot.VolumeDirection
import com.autodeck.headunit.agent.copilot.WebKnowledgeTools
import com.autodeck.headunit.data.HeadUnitConfig
import com.autodeck.headunit.data.VoiceAssistantMode
import com.autodeck.headunit.media.MediaPlaybackState
import com.autodeck.headunit.media.WhatsAppNotificationItem
import com.autodeck.protocol.coordinator.HeadUnitState
import com.autodeck.protocol.coordinator.MainTab
import com.autodeck.protocol.obd2.ObdSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CopilotIntegrationTest {

    private class FakeHost : CarAgentHostBridge {
        var cfg = HeadUnitConfig(
            voiceAssistantMode = VoiceAssistantMode.LOCAL_ONLY,
            geminiApiKey = "fake-key"
        )
        val favorites = listOf(
            FavoriteRef(id = "casa", name = "Casa", address = "Rua 1, Anápolis", lat = -16.32, lon = -48.95, voice = "ir para casa")
        )
        val actions = mutableListOf<String>()
        var lastMusicSearch: String? = null
        var lastNavigateDest: String? = null
        var lastNavigateFav: FavoriteRef? = null
        var lastMapSearch: String? = null
        var lastReplyWhatsApp: String? = null
        var openCameraCount = 0
        var closeCameraCount = 0
        var lastVolume: Pair<VolumeDirection, Int?>? = null
        var lastRefuelLiters: Double? = null

        override fun getConfig(): HeadUnitConfig = cfg

        override fun getHeadUnitState(): HeadUnitState = HeadUnitState(
            obdSnapshot = ObdSnapshot(
                rpm = 1800.0,
                speedKmh = 52.0,
                coolantTempC = 90.0,
                batteryVoltageV = 14.1,
                engineLoadPct = 30.0,
                instantKmPerL = 11.2,
                averageKmPerL = 10.4
            )
        )

        override fun getMediaState(): MediaPlaybackState = MediaPlaybackState()
        override fun getWhatsAppMessages(): List<WhatsAppNotificationItem> = emptyList()
        override fun getFavoritePlaces(): List<FavoriteRef> = favorites

        override fun controlMusic(action: String, query: String?): String {
            actions += "MUSIC:$action"
            return action
        }

        override suspend fun searchAndPlayMusic(query: String): String {
            lastMusicSearch = query
            actions += "MUSIC_SEARCH:$query"
            return "Spotify: $query"
        }

        override fun navigateTo(destination: String?): String = navigateTo(destination, null, false)

        override fun navigateTo(destination: String?, favorite: FavoriteRef?, forceExternalMaps: Boolean): String {
            lastNavigateDest = destination
            lastNavigateFav = favorite
            actions += "NAVIGATE:${destination.orEmpty()}"
            return "Rota"
        }

        override fun searchPlacesOnMap(query: String): String {
            lastMapSearch = query
            actions += "MAP_SEARCH:$query"
            return "Mapa"
        }

        override fun switchTab(tab: MainTab): String {
            actions += "TAB:${tab.name}"
            return tab.name
        }

        override fun toggleReverseCamera(): String {
            actions += "CAM_TOGGLE"
            return "Cam"
        }

        override fun openReverseCamera(): String {
            openCameraCount++
            actions += "OPEN_CAMERA"
            return "Cam aberta"
        }

        override fun closeReverseCamera(): String {
            closeCameraCount++
            actions += "CLOSE_CAMERA"
            return "Cam fechada"
        }

        override fun adjustVolume(direction: VolumeDirection, value: Int?): String {
            lastVolume = direction to value
            actions += "VOLUME:${direction.name}:${value ?: ""}"
            return "Vol"
        }

        override fun replyLatestWhatsApp(replyText: String): String {
            lastReplyWhatsApp = replyText
            actions += "REPLY_WA:$replyText"
            return "Respondido: $replyText"
        }

        override fun markMessagesRead() = Unit
        override fun getWeatherSummary(): String = "Anápolis: 25°C, céu limpo, chance de chuva 10%."
        override fun getFuelLogSummary(): String = "último tanque 10,8 km/L; gasto 30 dias 220,00 reais"
        override fun logRefuelFromAgent(
            liters: Double,
            pricePerLiter: Double?,
            odometerKm: Double?,
            fullTank: Boolean
        ): String {
            lastRefuelLiters = liters
            actions += "LOG_FUEL:$liters"
            return "Abastecimento de $liters litros registrado."
        }
    }

    private fun makeOnlineAgent(
        host: FakeHost,
        http: HttpGetter = HttpGetter { "" },
        geminiResponder: (model: String, body: JSONObject) -> Result<String>
    ): GeminiCarAgent {
        host.cfg = host.cfg.copy(
            voiceAssistantMode = VoiceAssistantMode.HYBRID_GEMINI,
            geminiApiKey = "test-key"
        )
        return GeminiCarAgent(
            appContext = Application(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            host = host,
            mainDispatcher = Dispatchers.Unconfined,
            webKnowledgeTools = WebKnowledgeTools(http),
            rawGeminiPostOverride = { model, body, _ -> geminiResponder(model, body) },
            internetCheckOverride = { true },
            ioDispatcher = Dispatchers.Unconfined
        )
    }

    @Test
    fun `offline intents cover music, favorites, navigation priority, camera, volume, report and fuel`() = runBlocking {
        val host = FakeHost()
        val agent = GeminiCarAgent(
            appContext = Application(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            host = host,
            mainDispatcher = Dispatchers.Unconfined
        )

        agent.sendUserPrompt("Tocar Coldplay", speakReply = false)
        assertEquals("coldplay", host.lastMusicSearch)

        agent.sendUserPrompt("ir para casa", speakReply = false)
        assertEquals("Casa", host.lastNavigateFav?.name)

        agent.sendUserPrompt("ir para a próxima rua", speakReply = false)
        assertFalse(host.actions.contains("MUSIC:MUSIC_NEXT"))
        assertTrue(host.actions.any { it.startsWith("NAVIGATE:") })

        agent.sendUserPrompt("próxima música", speakReply = false)
        assertTrue(host.actions.contains("MUSIC:MUSIC_NEXT"))

        agent.sendUserPrompt("responder whatsapp ok chego já", speakReply = false)
        assertEquals("ok chego ja", host.lastReplyWhatsApp)

        agent.sendUserPrompt("procure um posto", speakReply = false)
        assertEquals("posto", host.lastMapSearch)

        agent.sendUserPrompt("relatório do carro", speakReply = false)
        val report = agent.state.value.messages.last().text
        assertTrue(report.contains("90°C"))
        assertTrue(report.contains("14") && report.contains("V"))
        assertTrue(report.contains("Anápolis"))

        agent.sendUserPrompt("como está o tempo", speakReply = false)
        assertTrue(agent.state.value.messages.last().text.contains("25°C"))

        agent.sendUserPrompt("abasteci 30 litros a 6,19", speakReply = false)
        assertEquals(30.0, host.lastRefuelLiters ?: 0.0, 0.01)

        agent.sendUserPrompt("abrir câmera", speakReply = false)
        agent.sendUserPrompt("abrir câmera", speakReply = false)
        assertEquals(2, host.openCameraCount)
        assertEquals(0, host.closeCameraCount)

        agent.sendUserPrompt("fechar câmera", speakReply = false)
        assertEquals(1, host.closeCameraCount)

        agent.sendUserPrompt("volume no 40", speakReply = false)
        assertEquals(VolumeDirection.SET to 40, host.lastVolume)
    }

    @Test
    fun `google_search 429 falls back to GeminiToolLoop for news and wikipedia and parses fenced ACTION_JSON`() = runBlocking {
        val host = FakeHost()
        val fakeHttp = HttpGetter { url ->
            when {
                url.contains("news.google.com") -> """
                    <rss><channel><item>
                      <title>Chuva forte em Anápolis hoje - G1 Goiás</title>
                      <source>G1 Goiás</source>
                      <pubDate>Tue, 24 Nov 2026 12:00:00 GMT</pubDate>
                    </item></channel></rss>
                """.trimIndent()
                url.contains("action=opensearch") -> """["Ayrton Senna",["Ayrton Senna"],[""],["https://pt.wikipedia.org/wiki/Ayrton_Senna"]]"""
                url.contains("page/summary") -> """{"title":"Ayrton Senna","description":"piloto brasileiro","extract":"Ayrton Senna da Silva foi um piloto brasileiro de Fórmula 1 tricampeão mundial."}"""
                else -> "{}"
            }
        }

        var round = 0
        val agent = makeOnlineAgent(host, fakeHttp) { _, body ->
            val toolsStr = body.optJSONArray("tools")?.toString().orEmpty()
            if (toolsStr.contains("google_search")) {
                Result.failure(IllegalStateException("HTTP 429: RESOURCE_EXHAUSTED"))
            } else {
                round++
                val contentsStr = body.getJSONArray("contents").toString()
                if (!contentsStr.contains("functionResponse")) {
                    val fnName = if (contentsStr.contains("Ayrton Senna")) "buscar_wikipedia" else "buscar_noticias"
                    val q = if (fnName == "buscar_noticias") "Anápolis" else "Ayrton Senna"
                    Result.success(
                        """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"$fnName","args":{"consulta":"$q"}}}]}}]}"""
                    )
                } else if (contentsStr.contains("Ayrton Senna da Silva")) {
                    Result.success(
                        """{"candidates":[{"content":{"parts":[{"text":"Ayrton Senna foi tricampeão mundial de Fórmula 1.\n```json\nACTION_JSON:{\"action\":\"OPEN_MAP\",\"arg\":\"\"}\n```"}]}}]}"""
                    )
                } else {
                    Result.success(
                        """{"candidates":[{"content":{"parts":[{"text":"A principal notícia é chuva forte em Anápolis hoje pelo G1 Goiás."}]}}]}"""
                    )
                }
            }
        }

        agent.sendUserPrompt("quais as notícias de Anápolis hoje", speakReply = false)
        assertTrue(agent.state.value.messages.last().text.contains("Anápolis"))

        agent.sendUserPrompt("quem foi Ayrton Senna", speakReply = false)
        val sennaReply = agent.state.value.messages.last()
        assertTrue(sennaReply.text.contains("tricampeão"))
        assertFalse(sennaReply.text.contains("ACTION_JSON"))
        assertTrue(host.actions.contains("NAVIGATE:"))

        // Ação inválida deve ser ignorada sem quebrar a resposta
        val invalidActionAgent = makeOnlineAgent(host) { _, body ->
            val toolsStr = body.optJSONArray("tools")?.toString().orEmpty()
            if (toolsStr.contains("google_search")) {
                Result.success("""{"candidates":[{"content":{"parts":[{"text":"Tudo certo!\nACTION_JSON:{\"action\":\"INEXISTENTE\",\"arg\":\"x\"}"}]}}]}""")
            } else {
                Result.failure(IllegalStateException("unused"))
            }
        }
        invalidActionAgent.sendUserPrompt("teste", speakReply = false)
        val invalidReply = invalidActionAgent.state.value.messages.last()
        assertEquals("Tudo certo!", invalidReply.text)
        assertNull(invalidReply.actionBadge)
    }
}

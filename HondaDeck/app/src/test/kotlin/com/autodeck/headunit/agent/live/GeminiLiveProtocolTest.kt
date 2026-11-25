package com.autodeck.headunit.agent.live

import com.autodeck.headunit.agent.CarAgentHostBridge
import com.autodeck.headunit.agent.copilot.FavoriteRef
import com.autodeck.headunit.agent.copilot.VolumeDirection
import com.autodeck.headunit.data.DEFAULT_GEMINI_LIVE_MODEL
import com.autodeck.headunit.data.HeadUnitConfig
import com.autodeck.headunit.media.MediaPlaybackState
import com.autodeck.headunit.media.WhatsAppNotificationItem
import com.autodeck.protocol.coordinator.HeadUnitState
import com.autodeck.protocol.coordinator.LinkStatus
import com.autodeck.protocol.coordinator.MainTab
import com.autodeck.protocol.obd2.AlertSeverity
import com.autodeck.protocol.obd2.DerivedTelemetry
import com.autodeck.protocol.obd2.DtcCode
import com.autodeck.protocol.obd2.DtcSystem
import com.autodeck.protocol.obd2.ObdAlert
import com.autodeck.protocol.obd2.ObdSnapshot
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

class GeminiLiveProtocolTest {

    @Test
    fun `monta URL WebSocket e mensagem setup completa com AUDIO, voz pt-BR, googleSearch e functionDeclarations`() {
        val url = GeminiLiveProtocol.buildWebSocketUrl("AIza-test-key-123")
        assertTrue(url.startsWith("wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key="))
        assertTrue(url.endsWith("AIza-test-key-123"))

        val decls = GeminiLiveTools.buildFunctionDeclarations()
        assertTrue("Esperadas ferramentas do app", decls.length() >= 15)

        val setupEnvelope = GeminiLiveProtocol.buildSetupMessage(
            model = DEFAULT_GEMINI_LIVE_MODEL,
            systemInstruction = "Você é o copiloto do Honda Civic em Anápolis-GO.",
            functionDeclarations = decls,
            voiceName = "Aoede",
            languageCode = "pt-BR",
            enableGoogleSearch = true
        )

        val setup = setupEnvelope.getJSONObject("setup")
        assertEquals("models/gemini-2.5-flash-native-audio-latest", setup.getString("model"))

        val genCfg = setup.getJSONObject("generationConfig")
        val modalities = genCfg.getJSONArray("responseModalities")
        assertEquals(1, modalities.length())
        assertEquals("AUDIO", modalities.getString(0))

        val speechCfg = genCfg.getJSONObject("speechConfig")
        assertEquals("pt-BR", speechCfg.getString("languageCode"))
        assertEquals(
            "Aoede",
            speechCfg.getJSONObject("voiceConfig")
                .getJSONObject("prebuiltVoiceConfig")
                .getString("voiceName")
        )

        val sysText = setup.getJSONObject("systemInstruction")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
        assertTrue(sysText.contains("Anápolis-GO"))

        assertTrue(setup.has("inputAudioTranscription"))
        assertTrue(setup.has("outputAudioTranscription"))

        val tools = setup.getJSONArray("tools")
        assertEquals(2, tools.length())
        assertTrue(tools.getJSONObject(0).has("googleSearch"))
        val fnDecls = tools.getJSONObject(1).getJSONArray("functionDeclarations")
        val names = (0 until fnDecls.length()).map { fnDecls.getJSONObject(it).getString("name") }.toSet()
        assertTrue("controlar_musica" in names)
        assertTrue("ajustar_volume" in names)
        assertTrue("iniciar_rota" in names)
        assertTrue("encerrar_rota" in names)
        assertTrue("ler_telemetria_carro" in names)
        assertTrue("registrar_abastecimento" in names)
        assertTrue("limpar_memoria_conversas" in names)
    }

    @Test
    fun `monta realtimeInput audio PCM 16kHz base64 e toolResponse corretamente`() {
        val rawPcm = byteArrayOf(0x01, 0x02, 0x10, 0x20, 0x7F, -128)
        val audioMsg = GeminiLiveProtocol.buildRealtimeAudioMessage(rawPcm)

        val audio = audioMsg.getJSONObject("realtimeInput").getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", audio.getString("mimeType"))
        val decoded = Base64.getDecoder().decode(audio.getString("data"))
        assertArrayEquals(rawPcm, decoded)

        val toolResp = GeminiLiveProtocol.buildToolResponseMessage(
            listOf(
                LiveToolResponse(
                    id = "call-42",
                    name = "ajustar_volume",
                    result = "Volume 50%",
                    success = true
                )
            )
        )
        val fnResponses = toolResp.getJSONObject("toolResponse").getJSONArray("functionResponses")
        assertEquals(1, fnResponses.length())
        val first = fnResponses.getJSONObject(0)
        assertEquals("call-42", first.getString("id"))
        assertEquals("ajustar_volume", first.getString("name"))
        assertEquals("Volume 50%", first.getJSONObject("response").getString("result"))
        assertTrue(first.getJSONObject("response").getBoolean("ok"))
    }

    @Test
    fun `parsing de setupComplete, serverContent (audio 24kHz, transcricoes, interrupted, turnComplete) e toolCall`() {
        assertEquals(
            LiveServerEvent.SetupComplete,
            GeminiLiveProtocol.parseServerMessage("""{"setupComplete": {}}""")
        )

        val samplePcm = byteArrayOf(10, 20, 30, 40, 50, 60)
        val b64 = Base64.getEncoder().encodeToString(samplePcm)
        val serverContentJson = """
            {
              "serverContent": {
                "modelTurn": {
                  "parts": [
                    {
                      "inlineData": {
                        "mimeType": "audio/pcm;rate=24000",
                        "data": "$b64"
                      }
                    }
                  ]
                },
                "inputTranscription": { "text": "Qual a temperatura do motor?" },
                "outputTranscription": { "text": "O motor está a 89 graus, tudo normal." },
                "turnComplete": true
              }
            }
        """.trimIndent()

        val parsedContent = GeminiLiveProtocol.parseServerMessage(serverContentJson)
        assertTrue(parsedContent is LiveServerEvent.ServerContent)
        parsedContent as LiveServerEvent.ServerContent
        assertEquals(1, parsedContent.audioChunks.size)
        assertArrayEquals(samplePcm, parsedContent.audioChunks.first())
        assertEquals("Qual a temperatura do motor?", parsedContent.inputTranscription)
        assertEquals("O motor está a 89 graus, tudo normal.", parsedContent.outputTranscription)
        assertTrue(parsedContent.turnComplete)
        assertFalse(parsedContent.interrupted)

        // Barge-in (interrupted)
        val interruptedJson = """{"serverContent": {"interrupted": true}}"""
        val parsedInterrupted = GeminiLiveProtocol.parseServerMessage(interruptedJson) as LiveServerEvent.ServerContent
        assertTrue(parsedInterrupted.interrupted)
        assertFalse(parsedInterrupted.turnComplete)

        // Tool call com múltiplos comandos
        val toolCallJson = """
            {
              "toolCall": {
                "functionCalls": [
                  {
                    "id": "fc_1",
                    "name": "iniciar_rota",
                    "args": { "destino": "Casa", "mapa_externo": false }
                  },
                  {
                    "id": "fc_2",
                    "name": "ajustar_volume",
                    "args": { "acao": "set", "porcentagem": 55 }
                  }
                ]
              }
            }
        """.trimIndent()

        val parsedTool = GeminiLiveProtocol.parseServerMessage(toolCallJson)
        assertTrue(parsedTool is LiveServerEvent.ToolCall)
        parsedTool as LiveServerEvent.ToolCall
        assertEquals(2, parsedTool.calls.size)
        assertEquals("fc_1", parsedTool.calls[0].id)
        assertEquals("iniciar_rota", parsedTool.calls[0].name)
        assertEquals("Casa", JSONObject(parsedTool.calls[0].argsJson).getString("destino"))
        assertEquals("fc_2", parsedTool.calls[1].id)
        assertEquals(55, JSONObject(parsedTool.calls[1].argsJson).getInt("porcentagem"))

        // GoAway, Cancel e Error 429
        val goAway = GeminiLiveProtocol.parseServerMessage("""{"goAway": {"timeLeft": "30s"}}""")
        assertEquals(LiveServerEvent.GoAway("30s"), goAway)

        val cancel = GeminiLiveProtocol.parseServerMessage("""{"toolCallCancellation": {"ids": ["fc_1"]}}""")
        assertEquals(LiveServerEvent.ToolCallCancellation(listOf("fc_1")), cancel)

        val err429 = GeminiLiveProtocol.parseServerMessage(
            """{"error": {"code": 429, "message": "RESOURCE_EXHAUSTED: quota exceeded"}}"""
        ) as LiveServerEvent.Error
        assertEquals(429, err429.code)
        assertTrue(err429.isQuotaOrRateLimit)
    }

    @Test
    fun `executor de ferramentas Live aciona Spotify, volume, telas, rota, OBD, diario, WhatsApp, camera, tema e memoria`() = runBlocking {
        val host = FakeLiveHost()
        var memoryCleared = false

        val r1 = GeminiLiveTools.executeTool(
            LiveFunctionCall("1", "controlar_musica", """{"acao":"search_and_play","consulta":"Legião Urbana"}"""),
            host
        ) { memoryCleared = true }
        assertTrue(r1.result.contains("Legião Urbana"))
        assertTrue("MUSIC_SEARCH:Legião Urbana" in host.log)

        GeminiLiveTools.executeTool(
            LiveFunctionCall("2", "ajustar_volume", """{"acao":"set","porcentagem":45}"""),
            host
        ) {}
        assertTrue("VOLUME:SET:45" in host.log)

        GeminiLiveTools.executeTool(
            LiveFunctionCall("3", "controlar_tela", """{"tela":"map_full","acao":"expand"}"""),
            host
        ) {}
        assertTrue("MAP_FULL" in host.log)

        GeminiLiveTools.executeTool(
            LiveFunctionCall("4", "iniciar_rota", """{"destino":"Casa"}"""),
            host
        ) {}
        assertTrue("NAV:Casa:fav=casa" in host.log)

        GeminiLiveTools.executeTool(
            LiveFunctionCall("5", "encerrar_rota", """{}"""),
            host
        ) {}
        assertTrue("STOP_ROUTE" in host.log)

        val dtcRes = GeminiLiveTools.executeTool(
            LiveFunctionCall("6", "ler_telemetria_carro", """{"tipo":"dtc"}"""),
            host
        ) {}
        assertTrue(dtcRes.result.contains("P0420"))

        val refuelRes = GeminiLiveTools.executeTool(
            LiveFunctionCall("7", "registrar_abastecimento", """{"litros":38.5,"preco_por_litro":6.19,"tanque_cheio":true}"""),
            host
        ) {}
        assertTrue(refuelRes.result.contains("38.5"))
        assertTrue("REFUEL:38.5" in host.log)

        val waRead = GeminiLiveTools.executeTool(LiveFunctionCall("8", "ler_whatsapp", """{}"""), host) {}
        assertTrue(waRead.result.contains("Maria"))

        val waReplyAsk = GeminiLiveTools.executeTool(
            LiveFunctionCall("9a", "responder_whatsapp", """{"mensagem":"Já estou chegando"}"""),
            host
        ) {}
        assertTrue(waReplyAsk.result.contains("CONFIRMAÇÃO OBRIGATÓRIA"))

        GeminiLiveTools.executeTool(
            LiveFunctionCall("9", "responder_whatsapp", """{"mensagem":"Já estou chegando","confirmado":true}"""),
            host
        ) {}
        assertTrue("REPLY_WA:Já estou chegando" in host.log)

        GeminiLiveTools.executeTool(LiveFunctionCall("10", "controlar_camera_re", """{"acao":"open"}"""), host) {}
        assertTrue("OPEN_CAM" in host.log)

        GeminiLiveTools.executeTool(
            LiveFunctionCall("11", "configurar_aparencia", """{"tema_mapa":"LIGHT","cor_destaque":"lima"}"""),
            host
        ) {}
        assertTrue("MAP_THEME:LIGHT" in host.log)
        assertTrue("ACCENT:#A3E635" in host.log)

        GeminiLiveTools.executeTool(
            LiveFunctionCall("12", "limpar_memoria_conversas", """{}"""),
            host
        ) { memoryCleared = true }
        assertTrue(memoryCleared)

        val instruction = GeminiLiveTools.buildSystemInstruction(
            host = host,
            recentSummaries = listOf("24/11 19:00 (Conversa ao vivo): Motorista perguntou do consumo"),
            oneShot = true
        )
        assertTrue(instruction.contains("Anápolis-GO"))
        assertTrue(instruction.contains("Honda Civic"))
        assertTrue(instruction.contains("Motorista perguntou do consumo"))
        assertTrue(instruction.contains("MODO RÁPIDO"))
        assertTrue(instruction.contains("Indo para Brasil Park Shopping"))
    }

    @Test
    fun `CopilotMemoryStore persiste sessoes, gera resumo curto e limpa com clear`() {
        val tempFile = File.createTempFile("copilot_mem_test_", ".json")
        tempFile.deleteOnExit()
        try {
            val store = CopilotMemoryStore(tempFile)
            val saved = store.recordSession(
                mode = "live",
                turns = listOf(
                    CopilotMemoryTurn("user", "Qual a temperatura do motor?", 1_760_000_000_000L),
                    CopilotMemoryTurn("model", "Motor a 89 graus, tudo normal.", 1_760_000_005_000L)
                ),
                startedAtMs = 1_760_000_000_000L,
                endedAtMs = 1_760_000_010_000L
            )
            assertNotNull(saved)
            assertTrue(saved!!.summary.contains("temperatura do motor"))

            // Recarrega do arquivo para garantir persistência real
            val reloaded = CopilotMemoryStore(tempFile)
            assertEquals(1, reloaded.loadSessions().size)
            val summaries = reloaded.recentSummaries(limit = 3)
            assertEquals(1, summaries.size)
            assertTrue(summaries[0].contains("Conversa ao vivo"))

            // Comando "esqueça as conversas"
            reloaded.clear()
            assertTrue(reloaded.loadSessions().isEmpty())
            assertFalse(tempFile.exists())
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `calculo de nivel PCM16 e concatenacao de transcricoes funcionam corretamente`() {
        val silence = ByteArray(100)
        assertEquals(0f, GeminiLiveSession.computePcm16Level(silence), 0.001f)

        // Onda quadrada moderada (~8000 / 32768)
        val loud = ByteArray(200)
        for (i in 0 until 100) {
            val v = if (i % 2 == 0) 8000 else -8000
            loud[i * 2] = (v and 0xFF).toByte()
            loud[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        val level = GeminiLiveSession.computePcm16Level(loud)
        assertTrue("Esperado nível > 0.5, veio $level", level > 0.5f && level <= 1.0f)

        val sb = StringBuilder()
        GeminiLiveSession.appendTranscriptChunk(sb, "Olá")
        GeminiLiveSession.appendTranscriptChunk(sb, "motorista")
        GeminiLiveSession.appendTranscriptChunk(sb, "!")
        assertEquals("Olá motorista!", sb.toString())
    }

    private class FakeLiveHost : CarAgentHostBridge {
        val log = mutableListOf<String>()

        override fun getConfig(): HeadUnitConfig = HeadUnitConfig(geminiApiKey = "test")

        override fun getHeadUnitState(): HeadUnitState = HeadUnitState(
            obdStatus = LinkStatus.CONNECTED,
            obdSnapshot = ObdSnapshot(
                rpm = 2200.0,
                speedKmh = 60.0,
                coolantTempC = 89.0,
                batteryVoltageV = 14.1,
                engineLoadPct = 28.0,
                averageKmPerL = 11.2,
                derived = DerivedTelemetry(
                    correctedSpeedKmh = 60.0,
                    estimatedGearLabel = "4ª",
                    virtualTankPercent = 70.0,
                    virtualTankRemainingLiters = 35.0,
                    estimatedRangeKm = 390.0
                )
            ),
            obdAlerts = listOf(ObdAlert(AlertSeverity.WARNING, "Bateria atenção", "11.9V")),
            dtcCodes = listOf(DtcCode("P0420", DtcSystem.POWERTRAIN, "Eficiência catalisador"))
        )

        override fun getMediaState(): MediaPlaybackState = MediaPlaybackState(
            title = "Tempo Perdido",
            artist = "Legião Urbana",
            isPlaying = true,
            hasActiveTrack = true
        )

        override fun getWhatsAppMessages(): List<WhatsAppNotificationItem> = listOf(
            WhatsAppNotificationItem(
                key = "w1",
                packageName = "com.whatsapp",
                appLabel = "WhatsApp",
                sender = "Maria",
                message = "Pode passar no mercado?",
                timestampMs = 1_760_000_000_000L
            )
        )

        override fun getFavoritePlaces(): List<FavoriteRef> = listOf(
            FavoriteRef("casa", "Casa", "Bairro Jundiaí", -16.33, -48.94, "ir para casa")
        )

        override fun controlMusic(action: String, query: String?): String {
            log += "MUSIC:$action"
            return action
        }

        override suspend fun searchAndPlayMusic(query: String): String {
            log += "MUSIC_SEARCH:$query"
            return "Tocando $query no Spotify"
        }

        override fun navigateTo(destination: String?): String = navigateTo(destination, null, false)

        override fun navigateTo(destination: String?, favorite: FavoriteRef?, forceExternalMaps: Boolean): String {
            log += "NAV:${destination.orEmpty()}:fav=${favorite?.id.orEmpty()}"
            return "Rota para ${favorite?.name ?: destination}"
        }

        override fun stopRoute(): String {
            log += "STOP_ROUTE"
            return "Rota encerrada."
        }

        override fun openMapFullScreen(): String {
            log += "MAP_FULL"
            return "Mapa em tela cheia"
        }

        override fun switchTab(tab: MainTab): String {
            log += "TAB:${tab.name}"
            return tab.name
        }

        override fun toggleReverseCamera(): String = "Cam"
        override fun openReverseCamera(): String {
            log += "OPEN_CAM"
            return "Câmera aberta"
        }

        override fun closeReverseCamera(): String {
            log += "CLOSE_CAM"
            return "Câmera fechada"
        }

        override fun adjustVolume(direction: VolumeDirection, value: Int?): String {
            log += "VOLUME:${direction.name}:${value ?: ""}"
            return "Volume ${value ?: direction.name}"
        }

        override fun setMapTheme(theme: String): String {
            log += "MAP_THEME:$theme"
            return "Tema do mapa: $theme"
        }

        override fun setAccentColor(colorHex: String): String {
            log += "ACCENT:$colorHex"
            return "Cor $colorHex"
        }

        override fun replyLatestWhatsApp(replyText: String): String {
            log += "REPLY_WA:$replyText"
            return "Enviado: $replyText"
        }

        override fun markMessagesRead() {
            log += "WA_READ"
        }

        override fun logRefuelFromAgent(
            liters: Double,
            pricePerLiter: Double?,
            odometerKm: Double?,
            fullTank: Boolean
        ): String {
            log += "REFUEL:$liters"
            return "Abastecimento de $liters litros registrado."
        }

        override fun getRouteSummary(): String = "Indo para Brasil Park Shopping (1,2 km, cerca de 3 min)."
        override fun getWeatherSummary(): String = "Anápolis: 26°C, parcialmente nublado."
        override fun getFuelLogSummary(): String = "média real 11,2 km/L"
    }
}

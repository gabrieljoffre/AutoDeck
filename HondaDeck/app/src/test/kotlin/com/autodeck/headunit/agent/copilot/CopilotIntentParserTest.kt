package com.autodeck.headunit.agent.copilot

import com.autodeck.protocol.obd2.FuelLogCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CopilotIntentParserTest {

    private val favorites = listOf(
        FavoriteRef(
            id = "casa",
            name = "Casa",
            address = "Av. Brasil Norte, Anápolis",
            lat = -16.3100,
            lon = -48.9500,
            voice = "ir para casa"
        ),
        FavoriteRef(
            id = "trabalho",
            name = "Trabalho",
            address = "DAIA, Anápolis",
            lat = -16.3900,
            lon = -48.9300,
            voice = "bora pro servico"
        )
    )

    @Test
    fun `tocar Coldplay e toque bem querer extraem query normalizada sem bug de maiusculas`() {
        val coldplay = CopilotIntentParser.parse("Tocar Coldplay", favorites)
        assertEquals(CopilotIntent.MusicSearch("coldplay"), coldplay)

        val bemQuerer = CopilotIntentParser.parse("toque bem querer", favorites)
        assertEquals(CopilotIntent.MusicSearch("bem querer"), bemQuerer)

        val legiao = CopilotIntentParser.parse("Coloca Legião Urbana no Spotify", favorites)
        assertEquals(CopilotIntent.MusicSearch("legiao urbana"), legiao)
    }

    @Test
    fun `ir para casa resolve favorito pelo comando de voz e pelo nome`() {
        val byVoice = CopilotIntentParser.parse("ir para casa", favorites)
        assertTrue(byVoice is CopilotIntent.Navigate)
        byVoice as CopilotIntent.Navigate
        assertEquals("casa", byVoice.dest)
        assertEquals("casa", byVoice.favorite?.id)
        assertFalse(byVoice.forceExternalMaps)

        val byCustomVoice = CopilotIntentParser.parse("Bora pro serviço!", favorites)
        assertTrue(byCustomVoice is CopilotIntent.Navigate)
        byCustomVoice as CopilotIntent.Navigate
        assertEquals("trabalho", byCustomVoice.favorite?.id)

        val byRoutePrefix = CopilotIntentParser.parse("Navegar para o Trabalho", favorites)
        assertTrue(byRoutePrefix is CopilotIntent.Navigate)
        byRoutePrefix as CopilotIntent.Navigate
        assertEquals("trabalho", byRoutePrefix.dest)
        assertEquals("trabalho", byRoutePrefix.favorite?.id)

        val byIsolatedName = CopilotIntentParser.parse("Casa", favorites)
        assertTrue(byIsolatedName is CopilotIntent.Navigate)
        assertEquals("casa", (byIsolatedName as CopilotIntent.Navigate).favorite?.id)
    }

    @Test
    fun `ir para a proxima rua prioriza rota e NAO pula musica`() {
        val nextStreet = CopilotIntentParser.parse("ir para a próxima rua", favorites)
        assertTrue("Esperado Navigate, mas veio $nextStreet", nextStreet is CopilotIntent.Navigate)
        nextStreet as CopilotIntent.Navigate
        assertEquals("a proxima rua", nextStreet.dest)
        assertNull(nextStreet.favorite)

        // Sem prefixo de rota, "próxima" fora de contexto musical também não pode pular faixa
        val nextCorner = CopilotIntentParser.parse("vire na próxima esquina", favorites)
        assertEquals(CopilotIntent.Unknown, nextCorner)
    }

    @Test
    fun `proxima e anterior so disparam com contexto de musica ou frase curta`() {
        assertEquals(CopilotIntent.MusicNext, CopilotIntentParser.parse("próxima música", favorites))
        assertEquals(CopilotIntent.MusicNext, CopilotIntentParser.parse("próxima", favorites))
        assertEquals(CopilotIntent.MusicNext, CopilotIntentParser.parse("pular faixa", favorites))
        assertEquals(CopilotIntent.MusicNext, CopilotIntentParser.parse("ir para a próxima música", favorites))

        assertEquals(CopilotIntent.MusicPrev, CopilotIntentParser.parse("música anterior", favorites))
        assertEquals(CopilotIntent.MusicPrev, CopilotIntentParser.parse("anterior", favorites))
        assertEquals(CopilotIntent.MusicPrev, CopilotIntentParser.parse("voltar música", favorites))
        assertEquals(CopilotIntent.MusicPrev, CopilotIntentParser.parse("voltar para a música anterior", favorites))
    }

    @Test
    fun `pause e parar musica funcionam sem conflitar com parar isolado de encerrar`() {
        assertEquals(CopilotIntent.MusicPause, CopilotIntentParser.parse("pausar", favorites))
        assertEquals(CopilotIntent.MusicPause, CopilotIntentParser.parse("pause", favorites))
        assertEquals(CopilotIntent.MusicPause, CopilotIntentParser.parse("parar música", favorites))

        // "parar" sozinho é usado pelo agente de voz para encerrar escuta contínua
        assertEquals(CopilotIntent.Unknown, CopilotIntentParser.parse("parar", favorites))
        assertEquals(CopilotIntent.Unknown, CopilotIntentParser.parse("nao pare", favorites))

        assertEquals(CopilotIntent.MusicPlay, CopilotIntentParser.parse("tocar", favorites))
        assertEquals(CopilotIntent.MusicPlay, CopilotIntentParser.parse("play", favorites))
        assertEquals(CopilotIntent.MusicPlay, CopilotIntentParser.parse("tocar música", favorites))
    }

    @Test
    fun `responder whatsapp vem antes do ramo de leitura do whatsapp e de rota`() {
        val reply = CopilotIntentParser.parse("responder whatsapp ok chego já", favorites)
        assertEquals(CopilotIntent.ReplyWhatsApp("ok chego ja"), reply)

        val replyMsg = CopilotIntentParser.parse("responder a mensagem do WhatsApp ok chego já", favorites)
        assertEquals(CopilotIntent.ReplyWhatsApp("ok chego ja"), replyMsg)

        val replyWithRouteTrap = CopilotIntentParser.parse("responder mensagem já estou indo para casa", favorites)
        assertEquals(CopilotIntent.ReplyWhatsApp("ja estou indo para casa"), replyWithRouteTrap)

        val read = CopilotIntentParser.parse("ler mensagens do whatsapp", favorites)
        assertEquals(CopilotIntent.ReadWhatsApp, read)
    }

    @Test
    fun `procure um posto gera MapSearch limpo e abrir mapa ou spotify gera acoes do app`() {
        assertEquals(
            CopilotIntent.MapSearch("posto"),
            CopilotIntentParser.parse("procure um posto", favorites)
        )
        assertEquals(
            CopilotIntent.MapSearch("oficina mecanica"),
            CopilotIntentParser.parse("procurar uma oficina mecânica perto daqui", favorites)
        )
        assertEquals(
            CopilotIntent.MapSearch("farmacia"),
            CopilotIntentParser.parse("onde tem uma farmácia?", favorites)
        )

        assertEquals(CopilotIntent.OpenMap, CopilotIntentParser.parse("abrir mapa", favorites))
        assertEquals(CopilotIntent.OpenSpotify, CopilotIntentParser.parse("abrir o Spotify", favorites))

        val externalMaps = CopilotIntentParser.parse("ir para Goiânia no Google Maps", favorites)
        assertEquals(
            CopilotIntent.Navigate(dest = "goiania", favorite = null, forceExternalMaps = true),
            externalMaps
        )
    }

    @Test
    fun `relatorio clima abastecimento volume camera gemini e telemetria`() {
        assertEquals(CopilotIntent.Report, CopilotIntentParser.parse("relatório do carro", favorites))
        assertEquals(CopilotIntent.Weather, CopilotIntentParser.parse("como está o tempo", favorites))
        assertEquals(CopilotIntent.Weather, CopilotIntentParser.parse("vai chover amanhã?", favorites))
        assertEquals(CopilotIntent.Weather, CopilotIntentParser.parse("qual a temperatura lá fora", favorites))

        val fuel = CopilotIntentParser.parse("abasteci 30 litros a 6,19", favorites)
        assertEquals(CopilotIntent.LogFuel("abasteci 30 litros a 6,19"), fuel)
        val parsedRefuel = FuelLogCalculator.parseUtterance((fuel as CopilotIntent.LogFuel).raw)
        assertNotNull(parsedRefuel)
        assertEquals(30.0, parsedRefuel!!.resolvedLiters!!, 0.001)
        assertEquals(6.19, parsedRefuel.resolvedPrice!!, 0.001)

        assertEquals(
            CopilotIntent.FuelSummary,
            CopilotIntentParser.parse("quanto gastei com combustível", favorites)
        )

        assertEquals(CopilotIntent.Volume.up(), CopilotIntentParser.parse("aumentar volume", favorites))
        assertEquals(CopilotIntent.Volume.down(), CopilotIntentParser.parse("abaixar o som", favorites))
        assertEquals(CopilotIntent.Volume.set(65), CopilotIntentParser.parse("volume 65%", favorites))
        assertEquals(CopilotIntent.Volume.mute(), CopilotIntentParser.parse("mutar", favorites))

        assertEquals(CopilotIntent.OpenCamera, CopilotIntentParser.parse("abrir câmera de ré", favorites))
        assertEquals(CopilotIntent.CloseCamera, CopilotIntentParser.parse("fechar câmera de ré", favorites))

        assertEquals(
            CopilotIntent.AskGeminiApp("quem descobriu o brasil"),
            CopilotIntentParser.parse("pergunte ao Gemini quem descobriu o Brasil", favorites)
        )
        assertEquals(
            CopilotIntent.AskGeminiApp("cotacao do dolar"),
            CopilotIntentParser.parse("pesquise no Google cotação do dólar", favorites)
        )

        assertEquals(
            CopilotIntent.Telemetry(TelemetryKind.COOLANT_TEMP),
            CopilotIntentParser.parse("qual a temperatura do motor", favorites)
        )
        assertEquals(
            CopilotIntent.Telemetry(TelemetryKind.BATTERY_VOLTAGE),
            CopilotIntentParser.parse("como está a voltagem da bateria", favorites)
        )
        assertEquals(
            CopilotIntent.Telemetry(TelemetryKind.FUEL_CONSUMPTION),
            CopilotIntentParser.parse("qual a autonomia do tanque", favorites)
        )
        assertEquals(
            CopilotIntent.Telemetry(TelemetryKind.DIAGNOSTICS_DTC),
            CopilotIntentParser.parse("tem algum código de falha DTC?", favorites)
        )
        assertEquals(
            CopilotIntent.Telemetry(TelemetryKind.SPEED_RPM),
            CopilotIntentParser.parse("qual a rotação rpm agora", favorites)
        )
        assertEquals(
            CopilotIntent.Telemetry(TelemetryKind.ENGINE_SUMMARY),
            CopilotIntentParser.parse("como está o motor", favorites)
        )

        assertEquals(
            CopilotIntent.SwitchTab(CopilotTab.SETTINGS),
            CopilotIntentParser.parse("abrir configurações", favorites)
        )
        assertEquals(
            CopilotIntent.SwitchTab(CopilotTab.HOME),
            CopilotIntentParser.parse("voltar ao painel principal", favorites)
        )
    }

    @Test
    fun `armadilhas de subpalavra nao geram falsos positivos`() {
        val traps = listOf(
            "quero replay",
            "limpar display",
            "ir no restaurante",
            "problema no freio",
            "nao pare",
            "wifi na rede"
        )
        for (trap in traps) {
            assertEquals("Armadilha '$trap' deveria ser Unknown", CopilotIntent.Unknown, CopilotIntentParser.parse(trap, favorites))
        }

        // Prefixo de wake word opcional é removido antes da análise
        assertEquals(
            CopilotIntent.MusicSearch("coldplay"),
            CopilotIntentParser.parse("Ok Honda, Tocar Coldplay", favorites)
        )
    }

    @Test
    fun `minha agenda de hoje e amanha resolvem para CalendarAgenda`() {
        assertEquals(CopilotIntent.CalendarAgenda(0), CopilotIntentParser.parse("minha agenda de hoje", favorites))
        assertEquals(CopilotIntent.CalendarAgenda(0), CopilotIntentParser.parse("o que tenho hoje", favorites))
        assertEquals(CopilotIntent.CalendarAgenda(1), CopilotIntentParser.parse("agenda de amanhã", favorites))
        assertEquals(CopilotIntent.CalendarAgenda(1), CopilotIntentParser.parse("tenho algum compromisso amanhã?", favorites))
    }

    @Test
    fun `comandos de voz para tema do mapa e mapa em tela cheia`() {
        assertEquals(CopilotIntent.SetMapTheme("LIGHT"), CopilotIntentParser.parse("mapa claro", favorites))
        assertEquals(CopilotIntent.SetMapTheme("LIGHT"), CopilotIntentParser.parse("colocar o mapa no modo claro", favorites))
        assertEquals(CopilotIntent.SetMapTheme("DARK"), CopilotIntentParser.parse("deixar o mapa escuro", favorites))
        assertEquals(CopilotIntent.SetMapTheme("DARK"), CopilotIntentParser.parse("tema noturno do mapa", favorites))
        assertEquals(CopilotIntent.SetMapTheme("AUTO"), CopilotIntentParser.parse("mapa no modo automático", favorites))
        assertEquals(CopilotIntent.ExpandMapFull, CopilotIntentParser.parse("aumentar o mapa", favorites))
        assertEquals(CopilotIntent.ExpandMapFull, CopilotIntentParser.parse("mapa em tela cheia", favorites))
        assertEquals(CopilotIntent.StopRoute, CopilotIntentParser.parse("encerrar rota", favorites))
        assertEquals(CopilotIntent.StopRoute, CopilotIntentParser.parse("cancelar navegação", favorites))
        assertEquals(CopilotIntent.ForgetConversations, CopilotIntentParser.parse("esqueça as conversas", favorites))
        assertEquals(CopilotIntent.ForgetConversations, CopilotIntentParser.parse("apagar histórico de conversas", favorites))
    }

    @Test
    fun `comandos da Ponte celular para WhatsApp texto, audio, ligacoes e chamadas`() {
        assertEquals(
            CopilotIntent.SendWhatsApp(recipient = "ana", text = "to chegando"),
            CopilotIntentParser.parse("manda whatsapp para Ana dizendo tô chegando", favorites)
        )
        assertEquals(
            CopilotIntent.SendWhatsApp(recipient = "maria", text = "ja sai de casa"),
            CopilotIntentParser.parse("enviar mensagem no zap para Maria: já saí de casa", favorites)
        )
        assertEquals(
            CopilotIntent.SendWhatsAppAudio(recipient = "ana"),
            CopilotIntentParser.parse("manda um áudio no whatsapp para Ana", favorites)
        )
        assertEquals(
            CopilotIntent.SendWhatsAppAudio(recipient = "joao"),
            CopilotIntentParser.parse("enviar mensagem de voz para João", favorites)
        )
        assertEquals(
            CopilotIntent.CallWhatsApp(recipient = "ana"),
            CopilotIntentParser.parse("ligar no whatsapp para Ana", favorites)
        )
        assertEquals(
            CopilotIntent.CallWhatsApp(recipient = "maria"),
            CopilotIntentParser.parse("fazer ligação de voz para Maria pelo zap", favorites)
        )
        assertEquals(
            CopilotIntent.CallPhone(recipient = "carlos"),
            CopilotIntentParser.parse("ligar para Carlos", favorites)
        )
        assertEquals(
            CopilotIntent.CallPhone(recipient = "62 99999-8888"),
            CopilotIntentParser.parse("liga pro 62 99999-8888", favorites)
        )
        assertEquals(CopilotIntent.AnswerCall, CopilotIntentParser.parse("atender", favorites))
        assertEquals(CopilotIntent.AnswerCall, CopilotIntentParser.parse("atender chamada", favorites))
        assertEquals(CopilotIntent.EndCall, CopilotIntentParser.parse("desligar", favorites))
        assertEquals(CopilotIntent.EndCall, CopilotIntentParser.parse("encerrar ligação", favorites))
    }
}

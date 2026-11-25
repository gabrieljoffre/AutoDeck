package com.autodeck.headunit.agent.copilot

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebKnowledgeToolsAndLoopTest {

    private val sampleRssXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0">
          <channel>
            <title>"Anápolis" - Google Notícias</title>
            <item>
              <title>Obras no viaduto avançam em Anápolis - O Popular</title>
              <pubDate>Tue, 24 Nov 2026 14:30:00 GMT</pubDate>
              <source url="https://opopular.com.br">O Popular</source>
            </item>
            <item>
              <title><![CDATA[DAIA recebe nova indústria farmacêutica &amp; logística - DM Anápolis]]></title>
              <pubDate>Tue, 24 Nov 2026 12:00:00 GMT</pubDate>
              <source>DM Anápolis</source>
            </item>
            <item><title>Notícia 3 - Portal 6</title><pubDate>24/11/2026</pubDate><source>Portal 6</source></item>
            <item><title>Notícia 4 - G1 Goiás</title><pubDate>24/11/2026</pubDate><source>G1 Goiás</source></item>
            <item><title>Notícia 5 - Mais Goiás</title><pubDate>24/11/2026</pubDate><source>Mais Goiás</source></item>
            <item><title>Notícia 6 - Rádio São Francisco</title><pubDate>24/11/2026</pubDate><source>Rádio São Francisco</source></item>
            <item><title>Notícia 7 excedente - Extra</title><pubDate>24/11/2026</pubDate><source>Extra</source></item>
          </channel>
        </rss>
    """.trimIndent()

    private val sennaOpenSearchJson = """
        ["Ayrton Senna", ["Ayrton Senna"], [""], ["https://pt.wikipedia.org/wiki/Ayrton_Senna"]]
    """.trimIndent()

    private val sennaSummaryJson = """
        {
          "title": "Ayrton Senna",
          "description": "piloto brasileiro de Fórmula 1",
          "extract": "Ayrton Senna da Silva foi um piloto de automóveis brasileiro, tricampeão mundial de Fórmula 1 em 1988, 1990 e 1991. É amplamente considerado um dos maiores pilotos da história do automobilismo. Começou no kart aos treze anos e venceu em Mônaco seis vezes. Faleceu em Ímola em 1994, deixando enorme legado esportivo e social no Brasil."
        }
    """.trimIndent()

    private val openMeteoSampleJson = """
        {
          "current": {
            "temperature_2m": 25.4,
            "apparent_temperature": 27.1,
            "weather_code": 2
          },
          "daily": {
            "temperature_2m_min": [18.2, 17.9],
            "temperature_2m_max": [28.6, 26.4],
            "precipitation_probability_max": [20, 75]
          }
        }
    """.trimIndent()

    @Test
    fun `buscarNoticias retorna top 6 com titulo fonte e data`() {
        val requestedUrls = mutableListOf<String>()
        val tools = WebKnowledgeTools { url ->
            requestedUrls += url
            sampleRssXml
        }

        val out = tools.buscarNoticias("Anápolis")
        assertEquals(1, requestedUrls.size)
        assertTrue(requestedUrls[0].contains("news.google.com/rss/search"))
        assertTrue(requestedUrls[0].contains("hl=pt-BR&gl=BR&ceid=BR:pt-419"))

        assertTrue(out.contains("1. Obras no viaduto avançam em Anápolis (O Popular, 24/11/2026)"))
        assertTrue(out.contains("2. DAIA recebe nova indústria farmacêutica & logística (DM Anápolis, 24/11/2026)"))
        assertTrue(out.contains("6. Notícia 6 (Rádio São Francisco, 24/11/2026)"))
        assertTrue("Não deve incluir o 7º item", !out.contains("Notícia 7"))
    }

    @Test
    fun `buscarWikipedia usa opensearch e summary pt com formatacao curta para voz`() {
        val requestedUrls = mutableListOf<String>()
        val tools = WebKnowledgeTools { url ->
            requestedUrls += url
            when {
                url.contains("action=opensearch") -> sennaOpenSearchJson
                url.contains("/api/rest_v1/page/summary/Ayrton_Senna") -> sennaSummaryJson
                else -> error("URL inesperada: $url")
            }
        }

        val summary = tools.buscarWikipedia("Ayrton Senna")
        assertEquals(2, requestedUrls.size)
        assertTrue(summary.contains("Ayrton Senna"))
        assertTrue(summary.contains("tricampeão mundial de Fórmula 1"))
        // Deve ser encurtado para leitura em voz alta (máx. 3 frases, sem a 4ª frase de Ímola)
        assertTrue("Resumo para voz deve ser curto: $summary", summary.length <= 380)
        assertTrue(!summary.contains("Faleceu em Ímola"))
    }

    @Test
    fun `functionDeclarationsJson declara as seis ferramentas obrigatorias em JSON valido`() {
        val parsed = CopilotJson.parseObjOrNull(WebKnowledgeTools.functionDeclarationsJson())
        assertNotNull(parsed)
        val decls = parsed!!.optArr("functionDeclarations")
        assertNotNull(decls)
        assertEquals(6, decls!!.size)

        val names = (0 until decls.size).mapNotNull { decls.optObj(it)?.optStrictString("name") }.toSet()
        assertEquals(
            setOf("buscar_noticias", "buscar_wikipedia", "clima", "buscar_lugar", "dados_do_carro", "minha_agenda"),
            names
        )
    }

    @Test
    fun `GeminiToolLoop executa ferramentas para noticias wikipedia e clima com fake de rede`() {
        val fakeHttp = HttpGetter { url ->
            when {
                url.contains("news.google.com/rss") -> sampleRssXml
                url.contains("action=opensearch") -> sennaOpenSearchJson
                url.contains("page/summary/Ayrton_Senna") -> sennaSummaryJson
                url.contains("api.open-meteo.com") -> openMeteoSampleJson
                else -> error("URL não mapeada: $url")
            }
        }
        val webTools = WebKnowledgeTools(fakeHttp)

        val executedTools = mutableListOf<Pair<String, String>>()
        val executeTool: (String, String) -> String = { name, argsJson ->
            executedTools += name to argsJson
            val args = CopilotJson.parseObjOrNull(argsJson) ?: JsonObj()
            when (name) {
                "buscar_noticias" -> webTools.buscarNoticias(args.optString("consulta").orEmpty())
                "buscar_wikipedia" -> webTools.buscarWikipedia(args.optString("consulta").orEmpty())
                "clima" -> webTools.buscarClima(args.optString("cidade"))
                else -> "Ferramenta desconhecida"
            }
        }

        // 1) "quais as notícias de Anápolis hoje"
        executedTools.clear()
        val newsAnswer = GeminiToolLoop.run(
            contentsJson = """[{"role":"user","parts":[{"text":"quais as notícias de Anápolis hoje"}]}]""",
            callModel = { contents ->
                if (!contents.contains("functionResponse")) {
                    """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"buscar_noticias","args":{"consulta":"Anápolis"}}}]}}]}"""
                } else {
                    assertTrue(contents.contains("Obras no viaduto avançam em Anápolis"))
                    """{"candidates":[{"content":{"role":"model","parts":[{"text":"Em Anápolis hoje, as obras no viaduto avançam e o DAIA recebe nova indústria."}]}}]}"""
                }
            },
            executeTool = executeTool
        )
        assertEquals("buscar_noticias", executedTools.single().first)
        assertEquals(
            "Em Anápolis hoje, as obras no viaduto avançam e o DAIA recebe nova indústria.",
            newsAnswer
        )

        // 2) "quem foi Ayrton Senna"
        executedTools.clear()
        val wikiAnswer = GeminiToolLoop.run(
            contentsJson = """[{"role":"user","parts":[{"text":"quem foi Ayrton Senna"}]}]""",
            callModel = { contents ->
                if (!contents.contains("functionResponse")) {
                    """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"buscar_wikipedia","args":{"consulta":"Ayrton Senna"}}}]}}]}"""
                } else {
                    assertTrue(contents.contains("tricampeão mundial"))
                    """{"candidates":[{"content":{"role":"model","parts":[{"text":"Ayrton Senna foi um piloto brasileiro tricampeão mundial de Fórmula 1."}]}}]}"""
                }
            },
            executeTool = executeTool
        )
        assertEquals("buscar_wikipedia", executedTools.single().first)
        assertEquals("Ayrton Senna foi um piloto brasileiro tricampeão mundial de Fórmula 1.", wikiAnswer)

        // 3) "vai chover amanhã"
        executedTools.clear()
        val weatherAnswer = runBlocking {
            GeminiToolLoop.runSuspend(
                contentsJson = """[{"role":"user","parts":[{"text":"vai chover amanhã"}]}]""",
                callModel = { contents ->
                    if (!contents.contains("functionResponse")) {
                        """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"clima","args":{}}}]}}]}"""
                    } else {
                        assertTrue(contents.contains("amanhã: 75%"))
                        """{"candidates":[{"content":{"role":"model","parts":[{"text":"Sim, há 75% de chance de chuva amanhã em Anápolis."}]}}]}"""
                    }
                },
                executeTool = { name, args -> executeTool(name, args) }
            )
        }
        assertEquals("clima", executedTools.single().first)
        assertEquals("Sim, há 75% de chance de chuva amanhã em Anápolis.", weatherAnswer)
    }

    @Test
    fun `GeminiToolLoop respeita limite de 3 rodadas e devolve null em 429 ou erro`() {
        // 429 via JSON de erro da API Gemini
        val on429Json = GeminiToolLoop.run(
            contentsJson = """[{"role":"user","parts":[{"text":"teste"}]}]""",
            callModel = { """{"error":{"code":429,"message":"Resource has been exhausted","status":"RESOURCE_EXHAUSTED"}}""" },
            executeTool = { _, _ -> "ok" }
        )
        assertNull(on429Json)

        // 429 via exceção lançada pelo cliente HTTP
        val on429Exception = GeminiToolLoop.run(
            contentsJson = """[{"role":"user","parts":[{"text":"teste"}]}]""",
            callModel = { throw IllegalStateException("HTTP 429: quota exceeded") },
            executeTool = { _, _ -> "ok" }
        )
        assertNull(on429Exception)

        // Até 3 rodadas encadeadas funcionando na 3ª rodada
        var rounds = 0
        val threeRoundsOk = GeminiToolLoop.run(
            contentsJson = """[{"role":"user","parts":[{"text":"relatório completo"}]}]""",
            callModel = {
                rounds++
                when (rounds) {
                    1 -> """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"dados_do_carro","args":{}}}]}}]}"""
                    2 -> """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"clima","args":{"cidade":"Anápolis"}}}]}}]}"""
                    3 -> """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"buscar_noticias","args":{"consulta":"trânsito Anápolis"}}}]}}]}"""
                    else -> """{"candidates":[{"content":{"role":"model","parts":[{"text":"Motor normal, 25 graus e trânsito livre."}]}}]}"""
                }
            },
            executeTool = { name, _ -> "resultado_$name" }
        )
        assertEquals(4, rounds)
        assertEquals("Motor normal, 25 graus e trânsito livre.", threeRoundsOk)

        // Modelo preso pedindo função infinitamente para após 3 rodadas e devolve null
        var infiniteCalls = 0
        val infiniteResult = GeminiToolLoop.run(
            contentsJson = """[{"role":"user","parts":[{"text":"loop infinito"}]}]""",
            callModel = {
                infiniteCalls++
                """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"clima","args":{}}}]}}]}"""
            },
            executeTool = { _, _ -> "25 graus" }
        )
        assertEquals(4, infiniteCalls)
        assertNull(infiniteResult)
    }
}

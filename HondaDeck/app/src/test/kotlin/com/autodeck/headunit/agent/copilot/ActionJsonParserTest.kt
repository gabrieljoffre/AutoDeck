package com.autodeck.headunit.agent.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionJsonParserTest {

    @Test
    fun `extrai ACTION_JSON na ultima linha`() {
        val raw = """
            Iniciando busca por Coldplay no Spotify.
            ACTION_JSON:{"action":"MUSIC_SEARCH","arg":"Coldplay"}
        """.trimIndent()

        val (clean, action, arg) = ActionJsonParser.parse(raw)
        assertEquals("Iniciando busca por Coldplay no Spotify.", clean)
        assertEquals("MUSIC_SEARCH", action)
        assertEquals("Coldplay", arg)
        assertTrue(ActionJsonParser.isValidAction(action))
    }

    @Test
    fun `extrai ACTION_JSON dentro de bloco crase json e remove cerca vazia`() {
        val raw = """
            Claro! Aqui está o relatório completo do seu Civic.
            ```json
            ACTION_JSON: {
              "action": "REPORT"
            }
            ```
        """.trimIndent()

        val (clean, action, arg) = ActionJsonParser.parse(raw)
        assertEquals("Claro! Aqui está o relatório completo do seu Civic.", clean)
        assertEquals("REPORT", action)
        assertNull(arg)
        assertFalse(clean.contains("```"))
    }

    @Test
    fun `extrai ACTION_JSON em bloco crase simples ou no meio do texto`() {
        val middleRaw = """
            Certo, abrindo a câmera traseira agora.
            ```
            ACTION_JSON:{"action":"open_camera"}
            ```
            Cuidado ao manobrar.
        """.trimIndent()

        val (clean, action, arg) = ActionJsonParser.parse(middleRaw)
        assertEquals("Certo, abrindo a câmera traseira agora.\n\nCuidado ao manobrar.", clean)
        assertEquals("OPEN_CAMERA", action)
        assertNull(arg)

        val fenceAfterMarker = """
            Registrando o abastecimento informado.
            ACTION_JSON:
            ```json
            {"action":"LOG_FUEL","arg":{"litros":30,"preco":6.19,"cheio":true}}
            ```
        """.trimIndent()

        val res2 = ActionJsonParser.parse(fenceAfterMarker)
        assertEquals("Registrando o abastecimento informado.", res2.cleanText)
        assertEquals("LOG_FUEL", res2.action)
        assertEquals("""{"litros":30,"preco":6.19,"cheio":true}""", res2.arg)

        val inlineBackticks = "Clima consultado. `ACTION_JSON:{\"action\":\"WEATHER\"}`"
        val res3 = ActionJsonParser.parse(inlineBackticks)
        assertEquals("Clima consultado.", res3.cleanText)
        assertEquals("WEATHER", res3.action)
        assertNull(res3.arg)
    }

    @Test
    fun `suporta chaves escapadas dentro de arg em texto e nunca lanca excecao em JSON invalido`() {
        val escapedArg = """Anotei! ACTION_JSON:{"action":"LOG_FUEL","arg":"{\"litros\":40,\"preco\":6.29}"}"""
        val parsed = ActionJsonParser.parse(escapedArg)
        assertEquals("Anotei!", parsed.cleanText)
        assertEquals("LOG_FUEL", parsed.action)
        assertEquals("""{"litros":40,"preco":6.29}""", parsed.arg)

        val brokenCases = listOf(
            "",
            "   ",
            "Resposta comum sem JSON.",
            "Texto antes\nACTION_JSON:{json quebrado sem fechar\nTexto depois",
            "Texto antes\nACTION_JSON: sem chaves",
            "ACTION_JSON:{\"sem_campo_action\":123}",
            "ACTION_JSON:{\"action\":\"\"}"
        )

        for (broken in brokenCases) {
            val res = ActionJsonParser.parse(broken)
            assertNull("Esperava action nula para '$broken'", res.action)
            assertNull(res.arg)
            assertFalse(res.cleanText.contains("ACTION_JSON"))
        }

        val unknown = ActionJsonParser.parse("""Ok. ACTION_JSON:{"action":"ACAO_INEXISTENTE","arg":"x"}""")
        assertEquals("Ok.", unknown.cleanText)
        assertEquals("ACAO_INEXISTENTE", unknown.action)
        assertFalse(ActionJsonParser.isValidAction(unknown.action))
    }
}

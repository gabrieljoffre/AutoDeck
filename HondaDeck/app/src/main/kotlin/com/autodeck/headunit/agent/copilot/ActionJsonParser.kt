package com.autodeck.headunit.agent.copilot

/**
 * Resultado da extração de `ACTION_JSON:{...}` da resposta do modelo.
 * Suporta desestruturação direta: `val (textoLimpo, acao, argumento) = ActionJsonParser.parse(raw)`.
 */
data class ActionParseResult(
    val cleanText: String,
    val action: String?,
    val arg: String?
)

/**
 * Extrator tolerante a falhas para diretivas `ACTION_JSON:{...}` geradas pelo Gemini.
 *
 * - Lê `ACTION_JSON:{...}` em qualquer linha (início, meio ou fim) e também dentro de blocos ``` ou ```json.
 * - Suporta JSON em múltiplas linhas e `arg` tanto como texto quanto como objeto/número JSON aninhado.
 * - Remove cercas ```json vazias remanescentes para que o TTS nunca leia marcação Markdown.
 * - Nunca lança exceção: em caso de JSON inválido ou ação vazia, limpa o artefato e devolve `(textoLimpo, null, null)`.
 */
object ActionJsonParser {

    val KNOWN_ACTIONS: Set<String> = setOf(
        "MUSIC_PLAY",
        "MUSIC_PAUSE",
        "MUSIC_NEXT",
        "MUSIC_PREV",
        "MUSIC_SEARCH",
        "OPEN_SPOTIFY",
        "OPEN_MAP",
        "OPEN_MAPS",
        "MAP_SEARCH",
        "NAVIGATE",
        "STOP_ROUTE",
        "READ_WHATSAPP",
        "REPLY_WHATSAPP",
        "READ_MESSAGES",
        "REPLY_MESSAGE",
        "SEND_WHATSAPP",
        "SEND_WHATSAPP_AUDIO",
        "CALL_PHONE",
        "CALL_WHATSAPP",
        "ANSWER_CALL",
        "END_CALL",
        "SEARCH_CONTACT",
        "AGENDA",
        "CREATE_EVENT",
        "SET_REMINDER",
        "OPEN_APP",
        "OPEN_PHONE_APP",
        "SWITCH_TAB",
        "OPEN_CAMERA",
        "CLOSE_CAMERA",
        "LOG_FUEL",
        "FUEL_SUMMARY",
        "REPORT",
        "WEATHER",
        "VOLUME",
        "ASK_GEMINI_APP",
        "FORGET_CONVERSATIONS"
    )

    private val MARKER_REGEX = Regex("""(?i)ACTION_JSON\s*:""")
    private val MARKER_BEFORE_FENCE_REGEX = Regex(
        """(?i)ACTION_JSON\s*:\s*```(?:json)?\s*(\{[\s\S]*?\})\s*```"""
    )
    private val FENCED_ACTION_FALLBACK_REGEX = Regex(
        """```(?:json)?\s*(\{[\s\S]*?"action"\s*:\s*"[^"]+"[\s\S]*?\})\s*```""",
        RegexOption.IGNORE_CASE
    )
    private val EMPTY_CODE_FENCE_REGEX = Regex("""```(?:[a-zA-Z0-9_-]+)?\s*```""")
    private val EMPTY_INLINE_CODE_REGEX = Regex("""`[ \t]*`""")

    fun isValidAction(action: String?): Boolean =
        !action.isNullOrBlank() && action.trim().uppercase() in KNOWN_ACTIONS

    fun parse(rawText: String): ActionParseResult {
        return try {
            parseInternal(rawText)
        } catch (_: Throwable) {
            ActionParseResult(cleanText = rawText.trim(), action = null, arg = null)
        }
    }

    private fun parseInternal(rawText: String): ActionParseResult {
        if (rawText.isBlank()) {
            return ActionParseResult(cleanText = "", action = null, arg = null)
        }

        // Caso 1: ACTION_JSON: seguido imediatamente por um bloco ```json { ... } ```
        MARKER_BEFORE_FENCE_REGEX.find(rawText)?.let { m ->
            val jsonCandidate = m.groupValues[1].trim()
            val cleaned = cleanupRemainingText(rawText.removeRange(m.range))
            val (act, arg) = extractActionAndArg(jsonCandidate)
            return ActionParseResult(cleanText = cleaned, action = act, arg = arg)
        }

        // Caso 2: ACTION_JSON: em qualquer linha (inclusive dentro de ``` ou ```json)
        val markerMatch = MARKER_REGEX.find(rawText)
        if (markerMatch == null) {
            // Fallback opcional: bloco ```json {"action":"..."} ``` sem o prefixo literal ACTION_JSON:
            FENCED_ACTION_FALLBACK_REGEX.find(rawText)?.let { fm ->
                val (act, arg) = extractActionAndArg(fm.groupValues[1].trim())
                if (isValidAction(act)) {
                    val cleaned = cleanupRemainingText(rawText.removeRange(fm.range))
                    return ActionParseResult(cleanText = cleaned, action = act, arg = arg)
                }
            }
            return ActionParseResult(cleanText = rawText.trim(), action = null, arg = null)
        }

        val afterMarkerIdx = markerMatch.range.last + 1
        var braceStart = afterMarkerIdx
        while (braceStart < rawText.length && rawText[braceStart].isWhitespace()) {
            braceStart++
        }

        if (braceStart >= rawText.length || rawText[braceStart] != '{') {
            // Marcador malformado sem '{': remove até o fim da linha e segue sem quebrar
            val lineEnd = rawText.indexOf('\n', markerMatch.range.first).let { if (it < 0) rawText.length else it }
            val cleaned = cleanupRemainingText(rawText.removeRange(markerMatch.range.first, lineEnd))
            return ActionParseResult(cleanText = cleaned, action = null, arg = null)
        }

        val braceEndInclusive = findBalancedObjectEnd(rawText, braceStart)
        if (braceEndInclusive < 0) {
            // Objeto JSON truncado/sem fechar '}': remove até o fim da linha ou cerca
            val lineEnd = rawText.indexOf('\n', markerMatch.range.first).let { if (it < 0) rawText.length else it }
            val cleaned = cleanupRemainingText(rawText.removeRange(markerMatch.range.first, lineEnd))
            return ActionParseResult(cleanText = cleaned, action = null, arg = null)
        }

        val jsonSlice = rawText.substring(braceStart, braceEndInclusive + 1)
        val cleaned = cleanupRemainingText(rawText.removeRange(markerMatch.range.first, braceEndInclusive + 1))
        val (act, arg) = extractActionAndArg(jsonSlice)
        return ActionParseResult(cleanText = cleaned, action = act, arg = arg)
    }

    private fun extractActionAndArg(jsonSlice: String): Pair<String?, String?> {
        val obj = CopilotJson.parseObjOrNull(jsonSlice) ?: return null to null
        val rawAction = obj.optString("action")?.trim()?.uppercase().orEmpty()
        if (rawAction.isEmpty()) return null to null

        val rawArgValue = obj["arg"] ?: obj["argument"] ?: obj["value"] ?: obj["query"]
        val argStr = when (rawArgValue) {
            null, is JsonNull -> null
            is JsonStr -> rawArgValue.value.takeIf { it.isNotEmpty() }
            is JsonNum -> rawArgValue.raw
            is JsonBool -> rawArgValue.toJsonString()
            is JsonObj, is JsonArr -> rawArgValue.toJsonString()
        }
        return rawAction to argStr
    }

    /**
     * Encontra o índice do `}` que fecha o objeto iniciado em `openBraceIdx`,
     * respeitando aspas e escapes `\"` / `\\`. Devolve -1 se não fechar.
     */
    private fun findBalancedObjectEnd(src: String, openBraceIdx: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in openBraceIdx until src.length) {
            val ch = src[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    private fun cleanupRemainingText(text: String): String {
        return text
            .replace(EMPTY_CODE_FENCE_REGEX, "")
            .replace(EMPTY_INLINE_CODE_REGEX, "")
            .replace(Regex("""[ \t]+\n"""), "\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }
}

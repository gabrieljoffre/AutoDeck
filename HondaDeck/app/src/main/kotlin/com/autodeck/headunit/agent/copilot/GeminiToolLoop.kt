package com.autodeck.headunit.agent.copilot

/**
 * Laço de *function calling* da API Gemini desacoplado de rede e de Android.
 *
 * Fluxo:
 * 1. Envia `contentsJson` (array JSON de turnos `[{"role":"user","parts":[{"text":"..."}]}]`) para `callModel`.
 * 2. Se `candidates[0].content.parts` contiver `functionCall`, executa cada ferramenta via `executeTool(name, argsJson)`,
 *    acrescenta o turno `"model"` (com o `functionCall`) e o turno `"user"` (com `functionResponse`), e repete.
 * 3. Faz até `maxRounds` (padrão 3) rodadas de chamadas de função e devolve o texto final concatenado.
 * 4. Em caso de HTTP 429, erro de rede/JSON, resposta bloqueada/vazia, timeout ou excesso de rodadas,
 *    devolve `null` (nunca lança exceção) para o chamador cair no fallback.
 */
object GeminiToolLoop {

    fun run(
        contentsJson: String,
        callModel: (contentsJson: String) -> String,
        executeTool: (name: String, argsJson: String) -> String,
        maxRounds: Int = 3,
        timeoutMs: Long = 12_000L
    ): String? {
        return try {
            val history = normalizeInitialContents(contentsJson) ?: return null
            val startMs = System.currentTimeMillis()
            var toolRounds = 0

            while (true) {
                if (timeoutMs > 0 && System.currentTimeMillis() - startMs > timeoutMs) {
                    return null
                }

                val currentContentsStr = JsonArr(history).toJsonString()
                val rawResponse = runCatching { callModel(currentContentsStr) }.getOrElse { return null }

                val step = parseModelStep(rawResponse) ?: return null
                if (step.functionCalls.isEmpty()) {
                    return step.text.takeIf { it.isNotBlank() }
                }

                if (toolRounds >= maxRounds) {
                    // Limite de rodadas atingido e o modelo ainda pediu outra função em vez de concluir
                    return step.text.takeIf { it.isNotBlank() }
                }
                toolRounds++

                // Preserva o content retornado pelo modelo (incluindo eventuais metadados como thoughtSignature)
                val modelTurn = step.modelContent.with("role", JsonStr("model"))
                history += modelTurn

                val responseParts = step.functionCalls.map { fc ->
                    if (timeoutMs > 0 && System.currentTimeMillis() - startMs > timeoutMs) {
                        return null
                    }
                    val toolOutput = runCatching { executeTool(fc.name, fc.argsJson) }
                        .getOrElse { err -> "Erro ao executar ${fc.name}: ${err.message ?: "falha"}" }

                    val responseStruct = buildFunctionResponsePayload(toolOutput)
                    JsonObj(
                        linkedMapOf(
                            "functionResponse" to JsonObj(
                                linkedMapOf(
                                    "name" to JsonStr(fc.name),
                                    "response" to responseStruct
                                )
                            )
                        )
                    )
                }

                history += JsonObj(
                    linkedMapOf(
                        "role" to JsonStr("user"),
                        "parts" to JsonArr(responseParts)
                    )
                )
            }
            @Suppress("UNREACHABLE_CODE")
            null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Variante `suspend` com a mesma semântica de [run], facilitando integração em coroutines.
     */
    suspend fun runSuspend(
        contentsJson: String,
        maxRounds: Int = 3,
        timeoutMs: Long = 12_000L,
        callModel: suspend (contentsJson: String) -> String,
        executeTool: suspend (name: String, argsJson: String) -> String
    ): String? {
        return try {
            val history = normalizeInitialContents(contentsJson) ?: return null
            val startMs = System.currentTimeMillis()
            var toolRounds = 0

            while (true) {
                if (timeoutMs > 0 && System.currentTimeMillis() - startMs > timeoutMs) {
                    return null
                }

                val currentContentsStr = JsonArr(history).toJsonString()
                val rawResponse = try {
                    callModel(currentContentsStr)
                } catch (_: Throwable) {
                    return null
                }

                val step = parseModelStep(rawResponse) ?: return null
                if (step.functionCalls.isEmpty()) {
                    return step.text.takeIf { it.isNotBlank() }
                }

                if (toolRounds >= maxRounds) {
                    return step.text.takeIf { it.isNotBlank() }
                }
                toolRounds++

                history += step.modelContent.with("role", JsonStr("model"))

                val responseParts = ArrayList<JsonValue>(step.functionCalls.size)
                for (fc in step.functionCalls) {
                    if (timeoutMs > 0 && System.currentTimeMillis() - startMs > timeoutMs) {
                        return null
                    }
                    val toolOutput = try {
                        executeTool(fc.name, fc.argsJson)
                    } catch (err: Throwable) {
                        "Erro ao executar ${fc.name}: ${err.message ?: "falha"}"
                    }
                    responseParts += JsonObj(
                        linkedMapOf(
                            "functionResponse" to JsonObj(
                                linkedMapOf(
                                    "name" to JsonStr(fc.name),
                                    "response" to buildFunctionResponsePayload(toolOutput)
                                )
                            )
                        )
                    )
                }

                history += JsonObj(
                    linkedMapOf(
                        "role" to JsonStr("user"),
                        "parts" to JsonArr(responseParts)
                    )
                )
            }
            @Suppress("UNREACHABLE_CODE")
            null
        } catch (_: Throwable) {
            null
        }
    }

    private data class FunctionCallReq(
        val name: String,
        val argsJson: String
    )

    private data class ModelStep(
        val modelContent: JsonObj,
        val functionCalls: List<FunctionCallReq>,
        val text: String
    )

    private fun normalizeInitialContents(contentsJson: String): MutableList<JsonValue>? {
        val trimmed = contentsJson.trim()
        if (trimmed.isEmpty()) return null

        val parsed = CopilotJson.parseOrNull(trimmed)
        return when (parsed) {
            is JsonArr -> parsed.items.toMutableList()
            is JsonObj -> {
                val inner = parsed.optArr("contents")
                if (inner != null) {
                    inner.items.toMutableList()
                } else if (parsed.has("parts")) {
                    mutableListOf(parsed)
                } else {
                    null
                }
            }
            is JsonStr -> mutableListOf(userTextTurn(parsed.value))
            null -> {
                // Caso o chamador passe uma pergunta simples em texto puro
                if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                    mutableListOf(userTextTurn(trimmed))
                } else {
                    null
                }
            }
            else -> null
        }
    }

    private fun userTextTurn(text: String): JsonObj = JsonObj(
        linkedMapOf(
            "role" to JsonStr("user"),
            "parts" to JsonArr(listOf(JsonObj(linkedMapOf("text" to JsonStr(text)))))
        )
    )

    private fun parseModelStep(rawResponse: String): ModelStep? {
        val trimmed = rawResponse.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("HTTP 4") || trimmed.startsWith("HTTP 5") || trimmed == "429") {
            return null
        }

        val root = CopilotJson.parseObjOrNull(trimmed) ?: return null

        // Erro explícito da API Gemini (ex.: 429 RESOURCE_EXHAUSTED, 403, 500)
        if (root.has("error")) return null

        val candidates = root.optArr("candidates") ?: return null
        val firstCandidate = candidates.optObj(0) ?: return null
        val content = firstCandidate.optObj("content") ?: return null
        val parts = content.optArr("parts") ?: return null

        val calls = ArrayList<FunctionCallReq>()
        val texts = StringBuilder()

        for (p in parts.items) {
            val partObj = p as? JsonObj ?: continue
            // Ignora partes internas de raciocínio ("thought": true) no texto falado
            val isThought = partObj.optBoolean("thought") == true
            val fcObj = partObj.optObj("functionCall")
            if (fcObj != null) {
                val name = fcObj.optStrictString("name")?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    val argsVal = fcObj["args"]
                    val argsJson = when (argsVal) {
                        is JsonObj -> argsVal.toJsonString()
                        is JsonStr -> argsVal.value.ifBlank { "{}" }
                        else -> "{}"
                    }
                    calls += FunctionCallReq(name = name, argsJson = argsJson)
                }
            }
            if (!isThought) {
                val txt = partObj.optStrictString("text")
                if (!txt.isNullOrEmpty()) {
                    texts.append(txt)
                }
            }
        }

        return ModelStep(
            modelContent = content,
            functionCalls = calls,
            text = texts.toString().trim()
        )
    }

    private fun buildFunctionResponsePayload(toolOutput: String): JsonObj {
        val parsedObj = CopilotJson.parseObjOrNull(toolOutput)
        return parsedObj ?: JsonObj(linkedMapOf("result" to JsonStr(toolOutput)))
    }
}

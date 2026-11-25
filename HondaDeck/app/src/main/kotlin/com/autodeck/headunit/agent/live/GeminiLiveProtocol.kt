package com.autodeck.headunit.agent.live

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64

data class LiveFunctionCall(
    val id: String,
    val name: String,
    val argsJson: String
)

data class LiveToolResponse(
    val id: String,
    val name: String,
    val result: String,
    val success: Boolean = true
)

sealed interface LiveServerEvent {
    data object SetupComplete : LiveServerEvent

    data class ServerContent(
        val audioChunks: List<ByteArray> = emptyList(),
        val textParts: List<String> = emptyList(),
        val inputTranscription: String? = null,
        val outputTranscription: String? = null,
        val interrupted: Boolean = false,
        val turnComplete: Boolean = false,
        val generationComplete: Boolean = false
    ) : LiveServerEvent

    data class ToolCall(
        val calls: List<LiveFunctionCall>
    ) : LiveServerEvent

    data class ToolCallCancellation(
        val ids: List<String>
    ) : LiveServerEvent

    data class GoAway(
        val timeLeft: String?
    ) : LiveServerEvent

    data class Error(
        val code: Int?,
        val message: String,
        val isQuotaOrRateLimit: Boolean
    ) : LiveServerEvent

    data class Unknown(
        val raw: String
    ) : LiveServerEvent
}

/**
 * Protocolo puro (sem dependências Android) para a Gemini Live API (`BidiGenerateContent` WebSocket).
 * Responsável por montar `setup`, `realtimeInput.audio`, `toolResponse` e interpretar `serverContent`/`toolCall`.
 */
object GeminiLiveProtocol {

    const val WS_ENDPOINT_BASE =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    const val INPUT_SAMPLE_RATE_HZ = 16_000
    const val OUTPUT_SAMPLE_RATE_HZ = 24_000
    const val DEFAULT_VOICE_NAME = "Aoede"
    const val DEFAULT_LANGUAGE_CODE = "pt-BR"

    fun buildWebSocketUrl(apiKey: String): String {
        val encodedKey = URLEncoder.encode(apiKey.trim(), Charsets.UTF_8.name())
        return "$WS_ENDPOINT_BASE?key=$encodedKey"
    }

    fun normalizeModelName(model: String): String {
        val clean = model.trim()
        return if (clean.startsWith("models/")) clean else "models/$clean"
    }

    /**
     * Monta a primeira mensagem `setup` exigida pelo endpoint BidiGenerateContent.
     */
    fun buildSetupMessage(
        model: String,
        systemInstruction: String,
        functionDeclarations: JSONArray = JSONArray(),
        voiceName: String = DEFAULT_VOICE_NAME,
        languageCode: String? = DEFAULT_LANGUAGE_CODE,
        enableGoogleSearch: Boolean = true
    ): JSONObject {
        val speechConfig = JSONObject().apply {
            put(
                "voiceConfig",
                JSONObject().put(
                    "prebuiltVoiceConfig",
                    JSONObject().put("voiceName", voiceName.ifBlank { DEFAULT_VOICE_NAME })
                )
            )
            if (!languageCode.isNullOrBlank()) {
                put("languageCode", languageCode.trim())
            }
        }

        val generationConfig = JSONObject().apply {
            put("responseModalities", JSONArray().put("AUDIO"))
            put("speechConfig", speechConfig)
        }

        val tools = JSONArray().apply {
            if (enableGoogleSearch) {
                put(JSONObject().put("googleSearch", JSONObject()))
            }
            if (functionDeclarations.length() > 0) {
                put(JSONObject().put("functionDeclarations", functionDeclarations))
            }
        }

        val setup = JSONObject().apply {
            put("model", normalizeModelName(model))
            put("generationConfig", generationConfig)
            put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systemInstruction))
                )
            )
            put("inputAudioTranscription", JSONObject())
            put("outputAudioTranscription", JSONObject())
            if (tools.length() > 0) {
                put("tools", tools)
            }
        }

        return JSONObject().put("setup", setup)
    }

    /**
     * Monta frame `realtimeInput.audio` com PCM 16 kHz mono 16-bit em base64.
     */
    fun buildRealtimeAudioMessage(
        pcmBytes: ByteArray,
        sampleRateHz: Int = INPUT_SAMPLE_RATE_HZ
    ): JSONObject {
        val b64 = Base64.getEncoder().encodeToString(pcmBytes)
        return buildRealtimeAudioBase64Message(b64, sampleRateHz)
    }

    fun buildRealtimeAudioBase64Message(
        pcmBase64: String,
        sampleRateHz: Int = INPUT_SAMPLE_RATE_HZ
    ): JSONObject {
        val audioBlob = JSONObject().apply {
            put("mimeType", "audio/pcm;rate=$sampleRateHz")
            put("data", pcmBase64)
        }
        return JSONObject().put(
            "realtimeInput",
            JSONObject().put("audio", audioBlob)
        )
    }

    /**
     * Monta mensagem `toolResponse` em resposta a `toolCall`.
     */
    fun buildToolResponseMessage(responses: List<LiveToolResponse>): JSONObject {
        val arr = JSONArray()
        for (resp in responses) {
            val responseBody = JSONObject().apply {
                put("result", resp.result)
                put("ok", resp.success)
            }
            arr.put(
                JSONObject().apply {
                    put("id", resp.id)
                    put("name", resp.name)
                    put("response", responseBody)
                }
            )
        }
        return JSONObject().put(
            "toolResponse",
            JSONObject().put("functionResponses", arr)
        )
    }

    /**
     * Interpreta qualquer mensagem JSON recebida do WebSocket da Gemini Live API.
     */
    fun parseServerMessage(rawJson: String): LiveServerEvent {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) return LiveServerEvent.Unknown(rawJson)

        val root = runCatching { JSONObject(trimmed) }.getOrNull()
            ?: return LiveServerEvent.Unknown(rawJson)

        if (root.has("setupComplete") || root.has("setup_complete")) {
            return LiveServerEvent.SetupComplete
        }

        val errObj = root.optJSONObject("error")
        if (errObj != null) {
            val code = if (errObj.has("code") && !errObj.isNull("code")) errObj.optInt("code") else null
            val message = errObj.optString("message").ifBlank { errObj.optString("status", "Erro na Live API") }
            val is429 = code == 429 ||
                message.contains("429") ||
                message.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                message.contains("quota", ignoreCase = true)
            return LiveServerEvent.Error(code = code, message = message, isQuotaOrRateLimit = is429)
        }

        val toolCallObj = root.optJSONObject("toolCall") ?: root.optJSONObject("tool_call")
        if (toolCallObj != null) {
            val callsArr = toolCallObj.optJSONArray("functionCalls")
                ?: toolCallObj.optJSONArray("function_calls")
                ?: JSONArray()
            val calls = buildList {
                for (i in 0 until callsArr.length()) {
                    val c = callsArr.optJSONObject(i) ?: continue
                    val name = c.optString("name").trim()
                    if (name.isBlank()) continue
                    val id = c.optString("id").trim().ifBlank { "call_$i" }
                    val argsObj = c.optJSONObject("args") ?: JSONObject()
                    add(LiveFunctionCall(id = id, name = name, argsJson = argsObj.toString()))
                }
            }
            return LiveServerEvent.ToolCall(calls)
        }

        val cancelObj = root.optJSONObject("toolCallCancellation")
            ?: root.optJSONObject("tool_call_cancellation")
        if (cancelObj != null) {
            val idsArr = cancelObj.optJSONArray("ids") ?: JSONArray()
            val ids = (0 until idsArr.length()).mapNotNull { i ->
                idsArr.optString(i).trim().takeIf { it.isNotBlank() }
            }
            return LiveServerEvent.ToolCallCancellation(ids)
        }

        val goAwayObj = root.optJSONObject("goAway") ?: root.optJSONObject("go_away")
        if (goAwayObj != null) {
            val left = goAwayObj.optString("timeLeft").ifBlank { goAwayObj.optString("time_left") }
            return LiveServerEvent.GoAway(left.takeIf { it.isNotBlank() })
        }

        val sc = root.optJSONObject("serverContent") ?: root.optJSONObject("server_content")
        if (sc != null) {
            val audioChunks = mutableListOf<ByteArray>()
            val textParts = mutableListOf<String>()

            val modelTurn = sc.optJSONObject("modelTurn") ?: sc.optJSONObject("model_turn")
            val parts = modelTurn?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val p = parts.optJSONObject(i) ?: continue
                    val text = p.optString("text").takeIf { it.isNotBlank() }
                    if (text != null) textParts.add(text)

                    val inline = p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data")
                    if (inline != null) {
                        val mime = inline.optString("mimeType").ifBlank { inline.optString("mime_type") }
                        val dataB64 = inline.optString("data")
                        if (dataB64.isNotBlank() && (mime.isBlank() || mime.startsWith("audio/", ignoreCase = true))) {
                            runCatching { Base64.getDecoder().decode(dataB64) }
                                .getOrNull()
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { audioChunks.add(it) }
                        }
                    }
                }
            }

            val inputTrans = (sc.optJSONObject("inputTranscription") ?: sc.optJSONObject("input_transcription"))
                ?.optString("text")
                ?.takeIf { it.isNotBlank() }

            val outputTrans = (sc.optJSONObject("outputTranscription") ?: sc.optJSONObject("output_transcription"))
                ?.optString("text")
                ?.takeIf { it.isNotBlank() }

            val interrupted = sc.optBoolean("interrupted", false)
            val turnComplete = sc.optBoolean("turnComplete", false) || sc.optBoolean("turn_complete", false)
            val generationComplete =
                sc.optBoolean("generationComplete", false) || sc.optBoolean("generation_complete", false)

            return LiveServerEvent.ServerContent(
                audioChunks = audioChunks,
                textParts = textParts,
                inputTranscription = inputTrans,
                outputTranscription = outputTrans,
                interrupted = interrupted,
                turnComplete = turnComplete,
                generationComplete = generationComplete
            )
        }

        return LiveServerEvent.Unknown(rawJson)
    }

    /**
     * Detecta erros 429 / cota excedida em códigos HTTP ou mensagens de erro de WebSocket.
     */
    fun isRateLimitOrQuotaError(httpCode: Int?, message: String?): Boolean {
        if (httpCode == 429) return true
        val m = message.orEmpty()
        return m.contains("429") ||
            m.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
            m.contains("quota", ignoreCase = true) ||
            m.contains("rate limit", ignoreCase = true)
    }
}

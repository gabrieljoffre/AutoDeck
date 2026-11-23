package com.autodeck.headunit.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import com.autodeck.headunit.data.HeadUnitConfig
import com.autodeck.headunit.data.VoiceAssistantMode
import com.autodeck.headunit.media.MediaPlaybackState
import com.autodeck.headunit.media.WhatsAppNotificationItem
import com.autodeck.protocol.coordinator.HeadUnitState
import com.autodeck.protocol.coordinator.MainTab
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale

data class AgentChatMessage(
    val id: Long = System.nanoTime(),
    val isUser: Boolean,
    val text: String,
    val actionBadge: String? = null,
    val isOfflineEngine: Boolean = false
)

data class AgentUiState(
    val isListening: Boolean = false,
    val isWakeWordListening: Boolean = false,
    val isThinking: Boolean = false,
    val isSpeaking: Boolean = false,
    val partialTranscript: String = "",
    val statusText: String = "Pronto. Diga \"Ok Honda\" ou toque no microfone.",
    val isOnlineAvailable: Boolean = false,
    val engineBadge: String = "Híbrido (Gemini + Offline)",
    val messages: List<AgentChatMessage> = listOf(
        AgentChatMessage(
            isUser = false,
            text = "Olá! Sou o copiloto do seu Civic. Posso ler dados do motor, controlar o Spotify, traçar rotas no Google Maps e ler/responder mensagens do WhatsApp.",
            isOfflineEngine = true
        )
    )
)

interface CarAgentHostBridge {
    fun getConfig(): HeadUnitConfig
    fun getHeadUnitState(): HeadUnitState
    fun getMediaState(): MediaPlaybackState
    fun getWhatsAppMessages(): List<WhatsAppNotificationItem>
    fun controlMusic(action: String, query: String? = null): String
    fun navigateTo(destination: String?): String
    fun switchTab(tab: MainTab): String
    fun toggleReverseCamera(): String
    fun replyLatestWhatsApp(replyText: String): String
    fun markMessagesRead()
}

/**
 * Agente Automotivo Híbrido:
 * - Online: Google Gemini API com acesso em tempo real à telemetria OBD2, Spotify, GPS e WhatsApp.
 * - Offline: Motor Local instantâneo no tablet para operar sem internet do celular.
 * - Voz: Wake-word configurável ("Ok Honda"), SpeechRecognizer e conversa contínua via TTS pt-BR.
 */
class GeminiCarAgent(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val host: CarAgentHostBridge,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main
) {
    private val _state = MutableStateFlow(AgentUiState())
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    private val conversationHistory = mutableListOf<Pair<String, String>>() // ("user"|"model", text)
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var isForeground = true
    private var wakeLoopActive = false
    private var activeCommandMode = false
    private var continuousSession = false
    private var restartWakeJob: Job? = null

    init {
        scope.launch(mainDispatcher) {
            runCatching { initTts() }
        }
        refreshConnectivityBadge()
    }

    private fun initTts() {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val res = tts?.setLanguage(Locale("pt", "BR"))
                ttsReady = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
                tts?.setSpeechRate(1.06f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _state.update { it.copy(isSpeaking = true) }
                    }

                    override fun onDone(utteranceId: String?) {
                        scope.launch(mainDispatcher) {
                            _state.update { it.copy(isSpeaking = false) }
                            val cfg = host.getConfig()
                            if (continuousSession && cfg.continuousConversationEnabled && isForeground) {
                                delay(280L)
                                startDirectVoiceCommand(fromContinuous = true)
                            } else if (cfg.wakeWordEnabled && isForeground) {
                                scheduleWakeWordRestart(400L)
                            }
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _state.update { it.copy(isSpeaking = false) }
                    }
                })
            }
        }
    }

    fun setAppForeground(inForeground: Boolean) {
        isForeground = inForeground
        val cfg = host.getConfig()
        if (inForeground && cfg.wakeWordEnabled && cfg.voiceAssistantMode != VoiceAssistantMode.SYSTEM_ASSISTANT) {
            scheduleWakeWordRestart(700L)
        } else if (!inForeground && !activeCommandMode) {
            stopRecognizerNow()
        }
    }

    fun onConfigUpdated(config: HeadUnitConfig) {
        refreshConnectivityBadge()
        if (config.wakeWordEnabled &&
            isForeground &&
            config.voiceAssistantMode != VoiceAssistantMode.SYSTEM_ASSISTANT &&
            !activeCommandMode
        ) {
            scheduleWakeWordRestart(500L)
        } else if (!config.wakeWordEnabled) {
            wakeLoopActive = false
            if (!activeCommandMode) stopRecognizerNow()
            _state.update { it.copy(isWakeWordListening = false) }
        }
    }

    fun refreshConnectivityBadge() {
        val online = runCatching { hasInternetConnection() }.getOrDefault(false)
        val cfg = runCatching { host.getConfig() }.getOrNull() ?: HeadUnitConfig()
        val badge = when (cfg.voiceAssistantMode) {
            VoiceAssistantMode.SYSTEM_ASSISTANT -> "Assistente Android"
            VoiceAssistantMode.LOCAL_ONLY -> "Motor Local Offline"
            VoiceAssistantMode.HYBRID_GEMINI -> when {
                online && cfg.geminiApiKey.isNotBlank() -> "Gemini Online (${cfg.geminiModel})"
                online && cfg.geminiApiKey.isBlank() -> "Offline (Configure Chave Gemini)"
                else -> "Offline (Sem Internet)"
            }
        }
        _state.update { it.copy(isOnlineAvailable = online, engineBadge = badge) }
    }

    fun triggerVoiceCommand() {
        val cfg = host.getConfig()
        if (cfg.voiceAssistantMode == VoiceAssistantMode.SYSTEM_ASSISTANT) {
            launchSystemVoiceAssistant()
            return
        }
        if (_state.value.isListening && activeCommandMode) {
            stopListening()
            return
        }
        startDirectVoiceCommand(fromContinuous = false)
    }

    fun stopListening() {
        continuousSession = false
        activeCommandMode = false
        wakeLoopActive = false
        restartWakeJob?.cancel()
        scope.launch(mainDispatcher) {
            stopRecognizerNow()
            runCatching { tts?.stop() }
            _state.update {
                it.copy(
                    isListening = false,
                    isWakeWordListening = false,
                    isSpeaking = false,
                    partialTranscript = "",
                    statusText = "Escuta pausada."
                )
            }
        }
    }

    fun sendUserPrompt(text: String, speakReply: Boolean = true) {
        val clean = text.trim()
        if (clean.isBlank()) return
        appendMessage(AgentChatMessage(isUser = true, text = clean))
        processQuery(clean, speakReply = speakReply)
    }

    fun readLatestWhatsAppAloud() {
        val msgs = host.getWhatsAppMessages()
        if (msgs.isEmpty()) {
            val reply = "Nenhuma mensagem nova do WhatsApp no momento."
            appendMessage(AgentChatMessage(isUser = false, text = reply, isOfflineEngine = true))
            speak(reply)
            return
        }
        val latest = msgs.first()
        host.markMessagesRead()
        val reply = "Mensagem de ${latest.sender} no ${latest.appLabel}: ${latest.message}"
        appendMessage(AgentChatMessage(isUser = false, text = reply, actionBadge = "WhatsApp", isOfflineEngine = true))
        speak(reply)
    }

    private fun startDirectVoiceCommand(fromContinuous: Boolean) {
        if (!hasAudioPermission()) {
            _state.update { it.copy(statusText = "Permita o acesso ao microfone nas Configurações.") }
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            _state.update { it.copy(statusText = "Reconhecimento de voz indisponível neste aparelho.") }
            return
        }

        restartWakeJob?.cancel()
        scope.launch(mainDispatcher) {
            runCatching { tts?.stop() }
            wakeLoopActive = false
            activeCommandMode = true
            continuousSession = host.getConfig().continuousConversationEnabled
            _state.update {
                it.copy(
                    isListening = true,
                    isWakeWordListening = false,
                    isSpeaking = false,
                    partialTranscript = "",
                    statusText = if (fromContinuous) "Continuando conversa... pode falar." else "Ouvindo você..."
                )
            }
            startRecognizerInternal(isWakeWordMode = false)
        }
    }

    private fun scheduleWakeWordRestart(delayMs: Long) {
        restartWakeJob?.cancel()
        restartWakeJob = scope.launch(mainDispatcher) {
            delay(delayMs)
            val cfg = host.getConfig()
            if (!cfg.wakeWordEnabled || !isForeground || activeCommandMode || _state.value.isSpeaking) return@launch
            if (cfg.voiceAssistantMode == VoiceAssistantMode.SYSTEM_ASSISTANT) return@launch
            if (!hasAudioPermission() || !SpeechRecognizer.isRecognitionAvailable(appContext)) return@launch

            wakeLoopActive = true
            _state.update {
                it.copy(
                    isWakeWordListening = true,
                    statusText = "Aguardando \"${cfg.wakeWordPhrase}\"..."
                )
            }
            startRecognizerInternal(isWakeWordMode = true)
        }
    }

    private fun startRecognizerInternal(isWakeWordMode: Boolean) {
        stopRecognizerNow()
        val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
        speechRecognizer = recognizer

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (text.isBlank()) return

                if (!isWakeWordMode) {
                    _state.update { it.copy(partialTranscript = text) }
                } else {
                    val wakePhrase = host.getConfig().wakeWordPhrase
                    if (containsWakeWord(text, wakePhrase)) {
                        _state.update { it.copy(partialTranscript = text) }
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                val spoken = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()

                if (isWakeWordMode) {
                    val cfg = host.getConfig()
                    if (containsWakeWord(spoken, cfg.wakeWordPhrase)) {
                        val afterWake = stripWakeWord(spoken, cfg.wakeWordPhrase)
                        wakeLoopActive = false
                        if (afterWake.length >= 3) {
                            activeCommandMode = false
                            continuousSession = cfg.continuousConversationEnabled
                            sendUserPrompt(afterWake, speakReply = true)
                        } else {
                            startDirectVoiceCommand(fromContinuous = false)
                        }
                    } else if (cfg.wakeWordEnabled && isForeground && !activeCommandMode) {
                        scheduleWakeWordRestart(450L)
                    }
                    return
                }

                activeCommandMode = false
                _state.update { it.copy(isListening = false, partialTranscript = "") }
                if (spoken.isBlank()) {
                    continuousSession = false
                    if (host.getConfig().wakeWordEnabled) scheduleWakeWordRestart(500L)
                } else if (isStopPhrase(spoken)) {
                    continuousSession = false
                    val bye = "Certo, qualquer coisa é só chamar."
                    appendMessage(AgentChatMessage(isUser = true, text = spoken))
                    appendMessage(AgentChatMessage(isUser = false, text = bye, isOfflineEngine = true))
                    speak(bye)
                } else {
                    sendUserPrompt(spoken, speakReply = true)
                }
            }

            override fun onError(error: Int) {
                if (isWakeWordMode) {
                    if (host.getConfig().wakeWordEnabled && isForeground && !activeCommandMode) {
                        val backoff = when (error) {
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                            SpeechRecognizer.ERROR_CLIENT -> 1_200L
                            SpeechRecognizer.ERROR_AUDIO,
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> 3_000L
                            else -> 500L
                        }
                        scheduleWakeWordRestart(backoff)
                    }
                    return
                }

                activeCommandMode = false
                continuousSession = false
                _state.update {
                    it.copy(
                        isListening = false,
                        partialTranscript = "",
                        statusText = if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                            "Não ouvi nenhuma frase."
                        } else {
                            "Erro no microfone ($error)."
                        }
                    )
                }
                if (host.getConfig().wakeWordEnabled && isForeground) {
                    scheduleWakeWordRestart(700L)
                }
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (isWakeWordMode) {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
            }
        }
        runCatching { recognizer.startListening(intent) }
    }

    private fun stopRecognizerNow() {
        runCatching {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        }
        speechRecognizer = null
    }

    private fun processQuery(userText: String, speakReply: Boolean) {
        scope.launch {
            refreshConnectivityBadge()
            val cfg = host.getConfig()
            val online = _state.value.isOnlineAvailable
            val canUseGemini = cfg.voiceAssistantMode == VoiceAssistantMode.HYBRID_GEMINI &&
                online &&
                cfg.geminiApiKey.isNotBlank()

            _state.update {
                it.copy(
                    isThinking = true,
                    statusText = if (canUseGemini) "Consultando Gemini..." else "Processando localmente..."
                )
            }

            val result = if (canUseGemini) {
                callGeminiWithFallback(userText, cfg)
            } else {
                runLocalOfflineAgent(userText)
            }

            _state.update {
                it.copy(
                    isThinking = false,
                    statusText = result.actionBadge?.let { b -> "Executado: $b" } ?: "Resposta pronta."
                )
            }

            appendMessage(
                AgentChatMessage(
                    isUser = false,
                    text = result.replyText,
                    actionBadge = result.actionBadge,
                    isOfflineEngine = result.isOffline
                )
            )
            if (speakReply) {
                speak(result.replyText)
            }
        }
    }

    private data class AgentReplyResult(
        val replyText: String,
        val actionBadge: String? = null,
        val isOffline: Boolean = false
    )

    private suspend fun callGeminiWithFallback(
        userText: String,
        cfg: HeadUnitConfig
    ): AgentReplyResult = withContext(Dispatchers.IO) {
        val systemPrompt = buildSystemContextPrompt()
        val contents = JSONArray()

        // Últimos 6 turnos para manter contexto sem gastar muitos tokens
        conversationHistory.takeLast(6).forEach { (role, msg) ->
            contents.put(
                JSONObject().apply {
                    put("role", role)
                    put("parts", JSONArray().put(JSONObject().put("text", msg)))
                }
            )
        }
        contents.put(
            JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", userText)))
            }
        )

        val body = JSONObject().apply {
            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            put("contents", contents)
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.4)
                put("maxOutputTokens", 280)
            })
        }

        val apiResult = runCatching {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/${cfg.geminiModel}:generateContent?key=${cfg.geminiApiKey.trim()}"
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 7_000
                readTimeout = 12_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body.toString())
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
            conn.disconnect()

            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code: $raw")
            }
            val json = JSONObject(raw)
            val candidates = json.optJSONArray("candidates") ?: throw IllegalStateException("Sem resposta do Gemini")
            val first = candidates.getJSONObject(0)
            val parts = first.getJSONObject("content").getJSONArray("parts")
            parts.getJSONObject(0).getString("text").trim()
        }

        val rawText = apiResult.getOrNull()
        if (rawText.isNullOrBlank()) {
            // Se falhar rede/cota, responde localmente sem deixar o motorista na mão!
            val local = runLocalOfflineAgent(userText)
            return@withContext local.copy(
                replyText = local.replyText,
                isOffline = true
            )
        }

        val parsed = parseAndExecuteGeminiAction(rawText)
        conversationHistory.add("user" to userText)
        conversationHistory.add("model" to parsed.replyText)
        if (conversationHistory.size > 16) {
            repeat(2) { conversationHistory.removeAt(0) }
        }
        parsed
    }

    private fun buildSystemContextPrompt(): String {
        val hu = host.getHeadUnitState()
        val snap = hu.obdSnapshot
        val derived = snap.derived
        val media = host.getMediaState()
        val msgs = host.getWhatsAppMessages()
        val latestMsg = msgs.firstOrNull()

        return """
            Você é o HondaDeck Copilot, assistente automotivo em tempo real de um Honda Civic com tablet no painel.
            Responda sempre em Português do Brasil, de forma clara, natural e curta (ideal para ser lida em voz alta enquanto dirige).
            
            TELEMETRIA DO CARRO AGORA:
            - Status OBD2: ${hu.obdStatus.labelPt}
            - Velocidade: ${derived.correctedSpeedKmh?.toInt() ?: snap.speedKmh?.toInt() ?: 0} km/h
            - RPM: ${snap.rpm?.toInt() ?: 0}
            - Marcha estimada: ${derived.estimatedGearLabel}
            - Temperatura motor: ${snap.coolantTempC?.let { "${it.toInt()}°C" } ?: "sem leitura"} (${derived.thermalStatusLabel})
            - Voltagem bateria: ${snap.batteryVoltageV?.let { "%.1fV".format(it) } ?: "sem leitura"}
            - Uso do motor: ${snap.engineLoadPct?.let { "${it.toInt()}%" } ?: "sem leitura"}
            - Consumo instantâneo: ${snap.instantKmPerL?.let { "%.1f km/L".format(it) } ?: "--"} | Médio: ${snap.averageKmPerL?.let { "%.1f km/L".format(it) } ?: "--"}
            - Tanque virtual: ${derived.virtualTankPercent.toInt()}% (${"%.1f".format(derived.virtualTankRemainingLiters)}L) | Autonomia: ${derived.estimatedRangeKm?.let { "${it.toInt()} km" } ?: "--"}
            - Alertas ativos: ${if (hu.obdAlerts.isEmpty()) "Nenhum" else hu.obdAlerts.joinToString { it.title }}
            - Códigos de falha DTC: ${if (hu.dtcCodes.isEmpty()) "Nenhum" else hu.dtcCodes.joinToString { it.code }}
            
            MÚSICA AGORA (${media.appLabel}):
            - Tocando: ${media.isPlaying} | Faixa: "${media.title}" por "${media.artist}"
            
            WHATSAPP RECEBIDO:
            - ${if (latestMsg != null) "De ${latestMsg.sender}: \"${latestMsg.message}\"" else "Nenhuma mensagem pendente"}
            
            Se o usuário pedir para executar uma ação no carro ou app, responda normalmente e inclua EXATAMENTE na última linha:
            ACTION_JSON:{"action":"NOME_DA_ACAO","arg":"VALOR_OPCIONAL"}
            Ações válidas:
            - MUSIC_PLAY | MUSIC_PAUSE | MUSIC_NEXT | MUSIC_PREV | OPEN_SPOTIFY
            - MUSIC_SEARCH (arg = nome da música ou artista)
            - OPEN_MAPS | NAVIGATE (arg = destino ou endereço)
            - READ_WHATSAPP | REPLY_WHATSAPP (arg = texto exato da resposta)
            - SWITCH_TAB (arg = HOME, NAVIGATION, MUSIC, AGENT_VOICE, OBD_SCANNER, SETTINGS)
            - OPEN_CAMERA
        """.trimIndent()
    }

    private fun parseAndExecuteGeminiAction(rawText: String): AgentReplyResult {
        val marker = "ACTION_JSON:"
        val idx = rawText.indexOf(marker)
        if (idx < 0) {
            return AgentReplyResult(replyText = rawText.trim(), isOffline = false)
        }
        val spoken = rawText.substring(0, idx).trim()
        val jsonStr = rawText.substring(idx + marker.length).lineSequence().firstOrNull().orEmpty().trim()
        var badge: String? = null
        runCatching {
            val obj = JSONObject(jsonStr)
            val action = obj.optString("action").uppercase()
            val arg = obj.optString("arg")
            badge = executeHostAction(action, arg)
        }
        return AgentReplyResult(
            replyText = spoken.ifBlank { badge ?: "Pronto." },
            actionBadge = badge,
            isOffline = false
        )
    }

    private fun executeHostAction(action: String, arg: String): String? {
        return when (action) {
            "MUSIC_PLAY", "MUSIC_PAUSE", "MUSIC_NEXT", "MUSIC_PREV", "OPEN_SPOTIFY" ->
                host.controlMusic(action, null)
            "MUSIC_SEARCH" -> host.controlMusic("SEARCH", arg)
            "OPEN_MAPS" -> host.navigateTo(null)
            "NAVIGATE" -> host.navigateTo(arg.takeIf { it.isNotBlank() })
            "READ_WHATSAPP" -> {
                host.markMessagesRead()
                "WhatsApp lido"
            }
            "REPLY_WHATSAPP" -> if (arg.isNotBlank()) host.replyLatestWhatsApp(arg) else null
            "SWITCH_TAB" -> {
                val tab = MainTab.entries.firstOrNull { it.name == arg.uppercase() } ?: MainTab.HOME
                host.switchTab(tab)
            }
            "OPEN_CAMERA" -> host.toggleReverseCamera()
            else -> null
        }
    }

    private fun runLocalOfflineAgent(rawInput: String): AgentReplyResult {
        val q = normalize(rawInput)
        val hu = host.getHeadUnitState()
        val snap = hu.obdSnapshot
        val derived = snap.derived

        return when {
            q.contains("proxima") || q.contains("pular") || q.contains("avancar musica") -> {
                val badge = host.controlMusic("MUSIC_NEXT")
                AgentReplyResult("Passando para a próxima música.", badge, isOffline = true)
            }
            q.contains("anterior") || q.contains("voltar musica") -> {
                val badge = host.controlMusic("MUSIC_PREV")
                AgentReplyResult("Voltando para a música anterior.", badge, isOffline = true)
            }
            q.contains("pausar") || q.contains("parar musica") || q.contains("pause") -> {
                val badge = host.controlMusic("MUSIC_PAUSE")
                AgentReplyResult("Música pausada.", badge, isOffline = true)
            }
            q.startsWith("tocar ") || q.contains("coloca ") -> {
                val query = rawInput.substringAfter("tocar ", rawInput.substringAfter("coloca ", "")).trim()
                if (query.length >= 2 && !query.equals("musica", ignoreCase = true)) {
                    val badge = host.controlMusic("SEARCH", query)
                    AgentReplyResult("Buscando $query no Spotify.", badge, isOffline = true)
                } else {
                    val badge = host.controlMusic("MUSIC_PLAY")
                    AgentReplyResult("Retomando a música.", badge, isOffline = true)
                }
            }
            q.contains("tocar") || q.contains("continuar musica") || q.contains("play") -> {
                val badge = host.controlMusic("MUSIC_PLAY")
                AgentReplyResult("Tocando música agora.", badge, isOffline = true)
            }
            q.contains("abrir spotify") || q.contains("abre o spotify") -> {
                val badge = host.controlMusic("OPEN_SPOTIFY")
                AgentReplyResult("Abrindo o Spotify.", badge, isOffline = true)
            }
            q.contains("ir para ") || q.contains("navegar para ") || q.contains("rota para ") -> {
                val dest = rawInput.replace(Regex("(?i).*(ir para|navegar para|rota para)\\s+"), "").trim()
                val badge = host.navigateTo(dest.takeIf { it.isNotBlank() })
                AgentReplyResult("Iniciando rota para $dest.", badge, isOffline = true)
            }
            q.contains("abrir maps") || q.contains("google maps") || q.contains("abrir mapa") -> {
                val badge = host.navigateTo(null)
                AgentReplyResult("Abrindo o mapa.", badge, isOffline = true)
            }
            q.contains("ler mensagem") || q.contains("whatsapp") || q.contains("mensagens") -> {
                val latest = host.getWhatsAppMessages().firstOrNull()
                if (latest == null) {
                    AgentReplyResult("Não há mensagens recentes do WhatsApp.", isOffline = true)
                } else {
                    host.markMessagesRead()
                    AgentReplyResult(
                        "Mensagem de ${latest.sender}: ${latest.message}",
                        "WhatsApp",
                        isOffline = true
                    )
                }
            }
            q.startsWith("responder ") -> {
                val reply = rawInput.replaceFirst(Regex("(?i)responder\\s+"), "").trim()
                val res = host.replyLatestWhatsApp(reply)
                AgentReplyResult(res, "WhatsApp", isOffline = true)
            }
            q.contains("temperatura") || q.contains("esquentando") -> {
                val t = snap.coolantTempC?.toInt()
                val msg = if (t != null) {
                    "O motor está a $t graus Celsius, status ${derived.thermalStatusLabel}."
                } else {
                    "Ainda não recebi a temperatura do motor pelo OBD2."
                }
                AgentReplyResult(msg, "OBD2 Offline", isOffline = true)
            }
            q.contains("voltagem") || q.contains("bateria") || q.contains("alternador") -> {
                val v = snap.batteryVoltageV
                val msg = if (v != null) {
                    "A bateria está em ${"%.1f".format(v)} volts."
                } else {
                    "Sem leitura de voltagem no momento."
                }
                AgentReplyResult(msg, "OBD2 Offline", isOffline = true)
            }
            q.contains("consumo") || q.contains("combustivel") || q.contains("autonomia") || q.contains("tanque") -> {
                val avg = snap.averageKmPerL?.let { "%.1f quilômetros por litro".format(it) } ?: "ainda calculando"
                val range = derived.estimatedRangeKm?.let { "${it.toInt()} quilômetros" } ?: "indisponível"
                AgentReplyResult(
                    "Seu consumo médio é $avg, com cerca de ${derived.virtualTankPercent.toInt()}% no tanque e autonomia estimada de $range.",
                    "OBD2 Offline",
                    isOffline = true
                )
            }
            q.contains("motor") || q.contains("diagnostico") || q.contains("como esta o carro") || q.contains("scanner") || q.contains("falha") -> {
                val temp = snap.coolantTempC?.let { "${it.toInt()}°C" } ?: "--"
                val volt = snap.batteryVoltageV?.let { "%.1fV".format(it) } ?: "--"
                val load = snap.engineLoadPct?.let { "${it.toInt()}%" } ?: "--"
                val dtcs = if (hu.dtcCodes.isEmpty()) "sem códigos de falha" else "${hu.dtcCodes.size} falhas registradas"
                AgentReplyResult(
                    "Resumo do Civic: motor a $temp, bateria em $volt, uso do motor em $load e $dtcs.",
                    "Diagnóstico Local",
                    isOffline = true
                )
            }
            q.contains("camera") || q.contains("re") -> {
                val badge = host.toggleReverseCamera()
                AgentReplyResult("Alternando visualização da câmera.", badge, isOffline = true)
            }
            q.contains("configurac") -> {
                val badge = host.switchTab(MainTab.SETTINGS)
                AgentReplyResult("Abrindo configurações.", badge, isOffline = true)
            }
            q.contains("inicio") || q.contains("home") || q.contains("painel") -> {
                val badge = host.switchTab(MainTab.HOME)
                AgentReplyResult("Voltando para o painel principal.", badge, isOffline = true)
            }
            else -> {
                val reason = if (!_state.value.isOnlineAvailable) {
                    "Estou sem internet do celular no momento."
                } else {
                    "Configure sua chave do Gemini nas Configurações para perguntas livres."
                }
                AgentReplyResult(
                    "$reason No modo offline você pode pedir temperatura do motor, voltagem, consumo, músicas do Spotify, rotas ou leitura do WhatsApp.",
                    "Modo Offline",
                    isOffline = true
                )
            }
        }
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        val clean = text.replace("*", "").trim()
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "honda_reply_${System.currentTimeMillis()}")
    }

    private fun appendMessage(msg: AgentChatMessage) {
        _state.update { cur ->
            cur.copy(messages = (cur.messages + msg).takeLast(24))
        }
    }

    private fun launchSystemVoiceAssistant() {
        val intent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { appContext.startActivity(intent) }
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    private fun hasInternetConnection(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun containsWakeWord(transcript: String, wakePhrase: String): Boolean {
        val normTranscript = normalize(transcript)
        val normWake = normalize(wakePhrase).ifBlank { "ok honda" }
        return normTranscript.contains(normWake)
    }

    private fun stripWakeWord(transcript: String, wakePhrase: String): String {
        val normWake = normalize(wakePhrase).ifBlank { "ok honda" }
        val words = normWake.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return transcript.trim()
        val pattern = words.joinToString("\\s+") { Regex.escape(it) }
        val idx = Regex(pattern, RegexOption.IGNORE_CASE).find(normalize(transcript))?.range?.last
        return if (idx != null && idx + 1 < transcript.length) {
            transcript.substring((idx + 1).coerceAtMost(transcript.length)).trim()
        } else {
            ""
        }
    }

    private fun isStopPhrase(transcript: String): Boolean {
        val q = normalize(transcript)
        return q == "obrigado" || q == "valeu" || q == "encerrar" || q == "parar" || q == "tchau"
    }

    private fun normalize(text: String): String {
        val nfd = Normalizer.normalize(text.lowercase(Locale("pt", "BR")), Normalizer.Form.NFD)
        return nfd.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "").trim()
    }

    fun release() {
        stopListening()
        runCatching { tts?.shutdown() }
    }
}

package com.autodeck.headunit.agent

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.media.AudioManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import com.autodeck.headunit.agent.copilot.ActionJsonParser
import com.autodeck.headunit.agent.copilot.CopilotIntent
import com.autodeck.headunit.agent.copilot.CopilotIntentParser
import com.autodeck.headunit.agent.copilot.CopilotJson
import com.autodeck.headunit.agent.copilot.CopilotTab
import com.autodeck.headunit.agent.copilot.FavoriteRef
import com.autodeck.headunit.agent.copilot.GeminiToolLoop
import com.autodeck.headunit.agent.copilot.TelemetryKind
import com.autodeck.headunit.agent.copilot.VolumeDirection
import com.autodeck.headunit.agent.copilot.WebKnowledgeTools
import com.autodeck.headunit.agent.live.CopilotMemoryStore
import com.autodeck.headunit.agent.live.CopilotMemoryTurn
import com.autodeck.headunit.agent.live.GeminiLiveCallbacks
import com.autodeck.headunit.agent.live.GeminiLiveSession
import com.autodeck.headunit.agent.live.GeminiLiveTools
import com.autodeck.headunit.agent.live.LiveFunctionCall
import com.autodeck.headunit.agent.live.LiveSessionMode
import com.autodeck.headunit.agent.live.LiveSessionPhase
import com.autodeck.headunit.agent.live.LiveSessionSnapshot
import com.autodeck.headunit.agent.live.LiveToolExecution
import com.autodeck.headunit.data.DEFAULT_GEMINI_MODEL
import com.autodeck.headunit.data.HeadUnitConfig
import com.autodeck.headunit.data.VoiceAssistantMode
import com.autodeck.headunit.media.MediaPlaybackState
import com.autodeck.headunit.media.WhatsAppNotificationItem
import com.autodeck.protocol.coordinator.HeadUnitState
import com.autodeck.protocol.coordinator.MainTab
import com.autodeck.protocol.obd2.FuelLogCalculator
import com.autodeck.protocol.phone.BrazilPhoneNumber
import com.autodeck.protocol.phone.PhoneContact
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
import java.io.File
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
    val isOfflineEngine: Boolean = false,
    val suppressReplyPopup: Boolean = false
)

data class AgentUiState(
    val isListening: Boolean = false,
    val isWakeWordListening: Boolean = false,
    val isThinking: Boolean = false,
    val isSpeaking: Boolean = false,
    val copilotMode: String = "idle", // "idle" | "live" | "ask"
    val livePhase: String = "idle",   // "idle" | "connecting" | "listening" | "thinking" | "speaking"
    val audioLevel: Float = 0f,       // 0..1 para animação da onda
    val userText: String = "",
    val replyText: String = "",
    val partialTranscript: String = "",
    val statusText: String = "Pronto. Toque em Conversar ou Peça ao Gemini.",
    val isOnlineAvailable: Boolean = false,
    val engineBadge: String = "Híbrido (Gemini Live + Offline)",
    val messages: List<AgentChatMessage> = listOf(
        AgentChatMessage(
            isUser = false,
            text = "Olá! Sou o copiloto do seu Civic. Posso ler dados do motor, controlar o Spotify, traçar rotas no mapa e ler/responder mensagens do WhatsApp.",
            isOfflineEngine = true
        )
    )
)

data class ResolvedPhoneRecipient(
    val ok: Boolean,
    val displayName: String = "",
    val normalizedNumber: String = "",
    val hasWhatsApp: Boolean = false,
    val voipDataId: Long? = null,
    val errorMessage: String? = null,
    val multipleCandidates: List<PhoneContact> = emptyList()
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

    /** Resumo do clima atual e previsão (null enquanto não houver GPS/internet). */
    fun getWeatherSummary(): String? = null

    /** Resumo da agenda do usuário para hoje (dayOffset=0) ou amanhã (dayOffset=1). */
    fun getCalendarAgendaSummary(dayOffset: Int = 0): String =
        "Agenda indisponível no momento."

    /** Registra um abastecimento e devolve a frase de confirmação para falar. */
    fun logRefuelFromAgent(liters: Double, pricePerLiter: Double?, odometerKm: Double?, fullTank: Boolean): String =
        "Diário de abastecimento indisponível."

    /** Consumo real e gastos do diário de abastecimento (null se não houver registros). */
    fun getFuelLogSummary(): String? = null

    /** Favoritos salvos pelo usuário para navegação por voz e contexto do Gemini. */
    fun getFavoritePlaces(): List<FavoriteRef> = emptyList()

    /** Navegação com suporte a favorito e mapa externo explícito. */
    fun navigateTo(
        destination: String?,
        favorite: FavoriteRef?,
        forceExternalMaps: Boolean
    ): String = navigateTo(destination)

    /** Busca lugares no mapa e abre a aba/modo mapa com os resultados. */
    fun searchPlacesOnMap(query: String): String = "Busca no mapa: $query"

    /** Busca lugares para retornar ao tool-calling do Gemini. */
    suspend fun searchPlacesForTool(query: String): String = searchPlacesOnMap(query)

    /** Altera o tema visual do mapa ("AUTO", "LIGHT" ou "DARK"). */
    fun setMapTheme(theme: String): String = "Tema do mapa: $theme"

    /** Altera a cor de destaque do painel (`#RRGGBB`). */
    fun setAccentColor(colorHex: String): String = "Cor do painel: $colorHex"

    /** Abre o mapa em modo tela cheia (`mapFull`). */
    fun openMapFullScreen(): String = navigateTo(null, null, false)

    /** Encerra a rota ativa no mapa do app. */
    fun stopRoute(): String = "Rota encerrada."

    /** Volta para a tela inicial do painel (fecha mapa/Spotify expandido). */
    fun openHome(): String = switchTab(MainTab.HOME)

    /** Resumo textual da rota ativa no app (ou null se não houver rota). */
    fun getRouteSummary(): String? = null

    /** Resumo dos itens de manutenção cadastrados (ou null se não houver). */
    fun getMaintenanceSummary(): String? = null

    /** Consulta clima de uma cidade específica (fallback web). */
    suspend fun getWeatherSummaryForCity(city: String): String? = null

    /** Lê e retorna resumo dos códigos de falha DTC atuais do veículo. */
    fun readDtcSummaryForAgent(): String {
        val dtcs = getHeadUnitState().dtcCodes
        return if (dtcs.isEmpty()) {
            "Nenhum código de falha DTC registrado no Civic."
        } else {
            "Encontradas ${dtcs.size} falhas DTC: ${dtcs.joinToString("; ") { "${it.code} (${it.descriptionPt})" }}."
        }
    }

    /** Lê a última mensagem recebida do WhatsApp e marca como lida. */
    fun readAndMarkWhatsApp(): String {
        val latest = getWhatsAppMessages().firstOrNull()
            ?: return "Nenhuma mensagem recente do WhatsApp."
        markMessagesRead()
        return "Mensagem de ${latest.sender} no ${latest.appLabel}: ${latest.message}"
    }

    /** Busca música na Spotify Web API e toca pelo App Remote (ou fallback). */
    suspend fun searchAndPlayMusic(query: String): String = controlMusic("SEARCH", query)

    /** Abre a câmera de ré (idempotente, não alterna). */
    fun openReverseCamera(): String = toggleReverseCamera()

    /** Fecha a visualização manual da câmera de ré. */
    fun closeReverseCamera(): String = toggleReverseCamera()

    /** Ajusta o volume principal do tablet. */
    fun adjustVolume(direction: VolumeDirection, value: Int? = null): String = "Volume"

    // --- Ponte Celular (WhatsApp, ligações, SMS via HondaDeck Ponte) ---
    fun isPhoneConnected(): Boolean = false

    fun getPhoneSummary(): String = "Celular não conectado."

    fun getLatestPhoneMessageSummary(): Pair<String, String>? = null

    suspend fun resolvePhoneRecipient(recipient: String): ResolvedPhoneRecipient {
        val clean = recipient.trim()
        if (clean.isEmpty()) {
            return ResolvedPhoneRecipient(ok = false, errorMessage = "Informe o nome do contato ou número.")
        }
        val norm = BrazilPhoneNumber.normalizeForDial(clean)
        if (norm != null && BrazilPhoneNumber.looksLikePhoneNumber(clean)) {
            return ResolvedPhoneRecipient(
                ok = true,
                displayName = BrazilPhoneNumber.formatDisplay(norm),
                normalizedNumber = norm
            )
        }
        if (!isPhoneConnected()) {
            return ResolvedPhoneRecipient(
                ok = false,
                errorMessage = "Celular não conectado ao HondaDeck Ponte. Pareie em Ajustes › Celular."
            )
        }
        return ResolvedPhoneRecipient(ok = false, errorMessage = "Contato \"$clean\" não encontrado.")
    }

    suspend fun sendWhatsAppTextViaPhone(recipient: String, text: String): String =
        "Celular não conectado ao HondaDeck Ponte."

    suspend fun recordAndSendWhatsAppVoiceViaPhone(recipient: String): String =
        "Celular não conectado ao HondaDeck Ponte."

    suspend fun dialPhoneViaPhone(recipient: String): String =
        "Celular não conectado ao HondaDeck Ponte."

    suspend fun callWhatsAppViaPhone(recipient: String): String =
        "Celular não conectado ao HondaDeck Ponte."

    suspend fun answerPhoneCall(): String =
        "Celular não conectado ao HondaDeck Ponte."

    suspend fun endPhoneCall(): String =
        "Celular não conectado ao HondaDeck Ponte."

    fun readPhoneMessages(): String = readAndMarkWhatsApp()

    suspend fun replyPhoneMessage(messageId: String?, text: String): String =
        replyLatestWhatsApp(text)

    /** Relatório completo do veículo (motor, consumo, diário, manutenção e clima). */
    fun buildCarReport(): String {
        val hu = getHeadUnitState()
        val snap = hu.obdSnapshot
        val derived = snap.derived
        val temp = snap.coolantTempC?.let { "${it.toInt()}°C" } ?: "sem leitura"
        val volt = snap.batteryVoltageV?.let { "%.1fV".format(it) } ?: "sem leitura"
        val load = snap.engineLoadPct?.let { "${it.toInt()}%" } ?: "--"
        val dtcs = if (hu.dtcCodes.isEmpty()) "sem falhas" else "${hu.dtcCodes.size} falhas (${hu.dtcCodes.joinToString { it.code }})"
        val avg = snap.averageKmPerL?.let { "%.1f km/L".format(it) } ?: "--"
        val range = derived.estimatedRangeKm?.let { "${it.toInt()} km" } ?: "--"
        val tank = "${derived.virtualTankPercent.toInt()}%"
        val fuel = getFuelLogSummary()
        val weather = getWeatherSummary()
        return buildString {
            append("Relatório do Civic: motor a ").append(temp)
            append(", bateria ").append(volt)
            append(", uso ").append(load)
            append(", ").append(dtcs).append(". ")
            append("Consumo médio ").append(avg)
            append(", tanque em ").append(tank)
            append(" e autonomia de ").append(range).append(". ")
            if (!fuel.isNullOrBlank()) append("Diário: ").append(fuel).append(". ")
            if (!weather.isNullOrBlank()) append(weather)
        }.trim()
    }
}

/**
 * Agente Automotivo Híbrido:
 * - Online: Google Gemini API (google_search -> function calling próprio -> app Gemini do tablet).
 * - Offline: Motor Local determinístico via [CopilotIntentParser].
 * - Voz: Wake-word configurável ("Ok Honda"), SpeechRecognizer e conversa contínua via TTS pt-BR.
 */
class GeminiCarAgent(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val host: CarAgentHostBridge,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val webKnowledgeTools: WebKnowledgeTools = WebKnowledgeTools(),
    private val rawGeminiPostOverride: ((model: String, body: JSONObject, apiKey: String) -> Result<String>)? = null,
    private val internetCheckOverride: (() -> Boolean)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val memoryStore: CopilotMemoryStore = CopilotMemoryStore(
        runCatching { appContext.filesDir?.let { File(it, "copilot_memory.json") } }.getOrNull()
    )
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
    private var fallbackOneShotAsk = false
    private var restartWakeJob: Job? = null
    private var liveSession: GeminiLiveSession? = null
    @Volatile
    private var liveSessionActive: Boolean = false

    private sealed interface PendingPhoneAction {
        val createdAtMs: Long
        data class SendWhatsApp(
            val recipient: String,
            val displayName: String,
            val text: String,
            override val createdAtMs: Long = System.currentTimeMillis()
        ) : PendingPhoneAction

        data class SendWhatsAppAudio(
            val recipient: String,
            val displayName: String,
            override val createdAtMs: Long = System.currentTimeMillis()
        ) : PendingPhoneAction

        data class ReplyMessage(
            val messageId: String?,
            val sender: String,
            val text: String,
            override val createdAtMs: Long = System.currentTimeMillis()
        ) : PendingPhoneAction
    }

    @Volatile
    private var pendingPhoneAction: PendingPhoneAction? = null

    private fun isLiveBusy(): Boolean = liveSessionActive || liveSession?.isActive() == true

    init {
        scope.launch(mainDispatcher) {
            runCatching { initTts() }
        }
        refreshConnectivityBadge()
    }

    private fun initTts() {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val res = tts?.setLanguage(Locale.forLanguageTag("pt-BR"))
                ttsReady = res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED
                tts?.setSpeechRate(1.06f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _state.update { it.copy(isSpeaking = true) }
                    }

                    override fun onDone(utteranceId: String?) {
                        scope.launch(mainDispatcher) {
                            val wasAskOneShot = fallbackOneShotAsk
                            fallbackOneShotAsk = false
                            _state.update { cur ->
                                cur.copy(
                                    isSpeaking = false,
                                    audioLevel = 0f,
                                    copilotMode = if (wasAskOneShot && cur.copilotMode == "ask") "idle" else cur.copilotMode,
                                    livePhase = if (wasAskOneShot && cur.copilotMode == "ask") "idle" else cur.livePhase
                                )
                            }
                            val cfg = host.getConfig()
                            if (!wasAskOneShot && continuousSession && cfg.continuousConversationEnabled && isForeground && !isLiveBusy()) {
                                delay(280L)
                                startDirectVoiceCommand(fromContinuous = true)
                            } else if (cfg.wakeWordEnabled && isForeground && !isLiveBusy()) {
                                scheduleWakeWordRestart(400L)
                            }
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        val wasAskOneShot = fallbackOneShotAsk
                        fallbackOneShotAsk = false
                        _state.update { cur ->
                            cur.copy(
                                isSpeaking = false,
                                audioLevel = 0f,
                                copilotMode = if (wasAskOneShot && cur.copilotMode == "ask") "idle" else cur.copilotMode,
                                livePhase = if (wasAskOneShot && cur.copilotMode == "ask") "idle" else cur.livePhase
                            )
                        }
                    }
                })
            }
        }
    }

    fun setAppForeground(inForeground: Boolean) {
        isForeground = inForeground
        val cfg = host.getConfig()
        if (inForeground && cfg.wakeWordEnabled && cfg.voiceAssistantMode != VoiceAssistantMode.SYSTEM_ASSISTANT && !isLiveBusy()) {
            scheduleWakeWordRestart(700L)
        } else if (!inForeground && !activeCommandMode && !isLiveBusy()) {
            stopRecognizerNow()
        }
    }

    fun onConfigUpdated(config: HeadUnitConfig) {
        refreshConnectivityBadge()
        if (config.wakeWordEnabled &&
            isForeground &&
            config.voiceAssistantMode != VoiceAssistantMode.SYSTEM_ASSISTANT &&
            !activeCommandMode &&
            !isLiveBusy()
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
                online && cfg.geminiApiKey.isNotBlank() -> "Gemini Live (${cfg.geminiLiveModel})"
                online && cfg.geminiApiKey.isBlank() -> "Offline (Configure Chave Gemini)"
                else -> "Offline (Sem Internet)"
            }
        }
        _state.update { it.copy(isOnlineAvailable = online, engineBadge = badge) }
    }

    /** Inicia modo Live contínuo ("Conversar") via Gemini Live API, com fallback automático. */
    fun startLiveConversation() {
        startLiveOrFallback(LiveSessionMode.LIVE)
    }

    /** Inicia modo rápido one-shot ("Peça ao Gemini"), com fallback automático. */
    fun startAskVoiceOneShot() {
        startLiveOrFallback(LiveSessionMode.ASK)
    }

    /** Encerra a sessão Live ou qualquer escuta/fala ativa. */
    fun stopLiveConversation() {
        val active = liveSession
        liveSession = null
        liveSessionActive = false
        if (active != null && active.isActive()) {
            active.stop("Conversa encerrada.")
        } else {
            stopListening()
        }
    }

    /** Limpa o histórico salvo e resumos de conversas ("esqueça as conversas"). */
    fun clearConversationMemory() {
        memoryStore.clear()
        conversationHistory.clear()
        _state.update {
            it.copy(
                userText = "",
                replyText = "",
                statusText = "Memória das conversas apagada."
            )
        }
    }

    private fun startLiveOrFallback(mode: LiveSessionMode) {
        val cfg = host.getConfig()
        if (cfg.voiceAssistantMode == VoiceAssistantMode.SYSTEM_ASSISTANT) {
            launchSystemVoiceAssistant()
            return
        }
        if (!hasAudioPermission()) {
            _state.update { it.copy(statusText = "Permita o acesso ao microfone nas Configurações.") }
            return
        }

        // Encerra qualquer sessão/escuta anterior antes de abrir a nova
        liveSession?.let { prev ->
            liveSession = null
            if (prev.isActive()) prev.stop("Reiniciando sessão…")
        }
        liveSessionActive = true
        restartWakeJob?.cancel()
        wakeLoopActive = false
        activeCommandMode = false
        continuousSession = false
        fallbackOneShotAsk = false

        refreshConnectivityBadge()
        val canUseLive = cfg.voiceAssistantMode == VoiceAssistantMode.HYBRID_GEMINI &&
            _state.value.isOnlineAvailable &&
            cfg.geminiApiKey.isNotBlank()

        if (!canUseLive) {
            liveSessionActive = false
            scope.launch(mainDispatcher) {
                stopRecognizerNow()
                runCatching { tts?.stop() }
            }
            startFallbackVoice(mode, "Usando modo local/texto…")
            return
        }

        Log.i("GeminiCarAgent", "Pausando wake word / SpeechRecognizer antes de iniciar sessão Live (${mode.wireValue})")
        _state.update {
            it.copy(
                userText = "",
                replyText = "",
                isWakeWordListening = false,
                copilotMode = mode.wireValue,
                livePhase = "connecting",
                statusText = if (mode == LiveSessionMode.LIVE) {
                    "Iniciando conversa ao vivo…"
                } else {
                    "Ouvindo… pode falar."
                }
            )
        }

        val session = GeminiLiveSession(
            appContext = appContext,
            scope = scope,
            apiKey = cfg.geminiApiKey,
            model = cfg.geminiLiveModel,
            mode = mode,
            systemInstructionProvider = {
                GeminiLiveTools.buildSystemInstruction(
                    host = host,
                    recentSummaries = memoryStore.recentSummaries(limit = 5),
                    oneShot = (mode == LiveSessionMode.ASK)
                )
            },
            functionDeclarations = GeminiLiveTools.buildFunctionDeclarations(),
            callbacks = object : GeminiLiveCallbacks {
                override fun onSnapshot(snapshot: LiveSessionSnapshot) {
                    val ended = snapshot.phase == LiveSessionPhase.ENDED
                    _state.update { cur ->
                        cur.copy(
                            isListening = snapshot.phase == LiveSessionPhase.LISTENING || snapshot.phase == LiveSessionPhase.CONNECTING,
                            isThinking = snapshot.phase == LiveSessionPhase.THINKING,
                            isSpeaking = snapshot.phase == LiveSessionPhase.SPEAKING,
                            isWakeWordListening = false,
                            copilotMode = if (ended) "idle" else snapshot.mode.wireValue,
                            livePhase = snapshot.phase.wireValue,
                            audioLevel = if (ended) 0f else snapshot.level,
                            userText = if (snapshot.userText.isNotBlank()) snapshot.userText else cur.userText,
                            replyText = if (snapshot.replyText.isNotBlank()) snapshot.replyText else cur.replyText,
                            statusText = snapshot.statusText
                        )
                    }
                }

                override suspend fun onToolCall(call: LiveFunctionCall): LiveToolExecution {
                    return GeminiLiveTools.executeTool(
                        call = call,
                        host = host,
                        onClearMemory = { clearConversationMemory() },
                        onRecordVoiceNote = { recipient ->
                            val active = liveSession
                            if (active != null && active.isActive()) {
                                active.withPausedAudioInput {
                                    host.recordAndSendWhatsAppVoiceViaPhone(recipient)
                                }
                            } else {
                                host.recordAndSendWhatsAppVoiceViaPhone(recipient)
                            }
                        }
                    )
                }

                override fun onFallbackRequired(reason: String, mode: LiveSessionMode) {
                    Log.w("GeminiCarAgent", "Gemini Live indisponível ($reason); alternando para fluxo texto/offline.")
                    liveSession = null
                    liveSessionActive = false
                    startFallbackVoice(mode, "Gemini Live indisponível · usando fallback")
                }

                override fun onSessionEnded(
                    mode: LiveSessionMode,
                    turns: List<CopilotMemoryTurn>,
                    startedAtMs: Long,
                    endedAtMs: Long,
                    reason: String
                ) {
                    liveSession = null
                    liveSessionActive = false
                    if (turns.isNotEmpty()) {
                        memoryStore.recordSession(
                            mode = mode.wireValue,
                            turns = turns,
                            startedAtMs = startedAtMs,
                            endedAtMs = endedAtMs
                        )
                        val lastU = turns.lastOrNull { it.role == "user" }?.text.orEmpty()
                        val lastM = turns.lastOrNull { it.role != "user" }?.text.orEmpty()
                        if (lastU.isNotBlank()) {
                            appendMessage(AgentChatMessage(isUser = true, text = lastU))
                        }
                        if (lastM.isNotBlank()) {
                            appendMessage(
                                AgentChatMessage(
                                    isUser = false,
                                    text = lastM,
                                    isOfflineEngine = false,
                                    suppressReplyPopup = true
                                )
                            )
                        }
                    }
                    _state.update { cur ->
                        cur.copy(
                            isListening = false,
                            isThinking = false,
                            isSpeaking = false,
                            copilotMode = "idle",
                            livePhase = "idle",
                            audioLevel = 0f,
                            statusText = reason
                        )
                    }
                    if (host.getConfig().wakeWordEnabled && isForeground && !isLiveBusy()) {
                        Log.i("GeminiCarAgent", "Sessão Live encerrada; retomando wake word.")
                        scheduleWakeWordRestart(800L)
                    }
                }
            }
        )
        liveSession = session
        _state.update {
            it.copy(
                copilotMode = mode.wireValue,
                livePhase = LiveSessionPhase.CONNECTING.wireValue,
                audioLevel = 0f,
                userText = "",
                replyText = "",
                isListening = true,
                isWakeWordListening = false,
                isThinking = false,
                isSpeaking = false,
                statusText = if (mode == LiveSessionMode.ASK) "Conectando ao Gemini…" else "Iniciando conversa ao vivo…"
            )
        }
        // Garante que o SpeechRecognizer/wake word seja destruído na Main Thread ANTES do AudioRecord Live abrir
        scope.launch(mainDispatcher) {
            stopRecognizerNow()
            runCatching { tts?.stop() }
            if (liveSession === session && liveSessionActive) {
                session.start()
            }
        }
    }

    private fun startFallbackVoice(mode: LiveSessionMode, statusHint: String) {
        fallbackOneShotAsk = (mode == LiveSessionMode.ASK)
        _state.update {
            it.copy(
                copilotMode = mode.wireValue,
                livePhase = "listening",
                audioLevel = 0f,
                userText = "",
                replyText = "",
                statusText = statusHint
            )
        }
        startDirectVoiceCommand(
            fromContinuous = false,
            overrideContinuous = (mode == LiveSessionMode.LIVE),
            copilotModeTag = mode.wireValue
        )
    }

    fun triggerVoiceCommand() {
        val cfg = host.getConfig()
        if (cfg.voiceAssistantMode == VoiceAssistantMode.SYSTEM_ASSISTANT) {
            launchSystemVoiceAssistant()
            return
        }
        if (liveSession?.isActive() == true) {
            stopLiveConversation()
            return
        }
        if (_state.value.isListening && activeCommandMode) {
            stopListening()
            return
        }
        startLiveConversation()
    }

    fun stopListening() {
        liveSession?.let { active ->
            liveSession = null
            liveSessionActive = false
            if (active.isActive()) active.stop("Escuta pausada.")
        }
        liveSessionActive = false
        continuousSession = false
        fallbackOneShotAsk = false
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
                    isThinking = false,
                    isSpeaking = false,
                    copilotMode = "idle",
                    livePhase = "idle",
                    audioLevel = 0f,
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
        val phoneReply = host.readPhoneMessages()
        if (!phoneReply.contains("Nenhuma mensagem", ignoreCase = true) || host.getWhatsAppMessages().isEmpty()) {
            appendMessage(AgentChatMessage(isUser = false, text = phoneReply, actionBadge = "Mensagens", isOfflineEngine = true))
            speak(phoneReply)
            return
        }
        val latest = host.getWhatsAppMessages().first()
        host.markMessagesRead()
        val reply = "Mensagem de ${latest.sender} no ${latest.appLabel}: ${latest.message}"
        appendMessage(AgentChatMessage(isUser = false, text = reply, actionBadge = "WhatsApp", isOfflineEngine = true))
        speak(reply)
    }

    private fun startDirectVoiceCommand(
        fromContinuous: Boolean,
        overrideContinuous: Boolean? = null,
        copilotModeTag: String? = null
    ) {
        if (!hasAudioPermission()) {
            _state.update { it.copy(copilotMode = "idle", livePhase = "idle", statusText = "Permita o acesso ao microfone nas Configurações.") }
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            _state.update { it.copy(copilotMode = "idle", livePhase = "idle", statusText = "Reconhecimento de voz indisponível neste aparelho.") }
            return
        }

        restartWakeJob?.cancel()
        scope.launch(mainDispatcher) {
            runCatching { tts?.stop() }
            wakeLoopActive = false
            activeCommandMode = true
            continuousSession = overrideContinuous ?: host.getConfig().continuousConversationEnabled
            _state.update { cur ->
                cur.copy(
                    isListening = true,
                    isWakeWordListening = false,
                    isSpeaking = false,
                    copilotMode = copilotModeTag ?: cur.copilotMode,
                    livePhase = "listening",
                    partialTranscript = "",
                    statusText = if (fromContinuous) "Continuando conversa... pode falar." else "Ouvindo você..."
                )
            }
            startRecognizerInternal(isWakeWordMode = false)
        }
    }

    private fun scheduleWakeWordRestart(delayMs: Long) {
        restartWakeJob?.cancel()
        if (isLiveBusy()) return
        restartWakeJob = scope.launch(mainDispatcher) {
            delay(delayMs)
            val cfg = host.getConfig()
            if (!cfg.wakeWordEnabled || !isForeground || activeCommandMode || _state.value.isSpeaking || isLiveBusy()) return@launch
            if (cfg.voiceAssistantMode == VoiceAssistantMode.SYSTEM_ASSISTANT) return@launch
            if (!hasAudioPermission() || !SpeechRecognizer.isRecognitionAvailable(appContext)) return@launch

            // O reconhecedor do Google pega foco de áudio exclusivo a cada ciclo e pausa o Spotify.
            // Enquanto houver música tocando, o "ok honda" fica em espera (o atalho do microfone continua valendo).
            val musicPlaying = host.getMediaState().isPlaying ||
                (appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.isMusicActive == true
            if (musicPlaying) {
                stopRecognizerNow()
                _state.update {
                    it.copy(
                        isWakeWordListening = false,
                        statusText = "\"${cfg.wakeWordPhrase}\" em espera durante a música · segure o microfone"
                    )
                }
                scheduleWakeWordRestart(3_000L)
                return@launch
            }

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
        if (isLiveBusy()) {
            stopRecognizerNow()
            return
        }
        stopRecognizerNow()
        val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
        speechRecognizer = recognizer

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) {
                if (!isWakeWordMode && !isLiveBusy()) {
                    val norm = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                    _state.update { it.copy(audioLevel = norm) }
                }
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                if (isLiveBusy()) return
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (text.isBlank()) return

                if (!isWakeWordMode) {
                    _state.update { it.copy(partialTranscript = text, userText = text) }
                } else {
                    val wakePhrase = host.getConfig().wakeWordPhrase
                    if (containsWakeWord(text, wakePhrase)) {
                        _state.update { it.copy(partialTranscript = text) }
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                if (isLiveBusy()) return
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
                    } else if (cfg.wakeWordEnabled && isForeground && !activeCommandMode && !isLiveBusy()) {
                        scheduleWakeWordRestart(450L)
                    }
                    return
                }

                activeCommandMode = false
                _state.update { it.copy(isListening = false, audioLevel = 0f, partialTranscript = "") }
                if (spoken.isBlank()) {
                    continuousSession = false
                    fallbackOneShotAsk = false
                    _state.update { it.copy(copilotMode = "idle", livePhase = "idle") }
                    if (host.getConfig().wakeWordEnabled && !isLiveBusy()) scheduleWakeWordRestart(500L)
                } else if (isStopPhrase(spoken)) {
                    continuousSession = false
                    fallbackOneShotAsk = false
                    val bye = "Certo, qualquer coisa é só chamar."
                    appendMessage(AgentChatMessage(isUser = true, text = spoken))
                    appendMessage(AgentChatMessage(isUser = false, text = bye, isOfflineEngine = true))
                    _state.update { it.copy(copilotMode = "idle", livePhase = "idle", userText = spoken, replyText = bye) }
                    speak(bye)
                } else {
                    _state.update { it.copy(userText = spoken) }
                    sendUserPrompt(spoken, speakReply = true)
                }
            }

            override fun onError(error: Int) {
                if (isLiveBusy()) return
                if (isWakeWordMode) {
                    if (host.getConfig().wakeWordEnabled && isForeground && !activeCommandMode && !isLiveBusy()) {
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
                fallbackOneShotAsk = false
                _state.update {
                    it.copy(
                        isListening = false,
                        copilotMode = "idle",
                        livePhase = "idle",
                        audioLevel = 0f,
                        partialTranscript = "",
                        statusText = if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                            "Não ouvi nenhuma frase."
                        } else {
                            "Erro no microfone ($error)."
                        }
                    )
                }
                if (host.getConfig().wakeWordEnabled && isForeground && !isLiveBusy()) {
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
            // Confirmação pendente de envio no WhatsApp / resposta de mensagem ("sim" / "não")
            val activePending = pendingPhoneAction?.takeIf { System.currentTimeMillis() - it.createdAtMs <= 90_000L }
            if (activePending != null) {
                val normAns = CopilotIntentParser.normalize(userText)
                if (isAffirmativeConfirmation(normAns)) {
                    pendingPhoneAction = null
                    _state.update { it.copy(isThinking = true, userText = userText, statusText = "Executando no celular…") }
                    val res = executePendingPhoneAction(activePending)
                    _state.update {
                        it.copy(
                            isThinking = false,
                            userText = userText,
                            replyText = res.replyText,
                            statusText = res.actionBadge ?: "Pronto."
                        )
                    }
                    appendMessage(AgentChatMessage(isUser = false, text = res.replyText, actionBadge = res.actionBadge, isOfflineEngine = true))
                    if (speakReply && ttsReady) speak(res.replyText)
                    return@launch
                } else if (isNegativeConfirmation(normAns)) {
                    pendingPhoneAction = null
                    val cancelMsg = "Tudo bem, envio cancelado."
                    _state.update { it.copy(isThinking = false, userText = userText, replyText = cancelMsg, statusText = "Cancelado") }
                    appendMessage(AgentChatMessage(isUser = false, text = cancelMsg, isOfflineEngine = true))
                    if (speakReply && ttsReady) speak(cancelMsg)
                    return@launch
                } else {
                    // Nova intenção diferente: limpa pendência antiga
                    pendingPhoneAction = null
                }
            } else {
                pendingPhoneAction = null
            }

            val cfg = host.getConfig()
            val online = _state.value.isOnlineAvailable
            val parsedIntent = CopilotIntentParser.parse(userText, host.getFavoritePlaces())
            if (parsedIntent is CopilotIntent.ForgetConversations) {
                clearConversationMemory()
                val reply = "Pronto, apaguei o histórico das nossas conversas."
                _state.update {
                    it.copy(
                        isThinking = false,
                        userText = userText,
                        replyText = reply,
                        statusText = "Memória limpa"
                    )
                }
                appendMessage(AgentChatMessage(isUser = false, text = reply, actionBadge = "Memória limpa", isOfflineEngine = true))
                if (speakReply) speak(reply)
                return@launch
            }

            val canUseGemini = cfg.voiceAssistantMode == VoiceAssistantMode.HYBRID_GEMINI &&
                online &&
                cfg.geminiApiKey.isNotBlank() &&
                parsedIntent !is CopilotIntent.AskGeminiApp

            _state.update {
                it.copy(
                    isThinking = true,
                    livePhase = if (it.copilotMode != "idle") "thinking" else it.livePhase,
                    userText = userText,
                    statusText = if (canUseGemini) "Consultando Gemini..." else "Processando localmente..."
                )
            }

            val result = if (canUseGemini) {
                callGeminiWithFallback(userText, cfg)
            } else {
                runLocalOfflineAgent(userText)
            }

            if (result.actionBadge != "Memória limpa") {
                memoryStore.recordSingleExchange(
                    userText = userText,
                    replyText = result.replyText,
                    mode = if (_state.value.copilotMode == "ask") "ask" else "text"
                )
            }

            _state.update {
                it.copy(
                    isThinking = false,
                    livePhase = if (it.copilotMode != "idle") "speaking" else it.livePhase,
                    userText = userText,
                    replyText = result.replyText,
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
            if (speakReply && ttsReady) {
                speak(result.replyText)
            } else if (fallbackOneShotAsk) {
                fallbackOneShotAsk = false
                delay(2_800L)
                _state.update { cur ->
                    if (cur.copilotMode == "ask") cur.copy(copilotMode = "idle", livePhase = "idle") else cur
                }
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
    ): AgentReplyResult = withContext(ioDispatcher) {
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

        fun buildRequestBody(
            contentsArr: JSONArray,
            toolsArr: JSONArray?
        ) = JSONObject().apply {
            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            put("contents", contentsArr)
            if (toolsArr != null) put("tools", toolsArr)
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.4)
                put("maxOutputTokens", 450)
            })
        }

        // Passo 1: tentar google_search nativo (funciona se o usuário ativar faturamento)
        val searchTools = JSONArray().put(JSONObject().put("google_search", JSONObject()))
        val searchResult = requestGeminiText(
            cfg.geminiModel,
            buildRequestBody(contents, searchTools),
            cfg.geminiApiKey
        )
        if (searchResult.isSuccess) {
            return@withContext recordAndParseGeminiReply(searchResult.getOrThrow(), userText)
        }
        Log.w(
            "GeminiCarAgent",
            "Gemini ${cfg.geminiModel} (google_search) falhou: ${searchResult.exceptionOrNull()?.message?.take(160)}"
        )

        // Passo 2: ferramentas próprias via function calling (funciona no plano gratuito)
        val ownTools = buildCombinedFunctionTools()
        var toolModel = cfg.geminiModel
        val toolLoopReply = GeminiToolLoop.runSuspend(
            contentsJson = contents.toString(),
            maxRounds = 3,
            timeoutMs = 12_000L,
            callModel = { currentContentsJson ->
                val arr = JSONArray(currentContentsJson)
                var res = performGeminiPost(toolModel, buildRequestBody(arr, ownTools), cfg.geminiApiKey)
                if (res.isFailure && toolModel != DEFAULT_GEMINI_MODEL) {
                    val errMsg = res.exceptionOrNull()?.message.orEmpty()
                    if (errMsg.contains("HTTP 404") || errMsg.contains("HTTP 400")) {
                        toolModel = DEFAULT_GEMINI_MODEL
                        res = performGeminiPost(toolModel, buildRequestBody(arr, ownTools), cfg.geminiApiKey)
                    }
                }
                res.getOrElse { err ->
                    Log.w("GeminiCarAgent", "GeminiToolLoop ($toolModel) falhou: ${err.message?.take(160)}")
                    throw err
                }
            },
            executeTool = { name, argsJson ->
                executeKnowledgeTool(name, argsJson)
            }
        )
        if (!toolLoopReply.isNullOrBlank()) {
            return@withContext recordAndParseGeminiReply(toolLoopReply, userText)
        }

        // Passo 3: se for comando local conhecido, resolve offline; senão tenta Gemini sem tools antes do fallback local
        val localIntent = CopilotIntentParser.parse(userText, host.getFavoritePlaces())
        if (localIntent !is CopilotIntent.Unknown) {
            return@withContext runLocalOfflineAgent(userText)
        }

        val plainAttempts = listOf(cfg.geminiModel, DEFAULT_GEMINI_MODEL).distinct()
        for (model in plainAttempts) {
            val plain = requestGeminiText(model, buildRequestBody(contents, null), cfg.geminiApiKey)
            if (plain.isSuccess) {
                return@withContext recordAndParseGeminiReply(plain.getOrThrow(), userText)
            }
            Log.w("GeminiCarAgent", "Gemini $model (sem tools) falhou: ${plain.exceptionOrNull()?.message?.take(160)}")
        }

        runLocalOfflineAgent(userText)
    }

    private suspend fun executeKnowledgeTool(name: String, argsJson: String): String {
        val args = CopilotJson.parseObjOrNull(argsJson)
        return when (name) {
            "buscar_noticias" -> {
                val q = args?.optStrictString("consulta").orEmpty()
                webKnowledgeTools.buscarNoticias(q)
            }
            "buscar_wikipedia" -> {
                val q = args?.optStrictString("consulta").orEmpty()
                webKnowledgeTools.buscarWikipedia(q)
            }
            "clima" -> {
                val city = args?.optStrictString("cidade")?.trim().orEmpty()
                if (city.isEmpty()) {
                    host.getWeatherSummary() ?: webKnowledgeTools.buscarClima(null)
                } else {
                    webKnowledgeTools.buscarClima(city)
                }
            }
            "buscar_lugar" -> {
                val q = args?.optStrictString("consulta").orEmpty()
                host.searchPlacesForTool(q)
            }
            "dados_do_carro" -> host.buildCarReport()
            "minha_agenda" -> {
                val dia = args?.optStrictString("dia")?.trim()?.lowercase().orEmpty()
                val offset = if (dia.contains("amanh")) 1 else 0
                host.getCalendarAgendaSummary(offset)
            }
            "enviar_whatsapp",
            "enviar_audio_whatsapp",
            "ligar",
            "ligar_whatsapp",
            "atender",
            "desligar",
            "ler_mensagens",
            "responder_mensagem" -> {
                val liveCall = LiveFunctionCall(id = "txt-tool", name = name, argsJson = argsJson)
                // Se for pedido sem confirmação, também arma pendingPhoneAction para o fluxo texto aceitar "sim"
                if (name == "enviar_whatsapp" && args?.optBoolean("confirmado") != true) {
                    val target = args?.optStrictString("contato")?.trim()?.takeIf { it.isNotBlank() }
                        ?: args?.optStrictString("numero")?.trim().orEmpty()
                    val texto = args?.optStrictString("texto")?.trim().orEmpty()
                    if (target.isNotBlank() && texto.isNotBlank()) {
                        val resolved = host.resolvePhoneRecipient(target)
                        if (resolved.ok) {
                            pendingPhoneAction = PendingPhoneAction.SendWhatsApp(
                                recipient = resolved.normalizedNumber,
                                displayName = resolved.displayName,
                                text = texto
                            )
                        }
                    }
                } else if (name == "enviar_audio_whatsapp" && args?.optBoolean("confirmado") != true) {
                    val target = args?.optStrictString("contato")?.trim()?.takeIf { it.isNotBlank() }
                        ?: args?.optStrictString("numero")?.trim().orEmpty()
                    if (target.isNotBlank()) {
                        val resolved = host.resolvePhoneRecipient(target)
                        if (resolved.ok) {
                            pendingPhoneAction = PendingPhoneAction.SendWhatsAppAudio(
                                recipient = resolved.normalizedNumber,
                                displayName = resolved.displayName
                            )
                        }
                    }
                } else if (name == "responder_mensagem" && args?.optBoolean("confirmado") != true) {
                    val msg = args?.optStrictString("mensagem")?.trim().orEmpty()
                    val msgId = args?.optStrictString("id")?.trim()?.takeIf { it.isNotBlank() }
                    val latest = host.getLatestPhoneMessageSummary()
                    if (msg.isNotBlank() && latest != null) {
                        pendingPhoneAction = PendingPhoneAction.ReplyMessage(
                            messageId = msgId ?: latest.first,
                            sender = latest.second,
                            text = msg
                        )
                    }
                }
                GeminiLiveTools.executeTool(
                    call = liveCall,
                    host = host,
                    onClearMemory = { clearConversationMemory() }
                ).result
            }
            else -> "Ferramenta desconhecida: $name"
        }
    }

    private fun buildCombinedFunctionTools(): JSONArray {
        val baseDecls = JSONArray(WebKnowledgeTools.FUNCTION_DECLARATIONS_ARRAY_JSON)
        val liveDecls = GeminiLiveTools.buildFunctionDeclarations()
        val phoneNames = setOf(
            "enviar_whatsapp",
            "enviar_audio_whatsapp",
            "ligar",
            "ligar_whatsapp",
            "atender",
            "desligar",
            "ler_mensagens",
            "responder_mensagem"
        )
        for (i in 0 until liveDecls.length()) {
            val fn = liveDecls.optJSONObject(i) ?: continue
            if (fn.optString("name") in phoneNames) {
                baseDecls.put(fn)
            }
        }
        return JSONArray().put(JSONObject().put("functionDeclarations", baseDecls))
    }

    private fun performGeminiPost(model: String, body: JSONObject, apiKey: String): Result<String> {
        rawGeminiPostOverride?.let { return it(model, body, apiKey) }
        return runCatching {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 7_000
                readTimeout = 12_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("x-goog-api-key", apiKey.trim())
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
            raw
        }
    }

    private fun requestGeminiText(model: String, body: JSONObject, apiKey: String): Result<String> {
        return performGeminiPost(model, body, apiKey).mapCatching { raw ->
            val json = JSONObject(raw)
            val candidates = json.optJSONArray("candidates") ?: throw IllegalStateException("Sem resposta do Gemini")
            val first = candidates.getJSONObject(0)
            val parts = first.getJSONObject("content").getJSONArray("parts")
            (0 until parts.length())
                .mapNotNull { parts.getJSONObject(it).optString("text").takeIf { t -> t.isNotBlank() } }
                .joinToString("")
                .trim()
                .ifBlank { throw IllegalStateException("Resposta vazia do Gemini") }
        }
    }

    private suspend fun recordAndParseGeminiReply(rawText: String, userText: String): AgentReplyResult {
        val parsed = parseAndExecuteGeminiAction(rawText)
        conversationHistory.add("user" to userText)
        conversationHistory.add("model" to parsed.replyText)
        if (conversationHistory.size > 16) {
            repeat(2) { conversationHistory.removeAt(0) }
        }
        return parsed
    }

    private fun buildSystemContextPrompt(): String {
        val hu = host.getHeadUnitState()
        val snap = hu.obdSnapshot
        val derived = snap.derived
        val media = host.getMediaState()
        val msgs = host.getWhatsAppMessages()
        val latestMsg = msgs.firstOrNull()
        val favorites = host.getFavoritePlaces()
        val favLines = if (favorites.isEmpty()) {
            "Nenhum favorito salvo ainda."
        } else {
            favorites.joinToString("\n") { f ->
                val voiceHint = f.voice.takeIf { it.isNotBlank() }?.let { " (voz: \"$it\")" }.orEmpty()
                val addrHint = f.address.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()
                "- ${f.name}$voiceHint$addrHint"
            }
        }

        val recentMemories = memoryStore.recentSummaries(limit = 5)
        val memoryText = if (recentMemories.isEmpty()) {
            "Nenhuma conversa anterior salva."
        } else {
            recentMemories.joinToString("\n") { "- $it" }
        }

        return """
            Você é o HondaDeck Copilot, assistente automotivo em tempo real de um Honda Civic com tablet no painel.
            Motorista em Anápolis-GO, Brasil.
            Responda sempre em Português do Brasil, de forma clara, natural e curta (ideal para ser lida em voz alta enquanto dirige) e sempre confirme ações executadas.

            MEMÓRIA DE CONVERSAS RECENTES:
            $memoryText

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

            ROTA NO APP:
            - ${host.getRouteSummary() ?: "nenhuma rota ativa"}

            MANUTENÇÃO:
            - ${host.getMaintenanceSummary() ?: "sem itens cadastrados"}

            CLIMA NO LOCAL DO CARRO:
            - ${host.getWeatherSummary() ?: "sem dados no momento"}

            FAVORITOS DO MOTORISTA:
            $favLines

            Você tem acesso a ferramentas de internet (notícias, Wikipédia, clima, busca de lugares, dados do carro). Use-as quando precisar de fatos atuais ou externos.

            CELULAR (HONDADECK PONTE):
            - ${host.getPhoneSummary()}

            WHATSAPP RECEBIDO:
            - ${if (latestMsg != null) "De ${latestMsg.sender}: \"${latestMsg.message}\"" else "Nenhuma mensagem pendente"}
            
            REGRA OBRIGATÓRIA DE CONFIRMAÇÃO: antes de enviar WhatsApp de texto, áudio ou responder mensagem, SEMPRE confirme com o motorista destinatário e texto (ex.: "Mando para Ana: 'tô chegando'?") e só execute após ele dizer "sim".

            Se o usuário pedir para executar uma ação no carro ou app, responda normalmente e inclua na última linha:
            ACTION_JSON:{"action":"NOME_DA_ACAO","arg":"VALOR_OPCIONAL"}
            Ações válidas:
            - MUSIC_PLAY | MUSIC_PAUSE | MUSIC_NEXT | MUSIC_PREV | OPEN_SPOTIFY
            - MUSIC_SEARCH (arg = nome da música ou artista para tocar no painel)
            - OPEN_MAP | MAP_SEARCH (arg = tipo de lugar ou nome para buscar no mapa do app)
            - NAVIGATE (arg = destino, favorito ou endereço; traça rota dentro do app) | STOP_ROUTE (encerra rota ativa)
            - READ_WHATSAPP | READ_MESSAGES | REPLY_WHATSAPP | REPLY_MESSAGE (arg = texto exato da resposta)
            - SEND_WHATSAPP (arg = JSON {"contato":"Ana","texto":"tô chegando"}) | SEND_WHATSAPP_AUDIO (arg = contato ou número)
            - CALL_PHONE (arg = contato ou número) | CALL_WHATSAPP (arg = contato ou número) | ANSWER_CALL | END_CALL
            - SWITCH_TAB (arg = HOME, NAVIGATION, MUSIC, AGENT_VOICE, OBD_SCANNER, SETTINGS)
            - OPEN_CAMERA | CLOSE_CAMERA
            - REPORT (relatório completo do veículo) | WEATHER (resumo do clima)
            - VOLUME (arg = "up", "down", "mute", "unmute" ou número de 0 a 100)
            - ASK_GEMINI_APP (arg = pergunta para abrir no app Gemini do tablet)
            - FORGET_CONVERSATIONS (apaga histórico/memória de conversas salvas)
            - LOG_FUEL (arg = JSON em texto: {"litros":40,"preco":6.29,"km":123456,"cheio":true}; omita campos que o motorista não disse; "cheio" só true se ele disse que completou)

            DIÁRIO DE ABASTECIMENTO (consumo real medido tanque cheio a tanque cheio):
            - ${host.getFuelLogSummary() ?: "sem registros ainda"}
            Quando o motorista contar um abastecimento, use LOG_FUEL e confirme em uma frase.
        """.trimIndent()
    }

    private suspend fun parseAndExecuteGeminiAction(rawText: String): AgentReplyResult {
        val (cleanText, action, arg) = ActionJsonParser.parse(rawText)
        if (action == null) {
            return AgentReplyResult(replyText = cleanText, isOffline = false)
        }
        if (!ActionJsonParser.isValidAction(action)) {
            Log.w("GeminiCarAgent", "Ação ACTION_JSON inválida ignorada: $action")
            return AgentReplyResult(replyText = cleanText.ifBlank { "Pronto." }, isOffline = false)
        }
        val badge = executeHostAction(action, arg.orEmpty())
        return AgentReplyResult(
            replyText = cleanText.ifBlank { badge ?: "Pronto." },
            actionBadge = badge,
            isOffline = false
        )
    }

    private suspend fun executeHostAction(action: String, arg: String): String? {
        return when (action.uppercase()) {
            "MUSIC_PLAY", "MUSIC_PAUSE", "MUSIC_NEXT", "MUSIC_PREV", "OPEN_SPOTIFY" ->
                host.controlMusic(action.uppercase(), null)
            "MUSIC_SEARCH" -> host.searchAndPlayMusic(arg)
            "OPEN_MAP", "OPEN_MAPS" -> host.navigateTo(null, null, false)
            "MAP_SEARCH" -> host.searchPlacesOnMap(arg)
            "NAVIGATE" -> {
                val fav = matchFavoriteByArg(arg)
                host.navigateTo(arg.takeIf { it.isNotBlank() }, fav, false)
            }
            "STOP_ROUTE" -> host.stopRoute()
            "FORGET_CONVERSATIONS" -> {
                clearConversationMemory()
                "Memória limpa"
            }
            "READ_WHATSAPP", "READ_MESSAGES" -> {
                host.readPhoneMessages()
                "Mensagens lidas"
            }
            "REPLY_WHATSAPP", "REPLY_MESSAGE" -> if (arg.isNotBlank()) host.replyPhoneMessage(null, arg) else null
            "SEND_WHATSAPP" -> {
                val o = CopilotJson.parseObjOrNull(arg)
                val target = o?.optStrictString("contato")?.takeIf { it.isNotBlank() }
                    ?: o?.optStrictString("numero").orEmpty()
                val text = o?.optStrictString("texto").orEmpty()
                if (target.isNotBlank() && text.isNotBlank()) {
                    host.sendWhatsAppTextViaPhone(target, text)
                } else {
                    null
                }
            }
            "SEND_WHATSAPP_AUDIO" -> if (arg.isNotBlank()) host.recordAndSendWhatsAppVoiceViaPhone(arg) else null
            "CALL_PHONE" -> if (arg.isNotBlank()) host.dialPhoneViaPhone(arg) else null
            "CALL_WHATSAPP" -> if (arg.isNotBlank()) host.callWhatsAppViaPhone(arg) else null
            "ANSWER_CALL" -> host.answerPhoneCall()
            "END_CALL" -> host.endPhoneCall()
            "SWITCH_TAB" -> {
                val tab = MainTab.entries.firstOrNull { it.name == arg.uppercase() } ?: MainTab.HOME
                host.switchTab(tab)
            }
            "OPEN_CAMERA" -> host.openReverseCamera()
            "CLOSE_CAMERA" -> host.closeReverseCamera()
            "REPORT" -> {
                host.buildCarReport()
                "Relatório do Civic"
            }
            "WEATHER" -> "Clima"
            "VOLUME" -> {
                val normArg = CopilotIntentParser.normalize(arg)
                when {
                    normArg == "up" || normArg == "aumentar" -> host.adjustVolume(VolumeDirection.UP, null)
                    normArg == "down" || normArg == "abaixar" || normArg == "diminuir" ->
                        host.adjustVolume(VolumeDirection.DOWN, null)
                    normArg == "mute" || normArg == "mudo" -> host.adjustVolume(VolumeDirection.MUTE, 0)
                    normArg == "unmute" || normArg == "desmutar" -> host.adjustVolume(VolumeDirection.UNMUTE, null)
                    else -> {
                        val pct = normArg.toIntOrNull()?.coerceIn(0, 100)
                        if (pct != null) host.adjustVolume(VolumeDirection.SET, pct) else null
                    }
                }
            }
            "ASK_GEMINI_APP" -> openExternalGeminiApp(arg)
            "LOG_FUEL" -> {
                val o = CopilotJson.parseObjOrNull(arg) ?: return null
                val liters = o.optDouble("litros")?.takeIf { it > 0 } ?: return null
                host.logRefuelFromAgent(
                    liters = liters,
                    pricePerLiter = o.optDouble("preco")?.takeIf { it > 0 },
                    odometerKm = o.optDouble("km")?.takeIf { it > 0 },
                    fullTank = o.optBoolean("cheio") ?: false
                )
            }
            else -> {
                Log.w("GeminiCarAgent", "Ação não tratada: $action")
                null
            }
        }
    }

    private fun matchFavoriteByArg(arg: String): FavoriteRef? {
        val norm = CopilotIntentParser.normalize(arg)
        if (norm.isEmpty()) return null
        return host.getFavoritePlaces().firstOrNull {
            CopilotIntentParser.normalize(it.name) == norm || CopilotIntentParser.normalize(it.id) == norm
        }
    }

    private suspend fun runLocalOfflineAgent(rawInput: String): AgentReplyResult {
        val favorites = host.getFavoritePlaces()
        val intent = CopilotIntentParser.parse(rawInput, favorites)
        val hu = host.getHeadUnitState()
        val snap = hu.obdSnapshot
        val derived = snap.derived

        return when (intent) {
            is CopilotIntent.AskGeminiApp -> {
                val badge = openExternalGeminiApp(intent.question)
                val msg = if (intent.question.isNotBlank()) {
                    "Abrindo o Gemini do tablet com a sua pergunta."
                } else {
                    "Abrindo o aplicativo Gemini do tablet."
                }
                AgentReplyResult(msg, badge, isOffline = true)
            }

            is CopilotIntent.SendWhatsApp -> {
                val resolved = host.resolvePhoneRecipient(intent.recipient)
                if (!resolved.ok) {
                    AgentReplyResult(
                        resolved.errorMessage ?: "Não encontrei \"${intent.recipient}\" nos contatos.",
                        "WhatsApp",
                        isOffline = true
                    )
                } else {
                    pendingPhoneAction = PendingPhoneAction.SendWhatsApp(
                        recipient = resolved.normalizedNumber,
                        displayName = resolved.displayName,
                        text = intent.text
                    )
                    AgentReplyResult(
                        "Mando para ${resolved.displayName}: '${intent.text}'? Diga sim para confirmar.",
                        "Confirmar envio",
                        isOffline = true
                    )
                }
            }

            is CopilotIntent.SendWhatsAppAudio -> {
                val resolved = host.resolvePhoneRecipient(intent.recipient)
                if (!resolved.ok) {
                    AgentReplyResult(
                        resolved.errorMessage ?: "Não encontrei \"${intent.recipient}\" nos contatos.",
                        "Áudio WhatsApp",
                        isOffline = true
                    )
                } else {
                    pendingPhoneAction = PendingPhoneAction.SendWhatsAppAudio(
                        recipient = resolved.normalizedNumber,
                        displayName = resolved.displayName
                    )
                    AgentReplyResult(
                        "Gravo e mando uma mensagem de voz no WhatsApp para ${resolved.displayName}? Diga sim para começar.",
                        "Confirmar áudio",
                        isOffline = true
                    )
                }
            }

            is CopilotIntent.CallPhone -> {
                val res = host.dialPhoneViaPhone(intent.recipient)
                AgentReplyResult(res, "Ligação", isOffline = true)
            }

            is CopilotIntent.CallWhatsApp -> {
                val res = host.callWhatsAppViaPhone(intent.recipient)
                AgentReplyResult(res, "Ligação WhatsApp", isOffline = true)
            }

            CopilotIntent.AnswerCall -> {
                val res = host.answerPhoneCall()
                AgentReplyResult(res, "Chamada", isOffline = true)
            }

            CopilotIntent.EndCall -> {
                val res = host.endPhoneCall()
                AgentReplyResult(res, "Chamada", isOffline = true)
            }

            is CopilotIntent.ReplyWhatsApp -> {
                val latestPhone = if (host.isPhoneConnected()) host.getLatestPhoneMessageSummary() else null
                if (latestPhone != null) {
                    pendingPhoneAction = PendingPhoneAction.ReplyMessage(
                        messageId = latestPhone.first,
                        sender = latestPhone.second,
                        text = intent.text
                    )
                    AgentReplyResult(
                        "Respondo para ${latestPhone.second}: '${intent.text}'? Diga sim para enviar.",
                        "Confirmar resposta",
                        isOffline = true
                    )
                } else {
                    val res = host.replyLatestWhatsApp(intent.text)
                    AgentReplyResult(res, "WhatsApp", isOffline = true)
                }
            }

            CopilotIntent.ReadWhatsApp -> {
                val res = host.readPhoneMessages()
                AgentReplyResult(res, "Mensagens", isOffline = true)
            }

            is CopilotIntent.LogFuel -> {
                val parsed = FuelLogCalculator.parseUtterance(intent.raw)
                val liters = parsed?.resolvedLiters
                if (parsed == null || liters == null) {
                    AgentReplyResult(
                        "Para registrar, diga os litros e, se puder, o preço e o hodômetro. Exemplo: abasteci 40 litros a 6,29 com 123 mil 456 quilômetros, completei.",
                        "Abastecimento",
                        isOffline = true
                    )
                } else {
                    val msg = host.logRefuelFromAgent(liters, parsed.resolvedPrice, parsed.odometerKm, parsed.fullTank)
                    AgentReplyResult(msg, "Abastecimento", isOffline = true)
                }
            }

            CopilotIntent.FuelSummary -> {
                AgentReplyResult(
                    host.getFuelLogSummary()?.let { "Pelo seu diário: $it." }
                        ?: "Ainda não há abastecimentos registrados. Diga, por exemplo: abasteci 40 litros a 6,29, completei.",
                    "Abastecimento",
                    isOffline = true
                )
            }

            is CopilotIntent.Navigate -> {
                val badge = host.navigateTo(intent.dest, intent.favorite, intent.forceExternalMaps)
                val label = intent.favorite?.name ?: intent.dest
                val msg = if (intent.forceExternalMaps) {
                    "Abrindo rota para $label no mapa externo."
                } else {
                    "Iniciando rota para $label no painel."
                }
                AgentReplyResult(msg, badge, isOffline = true)
            }

            is CopilotIntent.MapSearch -> {
                val badge = host.searchPlacesOnMap(intent.query)
                AgentReplyResult("Buscando ${intent.query} no mapa.", badge, isOffline = true)
            }

            CopilotIntent.OpenMap -> {
                val badge = host.navigateTo(null, null, false)
                AgentReplyResult("Abrindo o mapa.", badge, isOffline = true)
            }

            CopilotIntent.StopRoute -> {
                val badge = host.stopRoute()
                AgentReplyResult("Rota encerrada.", badge, isOffline = true)
            }

            CopilotIntent.ForgetConversations -> {
                clearConversationMemory()
                AgentReplyResult("Pronto, apaguei o histórico das nossas conversas.", "Memória limpa", isOffline = true)
            }

            CopilotIntent.ExpandMapFull -> {
                val badge = host.openMapFullScreen()
                AgentReplyResult("Abrindo o mapa em tela cheia.", badge, isOffline = true)
            }

            is CopilotIntent.SetMapTheme -> {
                val badge = host.setMapTheme(intent.theme)
                val label = when (intent.theme) {
                    "LIGHT" -> "claro"
                    "DARK" -> "escuro"
                    else -> "automático"
                }
                AgentReplyResult("Mapa alterado para o modo $label.", badge, isOffline = true)
            }

            CopilotIntent.OpenSpotify -> {
                val badge = host.controlMusic("OPEN_SPOTIFY")
                AgentReplyResult("Abrindo o Spotify no painel.", badge, isOffline = true)
            }

            is CopilotIntent.MusicSearch -> {
                val badge = host.searchAndPlayMusic(intent.query)
                AgentReplyResult("Buscando ${intent.query} no Spotify.", badge, isOffline = true)
            }

            CopilotIntent.MusicNext -> {
                val badge = host.controlMusic("MUSIC_NEXT")
                AgentReplyResult("Passando para a próxima música.", badge, isOffline = true)
            }

            CopilotIntent.MusicPrev -> {
                val badge = host.controlMusic("MUSIC_PREV")
                AgentReplyResult("Voltando para a música anterior.", badge, isOffline = true)
            }

            CopilotIntent.MusicPause -> {
                val badge = host.controlMusic("MUSIC_PAUSE")
                AgentReplyResult("Música pausada.", badge, isOffline = true)
            }

            CopilotIntent.MusicPlay -> {
                val badge = host.controlMusic("MUSIC_PLAY")
                AgentReplyResult("Tocando música agora.", badge, isOffline = true)
            }

            is CopilotIntent.Volume -> {
                val badge = host.adjustVolume(intent.direction, intent.value)
                val msg = when (intent.direction) {
                    VolumeDirection.UP -> "Aumentando o volume."
                    VolumeDirection.DOWN -> "Abaixando o volume."
                    VolumeDirection.SET -> "Volume ajustado para ${intent.value ?: 60}%."
                    VolumeDirection.MUTE -> "Áudio silenciado."
                    VolumeDirection.UNMUTE -> "Som reativado."
                }
                AgentReplyResult(msg, badge, isOffline = true)
            }

            CopilotIntent.OpenCamera -> {
                val badge = host.openReverseCamera()
                AgentReplyResult("Abrindo a câmera de ré.", badge, isOffline = true)
            }

            CopilotIntent.CloseCamera -> {
                val badge = host.closeReverseCamera()
                AgentReplyResult("Fechando a câmera de ré.", badge, isOffline = true)
            }

            CopilotIntent.Report -> {
                AgentReplyResult(host.buildCarReport(), "Relatório do Civic", isOffline = true)
            }

            CopilotIntent.Weather -> {
                val summary = host.getWeatherSummary()
                AgentReplyResult(
                    summary ?: "Ainda não tenho o clima. Preciso de GPS e internet do celular.",
                    "Clima",
                    isOffline = true
                )
            }

            is CopilotIntent.CalendarAgenda -> {
                AgentReplyResult(
                    host.getCalendarAgendaSummary(intent.dayOffset),
                    "Agenda",
                    isOffline = true
                )
            }

            is CopilotIntent.Telemetry -> when (intent.kind) {
                TelemetryKind.COOLANT_TEMP -> {
                    val t = snap.coolantTempC?.toInt()
                    val msg = if (t != null) {
                        "O motor está a $t graus Celsius, status ${derived.thermalStatusLabel}."
                    } else {
                        "Ainda não recebi a temperatura do motor pelo OBD2."
                    }
                    AgentReplyResult(msg, "OBD2 Offline", isOffline = true)
                }
                TelemetryKind.BATTERY_VOLTAGE -> {
                    val v = snap.batteryVoltageV
                    val msg = if (v != null) {
                        "A bateria está em ${"%.1f".format(v)} volts."
                    } else {
                        "Sem leitura de voltagem no momento."
                    }
                    AgentReplyResult(msg, "OBD2 Offline", isOffline = true)
                }
                TelemetryKind.FUEL_CONSUMPTION -> {
                    val avg = snap.averageKmPerL?.let { "%.1f quilômetros por litro".format(it) } ?: "ainda calculando"
                    val range = derived.estimatedRangeKm?.let { "${it.toInt()} quilômetros" } ?: "indisponível"
                    AgentReplyResult(
                        "Seu consumo médio é $avg, com cerca de ${derived.virtualTankPercent.toInt()}% no tanque e autonomia estimada de $range.",
                        "OBD2 Offline",
                        isOffline = true
                    )
                }
                TelemetryKind.DIAGNOSTICS_DTC -> {
                    val msg = if (hu.dtcCodes.isEmpty()) {
                        "Nenhum código de falha registrado no Civic."
                    } else {
                        "Encontrei ${hu.dtcCodes.size} falhas: ${hu.dtcCodes.joinToString { it.code }}."
                    }
                    AgentReplyResult(msg, "Diagnóstico OBD2", isOffline = true)
                }
                TelemetryKind.SPEED_RPM -> {
                    val spd = derived.correctedSpeedKmh?.toInt() ?: snap.speedKmh?.toInt() ?: 0
                    val rpm = snap.rpm?.toInt() ?: 0
                    AgentReplyResult(
                        "Velocidade atual de $spd quilômetros por hora, motor a $rpm giros.",
                        "OBD2 Offline",
                        isOffline = true
                    )
                }
                TelemetryKind.ENGINE_SUMMARY -> {
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
            }

            is CopilotIntent.SwitchTab -> {
                val mainTab = when (intent.tab) {
                    CopilotTab.HOME -> MainTab.HOME
                    CopilotTab.NAVIGATION -> MainTab.NAVIGATION
                    CopilotTab.MUSIC -> MainTab.MUSIC
                    CopilotTab.AGENT_VOICE -> MainTab.AGENT_VOICE
                    CopilotTab.OBD_SCANNER -> MainTab.OBD_SCANNER
                    CopilotTab.SETTINGS -> MainTab.SETTINGS
                }
                val badge = host.switchTab(mainTab)
                val label = when (intent.tab) {
                    CopilotTab.SETTINGS -> "Abrindo configurações."
                    CopilotTab.OBD_SCANNER -> "Abrindo scanner OBD2."
                    CopilotTab.AGENT_VOICE -> "Abrindo copiloto."
                    CopilotTab.NAVIGATION -> "Abrindo o mapa."
                    CopilotTab.MUSIC -> "Abrindo músicas."
                    CopilotTab.HOME -> "Voltando para o painel principal."
                }
                AgentReplyResult(label, badge, isOffline = true)
            }

            CopilotIntent.Unknown -> {
                val reason = if (!_state.value.isOnlineAvailable) {
                    "Estou sem internet do celular no momento."
                } else {
                    "Não consegui responder online agora."
                }
                AgentReplyResult(
                    "$reason No modo local você pode pedir temperatura do motor, voltagem, consumo, relatório do carro, músicas do Spotify, rotas, busca de lugares ou WhatsApp.",
                    "Modo Offline",
                    isOffline = true
                )
            }
        }
    }

    private suspend fun executePendingPhoneAction(action: PendingPhoneAction): AgentReplyResult =
        when (action) {
            is PendingPhoneAction.SendWhatsApp -> {
                val res = host.sendWhatsAppTextViaPhone(action.recipient, action.text)
                AgentReplyResult(res, "WhatsApp", isOffline = true)
            }
            is PendingPhoneAction.SendWhatsAppAudio -> {
                val res = host.recordAndSendWhatsAppVoiceViaPhone(action.recipient)
                AgentReplyResult(res, "Áudio WhatsApp", isOffline = true)
            }
            is PendingPhoneAction.ReplyMessage -> {
                val res = host.replyPhoneMessage(action.messageId, action.text)
                AgentReplyResult(res, "Mensagem respondida", isOffline = true)
            }
        }

    private fun isAffirmativeConfirmation(norm: String): Boolean {
        val clean = norm.trim()
        return clean in AFFIRMATIVE_ANSWERS ||
            clean.startsWith("sim ") ||
            clean.startsWith("pode mandar") ||
            clean.startsWith("pode enviar")
    }

    private fun isNegativeConfirmation(norm: String): Boolean {
        val clean = norm.trim()
        return clean in NEGATIVE_ANSWERS ||
            clean.startsWith("nao ") ||
            clean.startsWith("cancela")
    }

    private fun openExternalGeminiApp(question: String): String {
        val bardSend = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage("com.google.android.apps.bard")
            putExtra(Intent.EXTRA_TEXT, question)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launchedBard = runCatching { appContext.startActivity(bardSend) }.isSuccess
        if (launchedBard) return "Gemini"

        val googleSearch = Intent(Intent.ACTION_WEB_SEARCH).apply {
            setPackage("com.google.android.googlequicksearchbox")
            putExtra(SearchManager.QUERY, question)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launchedGoogle = runCatching { appContext.startActivity(googleSearch) }.isSuccess
        if (launchedGoogle) return "Pesquisa Google"

        val genericSearch = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(SearchManager.QUERY, question)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { appContext.startActivity(genericSearch) }
        return "Gemini"
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
        internetCheckOverride?.let { return it() }
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
        val nfd = Normalizer.normalize(text.lowercase(Locale.forLanguageTag("pt-BR")), Normalizer.Form.NFD)
        return nfd.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "").trim()
    }

    fun release() {
        stopListening()
        runCatching { tts?.shutdown() }
    }

    private companion object {
        val AFFIRMATIVE_ANSWERS = setOf(
            "sim", "s", "pode", "pode mandar", "pode enviar", "manda", "mande",
            "envia", "envie", "enviar", "confirma", "confirmo", "ok", "beleza",
            "claro", "isso", "manda sim", "pode sim", "confirma sim"
        )
        val NEGATIVE_ANSWERS = setOf(
            "nao", "n", "cancela", "cancelar", "deixa", "deixa pra la",
            "esquece", "nao manda", "nao enviar", "para", "parar"
        )
    }
}

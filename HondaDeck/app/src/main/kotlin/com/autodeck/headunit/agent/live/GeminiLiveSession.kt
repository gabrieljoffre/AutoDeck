package com.autodeck.headunit.agent.live

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class LiveSessionMode(val wireValue: String) {
    LIVE("live"),
    ASK("ask")
}

enum class LiveSessionPhase(val wireValue: String) {
    CONNECTING("connecting"),
    LISTENING("listening"),
    THINKING("thinking"),
    SPEAKING("speaking"),
    ENDED("idle")
}

data class LiveSessionSnapshot(
    val mode: LiveSessionMode,
    val phase: LiveSessionPhase,
    val level: Float = 0f,
    val userText: String = "",
    val replyText: String = "",
    val statusText: String = "",
    val lastActionBadge: String? = null
)

interface GeminiLiveCallbacks {
    fun onSnapshot(snapshot: LiveSessionSnapshot)
    suspend fun onToolCall(call: LiveFunctionCall): LiveToolExecution
    fun onFallbackRequired(reason: String, mode: LiveSessionMode)
    fun onSessionEnded(
        mode: LiveSessionMode,
        turns: List<CopilotMemoryTurn>,
        startedAtMs: Long,
        endedAtMs: Long,
        reason: String
    )
}

/**
 * Sessão de voz em tempo real com a Gemini Live API (WebSocket BidiGenerateContent):
 * - Captura PCM 16 kHz mono com VOICE_COMMUNICATION + AEC/NS na mesma sessão de áudio.
 * - Supressão de eco no envio: pausa o envio do microfone enquanto o modelo fala e por ~450 ms depois;
 *   interrupção ocorre pelo botão manual ou por voz muito alta sustentada acima de limiar alto.
 * - Reprodução PCM 24 kHz mono via thread dedicada de escrita + jitter buffer (~200 ms) + AudioTrack >= 4x mínimo,
 *   sem nunca chamar stop/flush entre pedaços do mesmo turno.
 * - Foco de áudio com ducking (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`) pedido uma única vez por turno
 *   de fala e liberado somente após o drain do `turnComplete` (ou em `interrupted` real / fim da sessão).
 * - Modo "Peça ao Gemini" (one-shot): grava imediatamente, executa ação, responde inteiro e encerra sozinho.
 */
class GeminiLiveSession(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val apiKey: String,
    private val model: String,
    private val mode: LiveSessionMode,
    private val systemInstructionProvider: () -> String,
    private val functionDeclarations: JSONArray,
    private val callbacks: GeminiLiveCallbacks,
    private val voiceName: String = GeminiLiveProtocol.DEFAULT_VOICE_NAME,
    private val okHttpClient: OkHttpClient = defaultHttpClient(),
    private val initialPrompt: String? = null
) {
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val running = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val setupDone = AtomicBoolean(false)
    private val toolBusy = AtomicBoolean(false)
    private val turnDonePendingDrain = AtomicBoolean(false)
    private val sawAnyUserSpeech = AtomicBoolean(false)
    private val sawAnyModelOutput = AtomicBoolean(false)

    /** Verdadeiro quando o modelo já respondeu à última fala do usuário (zera a cada nova transcrição de entrada). */
    private val modelRepliedToLatestUser = AtomicBoolean(false)

    /** Token da janela de seguimento do "Peça ao Gemini": qualquer nova fala do usuário invalida o fechamento agendado. */
    private val followUpToken = AtomicInteger(0)

    private val startedAtMs = System.currentTimeMillis()
    private val lastActivityMs = AtomicLong(startedAtMs)
    private val lastLevelEmitMs = AtomicLong(0L)
    private val reconnectCount = AtomicInteger(0)
    private val languageRetryDone = AtomicBoolean(false)

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var echoCanceler: AcousticEchoCanceler? = null

    @Volatile
    private var noiseSuppressor: NoiseSuppressor? = null

    @Volatile
    private var audioTrack: AudioTrack? = null

    private val audioFocusLock = Any()

    @Volatile
    private var audioFocusRequest: AudioFocusRequest? = null

    @Volatile
    private var currentPhase: LiveSessionPhase = LiveSessionPhase.CONNECTING

    @Volatile
    private var currentLevel: Float = 0f

    @Volatile
    private var lastActionBadge: String? = null

    private val transcriptLock = Any()
    private val currentUserBuf = StringBuilder()
    private val currentModelBuf = StringBuilder()
    private val recordedTurns = mutableListOf<CopilotMemoryTurn>()

    private var recordJob: Job? = null
    private var playbackThread: Thread? = null
    private var watchdogJob: Job? = null
    private var reconnectJob: Job? = null

    private val playbackController: LiveAudioPlaybackController = LiveAudioPlaybackController(
        listener = object : LiveAudioStateListener {
            override fun onRequestAudioFocus() {
                requestDuckingAudioFocus()
            }

            override fun onAbandonAudioFocus() {
                abandonAudioFocus()
            }

            override fun onSpeakingStateChanged(speaking: Boolean, reason: String) {
                Log.i(TAG, "Estado de fala do modelo -> speaking=$speaking (motivo=$reason)")
                if (stopped.get()) return
                if (speaking) {
                    updateSnapshot(
                        phase = LiveSessionPhase.SPEAKING,
                        status = "Gemini respondendo…",
                        force = true
                    )
                } else if (!toolBusy.get() && !turnDonePendingDrain.get()) {
                    updateSnapshot(
                        phase = LiveSessionPhase.LISTENING,
                        level = 0f,
                        status = if (mode == LiveSessionMode.ASK) {
                            if (sawAnyModelOutput.get()) "Pode perguntar mais… (ouvindo)" else "Ouvindo…"
                        } else {
                            "Ao vivo · pode falar."
                        },
                        force = true
                    )
                }
            }

            override fun onTurnFinished(wasInterrupted: Boolean) {
                if (!wasInterrupted && mode == LiveSessionMode.ASK && turnDonePendingDrain.get() && !stopped.get()) {
                    val token = followUpToken.incrementAndGet()
                    scope.launch(Dispatchers.IO) {
                        // Janela de seguimento: após a resposta o microfone continua aberto por ~6 s para uma
                        // pergunta de continuação (conversa natural); sem fala, fecha sozinho em silêncio.
                        delay(ASK_POST_REPLY_GRACE_MS + ASK_FOLLOWUP_WINDOW_MS)
                        if (!stopped.get() &&
                            followUpToken.get() == token &&
                            turnDonePendingDrain.get() &&
                            !toolBusy.get() &&
                            !playbackController.isModelSpeaking
                        ) {
                            Log.i(TAG, "Janela de seguimento sem fala; fechando o Peça ao Gemini.")
                            stop("Pronto.")
                        }
                    }
                }
            }
        }
    )

    fun isActive(): Boolean = running.get() && !stopped.get()

    /** Última fala do usuário transcrita pela Live (usada para o fallback texto quando a Live falha sem responder). */
    fun pendingUserTranscript(): String = synchronized(transcriptLock) { currentUserBuf.toString().trim() }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        stopped.set(false)
        lastActivityMs.set(System.currentTimeMillis())

        Log.i(TAG, "Iniciando sessão Gemini Live (mode=${mode.wireValue}, model=$model)")
        updateSnapshot(
            phase = LiveSessionPhase.CONNECTING,
            status = if (mode == LiveSessionMode.ASK) "Conectando ao Gemini…" else "Iniciando conversa ao vivo…"
        )
        openWebSocket(includeLanguageCode = true)
        startWatchdog()
    }

    /**
     * Pausa temporariamente o microfone da sessão Live para que outra captura (ex.: gravação de
     * mensagem de voz do WhatsApp) possa usar o microfone sem conflito, retomando ao terminar.
     */
    suspend fun <T> withPausedAudioInput(block: suspend () -> T): T {
        val wasRunning = isActive()
        recordJob?.cancel()
        recordJob = null
        releaseAudioInput()
        delay(120L)
        return try {
            block()
        } finally {
            if (wasRunning && isActive() && setupDone.get()) {
                startAudioCapture()
            }
        }
    }

    /**
     * Interrompe manualmente a fala atual do modelo (ex.: botão de interromper), mantendo a sessão viva.
     */
    fun interruptPlayback(reason: String = "manual_button") {
        if (stopped.get()) return
        val dropped = playbackController.onInterrupted(System.currentTimeMillis(), reason)
        turnDonePendingDrain.set(false)
        lastActivityMs.set(System.currentTimeMillis())
        Log.i(TAG, "Interrupção manual da fala ($reason); chunks descartados=$dropped")
    }

    fun stop(reason: String = "Conversa encerrada.") {
        if (!stopped.compareAndSet(false, true)) return
        running.set(false)
        setupDone.set(false)
        turnDonePendingDrain.set(false)
        reconnectJob?.cancel()
        watchdogJob?.cancel()
        recordJob?.cancel()

        playbackController.close()
        playbackThread?.interrupt()
        playbackThread = null

        runCatching { webSocket?.close(1000, "client_stop") }
        webSocket = null

        releaseAudioInput()
        releaseAudioOutput()
        abandonAudioFocus()

        flushCurrentTurnLocked()
        val turnsSnapshot = synchronized(transcriptLock) { recordedTurns.toList() }
        val endedAt = System.currentTimeMillis()
        Log.i(TAG, "Sessão Gemini Live encerrada (mode=${mode.wireValue}, turnos=${turnsSnapshot.size}, motivo=$reason)")
        updateSnapshot(phase = LiveSessionPhase.ENDED, level = 0f, status = reason, force = true)
        callbacks.onSessionEnded(
            mode = mode,
            turns = turnsSnapshot,
            startedAtMs = startedAtMs,
            endedAtMs = endedAt,
            reason = reason
        )
    }

    private fun openWebSocket(includeLanguageCode: Boolean) {
        if (stopped.get()) return
        val url = GeminiLiveProtocol.buildWebSocketUrl(apiKey)
        val req = Request.Builder().url(url).build()

        val ws = okHttpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (stopped.get()) {
                    webSocket.close(1000, "already_stopped")
                    return
                }
                val setupMsg = GeminiLiveProtocol.buildSetupMessage(
                    model = model,
                    systemInstruction = systemInstructionProvider(),
                    functionDeclarations = functionDeclarations,
                    voiceName = voiceName,
                    languageCode = if (includeLanguageCode) GeminiLiveProtocol.DEFAULT_LANGUAGE_CODE else null,
                    enableGoogleSearch = true
                )
                val sent = webSocket.send(setupMsg.toString())
                if (!sent) {
                    handleConnectionFailure(null, "Não foi possível enviar configuração inicial.")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (stopped.get()) return
                handleServerText(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (stopped.get()) return
                handleServerText(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (stopped.get()) return
                Log.w(TAG, "WebSocket fechado ($code): $reason")
                if (!setupDone.get() && code == 1007 && includeLanguageCode && languageRetryDone.compareAndSet(false, true)) {
                    // Alguns modelos native-audio rejeitam languageCode explícito no speechConfig
                    Log.i(TAG, "Repetindo setup Live sem languageCode explícito.")
                    openWebSocket(includeLanguageCode = false)
                    return
                }
                handleUnexpectedDisconnect(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (stopped.get()) return
                val httpCode = response?.code
                val msg = t.message ?: response?.message ?: "Falha de conexão WebSocket"
                Log.w(TAG, "WebSocket erro (HTTP $httpCode): $msg")
                handleConnectionFailure(httpCode, msg)
            }
        })
        webSocket = ws
    }

    private fun handleServerText(rawJson: String) {
        when (val event = GeminiLiveProtocol.parseServerMessage(rawJson)) {
            LiveServerEvent.SetupComplete -> {
                setupDone.set(true)
                reconnectCount.set(0)
                lastActivityMs.set(System.currentTimeMillis())
                Log.i(TAG, "SetupComplete recebido; iniciando pipeline de áudio (captura 16 kHz + playback 24 kHz)")
                startAudioPipelineIfNeeded()
                val promptToSend = initialPrompt?.trim()?.takeIf { it.isNotEmpty() }
                if (promptToSend != null) {
                    val sent = webSocket?.send(GeminiLiveProtocol.buildClientTextMessage(promptToSend).toString()) == true
                    Log.i(TAG, "clientContent inicial enviado (sent=$sent, chars=${promptToSend.length})")
                }
                updateSnapshot(
                    phase = LiveSessionPhase.LISTENING,
                    status = if (mode == LiveSessionMode.ASK) "Ouvindo… pode falar." else "Ao vivo · pode falar.",
                    force = true
                )
            }

            is LiveServerEvent.ServerContent -> handleServerContent(event)

            is LiveServerEvent.ToolCall -> handleToolCall(event.calls)

            is LiveServerEvent.ToolCallCancellation -> {
                Log.d(TAG, "ToolCall cancelada pelo servidor: ${event.ids}")
            }

            is LiveServerEvent.GoAway -> {
                Log.w(TAG, "Servidor enviou goAway (timeLeft=${event.timeLeft})")
                if (mode == LiveSessionMode.LIVE) {
                    scheduleReconnect("Renovando sessão ao vivo…")
                } else {
                    stop("Sessão encerrada pelo servidor.")
                }
            }

            is LiveServerEvent.Error -> {
                Log.w(TAG, "Erro Live API (${event.code}): ${event.message}")
                if (event.isQuotaOrRateLimit || !setupDone.get()) {
                    triggerFallback(event.message)
                } else {
                    handleUnexpectedDisconnect(event.code ?: 1011, event.message)
                }
            }

            is LiveServerEvent.Unknown -> Unit
        }
    }

    private fun handleServerContent(sc: LiveServerEvent.ServerContent) {
        val now = System.currentTimeMillis()

        if (sc.interrupted) {
            val dropped = playbackController.onInterrupted(now, "server_interrupted")
            turnDonePendingDrain.set(false)
            lastActivityMs.set(now)
            Log.w(TAG, "Servidor enviou interrupted=true (chunks descartados=$dropped)")
            updateSnapshot(
                phase = LiveSessionPhase.LISTENING,
                level = 0f,
                status = "Ouvindo você…",
                force = true
            )
        }

        if (!sc.inputTranscription.isNullOrBlank()) {
            sawAnyUserSpeech.set(true)
            modelRepliedToLatestUser.set(false)
            // nova fala do usuário (inclusive de seguimento): cancela o fechamento agendado
            followUpToken.incrementAndGet()
            turnDonePendingDrain.set(false)
            lastActivityMs.set(now)
            synchronized(transcriptLock) {
                if (currentModelBuf.isNotEmpty() && !playbackController.isModelSpeaking) {
                    flushCurrentTurnLocked()
                }
                appendTranscriptChunk(currentUserBuf, sc.inputTranscription)
            }
            updateSnapshot(
                phase = if (playbackController.isModelSpeaking) LiveSessionPhase.SPEAKING else LiveSessionPhase.LISTENING,
                status = if (mode == LiveSessionMode.ASK) "Ouvindo…" else "Ouvindo você…",
                force = true
            )
        }

        if (sc.audioChunks.isNotEmpty()) {
            sawAnyModelOutput.set(true)
            modelRepliedToLatestUser.set(true)
            lastActivityMs.set(now)
            for (chunk in sc.audioChunks) {
                playbackController.enqueueChunk(chunk)
            }
        }

        val outText = sc.outputTranscription ?: sc.textParts.joinToString("").takeIf { it.isNotBlank() }
        if (!outText.isNullOrBlank()) {
            sawAnyModelOutput.set(true)
            modelRepliedToLatestUser.set(true)
            lastActivityMs.set(now)
            synchronized(transcriptLock) {
                appendTranscriptChunk(currentModelBuf, outText)
            }
            updateSnapshot(
                phase = if (playbackController.isModelSpeaking) LiveSessionPhase.SPEAKING else currentPhase,
                status = "Gemini respondendo…",
                force = true
            )
        }

        if (sc.turnComplete) {
            lastActivityMs.set(now)
            Log.i(
                TAG,
                "turnComplete recebido (queuedChunks=${playbackController.queuedChunkCount}, state=${playbackController.state})"
            )
            synchronized(transcriptLock) {
                flushCurrentTurnLocked()
            }
            if (mode == LiveSessionMode.ASK && sawAnyModelOutput.get()) {
                turnDonePendingDrain.set(true)
            }
            playbackController.onTurnComplete(now)
        }
    }

    private fun handleToolCall(calls: List<LiveFunctionCall>) {
        if (calls.isEmpty()) return
        toolBusy.set(true)
        lastActivityMs.set(System.currentTimeMillis())
        updateSnapshot(
            phase = LiveSessionPhase.THINKING,
            status = "Executando ação no painel…",
            force = true
        )

        scope.launch(Dispatchers.IO) {
            val responses = mutableListOf<LiveToolResponse>()
            for (call in calls) {
                if (stopped.get()) return@launch
                val exec = runCatching { callbacks.onToolCall(call) }
                    .getOrElse { err ->
                        Log.w(TAG, "Erro ao executar tool ${call.name}: ${err.message}")
                        LiveToolExecution("Erro ao executar ${call.name}: ${err.message ?: "falha"}")
                    }
                if (!exec.actionBadge.isNullOrBlank()) {
                    lastActionBadge = exec.actionBadge
                }
                responses += LiveToolResponse(
                    id = call.id,
                    name = call.name,
                    result = exec.result,
                    success = true
                )
            }
            toolBusy.set(false)
            lastActivityMs.set(System.currentTimeMillis())
            if (!stopped.get()) {
                val payload = GeminiLiveProtocol.buildToolResponseMessage(responses).toString()
                webSocket?.send(payload)
            }
        }
    }

    private fun handleConnectionFailure(httpCode: Int?, reason: String) {
        if (stopped.get()) return
        if (GeminiLiveProtocol.isRateLimitOrQuotaError(httpCode, reason) || httpCode == 401 || httpCode == 403) {
            triggerFallback("Limite ou chave da API Live ($httpCode): $reason")
            return
        }
        if (!setupDone.get() && !sawAnyModelOutput.get()) {
            triggerFallback(reason)
            return
        }
        handleUnexpectedDisconnect(httpCode ?: 1006, reason)
    }

    private fun handleUnexpectedDisconnect(code: Int, reason: String) {
        if (stopped.get()) return
        if (mode == LiveSessionMode.LIVE && reconnectCount.get() < MAX_RECONNECT_ATTEMPTS) {
            scheduleReconnect("Conexão instável ($code), reconectando…")
        } else if (!sawAnyModelOutput.get()) {
            triggerFallback("Conexão Live encerrada ($code): $reason")
        } else {
            stop("Conexão encerrada.")
        }
    }

    private fun scheduleReconnect(statusMsg: String) {
        if (stopped.get()) return
        val attempt = reconnectCount.incrementAndGet()
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            stop("Não foi possível reconectar ao Gemini Live.")
            return
        }
        setupDone.set(false)
        playbackController.onInterrupted(System.currentTimeMillis(), "reconnect")
        runCatching { webSocket?.cancel() }
        webSocket = null
        updateSnapshot(phase = LiveSessionPhase.CONNECTING, level = 0f, status = statusMsg, force = true)
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            delay(if (attempt == 1) 1_000L else 2_500L)
            if (!stopped.get()) {
                openWebSocket(includeLanguageCode = !languageRetryDone.get())
            }
        }
    }

    private fun triggerFallback(reason: String) {
        if (!stopped.compareAndSet(false, true)) return
        running.set(false)
        setupDone.set(false)
        turnDonePendingDrain.set(false)
        reconnectJob?.cancel()
        watchdogJob?.cancel()
        recordJob?.cancel()
        playbackController.close()
        playbackThread?.interrupt()
        playbackThread = null
        runCatching { webSocket?.cancel() }
        webSocket = null
        releaseAudioInput()
        releaseAudioOutput()
        abandonAudioFocus()
        callbacks.onFallbackRequired(reason, mode)
    }

    private fun startAudioPipelineIfNeeded() {
        if (recordJob?.isActive != true) {
            startAudioCapture()
        }
        if (playbackThread?.isAlive != true) {
            startAudioPlayback()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCapture() {
        val sampleRate = GeminiLiveProtocol.INPUT_SAMPLE_RATE_HZ
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(INPUT_CHUNK_BYTES * 2)

        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf
            )
        }.getOrNull()

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record?.release() }
            triggerFallback("Não foi possível iniciar o microfone para o Gemini Live.")
            return
        }
        audioRecord = record

        val sessionId = record.audioSessionId
        val aecAvailable = AcousticEchoCanceler.isAvailable()
        if (aecAvailable) {
            echoCanceler = runCatching {
                AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            }.getOrNull()
        }
        val nsAvailable = NoiseSuppressor.isAvailable()
        if (nsAvailable) {
            noiseSuppressor = runCatching {
                NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }.getOrNull()
        }
        Log.i(
            TAG,
            "AudioRecord VOICE_COMMUNICATION iniciado (rate=$sampleRate, minBuf=$minBuf, sessionId=$sessionId, " +
                "AEC=${echoCanceler?.enabled == true}/avail=$aecAvailable, NS=${noiseSuppressor?.enabled == true}/avail=$nsAvailable)"
        )

        recordJob = scope.launch(Dispatchers.IO) {
            try {
                record.startRecording()
                val pcmBuffer = ByteArray(INPUT_CHUNK_BYTES)
                var echoGateLogged = false

                while (isActive && !stopped.get()) {
                    val read = record.read(pcmBuffer, 0, pcmBuffer.size)
                    if (read <= 0) {
                        delay(15L)
                        continue
                    }
                    val slice = if (read == pcmBuffer.size) pcmBuffer.copyOf() else pcmBuffer.copyOf(read)
                    val now = System.currentTimeMillis()

                    when (val decision = playbackController.evaluateMicFrame(slice, now)) {
                        is MicFrameDecision.SuppressEcho -> {
                            if (!echoGateLogged) {
                                echoGateLogged = true
                                Log.d(TAG, "Microfone pausado no envio para evitar eco (motivo=${decision.reason})")
                            }
                            // Não envia áudio do microfone enquanto o modelo fala + hangover (~450 ms)
                        }

                        is MicFrameDecision.LocalBargeIn -> {
                            echoGateLogged = false
                            val dropped = playbackController.onInterrupted(now, "local_loud_barge_in")
                            turnDonePendingDrain.set(false)
                            sawAnyUserSpeech.set(true)
                            lastActivityMs.set(now)
                            Log.i(
                                TAG,
                                "Barge-in por voz alta (rms=${"%.3f".format(decision.rms)}); chunks descartados=$dropped"
                            )
                            emitAudioLevel(decision.visualLevel)
                            if (setupDone.get()) {
                                val msg = GeminiLiveProtocol.buildRealtimeAudioMessage(slice, sampleRate).toString()
                                webSocket?.send(msg)
                            }
                        }

                        is MicFrameDecision.SendMic -> {
                            if (echoGateLogged) {
                                echoGateLogged = false
                                Log.d(TAG, "Envio do microfone reaberto após fim da fala + hangover")
                            }
                            if (decision.speechDetected) {
                                sawAnyUserSpeech.set(true)
                                lastActivityMs.set(now)
                            }
                            emitAudioLevel(decision.visualLevel)
                            if (setupDone.get()) {
                                val msg = GeminiLiveProtocol.buildRealtimeAudioMessage(slice, sampleRate).toString()
                                webSocket?.send(msg)
                            }
                        }
                    }
                }
            } catch (_: CancellationException) {
                // encerramento normal
            } catch (e: Exception) {
                Log.w(TAG, "Erro na captura de áudio Live: ${e.message}")
            } finally {
                releaseAudioInput()
            }
        }
    }

    private fun startAudioPlayback() {
        val sampleRate = GeminiLiveProtocol.OUTPUT_SAMPLE_RATE_HZ
        val sysMinBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = LiveAudioPlaybackController.computeAudioTrackBufferSize(sysMinBuf)

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull()

        if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track?.release() }
            Log.w(TAG, "AudioTrack 24 kHz não inicializado (sysMinBuf=$sysMinBuf, bufferSize=$bufferSize).")
            return
        }
        audioTrack = track
        Log.i(
            TAG,
            "AudioTrack 24 kHz criado (sysMinBuf=$sysMinBuf, bufferSize=$bufferSize >= 4x, " +
                "jitterTarget=${LiveAudioPlaybackController.DEFAULT_JITTER_BUFFER_BYTES}B / ~200ms)"
        )

        val worker = Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
            runPlaybackLoop(track, bufferSize)
        }, "GeminiLiveAudioOut").apply {
            isDaemon = true
            start()
        }
        playbackThread = worker
    }

    private fun runPlaybackLoop(track: AudioTrack, bufferSizeBytes: Int) {
        var writtenFramesInTurn = 0L
        var chunksInTurn = 0
        var flushCountInTurn = 0
        var turnStartedAtMs = 0L
        var turnStartHeadFrames = readPlaybackHeadFrames(track)
        var turnHasStarted = false

        try {
            while (!stopped.get()) {
                val action = playbackController.takeNextAction()
                when (action) {
                    PlaybackAction.Shutdown -> break

                    is PlaybackAction.Flush -> {
                        flushCountInTurn++
                        Log.i(TAG, "AudioTrack flush (motivo=${action.reason}, gen=${action.generation}, flushes=$flushCountInTurn)")
                        runCatching {
                            if (track.state == AudioTrack.STATE_INITIALIZED) {
                                track.pause()
                                track.flush()
                            }
                        }
                        writtenFramesInTurn = 0L
                        chunksInTurn = 0
                        turnHasStarted = false
                        turnStartedAtMs = 0L
                        turnStartHeadFrames = readPlaybackHeadFrames(track)
                    }

                    is PlaybackAction.WriteChunk -> {
                        if (action.generation != playbackController.generation) continue
                        lastActivityMs.set(System.currentTimeMillis())

                        if (action.startPlayback || !turnHasStarted) {
                            turnHasStarted = true
                            writtenFramesInTurn = 0L
                            chunksInTurn = 0
                            flushCountInTurn = 0
                            turnStartedAtMs = System.currentTimeMillis()
                            turnStartHeadFrames = readPlaybackHeadFrames(track)
                            runCatching {
                                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                    track.play()
                                }
                            }
                            Log.i(TAG, "AudioTrack iniciou reprodução contínua do turno (gen=${action.generation})")
                        }

                        chunksInTurn++
                        val outLevel = LiveAudioPlaybackController.rmsToVisualLevel(
                            LiveAudioPlaybackController.computePcm16Rms(action.pcm)
                        )
                        emitAudioLevel(outLevel)

                        val writtenBytes = writeAllBlocking(track, action.pcm, action.generation)
                        if (writtenBytes > 0) {
                            writtenFramesInTurn += writtenBytes / LiveAudioPlaybackController.BYTES_PER_SAMPLE
                        }
                    }

                    is PlaybackAction.DrainTurn -> {
                        // Aguarda o hardware tocar todos os frames já escritos no buffer sem dar flush/stop
                        waitForHardwareDrain(
                            track = track,
                            generation = action.generation,
                            turnStartHeadFrames = turnStartHeadFrames,
                            writtenFramesInTurn = writtenFramesInTurn,
                            maxDrainMs = ((bufferSizeBytes / LiveAudioPlaybackController.BYTES_PER_MS_24KHZ) + 400L)
                                .coerceIn(400L, 2_000L)
                        )
                        val completed = playbackController.completeTurnDrain(
                            generation = action.generation,
                            nowMs = System.currentTimeMillis()
                        )
                        if (completed) {
                            val underruns = runCatching { track.underrunCount }.getOrDefault(-1)
                            val elapsedMs = if (turnStartedAtMs > 0L) (System.currentTimeMillis() - turnStartedAtMs) else 0L
                            val audioDurationMs = (writtenFramesInTurn * 1000L) / GeminiLiveProtocol.OUTPUT_SAMPLE_RATE_HZ
                            // Pausa suavemente após o hardware drenar tudo (sem flush), pronto para pré-buffer do próximo turno
                            runCatching {
                                if (track.state == AudioTrack.STATE_INITIALIZED &&
                                    track.playState == AudioTrack.PLAYSTATE_PLAYING
                                ) {
                                    track.pause()
                                }
                            }
                            Log.i(
                                TAG,
                                "AudioTrack drenou turno completamente (gen=${action.generation}, chunks=$chunksInTurn, " +
                                    "frames=$writtenFramesInTurn, audioMs=$audioDurationMs, elapsedMs=$elapsedMs, " +
                                    "underruns=$underruns, flushes=$flushCountInTurn)"
                            )
                            writtenFramesInTurn = 0L
                            chunksInTurn = 0
                            flushCountInTurn = 0
                            turnHasStarted = false
                            turnStartedAtMs = 0L
                            turnStartHeadFrames = readPlaybackHeadFrames(track)
                        }
                    }
                }
            }
        } catch (_: InterruptedException) {
            // encerramento normal
        } catch (e: Exception) {
            Log.w(TAG, "Erro na thread de reprodução Live: ${e.message}")
        } finally {
            releaseAudioOutput()
        }
    }

    private fun writeAllBlocking(track: AudioTrack, pcm: ByteArray, expectedGen: Int): Int {
        var offset = 0
        while (offset < pcm.size && !stopped.get() && expectedGen == playbackController.generation) {
            val wrote = runCatching {
                track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
            }.getOrDefault(-1)
            if (wrote <= 0) break
            offset += wrote
        }
        return offset
    }

    private fun waitForHardwareDrain(
        track: AudioTrack,
        generation: Int,
        turnStartHeadFrames: Long,
        writtenFramesInTurn: Long,
        maxDrainMs: Long
    ) {
        if (writtenFramesInTurn <= 0L) return
        val targetHead = turnStartHeadFrames + writtenFramesInTurn
        val deadline = System.currentTimeMillis() + maxDrainMs

        while (!playbackController.shouldAbortDrain(generation)) {
            val now = System.currentTimeMillis()
            if (now >= deadline) break
            val head = readPlaybackHeadFrames(track)
            if (head >= targetHead) break
            val remainingFrames = (targetHead - head).coerceAtLeast(0L)
            val remainingMs = (remainingFrames * 1000L) / GeminiLiveProtocol.OUTPUT_SAMPLE_RATE_HZ
            val sleepMs = remainingMs.coerceIn(15L, 40L)
            Thread.sleep(sleepMs)
        }
    }

    private fun readPlaybackHeadFrames(track: AudioTrack): Long = runCatching {
        if (track.state == AudioTrack.STATE_INITIALIZED) {
            track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        } else {
            0L
        }
    }.getOrDefault(0L)

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch(Dispatchers.Default) {
            while (isActive && !stopped.get()) {
                delay(500L)
                val now = System.currentTimeMillis()
                val elapsed = now - startedAtMs
                val idleMs = now - lastActivityMs.get()

                val maxDuration = if (mode == LiveSessionMode.ASK) {
                    MAX_ONESHOT_DURATION_MS
                } else {
                    MAX_LIVE_SESSION_DURATION_MS
                }
                if (elapsed >= maxDuration) {
                    stop("Limite de duração da sessão atingido.")
                    return@launch
                }

                if (!setupDone.get() && elapsed >= SETUP_TIMEOUT_MS) {
                    triggerFallback("Tempo esgotado ao conectar no Gemini Live.")
                    return@launch
                }

                if (setupDone.get() && !playbackController.isModelSpeaking && !toolBusy.get()) {
                    if (mode == LiveSessionMode.LIVE && idleMs >= LIVE_SILENCE_TIMEOUT_MS) {
                        stop("Conversa encerrada após 20 s de silêncio.")
                        return@launch
                    }
                    if (mode == LiveSessionMode.ASK &&
                        !modelRepliedToLatestUser.get() &&
                        pendingUserTranscript().isNotBlank() &&
                        idleMs >= ASK_NO_REPLY_TIMEOUT_MS
                    ) {
                        // O usuário falou mas a Live não respondeu: cai para o fluxo texto com a frase transcrita.
                        triggerFallback("Gemini Live sem resposta à pergunta.")
                        return@launch
                    }
                    if (mode == LiveSessionMode.ASK && !sawAnyUserSpeech.get() && idleMs >= ASK_NO_SPEECH_TIMEOUT_MS) {
                        stop("Não ouvi nenhuma pergunta.")
                        return@launch
                    }
                    if (mode == LiveSessionMode.ASK && sawAnyUserSpeech.get() && idleMs >= ASK_IDLE_AFTER_SPEECH_MS) {
                        stop("Pronto.")
                        return@launch
                    }
                }
            }
        }
    }

    private fun requestDuckingAudioFocus() {
        val mgr = audioManager ?: return
        synchronized(audioFocusLock) {
            if (audioFocusRequest != null) return
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(false)
                .build()
            runCatching {
                val res = mgr.requestAudioFocus(req)
                if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    audioFocusRequest = req
                    Log.i(TAG, "Foco de áudio adquirido para o turno (AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)")
                } else {
                    Log.w(TAG, "Pedido de foco de áudio retornou $res")
                }
            }
        }
    }

    private fun abandonAudioFocus() {
        val mgr = audioManager ?: return
        val req = synchronized(audioFocusLock) {
            val r = audioFocusRequest
            audioFocusRequest = null
            r
        } ?: return
        runCatching {
            mgr.abandonAudioFocusRequest(req)
            Log.i(TAG, "Foco de áudio liberado ao fim do turno")
        }
    }

    private fun releaseAudioInput() {
        runCatching { echoCanceler?.release() }
        echoCanceler = null
        runCatching { noiseSuppressor?.release() }
        noiseSuppressor = null
        runCatching {
            audioRecord?.let { r ->
                if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop()
                r.release()
            }
        }
        audioRecord = null
    }

    private fun releaseAudioOutput() {
        val t = audioTrack
        audioTrack = null
        if (t == null) return
        runCatching {
            if (t.state == AudioTrack.STATE_INITIALIZED) {
                t.pause()
                t.flush()
                t.stop()
            }
            t.release()
        }
    }

    private fun emitAudioLevel(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        val smoothed = currentLevel * 0.45f + clamped * 0.55f
        currentLevel = smoothed
        val now = System.currentTimeMillis()
        if (now - lastLevelEmitMs.get() >= LEVEL_EMIT_INTERVAL_MS) {
            lastLevelEmitMs.set(now)
            updateSnapshot(phase = currentPhase, level = smoothed, force = false)
        }
    }

    private fun updateSnapshot(
        phase: LiveSessionPhase,
        level: Float = currentLevel,
        status: String? = null,
        force: Boolean = true
    ) {
        currentPhase = phase
        val (userTxt, replyTxt) = synchronized(transcriptLock) {
            val u = currentUserBuf.toString().trim()
                .ifBlank { recordedTurns.lastOrNull { it.role == "user" }?.text.orEmpty() }
            val r = currentModelBuf.toString().trim()
                .ifBlank { recordedTurns.lastOrNull { it.role != "user" }?.text.orEmpty() }
            u to r
        }
        val resolvedStatus = status ?: when (phase) {
            LiveSessionPhase.CONNECTING -> "Conectando ao Gemini…"
            LiveSessionPhase.LISTENING -> if (mode == LiveSessionMode.ASK) "Ouvindo…" else "Ao vivo · pode falar."
            LiveSessionPhase.THINKING -> "Executando ação…"
            LiveSessionPhase.SPEAKING -> "Gemini respondendo…"
            LiveSessionPhase.ENDED -> "Conversa encerrada."
        }
        if (force) lastLevelEmitMs.set(System.currentTimeMillis())
        callbacks.onSnapshot(
            LiveSessionSnapshot(
                mode = mode,
                phase = phase,
                level = level.coerceIn(0f, 1f),
                userText = userTxt,
                replyText = replyTxt,
                statusText = resolvedStatus,
                lastActionBadge = lastActionBadge
            )
        )
    }

    private fun flushCurrentTurnLocked() {
        val now = System.currentTimeMillis()
        val u = currentUserBuf.toString().trim()
        if (u.isNotBlank()) {
            recordedTurns.add(CopilotMemoryTurn("user", u, now))
            currentUserBuf.clear()
        }
        val m = currentModelBuf.toString().trim()
        if (m.isNotBlank()) {
            recordedTurns.add(CopilotMemoryTurn("model", m, now))
            currentModelBuf.clear()
        }
    }

    companion object {
        private const val TAG = "GeminiLiveSession"

        /** ~80 ms a 16 kHz mono 16-bit (1280 samples = 2560 bytes). */
        private const val INPUT_CHUNK_BYTES = 2560

        private const val LEVEL_EMIT_INTERVAL_MS = 65L
        private const val SETUP_TIMEOUT_MS = 10_000L
        const val LIVE_SILENCE_TIMEOUT_MS = 20_000L
        private const val ASK_NO_SPEECH_TIMEOUT_MS = 12_000L
        private const val ASK_IDLE_AFTER_SPEECH_MS = 10_000L
        private const val ASK_NO_REPLY_TIMEOUT_MS = 9_000L
        const val ASK_FOLLOWUP_WINDOW_MS = 6_000L
        private const val ASK_POST_REPLY_GRACE_MS = 700L
        private const val MAX_LIVE_SESSION_DURATION_MS = 14 * 60_000L
        private const val MAX_ONESHOT_DURATION_MS = 120_000L
        private const val MAX_RECONNECT_ATTEMPTS = 2

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket aberto indefinidamente
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        internal fun computePcm16Level(pcm: ByteArray): Float =
            LiveAudioPlaybackController.rmsToVisualLevel(LiveAudioPlaybackController.computePcm16Rms(pcm))

        internal fun appendTranscriptChunk(target: StringBuilder, chunk: String) {
            val clean = chunk.trim()
            if (clean.isEmpty()) return
            if (target.isEmpty()) {
                target.append(clean)
                return
            }
            val needsSpace = !target.last().isWhitespace() &&
                !clean.first().let { it == '.' || it == ',' || it == '!' || it == '?' || it == ';' || it == ':' }
            if (needsSpace) target.append(' ')
            target.append(clean)
        }
    }
}

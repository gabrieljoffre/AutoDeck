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
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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
import kotlin.math.sqrt

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
 * - Captura PCM 16 kHz mono com VOICE_COMMUNICATION + AEC/NS (quando suportado pelo aparelho).
 * - Reprodução PCM 24 kHz mono via AudioTrack dedicado.
 * - Barge-in (interrupção imediata ao falar por cima ou ao receber `interrupted`).
 * - Foco de áudio com ducking (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`) que abaixa a música e restaura ao encerrar.
 * - Reconexão controlada, limite máximo de sessão e encerramento automático após 20 s de silêncio no modo Conversar.
 * - Modo "Peça ao Gemini" (one-shot): grava imediatamente, executa ação, responde e encerra sozinho.
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
    private val okHttpClient: OkHttpClient = defaultHttpClient()
) {
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val running = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val setupDone = AtomicBoolean(false)
    private val modelSpeaking = AtomicBoolean(false)
    private val toolBusy = AtomicBoolean(false)
    private val turnDonePendingDrain = AtomicBoolean(false)
    private val sawAnyUserSpeech = AtomicBoolean(false)
    private val sawAnyModelOutput = AtomicBoolean(false)

    private val startedAtMs = System.currentTimeMillis()
    private val lastActivityMs = AtomicLong(startedAtMs)
    private val lastLevelEmitMs = AtomicLong(0L)
    private val reconnectCount = AtomicInteger(0)
    private val languageRetryDone = AtomicBoolean(false)
    private val playbackGen = AtomicInteger(0)
    private val queuedAudioChunks = AtomicInteger(0)

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
    private var playbackJob: Job? = null
    private var watchdogJob: Job? = null
    private var reconnectJob: Job? = null
    private var playbackChannel: Channel<Pair<Int, ByteArray>>? = null

    fun isActive(): Boolean = running.get() && !stopped.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        stopped.set(false)
        lastActivityMs.set(System.currentTimeMillis())

        requestDuckingAudioFocus()
        updateSnapshot(
            phase = LiveSessionPhase.CONNECTING,
            status = if (mode == LiveSessionMode.ASK) "Conectando ao Gemini…" else "Iniciando conversa ao vivo…"
        )
        openWebSocket(includeLanguageCode = true)
        startWatchdog()
    }

    fun stop(reason: String = "Conversa encerrada.") {
        if (!stopped.compareAndSet(false, true)) return
        running.set(false)
        setupDone.set(false)
        modelSpeaking.set(false)
        reconnectJob?.cancel()
        watchdogJob?.cancel()
        recordJob?.cancel()
        playbackJob?.cancel()

        runCatching { playbackChannel?.close() }
        playbackChannel = null

        runCatching { webSocket?.close(1000, "client_stop") }
        webSocket = null

        releaseAudioInput()
        releaseAudioOutput()
        abandonAudioFocus()

        flushCurrentTurnLocked()
        val turnsSnapshot = synchronized(transcriptLock) { recordedTurns.toList() }
        val endedAt = System.currentTimeMillis()
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
                startAudioPipelineIfNeeded()
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
            clearPlaybackQueue()
            modelSpeaking.set(false)
            turnDonePendingDrain.set(false)
            lastActivityMs.set(now)
            updateSnapshot(
                phase = LiveSessionPhase.LISTENING,
                status = "Ouvindo você…",
                force = true
            )
        }

        if (!sc.inputTranscription.isNullOrBlank()) {
            sawAnyUserSpeech.set(true)
            lastActivityMs.set(now)
            synchronized(transcriptLock) {
                if (currentModelBuf.isNotEmpty() && !modelSpeaking.get()) {
                    flushCurrentTurnLocked()
                }
                appendTranscriptChunk(currentUserBuf, sc.inputTranscription)
            }
            updateSnapshot(
                phase = if (modelSpeaking.get()) LiveSessionPhase.SPEAKING else LiveSessionPhase.LISTENING,
                status = if (mode == LiveSessionMode.ASK) "Ouvindo…" else "Ouvindo você…",
                force = true
            )
        }

        if (sc.audioChunks.isNotEmpty()) {
            sawAnyModelOutput.set(true)
            lastActivityMs.set(now)
            modelSpeaking.set(true)
            val gen = playbackGen.get()
            for (chunk in sc.audioChunks) {
                if (playbackChannel?.trySend(gen to chunk)?.isSuccess == true) {
                    queuedAudioChunks.incrementAndGet()
                }
            }
            if (currentPhase != LiveSessionPhase.SPEAKING) {
                updateSnapshot(
                    phase = LiveSessionPhase.SPEAKING,
                    status = "Gemini respondendo…",
                    force = true
                )
            }
        }

        val outText = sc.outputTranscription ?: sc.textParts.joinToString("").takeIf { it.isNotBlank() }
        if (!outText.isNullOrBlank()) {
            sawAnyModelOutput.set(true)
            lastActivityMs.set(now)
            synchronized(transcriptLock) {
                appendTranscriptChunk(currentModelBuf, outText)
            }
            updateSnapshot(
                phase = if (modelSpeaking.get()) LiveSessionPhase.SPEAKING else currentPhase,
                status = "Gemini respondendo…",
                force = true
            )
        }

        if (sc.turnComplete) {
            lastActivityMs.set(now)
            synchronized(transcriptLock) {
                flushCurrentTurnLocked()
            }
            if (mode == LiveSessionMode.ASK && sawAnyModelOutput.get()) {
                turnDonePendingDrain.set(true)
                scope.launch(Dispatchers.IO) {
                    waitPlaybackDrainAndCloseAsk()
                }
            } else if (!modelSpeaking.get()) {
                updateSnapshot(
                    phase = LiveSessionPhase.LISTENING,
                    status = if (mode == LiveSessionMode.ASK) "Ouvindo…" else "Ao vivo · pode falar.",
                    force = true
                )
            }
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

    private suspend fun waitPlaybackDrainAndCloseAsk() {
        val waitStart = System.currentTimeMillis()
        while (!stopped.get() && modelSpeaking.get() && (System.currentTimeMillis() - waitStart) < 15_000L) {
            delay(120L)
        }
        // Pequena folga para o áudio terminar de tocar no Bluetooth A2DP e o motorista ler o texto
        delay(ASK_POST_REPLY_GRACE_MS)
        if (!stopped.get() && turnDonePendingDrain.get()) {
            stop("Pronto.")
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
        clearPlaybackQueue()
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
        modelSpeaking.set(false)
        reconnectJob?.cancel()
        watchdogJob?.cancel()
        recordJob?.cancel()
        playbackJob?.cancel()
        runCatching { playbackChannel?.close() }
        playbackChannel = null
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
        if (playbackJob?.isActive != true) {
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
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = runCatching {
                AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            }.getOrNull()
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = runCatching {
                NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }.getOrNull()
        }

        recordJob = scope.launch(Dispatchers.IO) {
            try {
                record.startRecording()
                val pcmBuffer = ByteArray(INPUT_CHUNK_BYTES)
                var loudBargeInFrames = 0

                while (isActive && !stopped.get()) {
                    val read = record.read(pcmBuffer, 0, pcmBuffer.size)
                    if (read <= 0) {
                        delay(15L)
                        continue
                    }
                    val slice = if (read == pcmBuffer.size) pcmBuffer.copyOf() else pcmBuffer.copyOf(read)
                    val level = computePcm16Level(slice)

                    if (level >= SPEECH_VAD_THRESHOLD) {
                        sawAnyUserSpeech.set(true)
                        lastActivityMs.set(System.currentTimeMillis())
                    }

                    // Barge-in local: se o motorista falar alto por cima da resposta, corta a reprodução na hora
                    if (modelSpeaking.get() && level >= BARGE_IN_LEVEL_THRESHOLD) {
                        loudBargeInFrames++
                        if (loudBargeInFrames >= BARGE_IN_CONSECUTIVE_FRAMES) {
                            loudBargeInFrames = 0
                            clearPlaybackQueue()
                            modelSpeaking.set(false)
                            turnDonePendingDrain.set(false)
                            updateSnapshot(
                                phase = LiveSessionPhase.LISTENING,
                                level = level,
                                status = "Ouvindo você…",
                                force = true
                            )
                        }
                    } else {
                        loudBargeInFrames = 0
                    }

                    if (!modelSpeaking.get()) {
                        emitAudioLevel(level)
                    }

                    if (setupDone.get()) {
                        val msg = GeminiLiveProtocol.buildRealtimeAudioMessage(slice, sampleRate).toString()
                        webSocket?.send(msg)
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
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(OUTPUT_MIN_BUFFER_BYTES)

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
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull()

        if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { track?.release() }
            Log.w(TAG, "AudioTrack 24 kHz não inicializado.")
            return
        }
        audioTrack = track
        runCatching { track.play() }

        val ch = Channel<Pair<Int, ByteArray>>(capacity = Channel.UNLIMITED)
        playbackChannel = ch

        playbackJob = scope.launch(Dispatchers.IO) {
            try {
                for ((gen, pcm) in ch) {
                    val remaining = queuedAudioChunks.decrementAndGet().coerceAtLeast(0)
                    if (stopped.get()) break
                    if (gen != playbackGen.get()) continue
                    modelSpeaking.set(true)
                    lastActivityMs.set(System.currentTimeMillis())
                    val outLevel = computePcm16Level(pcm)
                    emitAudioLevel(outLevel)
                    runCatching {
                        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            track.play()
                        }
                        track.write(pcm, 0, pcm.size)
                    }
                    if (remaining == 0 && gen == playbackGen.get()) {
                        modelSpeaking.set(false)
                        if (!stopped.get() && !toolBusy.get() && !turnDonePendingDrain.get()) {
                            updateSnapshot(
                                phase = LiveSessionPhase.LISTENING,
                                level = 0f,
                                status = if (mode == LiveSessionMode.ASK) "Ouvindo…" else "Ao vivo · pode falar.",
                                force = true
                            )
                        }
                    }
                }
            } catch (_: CancellationException) {
                // encerramento normal
            } finally {
                modelSpeaking.set(false)
                releaseAudioOutput()
            }
        }
    }

    private fun clearPlaybackQueue() {
        playbackGen.incrementAndGet()
        val ch = playbackChannel
        if (ch != null) {
            while (ch.tryReceive().isSuccess) {
                // descarta áudio enfileirado anterior ao barge-in
            }
        }
        queuedAudioChunks.set(0)
        runCatching {
            audioTrack?.let { t ->
                if (t.state == AudioTrack.STATE_INITIALIZED) {
                    t.pause()
                    t.flush()
                    t.play()
                }
            }
        }
    }

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

                if (setupDone.get() && !modelSpeaking.get() && !toolBusy.get()) {
                    if (mode == LiveSessionMode.LIVE && idleMs >= LIVE_SILENCE_TIMEOUT_MS) {
                        stop("Conversa encerrada após 20 s de silêncio.")
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
            if (mgr.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                audioFocusRequest = req
            }
        }
    }

    private fun abandonAudioFocus() {
        val mgr = audioManager ?: return
        val req = audioFocusRequest ?: return
        audioFocusRequest = null
        runCatching { mgr.abandonAudioFocusRequest(req) }
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
        runCatching {
            audioTrack?.let { t ->
                if (t.state == AudioTrack.STATE_INITIALIZED) {
                    t.pause()
                    t.flush()
                    t.stop()
                }
                t.release()
            }
        }
        audioTrack = null
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
        private const val OUTPUT_MIN_BUFFER_BYTES = 9600

        private const val LEVEL_EMIT_INTERVAL_MS = 65L
        private const val SETUP_TIMEOUT_MS = 10_000L
        const val LIVE_SILENCE_TIMEOUT_MS = 20_000L
        private const val ASK_NO_SPEECH_TIMEOUT_MS = 12_000L
        private const val ASK_IDLE_AFTER_SPEECH_MS = 15_000L
        private const val ASK_POST_REPLY_GRACE_MS = 900L
        private const val MAX_LIVE_SESSION_DURATION_MS = 14 * 60_000L
        private const val MAX_ONESHOT_DURATION_MS = 60_000L
        private const val MAX_RECONNECT_ATTEMPTS = 2

        private const val SPEECH_VAD_THRESHOLD = 0.09f
        private const val BARGE_IN_LEVEL_THRESHOLD = 0.24f
        private const val BARGE_IN_CONSECUTIVE_FRAMES = 3

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket aberto indefinidamente
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        internal fun computePcm16Level(pcm: ByteArray): Float {
            if (pcm.size < 2) return 0f
            val samples = pcm.size / 2
            var sumSq = 0.0
            var i = 0
            while (i + 1 < pcm.size) {
                val lo = pcm[i].toInt() and 0xFF
                val hi = pcm[i + 1].toInt()
                val s = (hi shl 8) or lo
                val norm = s / 32768.0
                sumSq += norm * norm
                i += 2
            }
            val rms = sqrt(sumSq / samples)
            // Ganho suave para deixar a onda visualmente expressiva na faixa de voz normal
            return (rms * 3.8).toFloat().coerceIn(0f, 1f)
        }

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

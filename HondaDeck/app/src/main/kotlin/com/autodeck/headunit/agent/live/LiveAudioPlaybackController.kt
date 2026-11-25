package com.autodeck.headunit.agent.live

import kotlin.math.sqrt

/**
 * Estado do turno de reprodução de áudio do Gemini Live.
 */
enum class PlaybackTurnState {
    /** Nenhum áudio do modelo em reprodução. */
    IDLE,
    /** Acumulando jitter buffer (~200 ms) antes de iniciar a reprodução contínua. */
    BUFFERING,
    /** Reprodução contínua em andamento (nunca dá stop/flush entre chunks do mesmo turno). */
    PLAYING
}

/**
 * Ações consumidas pela thread dedicada de escrita no AudioTrack.
 */
sealed interface PlaybackAction {
    data class WriteChunk(
        val generation: Int,
        val pcm: ByteArray,
        val startPlayback: Boolean
    ) : PlaybackAction

    data class DrainTurn(
        val generation: Int
    ) : PlaybackAction

    data class Flush(
        val generation: Int,
        val reason: String
    ) : PlaybackAction

    data object Shutdown : PlaybackAction
}

/**
 * Decisão para cada frame capturado pelo microfone (supressão de eco + barge-in por limiar alto).
 */
sealed interface MicFrameDecision {
    /** Envia o áudio capturado normalmente ao servidor. */
    data class SendMic(
        val rms: Float,
        val visualLevel: Float,
        val speechDetected: Boolean
    ) : MicFrameDecision

    /** Suprime o envio durante fala do modelo e janela de hangover pós-fala (evita eco). */
    data class SuppressEcho(
        val reason: String,
        val rms: Float,
        val visualLevel: Float
    ) : MicFrameDecision

    /** Voz muito alta sustentada acima do limiar alto: corta reprodução e devolve microfone. */
    data class LocalBargeIn(
        val rms: Float,
        val visualLevel: Float
    ) : MicFrameDecision
}

/**
 * Callbacks de transição de estado do controlador de áudio (executados fora do lock).
 */
interface LiveAudioStateListener {
    fun onRequestAudioFocus() {}
    fun onAbandonAudioFocus() {}
    fun onSpeakingStateChanged(speaking: Boolean, reason: String) {}
    fun onTurnFinished(wasInterrupted: Boolean) {}
}

/**
 * Controlador puro (sem dependências Android) para fila/jitter buffer, supressão de eco,
 * foco de áudio por turno e ciclo de vida de chunk / interrupted / turnComplete no Gemini Live.
 *
 * Regras garantidas:
 * - Acumula ~200 ms (9.600 bytes em 24 kHz PCM16 mono) antes de iniciar o turno de reprodução,
 *   ou libera imediatamente se `turnComplete` chegar com uma resposta mais curta.
 * - Nunca emite `Flush` entre pedaços do mesmo turno; apenas em `interrupted` real ou `close()`.
 * - Mantém `isModelSpeaking == true` durante todo o turno (inclusive entre chunks pela rede) até
 *   o drain final após `turnComplete` ou `interrupted`.
 * - Solicita foco de áudio (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`) uma única vez por turno e só
 *   libera quando o turno drena após `turnComplete` (ou no `interrupted`/`close`).
 * - Enquanto o modelo fala e durante `echoHangoverMs` (~450 ms) após terminar, bloqueia o envio
 *   de áudio do microfone para impedir eco no VAD do servidor; interrupção por voz só ocorre
 *   acima de um limiar alto sustentado.
 */
class LiveAudioPlaybackController(
    private val jitterBufferBytes: Int = DEFAULT_JITTER_BUFFER_BYTES,
    private val echoHangoverMs: Long = DEFAULT_ECHO_HANGOVER_MS,
    private val bargeInRmsThreshold: Float = DEFAULT_BARGE_IN_RMS_THRESHOLD,
    private val bargeInConsecutiveFrames: Int = DEFAULT_BARGE_IN_CONSECUTIVE_FRAMES,
    private val speechVadRmsThreshold: Float = DEFAULT_SPEECH_VAD_RMS_THRESHOLD,
    private val listener: LiveAudioStateListener = object : LiveAudioStateListener {}
) {
    private val lock = Object()

    private val pendingChunks = ArrayDeque<ByteArray>()
    private var turnState: PlaybackTurnState = PlaybackTurnState.IDLE
    private var turnCompleteReceived: Boolean = false
    private var playbackNotifiedForTurn: Boolean = false
    private var bufferedBytesInTurn: Int = 0
    private var currentGeneration: Int = 0
    private var pendingFlushGeneration: Int = 0
    private var lastHandledFlushGeneration: Int = 0
    private var pendingFlushReason: String = ""
    private var drainingGeneration: Int = -1
    private var audioFocusHeld: Boolean = false
    private var closed: Boolean = false
    private var micUnmuteAtMs: Long = 0L
    private var loudBargeInFrames: Int = 0

    val state: PlaybackTurnState
        get() = synchronized(lock) { turnState }

    val generation: Int
        get() = synchronized(lock) { currentGeneration }

    val isModelSpeaking: Boolean
        get() = synchronized(lock) { turnState != PlaybackTurnState.IDLE }

    val isAudioFocusHeld: Boolean
        get() = synchronized(lock) { audioFocusHeld }

    val queuedChunkCount: Int
        get() = synchronized(lock) { pendingChunks.size }

    val queuedByteCount: Int
        get() = synchronized(lock) { pendingChunks.sumOf { it.size } }

    fun isMicSuppressed(nowMs: Long): Boolean = synchronized(lock) {
        turnState != PlaybackTurnState.IDLE || nowMs < micUnmuteAtMs
    }

    /**
     * Enfileira um pedaço PCM 24 kHz recebido do servidor.
     * Retorna `true` se o chunk foi aceito na fila.
     */
    fun enqueueChunk(pcm: ByteArray): Boolean {
        if (pcm.isEmpty()) return false
        var requestFocus = false
        var notifySpeaking = false

        synchronized(lock) {
            if (closed) return false

            if (turnState == PlaybackTurnState.IDLE) {
                turnState = PlaybackTurnState.BUFFERING
                turnCompleteReceived = false
                playbackNotifiedForTurn = false
                bufferedBytesInTurn = 0
                drainingGeneration = -1
                notifySpeaking = true
                if (!audioFocusHeld) {
                    audioFocusHeld = true
                    requestFocus = true
                }
            } else {
                // Novo chunk no mesmo turno ou logo durante drain: cancela estado de drain pendente
                turnCompleteReceived = false
                drainingGeneration = -1
            }

            pendingChunks.addLast(pcm)
            bufferedBytesInTurn += pcm.size

            if (turnState == PlaybackTurnState.BUFFERING && bufferedBytesInTurn >= jitterBufferBytes) {
                turnState = PlaybackTurnState.PLAYING
            }
            lock.notifyAll()
        }

        if (requestFocus) listener.onRequestAudioFocus()
        if (notifySpeaking) listener.onSpeakingStateChanged(true, "chunk_received")
        return true
    }

    /**
     * Marca que o servidor sinalizou `turnComplete` para o turno atual.
     * Se ainda estiver em `BUFFERING` (resposta menor que ~200 ms), libera a reprodução imediatamente.
     */
    fun onTurnComplete(nowMs: Long) {
        var abandonFocus = false
        var finishImmediately = false

        synchronized(lock) {
            if (closed) return
            if (turnState == PlaybackTurnState.IDLE) {
                if (audioFocusHeld) {
                    audioFocusHeld = false
                    abandonFocus = true
                }
                finishImmediately = true
            } else {
                turnCompleteReceived = true
                if (turnState == PlaybackTurnState.BUFFERING) {
                    // Resposta curta (< 200 ms): toca o que já chegou sem travar no jitter buffer
                    turnState = PlaybackTurnState.PLAYING
                }
                lock.notifyAll()
            }
        }

        if (abandonFocus) listener.onAbandonAudioFocus()
        if (finishImmediately) listener.onTurnFinished(wasInterrupted = false)
    }

    /**
     * Trata `interrupted` real (do servidor, botão manual ou barge-in acima do limiar alto).
     * Descarta imediatamente os chunks pendentes, agenda `Flush` e libera o foco de áudio.
     * Retorna quantos chunks foram descartados.
     */
    fun onInterrupted(nowMs: Long, source: String): Int {
        var dropped = 0
        var wasSpeaking = false
        var abandonFocus = false

        synchronized(lock) {
            if (closed) return 0
            dropped = pendingChunks.size
            pendingChunks.clear()
            bufferedBytesInTurn = 0
            turnCompleteReceived = false
            playbackNotifiedForTurn = false
            drainingGeneration = -1
            loudBargeInFrames = 0

            wasSpeaking = turnState != PlaybackTurnState.IDLE
            if (wasSpeaking || dropped > 0) {
                currentGeneration++
                pendingFlushGeneration = currentGeneration
                pendingFlushReason = source
            }
            turnState = PlaybackTurnState.IDLE
            micUnmuteAtMs = nowMs

            if (audioFocusHeld) {
                audioFocusHeld = false
                abandonFocus = true
            }
            lock.notifyAll()
        }

        if (abandonFocus) listener.onAbandonAudioFocus()
        if (wasSpeaking) {
            listener.onSpeakingStateChanged(false, "interrupted:$source")
            listener.onTurnFinished(wasInterrupted = true)
        }
        return dropped
    }

    /**
     * Avalia um frame do microfone: decide entre suprimir eco, acionar barge-in por voz muito alta
     * ou enviar normalmente ao servidor.
     */
    fun evaluateMicFrame(pcm: ByteArray, nowMs: Long): MicFrameDecision {
        val rms = computePcm16Rms(pcm)
        val visualLevel = rmsToVisualLevel(rms)

        synchronized(lock) {
            if (turnState != PlaybackTurnState.IDLE) {
                if (rms >= bargeInRmsThreshold) {
                    loudBargeInFrames++
                    if (loudBargeInFrames >= bargeInConsecutiveFrames) {
                        loudBargeInFrames = 0
                        return MicFrameDecision.LocalBargeIn(rms = rms, visualLevel = visualLevel)
                    }
                } else {
                    loudBargeInFrames = 0
                }
                return MicFrameDecision.SuppressEcho(
                    reason = "model_speaking",
                    rms = rms,
                    visualLevel = visualLevel
                )
            }

            loudBargeInFrames = 0
            if (nowMs < micUnmuteAtMs) {
                return MicFrameDecision.SuppressEcho(
                    reason = "echo_hangover",
                    rms = rms,
                    visualLevel = visualLevel
                )
            }

            return MicFrameDecision.SendMic(
                rms = rms,
                visualLevel = visualLevel,
                speechDetected = rms >= speechVadRmsThreshold
            )
        }
    }

    /**
     * Retira a próxima ação para a thread dedicada de reprodução (não bloqueante).
     * Retorna `null` se não houver ação pronta (ex.: aguardando jitter buffer ou novo chunk).
     */
    fun pollNextAction(): PlaybackAction? = synchronized(lock) {
        pollNextActionLocked()
    }

    /**
     * Aguarda até que exista uma ação pronta para a thread dedicada de escrita no AudioTrack.
     */
    @Throws(InterruptedException::class)
    fun takeNextAction(): PlaybackAction {
        synchronized(lock) {
            while (true) {
                val action = pollNextActionLocked()
                if (action != null) return action
                lock.wait()
            }
        }
    }

    private fun pollNextActionLocked(): PlaybackAction? {
        if (closed) return PlaybackAction.Shutdown

        if (lastHandledFlushGeneration < pendingFlushGeneration) {
            lastHandledFlushGeneration = pendingFlushGeneration
            return PlaybackAction.Flush(
                generation = currentGeneration,
                reason = pendingFlushReason
            )
        }

        if (turnState == PlaybackTurnState.PLAYING) {
            if (pendingChunks.isNotEmpty()) {
                val chunk = pendingChunks.removeFirst()
                val startPlay = !playbackNotifiedForTurn
                playbackNotifiedForTurn = true
                return PlaybackAction.WriteChunk(
                    generation = currentGeneration,
                    pcm = chunk,
                    startPlayback = startPlay
                )
            }
            if (turnCompleteReceived && drainingGeneration != currentGeneration) {
                drainingGeneration = currentGeneration
                return PlaybackAction.DrainTurn(generation = currentGeneration)
            }
        }

        return null
    }

    /**
     * Indica se um drain em andamento deve ser abortado imediatamente (por `interrupted`,
     * encerramento ou chegada de novos chunks).
     */
    fun shouldAbortDrain(generation: Int): Boolean = synchronized(lock) {
        closed || generation != currentGeneration || pendingChunks.isNotEmpty()
    }

    /**
     * Confirma que o áudio do turno terminou de tocar no hardware após `DrainTurn`.
     * Libera o foco de áudio e inicia a janela de hangover (~450 ms) antes de reabrir o microfone.
     */
    fun completeTurnDrain(generation: Int, nowMs: Long): Boolean {
        var abandonFocus = false
        var finished = false

        synchronized(lock) {
            if (closed || generation != currentGeneration || turnState == PlaybackTurnState.IDLE) {
                return false
            }
            if (pendingChunks.isNotEmpty() || !turnCompleteReceived) {
                drainingGeneration = -1
                lock.notifyAll()
                return false
            }
            turnState = PlaybackTurnState.IDLE
            turnCompleteReceived = false
            playbackNotifiedForTurn = false
            bufferedBytesInTurn = 0
            drainingGeneration = -1
            loudBargeInFrames = 0
            micUnmuteAtMs = nowMs + echoHangoverMs
            finished = true
            if (audioFocusHeld) {
                audioFocusHeld = false
                abandonFocus = true
            }
            lock.notifyAll()
        }

        if (abandonFocus) listener.onAbandonAudioFocus()
        if (finished) {
            listener.onSpeakingStateChanged(false, "turn_complete_drained")
            listener.onTurnFinished(wasInterrupted = false)
        }
        return finished
    }

    /**
     * Encerra definitivamente o controlador e acorda a thread de playback para sair com segurança.
     */
    fun close() {
        var abandonFocus = false
        synchronized(lock) {
            if (closed) return
            closed = true
            pendingChunks.clear()
            turnState = PlaybackTurnState.IDLE
            turnCompleteReceived = false
            playbackNotifiedForTurn = false
            bufferedBytesInTurn = 0
            currentGeneration++
            if (audioFocusHeld) {
                audioFocusHeld = false
                abandonFocus = true
            }
            lock.notifyAll()
        }
        if (abandonFocus) listener.onAbandonAudioFocus()
    }

    companion object {
        const val OUTPUT_SAMPLE_RATE_HZ = GeminiLiveProtocol.OUTPUT_SAMPLE_RATE_HZ
        const val BYTES_PER_SAMPLE = 2
        const val BYTES_PER_MS_24KHZ = (OUTPUT_SAMPLE_RATE_HZ * BYTES_PER_SAMPLE) / 1000 // 48 bytes/ms

        /** Pré-buffer de ~200 ms (9.600 bytes em 24 kHz mono 16-bit) antes de iniciar reprodução. */
        const val DEFAULT_JITTER_BUFFER_MS = 200
        const val DEFAULT_JITTER_BUFFER_BYTES = DEFAULT_JITTER_BUFFER_MS * BYTES_PER_MS_24KHZ

        /** Janela pós-fala (~450 ms) mantendo o microfone mutado para absorver eco/reverberação. */
        const val DEFAULT_ECHO_HANGOVER_MS = 450L

        /** Limiar alto de RMS real (0..1) para barge-in por voz acima do alto-falante. */
        const val DEFAULT_BARGE_IN_RMS_THRESHOLD = 0.32f
        const val DEFAULT_BARGE_IN_CONSECUTIVE_FRAMES = 4

        /** Limiar de detecção de voz do motorista quando o modelo está em silêncio. */
        const val DEFAULT_SPEECH_VAD_RMS_THRESHOLD = 0.024f

        /** Multiplicador mínimo do buffer do AudioTrack (>= 4x o mínimo do sistema). */
        const val AUDIO_TRACK_MIN_BUFFER_MULTIPLIER = 4

        /** Piso de 1 segundo em 24 kHz PCM16 mono (48.000 bytes) para absorver jitter de rede. */
        const val AUDIO_TRACK_FLOOR_BYTES = OUTPUT_SAMPLE_RATE_HZ * BYTES_PER_SAMPLE

        fun computeAudioTrackBufferSize(minBufferSizeBytes: Int): Int {
            val safeMin = minBufferSizeBytes.coerceAtLeast(DEFAULT_JITTER_BUFFER_BYTES)
            val target = maxOf(safeMin * AUDIO_TRACK_MIN_BUFFER_MULTIPLIER, AUDIO_TRACK_FLOOR_BYTES)
            // Alinha a múltiplo de 4 bytes (2 frames PCM16)
            return (target + 3) and 3.inv()
        }

        fun computePcm16Rms(pcm: ByteArray): Float {
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
            return sqrt(sumSq / samples).toFloat().coerceIn(0f, 1f)
        }

        fun rmsToVisualLevel(rms: Float): Float = (rms * 3.8f).coerceIn(0f, 1f)
    }
}

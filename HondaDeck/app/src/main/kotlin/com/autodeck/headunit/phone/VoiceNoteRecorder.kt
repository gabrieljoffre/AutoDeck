package com.autodeck.headunit.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class RecordedVoiceNote(
    val file: File,
    val mimeType: String = "audio/mp4",
    val durationMs: Long
)

/**
 * Gravador de mensagem de voz (M4A / AAC) pelo microfone do tablet para envio no WhatsApp via Ponte:
 * - Grava até detectar silêncio contínuo de 2 segundos (após fala detectada) ou limite máximo de 60 segundos.
 * - Reporta progresso/nível para exibir "gravando…" na interface do painel.
 */
class VoiceNoteRecorder(private val appContext: Context) {

    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var activeRecorder: MediaRecorder? = null

    fun cancel() {
        cancelled.set(true)
        runCatching { activeRecorder?.stop() }
        runCatching { activeRecorder?.release() }
        activeRecorder = null
    }

    suspend fun recordUntilSilence(
        silenceStopMs: Long = SILENCE_STOP_MS,
        maxDurationMs: Long = MAX_DURATION_MS,
        onProgress: (recording: Boolean, level: Float, elapsedMs: Long) -> Unit = { _, _, _ -> }
    ): Result<RecordedVoiceNote> = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return@withContext Result.failure(SecurityException("Permissão de microfone não concedida no tablet."))
        }

        cancelled.set(false)
        val outDir = File(appContext.cacheDir, "voice_notes").apply { mkdirs() }
        outDir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 30 * 60_000L }?.forEach {
            runCatching { it.delete() }
        }
        // OGG/Opus (Android 10+): o WhatsApp mostra como recado de voz; antes disso, M4A/AAC.
        val useOpus = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
        val outFile = File(outDir, "voz_${System.currentTimeMillis()}." + if (useOpus) "ogg" else "m4a")

        val recorder = createRecorder()
        activeRecorder = recorder
        val startedAt = System.currentTimeMillis()
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            if (useOpus) {
                recorder.setOutputFormat(MediaRecorder.OutputFormat.OGG)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                recorder.setAudioSamplingRate(16_000)
            } else {
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioSamplingRate(24_000)
            }
            recorder.setAudioChannels(1)
            recorder.setAudioEncodingBitRate(32_000)
            recorder.setOutputFile(outFile.absolutePath)
            recorder.prepare()
            recorder.start()

            onProgress(true, 0.1f, 0L)

            var heardSpeech = false
            var silenceStartedAt = 0L

            while (isActive && !cancelled.get()) {
                delay(POLL_INTERVAL_MS)
                val now = System.currentTimeMillis()
                val elapsed = now - startedAt
                if (elapsed >= maxDurationMs) {
                    Log.i(TAG, "Limite máximo de ${maxDurationMs / 1000}s atingido na gravação.")
                    break
                }

                val amp = runCatching { recorder.maxAmplitude }.getOrDefault(0)
                val normLevel = (amp / 12_000f).coerceIn(0f, 1f)
                onProgress(true, normLevel, elapsed)

                if (amp >= SPEECH_AMPLITUDE_THRESHOLD) {
                    heardSpeech = true
                    silenceStartedAt = 0L
                } else if (heardSpeech || elapsed >= INITIAL_SPEECH_WAIT_MS) {
                    if (silenceStartedAt == 0L) {
                        silenceStartedAt = now
                    } else if (now - silenceStartedAt >= silenceStopMs) {
                        Log.i(TAG, "Silêncio de ${silenceStopMs}ms detectado; finalizando gravação.")
                        break
                    }
                }
            }

            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            activeRecorder = null
            onProgress(false, 0f, 0L)

            val duration = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
            if (cancelled.get()) {
                runCatching { outFile.delete() }
                return@withContext Result.failure(IllegalStateException("Gravação cancelada."))
            }
            if (!outFile.exists() || outFile.length() < 512L) {
                runCatching { outFile.delete() }
                return@withContext Result.failure(IllegalStateException("Não captei áudio suficiente na gravação."))
            }
            Result.success(RecordedVoiceNote(file = outFile, mimeType = if (useOpus) "audio/ogg; codecs=opus" else "audio/mp4", durationMs = duration))
        } catch (err: Throwable) {
            runCatching { recorder.release() }
            activeRecorder = null
            onProgress(false, 0f, 0L)
            runCatching { outFile.delete() }
            Result.failure(err)
        }
    }

    @Suppress("DEPRECATION")
    private fun createRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(appContext)
        } else {
            MediaRecorder()
        }

    companion object {
        private const val TAG = "VoiceNoteRecorder"
        const val SILENCE_STOP_MS: Long = 2_000L
        const val MAX_DURATION_MS: Long = 60_000L
        private const val INITIAL_SPEECH_WAIT_MS: Long = 7_000L
        private const val POLL_INTERVAL_MS: Long = 100L
        private const val SPEECH_AMPLITUDE_THRESHOLD: Int = 900
    }
}

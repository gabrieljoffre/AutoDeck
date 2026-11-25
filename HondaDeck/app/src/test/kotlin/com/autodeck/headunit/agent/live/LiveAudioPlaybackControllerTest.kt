package com.autodeck.headunit.agent.live

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAudioPlaybackControllerTest {

    private fun pcmBytes(sizeBytes: Int, amplitude: Int = 4000): ByteArray {
        val out = ByteArray(sizeBytes)
        var i = 0
        var sign = 1
        while (i + 1 < sizeBytes) {
            val v = amplitude * sign
            out[i] = (v and 0xFF).toByte()
            out[i + 1] = ((v shr 8) and 0xFF).toByte()
            sign = -sign
            i += 2
        }
        return out
    }

    private class RecordingListener : LiveAudioStateListener {
        var focusRequests = 0
        var focusAbandons = 0
        val speakingTransitions = mutableListOf<Pair<Boolean, String>>()
        val finishedTurns = mutableListOf<Boolean>()

        override fun onRequestAudioFocus() {
            focusRequests++
        }

        override fun onAbandonAudioFocus() {
            focusAbandons++
        }

        override fun onSpeakingStateChanged(speaking: Boolean, reason: String) {
            speakingTransitions += speaking to reason
        }

        override fun onTurnFinished(wasInterrupted: Boolean) {
            finishedTurns += wasInterrupted
        }
    }

    @Test
    fun `acumula jitter buffer de 200ms antes de tocar, nao da flush entre chunks e so libera foco no turnComplete`() {
        val listener = RecordingListener()
        val ctrl = LiveAudioPlaybackController(
            jitterBufferBytes = LiveAudioPlaybackController.DEFAULT_JITTER_BUFFER_BYTES, // 9600 B = 200 ms
            echoHangoverMs = 450L,
            listener = listener
        )

        // Chunk 1: 4800 bytes (100 ms) -> ainda em BUFFERING, sem liberar para escrita
        val c1 = pcmBytes(4800)
        assertTrue(ctrl.enqueueChunk(c1))
        assertEquals(PlaybackTurnState.BUFFERING, ctrl.state)
        assertTrue(ctrl.isModelSpeaking)
        assertTrue(ctrl.isAudioFocusHeld)
        assertEquals(1, listener.focusRequests)
        assertEquals(0, listener.focusAbandons)
        assertNull("Não deve começar a tocar antes de acumular ~200 ms", ctrl.pollNextAction())

        // Chunk 2: +4800 bytes (total 200 ms) -> transita para PLAYING sem pedir foco de novo
        val c2 = pcmBytes(4800)
        assertTrue(ctrl.enqueueChunk(c2))
        assertEquals(PlaybackTurnState.PLAYING, ctrl.state)
        assertEquals("Foco deve ser pedido só uma vez por turno", 1, listener.focusRequests)

        // Consome chunk 1 e chunk 2: nenhum Flush no meio
        val a1 = ctrl.pollNextAction() as PlaybackAction.WriteChunk
        assertTrue(a1.startPlayback)
        assertArrayEquals(c1, a1.pcm)

        val a2 = ctrl.pollNextAction() as PlaybackAction.WriteChunk
        assertFalse(a2.startPlayback)
        assertArrayEquals(c2, a2.pcm)

        // Fila momentaneamente vazia entre chunks do mesmo turno: continua SPEAKING e mantém foco!
        assertNull(ctrl.pollNextAction())
        assertTrue("Não pode sair de speaking entre chunks do mesmo turno", ctrl.isModelSpeaking)
        assertTrue("Foco deve permanecer ativo até o turnComplete", ctrl.isAudioFocusHeld)
        assertEquals(0, listener.focusAbandons)

        // Chunk 3 chega depois de pequena pausa na rede: toca direto sem Flush e sem novo foco
        val c3 = pcmBytes(2400)
        assertTrue(ctrl.enqueueChunk(c3))
        val a3 = ctrl.pollNextAction() as PlaybackAction.WriteChunk
        assertFalse(a3.startPlayback)
        assertArrayEquals(c3, a3.pcm)
        assertEquals(1, listener.focusRequests)

        // Servidor envia turnComplete -> emite DrainTurn -> só ao drenar libera foco e volta a IDLE
        ctrl.onTurnComplete(nowMs = 10_000L)
        val drain = ctrl.pollNextAction() as PlaybackAction.DrainTurn
        assertTrue("Ainda speaking enquanto hardware drena", ctrl.isModelSpeaking)
        assertEquals(0, listener.focusAbandons)

        assertTrue(ctrl.completeTurnDrain(drain.generation, nowMs = 10_200L))
        assertEquals(PlaybackTurnState.IDLE, ctrl.state)
        assertFalse(ctrl.isModelSpeaking)
        assertFalse(ctrl.isAudioFocusHeld)
        assertEquals(1, listener.focusRequests)
        assertEquals(1, listener.focusAbandons)
        assertEquals(listOf(false), listener.finishedTurns)
    }

    @Test
    fun `resposta curta menor que 200ms toca imediatamente ao receber turnComplete`() {
        val listener = RecordingListener()
        val ctrl = LiveAudioPlaybackController(
            jitterBufferBytes = 9600,
            listener = listener
        )

        val shortChunk = pcmBytes(1920) // 40 ms
        ctrl.enqueueChunk(shortChunk)
        assertEquals(PlaybackTurnState.BUFFERING, ctrl.state)
        assertNull(ctrl.pollNextAction())

        // Chegou turnComplete antes de 200 ms -> libera para reprodução sem perder o áudio
        ctrl.onTurnComplete(nowMs = 1_000L)
        assertEquals(PlaybackTurnState.PLAYING, ctrl.state)

        val write = ctrl.pollNextAction() as PlaybackAction.WriteChunk
        assertArrayEquals(shortChunk, write.pcm)
        val drain = ctrl.pollNextAction() as PlaybackAction.DrainTurn
        assertTrue(ctrl.completeTurnDrain(drain.generation, nowMs = 1_050L))
        assertEquals(PlaybackTurnState.IDLE, ctrl.state)
        assertEquals(1, listener.focusAbandons)
    }

    @Test
    fun `interrupted real descarta fila, agenda Flush unico e libera foco imediatamente`() {
        val listener = RecordingListener()
        val ctrl = LiveAudioPlaybackController(jitterBufferBytes = 4800, listener = listener)

        ctrl.enqueueChunk(pcmBytes(4800))
        ctrl.enqueueChunk(pcmBytes(4800))
        ctrl.enqueueChunk(pcmBytes(4800))
        assertEquals(3, ctrl.queuedChunkCount)
        assertEquals(1, listener.focusRequests)

        val dropped = ctrl.onInterrupted(nowMs = 5_000L, source = "server_interrupted")
        assertEquals(3, dropped)
        assertEquals(0, ctrl.queuedChunkCount)
        assertEquals(PlaybackTurnState.IDLE, ctrl.state)
        assertFalse(ctrl.isModelSpeaking)
        assertFalse(ctrl.isAudioFocusHeld)
        assertEquals(1, listener.focusAbandons)
        assertEquals(listOf(true), listener.finishedTurns)

        val flush = ctrl.pollNextAction() as PlaybackAction.Flush
        assertEquals("server_interrupted", flush.reason)
        assertNull("Não deve haver chunks antigos após Flush", ctrl.pollNextAction())
    }

    @Test
    fun `suprime envio do microfone durante fala e hangover de 450ms e so aceita barge-in acima do limiar alto`() {
        val ctrl = LiveAudioPlaybackController(
            jitterBufferBytes = 4800,
            echoHangoverMs = 450L,
            bargeInRmsThreshold = 0.32f,
            bargeInConsecutiveFrames = 4
        )

        // Sem fala do modelo: áudio normal é enviado
        val normalVoice = pcmBytes(2560, amplitude = 3200) // rms ~0.098
        val d0 = ctrl.evaluateMicFrame(normalVoice, nowMs = 100L)
        assertTrue(d0 is MicFrameDecision.SendMic)
        assertTrue((d0 as MicFrameDecision.SendMic).speechDetected)

        // Modelo começa a falar -> eco moderado (rms ~0.18) NÃO dispara barge-in nem é enviado
        ctrl.enqueueChunk(pcmBytes(4800))
        val echoFrame = pcmBytes(2560, amplitude = 6000) // rms ~0.183
        repeat(10) { idx ->
            val d = ctrl.evaluateMicFrame(echoFrame, nowMs = 200L + idx * 80L)
            assertTrue("Eco moderado deve ser suprimido", d is MicFrameDecision.SuppressEcho)
            assertEquals("model_speaking", (d as MicFrameDecision.SuppressEcho).reason)
        }

        // Voz MUITO alta sustentada (rms ~0.55) por 4 frames consecutivos dispara barge-in local
        val shoutFrame = pcmBytes(2560, amplitude = 18000)
        assertTrue(ctrl.evaluateMicFrame(shoutFrame, nowMs = 1_100L) is MicFrameDecision.SuppressEcho)
        assertTrue(ctrl.evaluateMicFrame(shoutFrame, nowMs = 1_180L) is MicFrameDecision.SuppressEcho)
        assertTrue(ctrl.evaluateMicFrame(shoutFrame, nowMs = 1_260L) is MicFrameDecision.SuppressEcho)
        assertTrue(ctrl.evaluateMicFrame(shoutFrame, nowMs = 1_340L) is MicFrameDecision.LocalBargeIn)

        // Fim normal de turno -> hangover de 450 ms mantém microfone mutado
        ctrl.onTurnComplete(nowMs = 2_000L)
        assertTrue(ctrl.pollNextAction() is PlaybackAction.WriteChunk)
        val drain = ctrl.pollNextAction() as PlaybackAction.DrainTurn
        assertTrue(ctrl.completeTurnDrain(drain.generation, nowMs = 2_200L))

        // 200 ms depois do drain (< 450 ms) ainda suprime eco
        val duringHangover = ctrl.evaluateMicFrame(normalVoice, nowMs = 2_400L)
        assertTrue(duringHangover is MicFrameDecision.SuppressEcho)
        assertEquals("echo_hangover", (duringHangover as MicFrameDecision.SuppressEcho).reason)

        // 460 ms depois do drain (>= 450 ms) volta a enviar normalmente
        val afterHangover = ctrl.evaluateMicFrame(normalVoice, nowMs = 2_660L)
        assertTrue(afterHangover is MicFrameDecision.SendMic)
    }

    @Test
    fun `buffer do AudioTrack e sempre pelo menos 4x o minimo do sistema`() {
        val minSmall = 4096
        val bufSmall = LiveAudioPlaybackController.computeAudioTrackBufferSize(minSmall)
        assertTrue(bufSmall >= minSmall * 4)
        assertTrue(bufSmall >= LiveAudioPlaybackController.AUDIO_TRACK_FLOOR_BYTES)
        assertEquals(0, bufSmall % 4)

        val minLarge = 16000
        val bufLarge = LiveAudioPlaybackController.computeAudioTrackBufferSize(minLarge)
        assertTrue(bufLarge >= minLarge * 4)
        assertEquals(0, bufLarge % 4)
    }
}

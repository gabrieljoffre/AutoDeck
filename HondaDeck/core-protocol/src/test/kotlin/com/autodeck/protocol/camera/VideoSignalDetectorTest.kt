package com.autodeck.protocol.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSignalDetectorTest {

    private fun solidGrid(lumaValue: Int): LumaGrid {
        return LumaGrid(width = 16, height = 12, luma = IntArray(192) { lumaValue })
    }

    private fun colorBarsNoSignalGrid(): LumaGrid {
        val values = IntArray(192) { idx ->
            val col = idx % 16
            if (col < 8) 40 else 210
        }
        return LumaGrid(width = 16, height = 12, luma = values)
    }

    private fun realCameraGrid(): LumaGrid {
        val values = IntArray(192) { idx ->
            val row = idx / 16
            val col = idx % 16
            ((row * 17 + col * 11) % 210) + 20
        }
        return LumaGrid(width = 16, height = 12, luma = values)
    }

    @Test
    fun `detects solid static standby screen as no signal`() {
        val detector = VideoSignalDetector()
        assertFalse(detector.analyze(solidGrid(0)).hasActiveSignal)
        assertFalse(detector.analyze(solidGrid(42)).hasActiveSignal)
        assertFalse(detector.analyze(solidGrid(42)).hasActiveSignal)
        assertTrue(detector.analyze(realCameraGrid()).hasActiveSignal)
    }

    @Test
    fun `detects dark night camera image via analog temporal noise between frames`() {
        val detector = VideoSignalDetector(minStdDevForSignal = 9.0, minTemporalNoise = 1.2)
        detector.captureCalibration(solidGrid(10), toleranceMad = 12.0)

        // 1º frame noturno muito escuro (stdDev < 9)
        val night1 = LumaGrid(16, 12, IntArray(192) { idx -> 12 + (idx % 4) })
        // 2º frame noturno com ruído analógico leve (~3 de diferença média)
        val night2 = LumaGrid(16, 12, IntArray(192) { idx -> 15 - (idx % 4) })

        detector.analyze(night1)
        val analysis = detector.analyze(night2)
        assertTrue(analysis.hasActiveSignal)
    }

    @Test
    fun `calibrated static bars are rejected while real camera scene triggers active signal`() {
        val detector = VideoSignalDetector()
        val bars = colorBarsNoSignalGrid()
        detector.captureCalibration(bars, toleranceMad = 12.0)

        assertFalse(detector.analyze(bars).hasActiveSignal)
        assertFalse(detector.analyze(bars).hasActiveSignal)
        assertTrue(detector.analyze(realCameraGrid()).hasActiveSignal)
    }

    @Test
    fun `reverse state machine requires consecutive frames and holds during hysteresis`() {
        val machine = ReverseStateMachine(ReverseTimingConfig(activateAfterFrames = 3, deactivateAfterMs = 1_200L))

        assertEquals(ReverseCameraState.CONFIRMING_SIGNAL, machine.onVideoSignalSample(true, 100L))
        assertEquals(ReverseCameraState.CONFIRMING_SIGNAL, machine.onVideoSignalSample(true, 200L))
        assertEquals(ReverseCameraState.STANDBY, machine.onVideoSignalSample(false, 250L))

        machine.onVideoSignalSample(true, 300L)
        machine.onVideoSignalSample(true, 350L)
        assertEquals(ReverseCameraState.ACTIVE, machine.onVideoSignalSample(true, 400L))

        assertEquals(ReverseCameraState.CONFIRMING_LOSS, machine.onVideoSignalSample(false, 500L))
        assertEquals(ReverseCameraState.CONFIRMING_LOSS, machine.onVideoSignalSample(false, 1_500L))
        assertEquals(ReverseCameraState.STANDBY, machine.onVideoSignalSample(false, 1_750L))
    }
}

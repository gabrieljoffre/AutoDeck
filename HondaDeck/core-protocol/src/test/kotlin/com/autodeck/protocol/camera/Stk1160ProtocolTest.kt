package com.autodeck.protocol.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Stk1160ProtocolTest {

    @Test
    fun `identifies stk1160 vid pid accurately`() {
        assertTrue(Stk1160Protocol.isStk1160Device(0x05E1, 0x0408))
        assertFalse(Stk1160Protocol.isStk1160Device(0x1314, 0x1520))
        assertFalse(Stk1160Protocol.isStk1160Device(0x534D, 0x2109))
    }

    @Test
    fun `maps channel index cleanly to GCTRL mux values`() {
        assertEquals(0x98, Stk1160Protocol.gctrlForChannel(0))
        assertEquals(0x90, Stk1160Protocol.gctrlForChannel(1))
        assertEquals(0x88, Stk1160Protocol.gctrlForChannel(2))
        assertEquals(0x80, Stk1160Protocol.gctrlForChannel(3))
        assertEquals(0x98, Stk1160Protocol.gctrlForChannel(-2))
        assertEquals(0x80, Stk1160Protocol.gctrlForChannel(9))
    }

    @Test
    fun `parses saa7113 reg 1F sync lock and 50hz or 60hz flag`() {
        val ntscLocked = Stk1160Protocol.parseSaa7113Status1F(0x21)
        assertTrue(ntscLocked.hasSyncLock)
        assertTrue(ntscLocked.is60Hz)
        assertFalse(ntscLocked.horizontalUnlocked)

        val palLocked = Stk1160Protocol.parseSaa7113Status1F(0x01)
        assertTrue(palLocked.hasSyncLock)
        assertFalse(palLocked.is60Hz)

        val hlockLost = Stk1160Protocol.parseSaa7113Status1F(0x41)
        assertFalse(hlockLost.hasSyncLock)
        assertTrue(hlockLost.horizontalUnlocked)

        val notReady = Stk1160Protocol.parseSaa7113Status1F(0x20)
        assertFalse(notReady.hasSyncLock)
        assertFalse(notReady.isReadyForCapture)
    }

    @Test
    fun `parses extended status reg 1E color standard and auto-detects NTSC PAL-M PAL-N PAL`() {
        // 60 Hz + STDC=PAL (0x20 em reg 1E) => PAL-M (Brasil, 720x480)
        val statusPalM = Stk1160Protocol.parseSaa7113Status(reg1F = 0xA1, reg1E = 0x21)
        assertTrue(statusPalM.hasSyncLock)
        assertTrue(statusPalM.is60Hz)
        assertTrue(statusPalM.isInterlaced)
        assertTrue(statusPalM.colorDetected)
        assertEquals(Saa7113ColorSystem.PAL, statusPalM.colorSystem)
        assertEquals(
            AnalogVideoStandard.PAL_M,
            Stk1160Protocol.detectVideoStandard(CameraVideoStandardMode.AUTO, statusPalM)
        )

        // 60 Hz + STDC=NTSC (0x10 em reg 1E) => NTSC (720x480)
        val statusNtsc = Stk1160Protocol.parseSaa7113Status(reg1F = 0xA1, reg1E = 0x11)
        assertEquals(
            AnalogVideoStandard.NTSC,
            Stk1160Protocol.detectVideoStandard(CameraVideoStandardMode.AUTO, statusNtsc)
        )

        // 50 Hz + STDC=PAL (0x20 em reg 1E) => PAL (720x576)
        val statusPal = Stk1160Protocol.parseSaa7113Status(reg1F = 0x81, reg1E = 0x21)
        assertEquals(
            AnalogVideoStandard.PAL,
            Stk1160Protocol.detectVideoStandard(CameraVideoStandardMode.AUTO, statusPal)
        )

        // Sem reg 1E (SAA7113 clássico), 60 Hz sem trava de cor em NTSC -> alterna para PAL-M
        val statusNo1E = Stk1160Protocol.parseSaa7113Status(reg1F = 0x21, reg1E = 0x00)
        assertEquals(
            AnalogVideoStandard.PAL_M,
            Stk1160Protocol.detectVideoStandard(
                mode = CameraVideoStandardMode.AUTO,
                status = statusNo1E,
                currentStandard = AnalogVideoStandard.NTSC,
                colorBurstMissingForCurrent = true
            )
        )

        // Sem reg 1E, 50 Hz sem trava de cor em PAL -> alterna para PAL-N
        val status50No1E = Stk1160Protocol.parseSaa7113Status(reg1F = 0x01, reg1E = 0x00)
        assertEquals(
            AnalogVideoStandard.PAL_N,
            Stk1160Protocol.detectVideoStandard(
                mode = CameraVideoStandardMode.AUTO,
                status = status50No1E,
                currentStandard = AnalogVideoStandard.PAL,
                colorBurstMissingForCurrent = true
            )
        )

        // Modo manual sempre sobrescreve status
        assertEquals(
            AnalogVideoStandard.PAL_M,
            Stk1160Protocol.detectVideoStandard(CameraVideoStandardMode.PAL_M, statusPal)
        )
        assertEquals(
            AnalogVideoStandard.PAL,
            Stk1160Protocol.detectVideoStandard(CameraVideoStandardMode.PAL, statusNtsc)
        )
    }

    @Test
    fun `auto detector applies hysteresis before switching standard and keeps standard when unlocked`() {
        val detector = VideoStandardAutoDetector(
            initialStandard = AnalogVideoStandard.NTSC,
            confirmSamples = 3
        )
        val palMStatus = Stk1160Protocol.parseSaa7113Status(reg1F = 0xA1, reg1E = 0x21)
        val unlocked = Stk1160Protocol.parseSaa7113Status(reg1F = 0x40, reg1E = 0x00)

        assertEquals(AnalogVideoStandard.NTSC, detector.onStatusSample(CameraVideoStandardMode.AUTO, palMStatus))
        assertEquals(AnalogVideoStandard.NTSC, detector.onStatusSample(CameraVideoStandardMode.AUTO, palMStatus))
        // Ruído/perda momentânea zera contagem
        assertEquals(AnalogVideoStandard.NTSC, detector.onStatusSample(CameraVideoStandardMode.AUTO, unlocked))
        assertEquals(AnalogVideoStandard.NTSC, detector.onStatusSample(CameraVideoStandardMode.AUTO, palMStatus))
        assertEquals(AnalogVideoStandard.NTSC, detector.onStatusSample(CameraVideoStandardMode.AUTO, palMStatus))
        // 3ª leitura consecutiva confirma troca para PAL-M
        assertEquals(AnalogVideoStandard.PAL_M, detector.onStatusSample(CameraVideoStandardMode.AUTO, palMStatus))

        // Modo manual troca imediatamente
        assertEquals(AnalogVideoStandard.PAL, detector.onStatusSample(CameraVideoStandardMode.PAL, palMStatus))
    }

    @Test
    fun `maps brightness contrast saturation levels to safe SAA7113 registers`() {
        // Nominal 50% corresponde aos defaults do datasheet SAA7113
        assertEquals(0x80, Stk1160Protocol.brightnessToSaa7113Reg0A(50))
        assertEquals(0x47, Stk1160Protocol.contrastToSaa7113Reg0B(50))
        assertEquals(0x40, Stk1160Protocol.saturationToSaa7113Reg0C(50))

        // Extremos limitados dentro de faixa segura
        assertEquals(0x48, Stk1160Protocol.brightnessToSaa7113Reg0A(-10))
        assertEquals(0xB8, Stk1160Protocol.brightnessToSaa7113Reg0A(150))
        assertEquals(0x24, Stk1160Protocol.contrastToSaa7113Reg0B(0))
        assertEquals(0x6A, Stk1160Protocol.contrastToSaa7113Reg0B(100))
        assertEquals(0x08, Stk1160Protocol.saturationToSaa7113Reg0C(0))
        assertEquals(0x78, Stk1160Protocol.saturationToSaa7113Reg0C(100))
    }

    @Test
    fun `decodes usb high-bandwidth maxPacketSize correctly`() {
        assertEquals(0, Stk1160Protocol.decodeHighBandwidthPacketSize(0x0000))
        assertEquals(1024, Stk1160Protocol.decodeHighBandwidthPacketSize(0x0400))
        assertEquals(2048, Stk1160Protocol.decodeHighBandwidthPacketSize(0x0C00))
        assertEquals(3072, Stk1160Protocol.decodeHighBandwidthPacketSize(0x1400))
    }

    @Test
    fun `ranks isochronous alt settings with optimal first and bandwidth fallbacks`() {
        val candidates = listOf(
            IsoAltSettingCandidate(altSetting = 0, endpointAddress = 0x82, rawMaxPacketSize = 0x0000),
            IsoAltSettingCandidate(altSetting = 1, endpointAddress = 0x82, rawMaxPacketSize = 0x0400), // 1024
            IsoAltSettingCandidate(altSetting = 2, endpointAddress = 0x81, rawMaxPacketSize = 0x1400), // wrong EP
            IsoAltSettingCandidate(altSetting = 3, endpointAddress = 0x82, rawMaxPacketSize = 0x0C00), // 2048
            IsoAltSettingCandidate(altSetting = 5, endpointAddress = 0x82, rawMaxPacketSize = 0x1400)  // 3072
        )

        val ranked = Stk1160Protocol.rankIsoAltSettings(candidates)
        assertEquals(listOf(5, 3, 1), ranked.map { it.altSetting })

        val chosen = Stk1160Protocol.selectBestIsoAltSetting(candidates)
        assertNotNull(chosen)
        assertEquals(5, chosen!!.altSetting)
        assertEquals(3072, Stk1160Protocol.decodeHighBandwidthPacketSize(chosen.rawMaxPacketSize))

        val empty = Stk1160Protocol.selectBestIsoAltSetting(
            listOf(IsoAltSettingCandidate(altSetting = 0, endpointAddress = 0x82, rawMaxPacketSize = 0x1400))
        )
        assertNull(empty)
    }

    @Test
    fun `parses raw usb descriptors and selects alt 5 with 3072 byte iso endpoint`() {
        val raw = byteArrayOf(
            // Interface 0 Alt 0
            9, 4, 0, 0, 1, 0xFF.toByte(), 0, 0, 0,
            7, 5, 0x82.toByte(), 0x01, 0x00, 0x00, 1,
            // Interface 0 Alt 5 with 0x1400 (3072) on EP 0x82
            9, 4, 0, 5, 1, 0xFF.toByte(), 0, 0, 0,
            7, 5, 0x82.toByte(), 0x01, 0x00, 0x14, 1,
            // Interface 1 Alt 1 (Audio EP 0x81 - must ignore)
            9, 4, 1, 1, 1, 0x01, 0x02, 0, 0,
            7, 5, 0x81.toByte(), 0x01, 0x00, 0x14, 1
        )
        val candidates = Stk1160Protocol.parseRawIsoCandidates(raw)
        assertEquals(2, candidates.size)
        val best = Stk1160Protocol.selectBestIsoAltSetting(candidates)
        assertNotNull(best)
        assertEquals(5, best!!.altSetting)
        assertEquals(3072, Stk1160Protocol.decodeHighBandwidthPacketSize(best.rawMaxPacketSize))
    }

    @Test
    fun `analog standards carry expected geometry and SAA7113 register constants`() {
        assertEquals(720, AnalogVideoStandard.NTSC.width)
        assertEquals(480, AnalogVideoStandard.NTSC.height)
        assertEquals(0x01, AnalogVideoStandard.NTSC.saa7113Reg0E)

        assertEquals(720, AnalogVideoStandard.PAL_M.width)
        assertEquals(480, AnalogVideoStandard.PAL_M.height)
        assertEquals(0x31, AnalogVideoStandard.PAL_M.saa7113Reg0E)

        assertEquals(720, AnalogVideoStandard.PAL_N.width)
        assertEquals(576, AnalogVideoStandard.PAL_N.height)
        assertEquals(0x21, AnalogVideoStandard.PAL_N.saa7113Reg0E)

        assertEquals(720, AnalogVideoStandard.PAL.width)
        assertEquals(576, AnalogVideoStandard.PAL.height)
        assertEquals(0x01, AnalogVideoStandard.PAL.saa7113Reg0E)

        assertEquals(720 * 480 * 2, Stk1160Protocol.expectedUyvyFrameBytes(AnalogVideoStandard.PAL_M))
        assertEquals(720 * 576 * 2, Stk1160Protocol.expectedUyvyFrameBytes(AnalogVideoStandard.PAL))
    }
}

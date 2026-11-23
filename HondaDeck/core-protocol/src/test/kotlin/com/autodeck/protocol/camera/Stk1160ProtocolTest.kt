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
    fun `decodes usb high-bandwidth maxPacketSize correctly`() {
        assertEquals(0, Stk1160Protocol.decodeHighBandwidthPacketSize(0x0000))
        assertEquals(1024, Stk1160Protocol.decodeHighBandwidthPacketSize(0x0400))
        assertEquals(2048, Stk1160Protocol.decodeHighBandwidthPacketSize(0x0C00))
        assertEquals(3072, Stk1160Protocol.decodeHighBandwidthPacketSize(0x1400))
    }

    @Test
    fun `selects optimal isochronous alt setting ignoring alt 0 and wrong endpoint`() {
        val candidates = listOf(
            IsoAltSettingCandidate(altSetting = 0, endpointAddress = 0x82, rawMaxPacketSize = 0x0000),
            IsoAltSettingCandidate(altSetting = 1, endpointAddress = 0x82, rawMaxPacketSize = 0x0400),
            IsoAltSettingCandidate(altSetting = 2, endpointAddress = 0x81, rawMaxPacketSize = 0x1400),
            IsoAltSettingCandidate(altSetting = 5, endpointAddress = 0x82, rawMaxPacketSize = 0x1400)
        )

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
}

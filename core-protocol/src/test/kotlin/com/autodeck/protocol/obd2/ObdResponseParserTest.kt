package com.autodeck.protocol.obd2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdResponseParserTest {

    @Test
    fun `parses coolant temperature and rpm with elm327 echo and searching noise`() {
        val coolantRaw = "SEARCHING...\r\n41 05 82\r\n>"
        val coolant = ObdResponseParser.parsePid(ObdPid.COOLANT_TEMP, coolantRaw)
        assertEquals(90.0, coolant!!, 0.01)

        val rpmRaw = "010C\r41 0C 1A F8 >"
        val rpm = ObdResponseParser.parsePid(ObdPid.RPM, rpmRaw)
        assertEquals(1726.0, rpm!!, 0.01)
    }

    @Test
    fun `parses speed, maf, map, throttle and ecu voltage`() {
        assertEquals(85.0, ObdResponseParser.parsePid(ObdPid.SPEED, "41 0D 55")!!, 0.01)
        assertEquals(12.55, ObdResponseParser.parsePid(ObdPid.MAF, "41 10 04 E7")!!, 0.01)
        assertEquals(42.0, ObdResponseParser.parsePid(ObdPid.INTAKE_MAP, "41 0B 2A")!!, 0.01)
        assertEquals(100.0, ObdResponseParser.parsePid(ObdPid.THROTTLE_POS, "41 11 FF")!!, 0.01)
        assertEquals(14.1, ObdResponseParser.parsePid(ObdPid.CONTROL_MODULE_VOLTAGE, "41 42 37 14")!!, 0.01)
    }

    @Test
    fun `returns null on ELM327 error responses`() {
        assertNull(ObdResponseParser.parsePid(ObdPid.RPM, "NO DATA\r>"))
        assertNull(ObdResponseParser.parsePid(ObdPid.COOLANT_TEMP, "?\r>"))
        assertNull(ObdResponseParser.parsePid(ObdPid.SPEED, "UNABLE TO CONNECT\r>"))
    }

    @Test
    fun `parses ATRV battery voltage in dot or comma format`() {
        assertEquals(14.2, ObdResponseParser.parseBatteryVoltage("ATRV\r14.2V\r>")!!, 0.01)
        assertEquals(12.4, ObdResponseParser.parseBatteryVoltage("12,4 V>")!!, 0.01)
        assertNull(ObdResponseParser.parseBatteryVoltage("NO DATA"))
    }

    @Test
    fun `parses DTCs containing byte 43 such as P0443 and P0143 without truncation`() {
        // 04 43 -> P0443, 01 43 -> P0143, 03 00 -> P0300
        val raw = "43 04 43 01 43 03 00\r\n>"
        val codes = ObdResponseParser.parseDtcCodes(raw)
        assertEquals(listOf("P0443", "P0143", "P0300"), codes.map { it.code })
        assertTrue(codes[0].descriptionPt.contains("EVAP", ignoreCase = true))
    }

    @Test
    fun `parses CAN ISO-TP multi-frame DTC response and pending or permanent modes`() {
        val multiFrameRaw = "00A\r\n0: 43 03 03 00 04 43\r\n1: 01 71 C1 00 00 00\r\n>"
        val codes = ObdResponseParser.parseDtcCodes(multiFrameRaw, DtcCategory.STORED)
        assertEquals(listOf("P0300", "P0443", "P0171", "U0100"), codes.map { it.code })

        val pending = ObdResponseParser.parseDtcCodes("47 01 71 00 00\r>", DtcCategory.PENDING)
        assertEquals(1, pending.size)
        assertEquals(DtcCategory.PENDING, pending[0].category)

        val permanent = ObdResponseParser.parseDtcCodes("4A 04 20 00 00\r>", DtcCategory.PERMANENT)
        assertEquals("P0420", permanent[0].code)
        assertEquals(DtcCategory.PERMANENT, permanent[0].category)
    }

    @Test
    fun `parses supported pids 0100 for MAP and MAF, MIL status 0101 and clear confirmation`() {
        // 41 00 BE 3F A8 13 -> byte1 = 0x3F -> bit5 (MAP)=1, bit0 (MAF)=1
        val capBoth = ObdResponseParser.parseSupportedPids0100("41 00 BE 3F A8 13\r>")
        assertNotNull(capBoth)
        assertTrue(capBoth!!.supportsMap)
        assertTrue(capBoth.supportsMaf)

        // byte1 = 0x20 -> MAP sim, MAF nao
        val capMapOnly = ObdResponseParser.parseSupportedPids0100("41 00 98 20 00 00")
        assertTrue(capMapOnly!!.supportsMap)
        assertFalse(capMapOnly.supportsMaf)

        val mil = ObdResponseParser.parseMilStatus0101("41 01 82 07 65 04")
        assertEquals(true to 2, mil)

        assertTrue(ObdResponseParser.isClearDtcConfirmed("44\r>"))
        assertFalse(ObdResponseParser.isClearDtcConfirmed("NO DATA\r>"))
    }
}

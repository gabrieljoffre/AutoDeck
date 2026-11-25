package com.autodeck.protocol.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Stk1160FramePipelineTest {

    private fun makeFieldPacket(
        headerByte0: Int,
        payload: ByteArray
    ): ByteArray {
        val hdr = Stk1160FrameAssembler.parsePacketHeader(headerByte0)
        val pkt = ByteArray(hdr.headerBytes + payload.size)
        pkt[0] = headerByte0.toByte()
        // Preenche cabeçalho extra com lixo conhecido para garantir que os 8 bytes de SOF sejam pulados
        for (i in 1 until hdr.headerBytes) {
            pkt[i] = 0xAA.toByte()
        }
        payload.copyInto(pkt, destinationOffset = hdr.headerBytes)
        return pkt
    }

    private fun solidUyvyField(width: Int, fieldLines: Int, u: Int, y: Int, v: Int): ByteArray {
        val bytes = ByteArray(width * 2 * fieldLines)
        var i = 0
        while (i + 3 < bytes.size) {
            bytes[i] = u.toByte()
            bytes[i + 1] = y.toByte()
            bytes[i + 2] = v.toByte()
            bytes[i + 3] = y.toByte()
            i += 4
        }
        return bytes
    }

    @Test
    fun `parses 8-byte header for new field and 4-byte header for continuation`() {
        val f0Start = Stk1160FrameAssembler.parsePacketHeader(0x80)
        assertTrue(f0Start.isNewField)
        assertEquals(0, f0Start.fieldIndex)
        assertEquals(8, f0Start.headerBytes)

        val f1Start = Stk1160FrameAssembler.parsePacketHeader(0xC0)
        assertTrue(f1Start.isNewField)
        assertEquals(1, f1Start.fieldIndex)
        assertEquals(8, f1Start.headerBytes)

        val f0Cont = Stk1160FrameAssembler.parsePacketHeader(0x00)
        assertFalse(f0Cont.isNewField)
        assertEquals(0, f0Cont.fieldIndex)
        assertEquals(4, f0Cont.headerBytes)

        val f1Cont = Stk1160FrameAssembler.parsePacketHeader(0x40)
        assertFalse(f1Cont.isNewField)
        assertEquals(1, f1Cont.fieldIndex)
        assertEquals(4, f1Cont.headerBytes)
    }

    @Test
    fun `ignores unsynchronized continuation packets and interleaves field 0 and field 1 accurately`() {
        val width = 8
        val height = 4 // 2 linhas por campo, 16 bytes por linha, 32 bytes por campo
        val assembler = Stk1160FrameAssembler(width = width, height = height)

        // Pacote solto de continuação antes de qualquer início de campo deve ser ignorado
        val orphan = makeFieldPacket(0x00, solidUyvyField(width, 2, 0x80, 0xCC, 0x80))
        assertFalse(assembler.feedPacket(orphan).frameCompleted)
        assertNull(assembler.copyLastGoodFrame())

        // Campo 0 (linhas 0 e 2) com luma 0x40
        val f0Payload = solidUyvyField(width, 2, 0x80, 0x40, 0x80)
        assertFalse(assembler.feedPacket(makeFieldPacket(0x80, f0Payload)).frameCompleted)

        // Campo 1 (linhas 1 e 3) com luma 0xB0 — cabeçalho 0xC0 fecha campo 0 (ainda falta campo 1)
        val f1Payload = solidUyvyField(width, 2, 0x80, 0xB0, 0x80)
        assertFalse(assembler.feedPacket(makeFieldPacket(0xC0, f1Payload)).frameCompleted)

        // Próximo início de campo (0x80) fecha campo 1 -> quadro entrelaçado completo!
        val res = assembler.feedPacket(makeFieldPacket(0x80, f0Payload))
        assertTrue(res.frameCompleted)
        assertFalse(res.frameDropped)
        assertFalse(res.usedSingleFieldFallback)

        val frame = assembler.copyLastGoodFrame()
        assertNotNull(frame)
        val stride = width * 2
        // Linha 0 (campo 0) -> Y = 0x40, sem contaminação do byte 0xAA do cabeçalho
        assertEquals(0x80, frame!![0].toInt() and 0xFF)
        assertEquals(0x40, frame[1].toInt() and 0xFF)
        // Linha 1 (campo 1) -> Y = 0xB0
        assertEquals(0x80, frame[stride].toInt() and 0xFF)
        assertEquals(0xB0, frame[stride + 1].toInt() and 0xFF)
        // Linha 2 (campo 0) -> Y = 0x40
        assertEquals(0x40, frame[2 * stride + 1].toInt() and 0xFF)
        // Linha 3 (campo 1) -> Y = 0xB0
        assertEquals(0xB0, frame[3 * stride + 1].toInt() and 0xFF)
    }

    @Test
    fun `drops incomplete or corrupted fields and preserves last good frame without green or zero screen`() {
        val width = 8
        val height = 4
        val assembler = Stk1160FrameAssembler(width = width, height = height)

        val goodF0 = solidUyvyField(width, 2, 0x80, 0x60, 0x80)
        val goodF1 = solidUyvyField(width, 2, 0x80, 0x60, 0x80)
        assembler.feedPacket(makeFieldPacket(0x80, goodF0))
        assembler.feedPacket(makeFieldPacket(0xC0, goodF1))
        assertTrue(assembler.feedPacket(makeFieldPacket(0x80, goodF0)).frameCompleted)
        val baseline = assembler.copyLastGoodFrame()!!

        // Campo 0 atual recebe apenas metade dos bytes (incompleto) -> deve ser descartado ao chegar 0xC0
        val truncatedF0 = solidUyvyField(width, 1, 0x80, 0xE0, 0x80)
        assembler.feedPacket(makeFieldPacket(0x80, truncatedF0))
        val dropRes = assembler.feedPacket(makeFieldPacket(0xC0, goodF1))
        assertFalse(dropRes.frameCompleted)
        assertTrue(dropRes.frameDropped)
        assertTrue(assembler.droppedFields >= 1L)

        // Pacote de continuação com erro isócrono ou tamanho ímpar também descarta o campo
        assembler.onIsoPacketError()
        val oddPacket = ByteArray(4 + 7) // 7 bytes ímpares de payload -> truncado
        oddPacket[0] = 0x40
        assembler.feedPacket(oddPacket)
        val afterErr = assembler.feedPacket(makeFieldPacket(0x80, goodF0))
        assertFalse(afterErr.frameCompleted)
        assertTrue(afterErr.frameDropped)

        // Último quadro bom permanece intacto (nunca retorna buffer vazio/zerado)
        val preserved = assembler.copyLastGoodFrame()!!
        assertTrue(baseline.contentEquals(preserved))
    }

    @Test
    fun `rejects all-zero UYVY frame that would render green`() {
        val width = 8
        val height = 4
        val assembler = Stk1160FrameAssembler(width = width, height = height)
        val allZeroField = ByteArray(width * 2 * 2) // tudo 0x00

        assembler.feedPacket(makeFieldPacket(0x80, allZeroField))
        assembler.feedPacket(makeFieldPacket(0xC0, allZeroField))
        val res = assembler.feedPacket(makeFieldPacket(0x80, allZeroField))

        assertFalse(res.frameCompleted)
        assertTrue(res.frameDropped)
        assertFalse(assembler.hasGoodFrame)
        assertNull(assembler.copyLastGoodFrame())
        assertTrue(UyvyColorConverter.isEffectivelyAllZero(allZeroField))
    }

    @Test
    fun `supports progressive single-field camera via automatic bob line doubling`() {
        val width = 8
        val height = 4
        val assembler = Stk1160FrameAssembler(width = width, height = height)
        val f0 = solidUyvyField(width, 2, 0x80, 0x90, 0x80)

        assertFalse(assembler.feedPacket(makeFieldPacket(0x80, f0)).frameCompleted)
        assertFalse(assembler.feedPacket(makeFieldPacket(0x80, f0)).frameCompleted)
        val res = assembler.feedPacket(makeFieldPacket(0x80, f0))
        assertTrue(res.frameCompleted)
        assertTrue(res.usedSingleFieldFallback)

        val frame = assembler.copyLastGoodFrame()!!
        val stride = width * 2
        // Todas as 4 linhas preenchidas com Y = 0x90 (linhas ímpares interpoladas)
        for (y in 0 until height) {
            assertEquals(0x90, frame[y * stride + 1].toInt() and 0xFF)
        }
    }

    @Test
    fun `deinterlacer eliminates motion combing via bob while keeping static detail smooth`() {
        val width = 4
        val height = 4
        val stride = width * 2
        val interlaced = ByteArray(stride * height)

        // Campo 0 (linhas 0 e 2): cinza escuro Y=40
        // Campo 1 (linhas 1 e 3): movimento brusco Y=220 (diferença = 180 > threshold 24)
        for (y in 0 until height) {
            val luma = if (y % 2 == 0) 40 else 220
            for (x in 0 until stride step 4) {
                interlaced[y * stride + x] = 128.toByte()
                interlaced[y * stride + x + 1] = luma.toByte()
                interlaced[y * stride + x + 2] = 128.toByte()
                interlaced[y * stride + x + 3] = luma.toByte()
            }
        }

        val deint = UyvyDeinterlacer.deinterlaceBobAdaptive(interlaced, width, height, motionThreshold = 24)
        // Linha 1 (entre linha 0=40 e linha 2=40) deve ser substituída por bob = 40 (sem efeito pente)
        assertEquals(40, deint[1 * stride + 1].toInt() and 0xFF)
    }

    @Test
    fun `converts BT601 UYVY black white red green blue accurately and proves all-zero buffer causes green`() {
        fun singlePairRgba(u: Int, y0: Int, v: Int, y1: Int): IntArray {
            val uyvy = byteArrayOf(u.toByte(), y0.toByte(), v.toByte(), y1.toByte())
            return UyvyColorConverter.uyvyToRgba8888(uyvy, width = 2, height = 1)
        }

        fun r(px: Int) = px and 0xFF
        fun g(px: Int) = (px ushr 8) and 0xFF
        fun b(px: Int) = (px ushr 16) and 0xFF
        fun a(px: Int) = (px ushr 24) and 0xFF

        // Preto UYVY (U=128, Y=16, V=128) -> RGB(0, 0, 0)
        val black = singlePairRgba(128, 16, 128, 16)[0]
        assertEquals(255, a(black))
        assertEquals(0, r(black))
        assertEquals(0, g(black))
        assertEquals(0, b(black))

        // Branco UYVY (U=128, Y=235, V=128) -> RGB(255, 255, 255)
        val white = singlePairRgba(128, 235, 128, 235)[0]
        assertEquals(255, r(white))
        assertEquals(255, g(white))
        assertEquals(255, b(white))

        // Vermelho BT.601 (Y=81, U=90, V=240) -> R alto, G/B baixos
        val red = singlePairRgba(90, 81, 240, 81)[0]
        assertTrue("R deveria ser alto: ${r(red)}", r(red) > 235)
        assertTrue("G deveria ser baixo: ${g(red)}", g(red) < 20)
        assertTrue("B deveria ser baixo: ${b(red)}", b(red) < 20)

        // Azul BT.601 (Y=41, U=240, V=110) -> B alto, R/G baixos
        val blue = singlePairRgba(240, 41, 110, 41)[0]
        assertTrue("B deveria ser alto: ${b(blue)}", b(blue) > 235)
        assertTrue("R deveria ser baixo: ${r(blue)}", r(blue) < 20)
        assertTrue("G deveria ser baixo: ${g(blue)}", g(blue) < 20)

        // Buffer zerado (0,0,0,0) é o causador clássico da tela verde em YUV -> confirmado e bloqueado
        val zeroBug = singlePairRgba(0, 0, 0, 0)[0]
        assertEquals(0, r(zeroBug))
        assertTrue("Buffer zerado produz verde (G=${g(zeroBug)})", g(zeroBug) > 120)
        assertEquals(0, b(zeroBug))
        assertTrue(UyvyColorConverter.isEffectivelyAllZero(ByteArray(16)))
        assertFalse(UyvyColorConverter.isEffectivelyAllZero(Stk1160FrameAssembler.createUyvyBlackBuffer(2, 2)))
    }
}

package com.autodeck.protocol.camera

import kotlin.math.abs

/**
 * Cabeçalho de um pacote isócrono recebido do endpoint `0x82` do Syntek STK1160.
 *
 * No protocolo STK1160:
 * - Bit 7 (`0x80`): início de novo campo (cabeçalho tem **8 bytes**; pacotes de continuação têm **4 bytes**).
 * - Bit 6 (`0x40`): identifica o campo (`0` = campo 0 / linhas pares 0, 2, 4...; `1` = campo 1 / linhas ímpares 1, 3, 5...).
 */
data class Stk1160PacketHeader(
    val rawByte0: Int,
    val isNewField: Boolean,
    val fieldIndex: Int,
    val headerBytes: Int
)

/**
 * Resultado da ingestão de um pacote isócrono pelo montador de quadros.
 */
data class FrameAssemblyResult(
    val frameCompleted: Boolean,
    val frameDropped: Boolean,
    val usedSingleFieldFallback: Boolean = false
)

/**
 * Montador puro de quadros entrelaçados UYVY para o fluxo isócrono do STK1160.
 *
 * Regras de estabilidade (espelhadas em `stk1160_jni.c`):
 * 1. Só inicia gravação após um cabeçalho explícito de início de campo (`bit 7 == 1`).
 * 2. Pula 8 bytes em pacotes de início de campo e 4 bytes em pacotes de continuação.
 * 3. Rejeita pacotes truncados/desalinhados ou com erro isócrono, descartando o campo corrompido.
 * 4. Só promove quadro quando ambos os campos (0 e 1) atingem cobertura mínima (`minFieldCoverage`),
 *    com fallback automático para câmeras progressivas/campo único (2 campos seguidos do mesmo índice).
 * 5. Inicializa buffers em preto UYVY (`0x80, 0x10, 0x80, 0x10`), nunca em zeros (`0x00` gera verde),
 *    e guarda `lastGoodFrame` para repetir o último quadro bom sem piscar nem exibir buffer vazio.
 */
class Stk1160FrameAssembler(
    val width: Int,
    val height: Int,
    private val minFieldCoverage: Double = 0.90
) {
    val bytesPerLine: Int = width * 2
    val fieldLines: Int = height / 2
    val fieldBytes: Int = bytesPerLine * fieldLines
    val frameBytes: Int = bytesPerLine * height
    private val minValidFieldBytes: Int = (fieldBytes * minFieldCoverage).toInt().coerceAtLeast(bytesPerLine)

    private val stagingFrame: ByteArray = createUyvyBlackBuffer(width, height)
    private val lastGoodFrame: ByteArray = createUyvyBlackBuffer(width, height)

    var hasGoodFrame: Boolean = false
        private set
    var completedFrames: Long = 0L
        private set
    var droppedFields: Long = 0L
        private set
    var packetErrors: Long = 0L
        private set

    private var syncedToField: Boolean = false
    private var currentField: Int = 0
    private var fieldPos: Int = 0
    private var fieldCorrupted: Boolean = false
    private var field0Ready: Boolean = false
    private var field1Ready: Boolean = false
    private var lastCompletedField: Int = -1
    private var sameFieldStreak: Int = 0

    fun reset(clearLastGood: Boolean = false) {
        fillUyvyBlack(stagingFrame)
        syncedToField = false
        currentField = 0
        fieldPos = 0
        fieldCorrupted = false
        field0Ready = false
        field1Ready = false
        lastCompletedField = -1
        sameFieldStreak = 0
        if (clearLastGood) {
            fillUyvyBlack(lastGoodFrame)
            hasGoodFrame = false
        }
    }

    /**
     * Retorna cópia do último quadro bom (ou `null` se nenhum quadro válido foi montado ainda).
     */
    fun copyLastGoodFrame(): ByteArray? = if (hasGoodFrame) lastGoodFrame.copyOf() else null

    /**
     * Registra erro de pacote isócrono (status != 0 no URB), invalidando o campo atual.
     */
    fun onIsoPacketError() {
        packetErrors++
        if (syncedToField) {
            fieldCorrupted = true
        }
    }

    /**
     * Processa um pacote bruto recebido do endpoint ISO `0x82`.
     */
    fun feedPacket(packet: ByteArray, length: Int = packet.size): FrameAssemblyResult {
        if (length < 4 || length > packet.size) {
            return FrameAssemblyResult(frameCompleted = false, frameDropped = false)
        }

        val header = parsePacketHeader(packet[0].toInt() and 0xFF)
        var frameCompleted = false
        var frameDropped = false
        var singleFieldFallback = false

        if (header.isNewField) {
            if (syncedToField) {
                val fieldValid = !fieldCorrupted && fieldPos >= minValidFieldBytes
                if (fieldValid) {
                    if (currentField == 0) {
                        field0Ready = true
                    } else {
                        field1Ready = true
                    }

                    if (currentField == lastCompletedField) {
                        sameFieldStreak++
                    } else {
                        sameFieldStreak = 1
                    }
                    lastCompletedField = currentField

                    if (field0Ready && field1Ready) {
                        if (!UyvyColorConverter.isEffectivelyAllZero(stagingFrame)) {
                            stagingFrame.copyInto(lastGoodFrame)
                            hasGoodFrame = true
                            completedFrames++
                            frameCompleted = true
                        } else {
                            droppedFields++
                            frameDropped = true
                        }
                        field0Ready = false
                        field1Ready = false
                    } else if (sameFieldStreak >= 2) {
                        // Câmera progressiva/não-entrelaçada que emite apenas um índice de campo
                        UyvyDeinterlacer.duplicateSingleFieldInPlace(
                            uyvy = stagingFrame,
                            width = width,
                            height = height,
                            sourceField = currentField
                        )
                        if (!UyvyColorConverter.isEffectivelyAllZero(stagingFrame)) {
                            stagingFrame.copyInto(lastGoodFrame)
                            hasGoodFrame = true
                            completedFrames++
                            frameCompleted = true
                            singleFieldFallback = true
                        } else {
                            droppedFields++
                            frameDropped = true
                        }
                        field0Ready = false
                        field1Ready = false
                    }
                } else {
                    droppedFields++
                    frameDropped = true
                    if (currentField == 0) field0Ready = false else field1Ready = false
                }
            }

            syncedToField = true
            currentField = header.fieldIndex
            fieldPos = 0
            fieldCorrupted = false
        } else {
            if (!syncedToField) {
                return FrameAssemblyResult(frameCompleted = false, frameDropped = false)
            }
            if (header.fieldIndex != currentField) {
                fieldCorrupted = true
                return FrameAssemblyResult(frameCompleted = false, frameDropped = false)
            }
        }

        val payloadOffset = header.headerBytes
        val payloadLen = length - payloadOffset
        if (payloadLen <= 0) {
            return FrameAssemblyResult(frameCompleted, frameDropped, singleFieldFallback)
        }

        // Em UYVY cada pixel ocupa 2 bytes e cada macropixel 4 bytes; comprimento ímpar
        // indica truncamento USB que deslocaria U/Y/V/Y e deixaria a imagem verde.
        if ((payloadLen and 0x01) != 0) {
            packetErrors++
            fieldCorrupted = true
            return FrameAssemblyResult(frameCompleted, frameDropped, singleFieldFallback)
        }

        if (fieldCorrupted) {
            return FrameAssemblyResult(frameCompleted, frameDropped, singleFieldFallback)
        }

        copyFieldPayload(packet, payloadOffset, payloadLen)
        return FrameAssemblyResult(frameCompleted, frameDropped, singleFieldFallback)
    }

    private fun copyFieldPayload(packet: ByteArray, startOffset: Int, payloadLen: Int) {
        var srcPos = startOffset
        var remaining = payloadLen
        val fieldRowOffset = if (currentField == 0) 0 else bytesPerLine

        while (remaining > 0) {
            val linesDone = fieldPos / bytesPerLine
            val lineOff = fieldPos % bytesPerLine
            if (linesDone >= fieldLines) break

            val availInLine = bytesPerLine - lineOff
            val toCopy = minOf(remaining, availInLine)
            val dstOffset = fieldRowOffset + linesDone * bytesPerLine * 2 + lineOff
            if (dstOffset + toCopy > frameBytes) break

            packet.copyInto(
                destination = stagingFrame,
                destinationOffset = dstOffset,
                startIndex = srcPos,
                endIndex = srcPos + toCopy
            )
            srcPos += toCopy
            remaining -= toCopy
            fieldPos += toCopy
        }
    }

    companion object {
        fun parsePacketHeader(byte0: Int): Stk1160PacketHeader {
            val masked = byte0 and 0xFF
            val isNewField = (masked and 0x80) != 0
            val fieldIndex = if ((masked and 0x40) != 0) 1 else 0
            val headerBytes = if (isNewField) 8 else 4
            return Stk1160PacketHeader(
                rawByte0 = masked,
                isNewField = isNewField,
                fieldIndex = fieldIndex,
                headerBytes = headerBytes
            )
        }

        /** Preenche buffer com preto ITU-R BT.601 UYVY (`U=128, Y=16, V=128, Y=16`). */
        fun fillUyvyBlack(buffer: ByteArray) {
            var i = 0
            while (i + 3 < buffer.size) {
                buffer[i] = 0x80.toByte()
                buffer[i + 1] = 0x10.toByte()
                buffer[i + 2] = 0x80.toByte()
                buffer[i + 3] = 0x10.toByte()
                i += 4
            }
        }

        fun createUyvyBlackBuffer(width: Int, height: Int): ByteArray {
            val buf = ByteArray(width * height * 2)
            fillUyvyBlack(buf)
            return buf
        }
    }
}

/**
 * Desentrelaçador leve para quadros UYVY entrelaçados da câmera de ré.
 *
 * Combina bob adaptativo ao movimento (interpolação vertical das linhas do campo 0 quando há
 * diferença grande de luminância entre os campos, eliminando efeito "pente"/tremor) com filtro
 * vertical suave 1-2-1 em áreas estáticas.
 */
object UyvyDeinterlacer {

    /**
     * Gera um quadro UYVY desentrelaçado a partir de um quadro entrelaçado (`width x height`).
     */
    fun deinterlaceBobAdaptive(
        interlacedUyvy: ByteArray,
        width: Int,
        height: Int,
        motionThreshold: Int = 24
    ): ByteArray {
        val stride = width * 2
        val totalBytes = stride * height
        require(interlacedUyvy.size >= totalBytes) { "Buffer UYVY menor que width*height*2" }
        val out = ByteArray(totalBytes)
        if (height <= 1) {
            interlacedUyvy.copyInto(out, 0, 0, totalBytes)
            return out
        }

        // Linhas pares (campo 0) preservadas como âncora espacial sem deslocamento vertical
        var y = 0
        while (y < height) {
            val rowStart = y * stride
            interlacedUyvy.copyInto(out, rowStart, rowStart, rowStart + stride)
            y += 2
        }

        // Linhas ímpares (campo 1): se houver movimento usa bob (média de y-1 e y+1); se estático, blend 1-2-1
        y = 1
        while (y < height) {
            val prevRow = (y - 1) * stride
            val currRow = y * stride
            val nextRow = minOf(y + 1, height - 1) * stride

            var x = 0
            while (x + 3 < stride) {
                val y0Above = interlacedUyvy[prevRow + x + 1].toInt() and 0xFF
                val y0Curr = interlacedUyvy[currRow + x + 1].toInt() and 0xFF
                val y0Below = interlacedUyvy[nextRow + x + 1].toInt() and 0xFF
                val y1Above = interlacedUyvy[prevRow + x + 3].toInt() and 0xFF
                val y1Curr = interlacedUyvy[currRow + x + 3].toInt() and 0xFF
                val y1Below = interlacedUyvy[nextRow + x + 3].toInt() and 0xFF

                val y0Bob = (y0Above + y0Below + 1) ushr 1
                val y1Bob = (y1Above + y1Below + 1) ushr 1
                val diff = maxOf(abs(y0Curr - y0Bob), abs(y1Curr - y1Bob))

                val uAbove = interlacedUyvy[prevRow + x].toInt() and 0xFF
                val uCurr = interlacedUyvy[currRow + x].toInt() and 0xFF
                val uBelow = interlacedUyvy[nextRow + x].toInt() and 0xFF
                val vAbove = interlacedUyvy[prevRow + x + 2].toInt() and 0xFF
                val vCurr = interlacedUyvy[currRow + x + 2].toInt() and 0xFF
                val vBelow = interlacedUyvy[nextRow + x + 2].toInt() and 0xFF

                if (diff > motionThreshold) {
                    // Movimento detectado: bob puro (interpolação linear do campo 0) sem "pente"
                    out[currRow + x] = ((uAbove + uBelow + 1) ushr 1).toByte()
                    out[currRow + x + 1] = y0Bob.toByte()
                    out[currRow + x + 2] = ((vAbove + vBelow + 1) ushr 1).toByte()
                    out[currRow + x + 3] = y1Bob.toByte()
                } else {
                    // Cena estática: filtro vertical suave (1-2-1)/4 para zero tremor mantendo nitidez
                    out[currRow + x] = ((uAbove + (uCurr shl 1) + uBelow + 2) ushr 2).toByte()
                    out[currRow + x + 1] = ((y0Above + (y0Curr shl 1) + y0Below + 2) ushr 2).toByte()
                    out[currRow + x + 2] = ((vAbove + (vCurr shl 1) + vBelow + 2) ushr 2).toByte()
                    out[currRow + x + 3] = ((y1Above + (y1Curr shl 1) + y1Below + 2) ushr 2).toByte()
                }
                x += 4
            }
            y += 2
        }

        return out
    }

    /**
     * Reconstrói o campo ausente por interpolação vertical (bob / linha dupla) para câmeras
     * não-entrelaçadas que emitem apenas um índice de campo.
     */
    fun duplicateSingleFieldInPlace(
        uyvy: ByteArray,
        width: Int,
        height: Int,
        sourceField: Int
    ) {
        val stride = width * 2
        if (height < 2) return
        val targetStart = if (sourceField == 0) 1 else 0
        var y = targetStart
        while (y < height) {
            val prevY = (y - 1).coerceAtLeast(0)
            val nextY = (y + 1).coerceAtMost(height - 1)
            val prevOff = prevY * stride
            val nextOff = nextY * stride
            val dstOff = y * stride

            if (prevY == y) {
                uyvy.copyInto(uyvy, dstOff, nextOff, nextOff + stride)
            } else if (nextY == y) {
                uyvy.copyInto(uyvy, dstOff, prevOff, prevOff + stride)
            } else {
                for (i in 0 until stride) {
                    val a = uyvy[prevOff + i].toInt() and 0xFF
                    val b = uyvy[nextOff + i].toInt() and 0xFF
                    uyvy[dstOff + i] = ((a + b + 1) ushr 1).toByte()
                }
            }
            y += 2
        }
    }
}

/**
 * Conversão BT.601 UYVY (Cb, Y0, Cr, Y1) -> RGBA_8888 (packed little-endian `0xAABBGGRR`,
 * idêntico ao formato `WINDOW_FORMAT_RGBA_8888` do `ANativeWindow` no Android) e detecção de buffer zerado.
 */
object UyvyColorConverter {

    /**
     * Converte um buffer UYVY (`width * height * 2` bytes) em pixels RGBA_8888 (`width * height` ints),
     * onde cada int é `0xFF000000 | (B << 16) | (G << 8) | R`.
     */
    fun uyvyToRgba8888(uyvy: ByteArray, width: Int, height: Int): IntArray {
        val stride = width * 2
        require(uyvy.size >= stride * height) { "Buffer UYVY menor que width*height*2" }
        val out = IntArray(width * height)

        for (y in 0 until height) {
            val rowSrc = y * stride
            val rowDst = y * width
            var x = 0
            var s = rowSrc
            while (x + 1 < width) {
                val u = (uyvy[s].toInt() and 0xFF) - 128
                val y0 = (uyvy[s + 1].toInt() and 0xFF) - 16
                val v = (uyvy[s + 2].toInt() and 0xFF) - 128
                val y1 = (uyvy[s + 3].toInt() and 0xFF) - 16
                s += 4

                val c0 = 298 * y0
                val c1 = 298 * y1
                val rUv = 409 * v + 128
                val gUv = -100 * u - 208 * v + 128
                val bUv = 516 * u + 128

                val r0 = clampU8((c0 + rUv) shr 8)
                val g0 = clampU8((c0 + gUv) shr 8)
                val b0 = clampU8((c0 + bUv) shr 8)

                val r1 = clampU8((c1 + rUv) shr 8)
                val g1 = clampU8((c1 + gUv) shr 8)
                val b1 = clampU8((c1 + bUv) shr 8)

                out[rowDst + x] = (0xFF shl 24) or (b0 shl 16) or (g0 shl 8) or r0
                out[rowDst + x + 1] = (0xFF shl 24) or (b1 shl 16) or (g1 shl 8) or r1
                x += 2
            }
        }
        return out
    }

    /**
     * Detecta buffer completamente zerado (`0x00`), que em YUV BT.601 (U=0, Y=0, V=0)
     * renderizaria uma tela verde sólida (`RGB(0, 135, 0)`).
     */
    fun isEffectivelyAllZero(uyvy: ByteArray): Boolean {
        if (uyvy.isEmpty()) return true
        val step = maxOf(1, uyvy.size / 256)
        var i = 0
        while (i < uyvy.size) {
            if (uyvy[i].toInt() != 0) return false
            i += step
        }
        return uyvy[uyvy.lastIndex].toInt() == 0
    }

    private fun clampU8(v: Int): Int = when {
        v < 0 -> 0
        v > 255 -> 255
        else -> v
    }
}

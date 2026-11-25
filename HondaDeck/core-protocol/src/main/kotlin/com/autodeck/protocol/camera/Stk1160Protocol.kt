package com.autodeck.protocol.camera

import kotlin.math.roundToInt

/**
 * Padrão analógico efetivamente configurado no decodificador SAA7113/GM7113C + STK1160.
 *
 * No Brasil o padrão mais comum em câmeras analógicas é PAL-M (60 Hz, 525 linhas, 720x480,
 * subportadora 3,5756 MHz -> reg 0x0E = 0x31) ou NTSC (60 Hz, 720x480 -> reg 0x0E = 0x01).
 * Câmeras europeias/chinesas podem emitir PAL (50 Hz, 625 linhas, 720x576 -> reg 0x0E = 0x01)
 * ou PAL-N (50 Hz, 720x576 -> reg 0x0E = 0x21).
 */
enum class AnalogVideoStandard(
    val nativeId: Int,
    val width: Int,
    val height: Int,
    val is60Hz: Boolean,
    val nominalFps: Int,
    val saa7113Reg0E: Int,
    val saa7113Reg08Manual: Int,
    val shortLabel: String,
    val label: String
) {
    NTSC(
        nativeId = 0,
        width = 720,
        height = 480,
        is60Hz = true,
        nominalFps = 30,
        saa7113Reg0E = 0x01,
        saa7113Reg08Manual = 0x58,
        shortLabel = "NTSC",
        label = "NTSC (720x480 · 60Hz)"
    ),
    PAL_M(
        nativeId = 1,
        width = 720,
        height = 480,
        is60Hz = true,
        nominalFps = 30,
        saa7113Reg0E = 0x31,
        saa7113Reg08Manual = 0x58,
        shortLabel = "PAL-M",
        label = "PAL-M (720x480 · 60Hz)"
    ),
    PAL_N(
        nativeId = 2,
        width = 720,
        height = 576,
        is60Hz = false,
        nominalFps = 25,
        saa7113Reg0E = 0x21,
        saa7113Reg08Manual = 0x18,
        shortLabel = "PAL-N",
        label = "PAL-N (720x576 · 50Hz)"
    ),
    PAL(
        nativeId = 3,
        width = 720,
        height = 576,
        is60Hz = false,
        nominalFps = 25,
        saa7113Reg0E = 0x01,
        saa7113Reg08Manual = 0x18,
        shortLabel = "PAL",
        label = "PAL (720x576 · 50Hz)"
    );

    /** Compatibilidade com código que diferencia 525 linhas (60 Hz) vs 625 linhas (50 Hz). */
    val isNtsc: Boolean
        get() = is60Hz

    val is525Lines: Boolean
        get() = is60Hz

    companion object {
        fun fromNativeId(id: Int): AnalogVideoStandard =
            entries.firstOrNull { it.nativeId == id } ?: NTSC
    }
}

/**
 * Preferência do usuário para seleção do padrão de vídeo da câmera de ré.
 */
enum class CameraVideoStandardMode(
    val labelPt: String,
    val forcedStandard: AnalogVideoStandard?
) {
    AUTO("Automático", null),
    NTSC("NTSC", AnalogVideoStandard.NTSC),
    PAL_M("PAL-M", AnalogVideoStandard.PAL_M),
    PAL_N("PAL-N", AnalogVideoStandard.PAL_N),
    PAL("PAL", AnalogVideoStandard.PAL);

    companion object {
        fun parse(raw: String?): CameraVideoStandardMode {
            val normalized = raw?.trim()?.uppercase()?.replace('-', '_') ?: return AUTO
            return entries.firstOrNull { it.name == normalized } ?: AUTO
        }
    }
}

/**
 * Ajustes de imagem da câmera de ré na escala de UI `0..100` (50 = nominal do SAA7113).
 */
data class CameraImageAdjustments(
    val brightness: Int = DEFAULT_LEVEL,
    val contrast: Int = DEFAULT_LEVEL,
    val saturation: Int = DEFAULT_LEVEL
) {
    fun clamped(): CameraImageAdjustments = CameraImageAdjustments(
        brightness = brightness.coerceIn(0, 100),
        contrast = contrast.coerceIn(0, 100),
        saturation = saturation.coerceIn(0, 100)
    )

    companion object {
        const val DEFAULT_LEVEL = 50
    }
}

enum class Saa7113ColorSystem {
    NONE,
    NTSC,
    PAL,
    SECAM
}

data class IsoAltSettingCandidate(
    val altSetting: Int,
    val endpointAddress: Int,
    val rawMaxPacketSize: Int
)

data class Saa7113SignalStatus(
    val rawReg1F: Int,
    val isReadyForCapture: Boolean,
    val horizontalUnlocked: Boolean,
    val is60Hz: Boolean,
    val isInterlaced: Boolean = false,
    val rawReg1E: Int = 0,
    val colorDetected: Boolean = false,
    val colorSystem: Saa7113ColorSystem = Saa7113ColorSystem.NONE
) {
    /**
     * SAA7113/GM7113 status 0x1F: bit 0 (0x01) = loops internos travados (RDCAP),
     * bit 6 (0x40) = perda de sincronismo horizontal/vertical (0 = travado, 1 = sem sinal).
     */
    val hasSyncLock: Boolean
        get() = isReadyForCapture && !horizontalUnlocked

    /**
     * Indica apenas o travamento horizontal/vertical (bit 6 == 0), útil para diagnóstico
     * de câmeras P&B/IR noturnas ou transições rápidas.
     */
    val hasHorizontalLock: Boolean
        get() = !horizontalUnlocked
}

/**
 * Constantes e utilitários puros do protocolo Syntek STK1160 + SAA7113/GM7113
 * para conversores EasyCap multi-RCA USB (`05E1:0408`).
 */
object Stk1160Protocol {

    const val VENDOR_ID = 0x05E1
    const val PRODUCT_ID = 0x0408

    const val SAA7113_I2C_ADDR = 0x4A
    const val SAA7113_REG_MODE0 = 0x02
    const val SAA7113_REG_SYNC_CNTL = 0x08
    const val SAA7113_REG_BRIGHTNESS = 0x0A
    const val SAA7113_REG_CONTRAST = 0x0B
    const val SAA7113_REG_SATURATION = 0x0C
    const val SAA7113_REG_CHROMA_CNTL1 = 0x0E
    const val SAA7113_REG_STATUS_1E = 0x1E
    const val SAA7113_REG_STATUS = 0x1F

    /** Reg 0x08 em modo automático: AUFD=1 (detecta 50/60 Hz automaticamente), HTC=11 (travamento rápido). */
    const val SAA7113_REG08_AUTO = 0x98

    const val ISO_ENDPOINT_ADDRESS = 0x82
    const val MIN_ISO_PACKET_SIZE = 3072
    const val MIN_FALLBACK_ISO_PACKET_SIZE = 1024

    const val VIDEO_INTERFACE_INDEX = 0

    /**
     * Valores do registrador STK1160_GCTRL (0x000) que controlam o mux analógico
     * das 4 entradas RCA (Cam 1..4) no hardware EasyCap 05E1:0408.
     */
    val GCTRL_INPUT_VALUES: IntArray = intArrayOf(0x98, 0x90, 0x88, 0x80)

    fun isStk1160Device(vendorId: Int, productId: Int): Boolean {
        return vendorId == VENDOR_ID && productId == PRODUCT_ID
    }

    fun gctrlForChannel(channel: Int): Int {
        return GCTRL_INPUT_VALUES[channel.coerceIn(0, GCTRL_INPUT_VALUES.lastIndex)]
    }

    fun parseSaa7113Status1F(reg1F: Int): Saa7113SignalStatus =
        parseSaa7113Status(reg1F = reg1F, reg1E = 0)

    /**
     * Interpreta os registradores de status do SAA7113/GM7113C:
     * - `0x1F`: bit 0 = RDCAP (pronto para captura), bit 5 = FIDT (1=60Hz, 0=50Hz),
     *   bit 6 = HLVLN (1=sem sincronismo), bit 7 = INTL (entrelaçado).
     * - `0x1E` (GM7113C/SAA7114/SAA7115): bit 0 = CODE (cor detectada),
     *   bits 5..4 = STDC (00=sem cor, 01=NTSC, 10=PAL, 11=SECAM).
     */
    fun parseSaa7113Status(reg1F: Int, reg1E: Int = 0): Saa7113SignalStatus {
        val masked1F = reg1F and 0xFF
        val masked1E = reg1E and 0xFF
        val stdc = (masked1E ushr 4) and 0x03
        val colorSystem = when (stdc) {
            1 -> Saa7113ColorSystem.NTSC
            2 -> Saa7113ColorSystem.PAL
            3 -> Saa7113ColorSystem.SECAM
            else -> Saa7113ColorSystem.NONE
        }
        return Saa7113SignalStatus(
            rawReg1F = masked1F,
            isReadyForCapture = (masked1F and 0x01) != 0,
            horizontalUnlocked = (masked1F and 0x40) != 0,
            is60Hz = (masked1F and 0x20) != 0,
            isInterlaced = (masked1F and 0x80) != 0,
            rawReg1E = masked1E,
            colorDetected = (masked1E and 0x01) != 0 || colorSystem != Saa7113ColorSystem.NONE,
            colorSystem = colorSystem
        )
    }

    /**
     * Resolve o padrão analógico alvo com base na preferência do usuário e nos registradores
     * de status do SAA7113 (frequência 50/60 Hz + sistema de cor).
     *
     * Quando [colorBurstMissingForCurrent] é `true` no modo automático (sinal travado em H/V,
     * mas sem travamento de subportadora de cor no padrão atual), alterna o par de mesma
     * frequência (`NTSC <-> PAL_M` em 60 Hz ou `PAL <-> PAL_N` em 50 Hz).
     */
    fun detectVideoStandard(
        mode: CameraVideoStandardMode,
        status: Saa7113SignalStatus,
        currentStandard: AnalogVideoStandard = AnalogVideoStandard.NTSC,
        colorBurstMissingForCurrent: Boolean = false
    ): AnalogVideoStandard {
        mode.forcedStandard?.let { return it }
        if (!status.hasSyncLock) return currentStandard

        return if (status.is60Hz) {
            when (status.colorSystem) {
                Saa7113ColorSystem.PAL -> AnalogVideoStandard.PAL_M
                Saa7113ColorSystem.NTSC -> AnalogVideoStandard.NTSC
                else -> {
                    val base = if (currentStandard.is60Hz) currentStandard else AnalogVideoStandard.NTSC
                    if (colorBurstMissingForCurrent) {
                        if (base == AnalogVideoStandard.NTSC) AnalogVideoStandard.PAL_M else AnalogVideoStandard.NTSC
                    } else {
                        base
                    }
                }
            }
        } else {
            when (status.colorSystem) {
                Saa7113ColorSystem.PAL -> {
                    if (colorBurstMissingForCurrent) {
                        if (currentStandard == AnalogVideoStandard.PAL) AnalogVideoStandard.PAL_N else AnalogVideoStandard.PAL
                    } else if (currentStandard == AnalogVideoStandard.PAL_N) {
                        AnalogVideoStandard.PAL_N
                    } else {
                        AnalogVideoStandard.PAL
                    }
                }
                else -> {
                    val base = if (!currentStandard.is60Hz) currentStandard else AnalogVideoStandard.PAL
                    if (colorBurstMissingForCurrent) {
                        if (base == AnalogVideoStandard.PAL) AnalogVideoStandard.PAL_N else AnalogVideoStandard.PAL
                    } else {
                        base
                    }
                }
            }
        }
    }

    /**
     * Valor do registrador `0x08` (Sync Control) para o SAA7113.
     * Em modo automático ativa AUFD=1 (detecção automática de 50/60 Hz).
     */
    fun saa7113Reg08For(mode: CameraVideoStandardMode, activeStandard: AnalogVideoStandard): Int {
        return if (mode == CameraVideoStandardMode.AUTO) {
            SAA7113_REG08_AUTO
        } else {
            activeStandard.saa7113Reg08Manual
        }
    }

    /**
     * Mapeia brilho da UI (`0..100`, padrão 50) para o registrador `0x0A` do SAA7113
     * (`0x48..0xB8`, nominal `0x80` = 128).
     */
    fun brightnessToSaa7113Reg0A(level0To100: Int): Int {
        val clamped = level0To100.coerceIn(0, 100)
        return (0x48 + ((clamped / 100.0) * (0xB8 - 0x48)).roundToInt()).coerceIn(0x48, 0xB8)
    }

    /**
     * Mapeia contraste da UI (`0..100`, padrão 50) para o registrador `0x0B` do SAA7113
     * (`0x24..0x6A`, nominal `0x47` = 71).
     */
    fun contrastToSaa7113Reg0B(level0To100: Int): Int {
        val clamped = level0To100.coerceIn(0, 100)
        return (0x24 + ((clamped / 100.0) * (0x6A - 0x24)).roundToInt()).coerceIn(0x24, 0x6A)
    }

    /**
     * Mapeia saturação da UI (`0..100`, padrão 50) para o registrador `0x0C` do SAA7113
     * (`0x08..0x78`, nominal `0x40` = 64).
     */
    fun saturationToSaa7113Reg0C(level0To100: Int): Int {
        val clamped = level0To100.coerceIn(0, 100)
        return (0x08 + ((clamped / 100.0) * (0x78 - 0x08)).roundToInt()).coerceIn(0x08, 0x78)
    }

    /**
     * Decodifica `wMaxPacketSize` USB 2.0 High-Speed (bits 0..10 = tamanho base,
     * bits 11..12 = transações extras por microframe - 1).
     * Exemplo: 0x1400 -> 1024 * 3 = 3072 bytes/microframe.
     */
    fun decodeHighBandwidthPacketSize(rawMaxPacketSize: Int): Int {
        val unsigned = rawMaxPacketSize and 0xFFFF
        val baseSize = unsigned and 0x07FF
        if (baseSize == 0) return 0
        val transactionsPerMicroframe = ((unsigned ushr 11) and 0x03) + 1
        return baseSize * transactionsPerMicroframe
    }

    /**
     * Ordena todos os Alternate Settings isócronos válidos do endpoint de vídeo:
     * primeiro os que atendem [minPacketSize] (menor banda suficiente primeiro, ex.: Alt 5 = 3072 B),
     * depois os demais em ordem decrescente de pacote como fallback quando o Hub USB recusa banda (-ENOSPC).
     */
    fun rankIsoAltSettings(
        candidates: List<IsoAltSettingCandidate>,
        targetEndpoint: Int = ISO_ENDPOINT_ADDRESS,
        minPacketSize: Int = MIN_ISO_PACKET_SIZE
    ): List<IsoAltSettingCandidate> {
        val valid = candidates
            .filter { candidate ->
                candidate.altSetting > 0 &&
                    candidate.endpointAddress == targetEndpoint &&
                    decodeHighBandwidthPacketSize(candidate.rawMaxPacketSize) > 0
            }
            .distinctBy { it.altSetting }

        if (valid.isEmpty()) return emptyList()

        val meetingMinimum = valid
            .filter { decodeHighBandwidthPacketSize(it.rawMaxPacketSize) >= minPacketSize }
            .sortedBy { decodeHighBandwidthPacketSize(it.rawMaxPacketSize) }

        val fallbacks = valid
            .filter { decodeHighBandwidthPacketSize(it.rawMaxPacketSize) < minPacketSize }
            .sortedByDescending { decodeHighBandwidthPacketSize(it.rawMaxPacketSize) }

        return meetingMinimum + fallbacks
    }

    fun selectBestIsoAltSetting(
        candidates: List<IsoAltSettingCandidate>,
        targetEndpoint: Int = ISO_ENDPOINT_ADDRESS,
        minPacketSize: Int = MIN_ISO_PACKET_SIZE
    ): IsoAltSettingCandidate? {
        return rankIsoAltSettings(candidates, targetEndpoint, minPacketSize).firstOrNull()
    }

    fun expectedUyvyFrameBytes(standard: AnalogVideoStandard): Int {
        return standard.width * standard.height * 2
    }

    /**
     * Faz parsing dos descritores USB brutos (`UsbDeviceConnection.getRawDescriptors()`)
     * para obter `(bAlternateSetting, bEndpointAddress, wMaxPacketSize)` sem depender
     * de quirks do framework Android em endpoints high-bandwidth.
     */
    fun parseRawIsoCandidates(
        rawDescriptors: ByteArray,
        targetInterface: Int = VIDEO_INTERFACE_INDEX
    ): List<IsoAltSettingCandidate> {
        val result = mutableListOf<IsoAltSettingCandidate>()
        var currentInterface = -1
        var currentAltSetting = 0
        var offset = 0

        while (offset + 2 <= rawDescriptors.size) {
            val len = rawDescriptors[offset].toInt() and 0xFF
            if (len < 2 || offset + len > rawDescriptors.size) break
            val type = rawDescriptors[offset + 1].toInt() and 0xFF

            when {
                type == 0x04 && len >= 9 -> {
                    currentInterface = rawDescriptors[offset + 2].toInt() and 0xFF
                    currentAltSetting = rawDescriptors[offset + 3].toInt() and 0xFF
                }
                type == 0x05 && len >= 7 && currentInterface == targetInterface -> {
                    val epAddress = rawDescriptors[offset + 2].toInt() and 0xFF
                    val attributes = rawDescriptors[offset + 3].toInt() and 0x03
                    if (attributes == 0x01) { // Isochronous transfer type
                        val rawMaxPkt = (rawDescriptors[offset + 4].toInt() and 0xFF) or
                            ((rawDescriptors[offset + 5].toInt() and 0xFF) shl 8)
                        result += IsoAltSettingCandidate(
                            altSetting = currentAltSetting,
                            endpointAddress = epAddress,
                            rawMaxPacketSize = rawMaxPkt
                        )
                    }
                }
            }
            offset += len
        }
        return result
    }
}

/**
 * Detector com histerese para evitar trocas oscilantes de padrão de vídeo no modo Automático.
 * Exige [confirmSamples] leituras consecutivas concordantes antes de confirmar a troca.
 */
class VideoStandardAutoDetector(
    initialStandard: AnalogVideoStandard = AnalogVideoStandard.NTSC,
    private val confirmSamples: Int = 3
) {
    var activeStandard: AnalogVideoStandard = initialStandard
        private set

    private var candidateStandard: AnalogVideoStandard? = null
    private var candidateStreak: Int = 0

    fun reset(standard: AnalogVideoStandard) {
        activeStandard = standard
        candidateStandard = null
        candidateStreak = 0
    }

    /**
     * Alimenta uma nova leitura de status do SAA7113 e devolve o padrão efetivo.
     * Em modo manual aplica imediatamente sem histerese.
     */
    fun onStatusSample(
        mode: CameraVideoStandardMode,
        status: Saa7113SignalStatus,
        colorBurstMissingForCurrent: Boolean = false
    ): AnalogVideoStandard {
        val forced = mode.forcedStandard
        if (forced != null) {
            reset(forced)
            return activeStandard
        }

        if (!status.hasSyncLock) {
            candidateStandard = null
            candidateStreak = 0
            return activeStandard
        }

        val proposed = Stk1160Protocol.detectVideoStandard(
            mode = mode,
            status = status,
            currentStandard = activeStandard,
            colorBurstMissingForCurrent = colorBurstMissingForCurrent
        )

        if (proposed == activeStandard) {
            candidateStandard = null
            candidateStreak = 0
            return activeStandard
        }

        if (proposed == candidateStandard) {
            candidateStreak++
        } else {
            candidateStandard = proposed
            candidateStreak = 1
        }

        if (candidateStreak >= confirmSamples.coerceAtLeast(1)) {
            activeStandard = proposed
            candidateStandard = null
            candidateStreak = 0
        }

        return activeStandard
    }
}

package com.autodeck.protocol.camera

enum class AnalogVideoStandard(
    val width: Int,
    val height: Int,
    val isNtsc: Boolean,
    val label: String
) {
    NTSC(width = 720, height = 480, isNtsc = true, label = "NTSC (720x480)"),
    PAL(width = 720, height = 576, isNtsc = false, label = "PAL (720x576)")
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
    val is60Hz: Boolean
) {
    /**
     * SAA7113/GM7113 status 0x1F: bit 0 (0x01) = loops internos travados,
     * bit 6 (0x40) = perda de sincronismo horizontal (0 = travado, 1 = sem sinal).
     */
    val hasSyncLock: Boolean
        get() = isReadyForCapture && !horizontalUnlocked
}

/**
 * Constantes e utilitários puros do protocolo Syntek STK1160 + SAA7113/GM7113
 * para conversores EasyCap multi-RCA USB (`05E1:0408`).
 */
object Stk1160Protocol {

    const val VENDOR_ID = 0x05E1
    const val PRODUCT_ID = 0x0408

    const val SAA7113_I2C_ADDR = 0x4A
    const val SAA7113_REG_STATUS = 0x1F
    const val SAA7113_REG_MODE0 = 0x02

    const val ISO_ENDPOINT_ADDRESS = 0x82
    const val MIN_ISO_PACKET_SIZE = 3072

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

    fun parseSaa7113Status1F(reg1F: Int): Saa7113SignalStatus {
        val masked = reg1F and 0xFF
        return Saa7113SignalStatus(
            rawReg1F = masked,
            isReadyForCapture = (masked and 0x01) != 0,
            horizontalUnlocked = (masked and 0x40) != 0,
            is60Hz = (masked and 0x20) != 0
        )
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

    fun selectBestIsoAltSetting(
        candidates: List<IsoAltSettingCandidate>,
        targetEndpoint: Int = ISO_ENDPOINT_ADDRESS,
        minPacketSize: Int = MIN_ISO_PACKET_SIZE
    ): IsoAltSettingCandidate? {
        val valid = candidates.filter { candidate ->
            candidate.altSetting > 0 &&
                candidate.endpointAddress == targetEndpoint &&
                decodeHighBandwidthPacketSize(candidate.rawMaxPacketSize) > 0
        }
        if (valid.isEmpty()) return null

        val meetingMinimum = valid.filter {
            decodeHighBandwidthPacketSize(it.rawMaxPacketSize) >= minPacketSize
        }
        return if (meetingMinimum.isNotEmpty()) {
            meetingMinimum.minByOrNull { decodeHighBandwidthPacketSize(it.rawMaxPacketSize) }
        } else {
            valid.maxByOrNull { decodeHighBandwidthPacketSize(it.rawMaxPacketSize) }
        }
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

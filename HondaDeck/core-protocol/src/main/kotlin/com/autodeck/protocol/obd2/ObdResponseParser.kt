package com.autodeck.protocol.obd2

/**
 * Parser tolerante para adaptadores ELM327 Bluetooth (incluindo clones v1.5 / v2.1)
 * seguindo SAE J1979 e remontagem CAN ISO-TP multi-frame.
 */
object ObdResponseParser {

    private val ERROR_TOKENS = listOf(
        "NO DATA",
        "UNABLE TO CONNECT",
        "STOPPED",
        "CAN ERROR",
        "BUS ERROR",
        "BUFFER FULL",
        "DATA ERROR",
        "ERR"
    )

    private val VOLTAGE_REGEX = Regex("""(\d{1,2}(?:[.,]\d{1,2})?)\s*V?""")
    private val CAN_MULTI_FRAME_LINE_REGEX = Regex("""^([0-9A-F]{1,2}):(.*)$""")
    private val HEX_ONLY_REGEX = Regex("""^[0-9A-F]+$""")

    private val KNOWN_DTC_DESCRIPTIONS = mapOf(
        "P0100" to "Falha no circuito do sensor de fluxo de ar (MAF)",
        "P0105" to "Falha no circuito do sensor de pressão absoluta (MAP)",
        "P0110" to "Falha no sensor de temperatura do ar admitido (IAT)",
        "P0113" to "Entrada alta no sensor de temperatura do ar (IAT)",
        "P0115" to "Falha no sensor de temperatura do líquido de arrefecimento",
        "P0118" to "Entrada alta no sensor de temperatura do motor (ECT)",
        "P0120" to "Falha no sensor de posição da borboleta (TPS)",
        "P0130" to "Falha na sonda lambda (Banco 1, Sensor 1)",
        "P0135" to "Aquecedor da sonda lambda (Banco 1, Sensor 1)",
        "P0171" to "Mistura muito pobre (Banco 1)",
        "P0172" to "Mistura muito rica (Banco 1)",
        "P0300" to "Falha de ignição aleatória / múltiplos cilindros",
        "P0301" to "Falha de ignição detectada no Cilindro 1",
        "P0302" to "Falha de ignição detectada no Cilindro 2",
        "P0303" to "Falha de ignição detectada no Cilindro 3",
        "P0304" to "Falha de ignição detectada no Cilindro 4",
        "P0325" to "Falha no sensor de detonação (Knock Sensor)",
        "P0335" to "Falha no sensor de rotação do virabrequim (CKP)",
        "P0340" to "Falha no sensor de fase do comando (CMP)",
        "P0401" to "Fluxo insuficiente na recirculação de gases (EGR)",
        "P0420" to "Eficiência do catalisador abaixo do limite (Banco 1)",
        "P0443" to "Falha na válvula de purga do cânister (EVAP)",
        "P0500" to "Falha no sensor de velocidade do veículo (VSS)",
        "P0505" to "Falha no controle de marcha lenta (IAC)",
        "P0562" to "Tensão do sistema / bateria baixa",
        "P0563" to "Tensão do sistema / alternador alta",
        "U0100" to "Perda de comunicação com o módulo do motor (ECM/PCM)"
    )

    fun sanitize(raw: String): String {
        return raw
            .uppercase()
            .replace("SEARCHING...", " ")
            .replace("BUS INIT...", " ")
            .replace("OK", " ")
            .replace("\r", " ")
            .replace("\n", " ")
            .replace(">", " ")
            .trim()
            .replace(Regex("""\s+"""), " ")
    }

    fun isErrorResponse(cleaned: String): Boolean {
        if (cleaned.isBlank() || cleaned == "?") return true
        return ERROR_TOKENS.any { token -> cleaned.contains(token) }
    }

    fun isValidElmInitResponse(raw: String): Boolean {
        val upper = raw.uppercase()
        return upper.contains("ELM") || upper.contains("OK")
    }

    fun isClearDtcConfirmed(raw: String): Boolean {
        val cleaned = sanitize(raw).replace(" ", "")
        return cleaned.contains("44")
    }

    fun parsePid(pid: ObdPid, rawResponse: String): Double? {
        val cleaned = sanitize(rawResponse)
        if (isErrorResponse(cleaned)) return null

        val hexOnly = cleaned.replace(" ", "")
        val headerIndex = hexOnly.indexOf(pid.expectedHeader)
        if (headerIndex < 0) return null

        val dataHex = hexOnly.substring(headerIndex + pid.expectedHeader.length)
        val bytes = parseHexBytes(dataHex)
        if (bytes.isEmpty()) return null

        val a = bytes[0]
        val b = bytes.getOrNull(1)

        return when (pid) {
            ObdPid.COOLANT_TEMP,
            ObdPid.INTAKE_TEMP -> a - 40.0

            ObdPid.ENGINE_LOAD,
            ObdPid.THROTTLE_POS,
            ObdPid.FUEL_LEVEL -> a * 100.0 / 255.0

            ObdPid.STFT_BANK1,
            ObdPid.LTFT_BANK1 -> (a - 128.0) * 100.0 / 128.0

            ObdPid.INTAKE_MAP,
            ObdPid.SPEED,
            ObdPid.FUEL_SYSTEM_STATUS -> a.toDouble()

            ObdPid.O2_SENSOR_B1S1_VOLTAGE -> a * 0.005

            ObdPid.RPM -> b?.let { ((a * 256) + it) / 4.0 }
            ObdPid.TIMING_ADVANCE -> (a / 2.0) - 64.0
            ObdPid.MAF -> b?.let { ((a * 256) + it) / 100.0 }
            ObdPid.CONTROL_MODULE_VOLTAGE -> b?.let { ((a * 256) + it) / 1000.0 }
        }
    }

    /**
     * Decodifica o bitmask de PIDs suportados (0100 -> 41 00 A B C D)
     * para detectar automaticamente se a ECU fornece MAP (PID 0B) e/ou MAF (PID 10).
     */
    fun parseSupportedPids0100(rawResponse: String): SupportedPidCapabilities? {
        val cleaned = sanitize(rawResponse)
        if (isErrorResponse(cleaned)) return null

        val hexOnly = cleaned.replace(" ", "")
        val idx = hexOnly.indexOf("4100")
        if (idx < 0) return null

        val bytes = parseHexBytes(hexOnly.substring(idx + 4))
        if (bytes.size < 4) return null

        // PID 0x0B está no byte 1 (bit 5: 1 << (16 - 11) = 0x20)
        // PID 0x10 está no byte 1 (bit 0: 1 << (16 - 16) = 0x01)
        val supportsMap = (bytes[1] and 0x20) != 0
        val supportsMaf = (bytes[1] and 0x01) != 0
        return SupportedPidCapabilities(supportsMap = supportsMap, supportsMaf = supportsMaf)
    }

    /**
     * Decodifica PID 0101 -> (MIL acesa, quantidade de DTCs confirmados).
     */
    fun parseMilStatus0101(rawResponse: String): Pair<Boolean, Int>? {
        val cleaned = sanitize(rawResponse)
        if (isErrorResponse(cleaned)) return null

        val hexOnly = cleaned.replace(" ", "")
        val idx = hexOnly.indexOf("4101")
        if (idx < 0) return null

        val bytes = parseHexBytes(hexOnly.substring(idx + 4))
        if (bytes.isEmpty()) return null

        val a = bytes[0]
        val milOn = (a and 0x80) != 0
        val dtcCount = a and 0x7F
        return milOn to dtcCount
    }

    fun parseBatteryVoltage(rawResponse: String): Double? {
        val cleaned = sanitize(rawResponse)
        if (isErrorResponse(cleaned)) return null
        val match = VOLTAGE_REGEX.find(cleaned) ?: return null
        val numeric = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        return if (numeric in 5.0..32.0) numeric else null
    }

    /**
     * Decodifica falhas DTC (Mode 03/07/0A) com suporte completo a:
     * - CAN ISO-TP multi-frame (`00A\r0: 43 ...\r1: ...`)
     * - Múltiplas linhas de ECUs diferentes
     * - Códigos que contêm '43'/'47'/'4A' nos seus próprios bytes (ex: P0443, P0143)
     */
    fun parseDtcCodes(
        rawResponse: String,
        category: DtcCategory = DtcCategory.STORED
    ): List<DtcCode> {
        val rawUpper = rawResponse.uppercase()
        if (isErrorResponse(sanitize(rawUpper))) return emptyList()

        val lines = rawUpper
            .replace("SEARCHING...", "\n")
            .replace("BUS INIT...", "\n")
            .replace(">", "\n")
            .split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val isoTpFrames = mutableListOf<Pair<Int, String>>()
        val plainStreams = mutableListOf<String>()
        var isMultiFrame = false

        for (line in lines) {
            val noSpaces = line.replace(" ", "")
            val frameMatch = CAN_MULTI_FRAME_LINE_REGEX.matchEntire(noSpaces)
            if (frameMatch != null) {
                isMultiFrame = true
                val index = frameMatch.groupValues[1].toInt(16)
                isoTpFrames += index to frameMatch.groupValues[2]
            } else if (noSpaces.length in 3..4 && HEX_ONLY_REGEX.matches(noSpaces) && !noSpaces.startsWith(category.responsePrefix)) {
                // Linha de contagem de bytes ISO-TP (ex: "00A") antes de "0: 43 ..."
                continue
            } else {
                plainStreams += noSpaces
            }
        }

        val streamsToDecode = if (isMultiFrame && isoTpFrames.isNotEmpty()) {
            // Ordena pelos índices de frame e concatena o payload hex remontado
            val joined = isoTpFrames
                .sortedBy { it.first }
                .joinToString("") { it.second }
            listOf(joined)
        } else {
            plainStreams
        }

        val result = LinkedHashMap<String, DtcCode>()
        val prefix = category.responsePrefix

        for (stream in streamsToDecode) {
            val cleanHex = stream.filter { it in '0'..'9' || it in 'A'..'F' }
            val startIdx = cleanHex.indexOf(prefix)
            if (startIdx < 0) continue

            // Remove o prefixo de modo (43/47/4A) APENAS no cabeçalho inicial da mensagem remontada
            val payloadBytes = parseHexBytes(cleanHex.substring(startIdx + prefix.length))
            if (payloadBytes.isEmpty()) continue

            // Em ISO-TP/CAN o primeiro byte após 43 costuma ser o contador de DTCs (ex.: 03 seguido de 6 bytes -> tamanho ímpar)
            val dataOffset = if (isMultiFrame || (payloadBytes.size % 2 == 1 && payloadBytes.size > 1)) 1 else 0
            var i = dataOffset
            while (i + 1 < payloadBytes.size) {
                val high = payloadBytes[i]
                val low = payloadBytes[i + 1]
                if (high != 0 || low != 0) {
                    val dtc = decodeDtcPair(high, low, category)
                    result[dtc.code] = dtc
                }
                i += 2
            }
        }

        return result.values.toList()
    }

    private fun decodeDtcPair(high: Int, low: Int, category: DtcCategory): DtcCode {
        val systemIndex = (high ushr 6) and 0x03
        val system = when (systemIndex) {
            0 -> DtcSystem.POWERTRAIN
            1 -> DtcSystem.CHASSIS
            2 -> DtcSystem.BODY
            else -> DtcSystem.NETWORK
        }
        val secondDigit = (high ushr 4) and 0x03
        val thirdDigit = high and 0x0F
        val fourthDigit = (low ushr 4) and 0x0F
        val fifthDigit = low and 0x0F

        val code = buildString {
            append(system.prefix)
            append(secondDigit.toString(16).uppercase())
            append(thirdDigit.toString(16).uppercase())
            append(fourthDigit.toString(16).uppercase())
            append(fifthDigit.toString(16).uppercase())
        }
        val description = KNOWN_DTC_DESCRIPTIONS[code]
            ?: "Código genérico (${system.labelPt})"
        return DtcCode(
            code = code,
            system = system,
            descriptionPt = description,
            category = category
        )
    }

    private fun parseHexBytes(hex: String): List<Int> {
        val cleanedHex = hex.filter { it in '0'..'9' || it in 'A'..'F' }
        val count = cleanedHex.length / 2
        if (count == 0) return emptyList()
        return List(count) { index ->
            cleanedHex.substring(index * 2, index * 2 + 2).toInt(16)
        }
    }
}

package com.autodeck.protocol.obd2

data class InitCommand(
    val command: String,
    val optional: Boolean = false,
    val timeoutMs: Long = 2_000L
)

object ObdPollingPlanner {

    val INIT_SEQUENCE: List<InitCommand> = initSequence(savedProtocolCode = null)

    fun initSequence(savedProtocolCode: String?): List<InitCommand> {
        val protocolCmd = if (!savedProtocolCode.isNullOrBlank()) {
            "ATSP${savedProtocolCode.trim().uppercase()}"
        } else {
            "ATSP0"
        }
        return listOf(
            InitCommand("ATZ", optional = false, timeoutMs = 2_500L),
            InitCommand("ATE0", optional = false, timeoutMs = 1_500L),
            InitCommand("ATL0", optional = true, timeoutMs = 1_000L),
            InitCommand("ATS0", optional = true, timeoutMs = 1_000L),
            InitCommand("ATH0", optional = true, timeoutMs = 1_000L),
            InitCommand("ATAT2", optional = true, timeoutMs = 1_000L),
            InitCommand("ATST14", optional = true, timeoutMs = 1_000L),
            InitCommand(protocolCmd, optional = false, timeoutMs = 1_500L),
            // Primeiro handshake com a ECU (K-line / Fiat / VW antigos podem levar até 8-10s)
            InitCommand("0100", optional = true, timeoutMs = 10_000L)
        )
    }

    /**
     * Adiciona o sufixo "1" aos PIDs Mode 01 (ex: "010C" -> "010C1") para o ELM327
     * responder imediatamente após o 1º frame da ECU sem esperar timeout de 2ª ECU.
     */
    fun formatCommand(command: String, useSingleFrameSuffix: Boolean): String {
        val trimmed = command.trim()
        return if (useSingleFrameSuffix && trimmed.length == 4 && trimmed.startsWith("01")) {
            "${trimmed}1"
        } else {
            trimmed
        }
    }

    fun nextCommands(
        profile: ObdPollingProfile,
        cycleIndex: Long,
        preferSpeedDensity: Boolean = false
    ): List<String> {
        return nextCommands(
            profile = profile,
            cycleIndex = cycleIndex,
            capabilities = SupportedPidCapabilities(
                supportsMap = preferSpeedDensity,
                supportsMaf = !preferSpeedDensity
            )
        )
    }

    fun nextCommands(
        profile: ObdPollingProfile,
        cycleIndex: Long,
        capabilities: SupportedPidCapabilities
    ): List<String> {
        val useMaf = capabilities.supportsMaf
        return when (profile) {
            ObdPollingProfile.DOCK_BASIC -> {
                val commands = mutableListOf(
                    ObdPid.RPM.command,
                    ObdPid.SPEED.command
                )
                if (useMaf) {
                    commands += ObdPid.MAF.command
                } else {
                    commands += ObdPid.INTAKE_MAP.command
                }
                when ((cycleIndex % 10).toInt()) {
                    0 -> commands += ObdPid.COOLANT_TEMP.command
                    4 -> commands += "ATRV"
                    8 -> if (!useMaf) commands += ObdPid.INTAKE_TEMP.command
                }
                commands
            }

            ObdPollingProfile.SCANNER_DETAILED -> {
                val commands = mutableListOf(
                    ObdPid.RPM.command,
                    ObdPid.SPEED.command,
                    if (useMaf) ObdPid.MAF.command else ObdPid.INTAKE_MAP.command,
                    ObdPid.THROTTLE_POS.command
                )
                when ((cycleIndex % 6).toInt()) {
                    0 -> commands += ObdPid.COOLANT_TEMP.command
                    1 -> commands += ObdPid.ENGINE_LOAD.command
                    2 -> commands += "ATRV"
                    3 -> {
                        commands += ObdPid.INTAKE_TEMP.command
                        commands += ObdPid.TIMING_ADVANCE.command
                    }
                    4 -> {
                        commands += ObdPid.FUEL_SYSTEM_STATUS.command
                        commands += ObdPid.O2_SENSOR_B1S1_VOLTAGE.command
                    }
                    else -> {
                        commands += ObdPid.STFT_BANK1.command
                        commands += ObdPid.LTFT_BANK1.command
                        if (useMaf) {
                            commands += ObdPid.FUEL_LEVEL.command
                            commands += ObdPid.CONTROL_MODULE_VOLTAGE.command
                        }
                        if (useMaf && capabilities.supportsMap) {
                            commands += ObdPid.INTAKE_MAP.command
                        } else if (!useMaf && capabilities.supportsMaf) {
                            commands += ObdPid.MAF.command
                        }
                    }
                }
                commands
            }
        }
    }

    fun evaluateAlerts(
        snapshot: ObdSnapshot,
        thresholds: AlertThresholds = AlertThresholds(),
        lowVoltageDurationMs: Long = Long.MAX_VALUE
    ): List<ObdAlert> {
        if (snapshot.isStale) return emptyList()
        val alerts = mutableListOf<ObdAlert>()

        snapshot.coolantTempC?.let { temp ->
            when {
                temp >= thresholds.coolantCriticalC -> alerts += ObdAlert(
                    severity = AlertSeverity.CRITICAL,
                    title = "SUPERAQUECIMENTO DO MOTOR (${temp.toInt()}°C)",
                    detail = "Temperatura crítica acima de ${thresholds.coolantCriticalC.toInt()}°C. Pare em local seguro."
                )
                temp >= thresholds.coolantWarningC -> alerts += ObdAlert(
                    severity = AlertSeverity.WARNING,
                    title = "Temperatura do motor elevada (${temp.toInt()}°C)",
                    detail = "Atenção ao arrefecimento (acima de ${thresholds.coolantWarningC.toInt()}°C)."
                )
            }
        }

        snapshot.batteryVoltageV?.let { volts ->
            val engineRunning = (snapshot.rpm ?: 0.0) > 400.0
            val sustainedLow = lowVoltageDurationMs >= thresholds.lowBatterySustainMs
            when {
                volts >= thresholds.batteryHighV -> alerts += ObdAlert(
                    severity = AlertSeverity.CRITICAL,
                    title = "Sobrecarga no alternador (${"%.1f".format(volts)}V)",
                    detail = "Tensão acima de ${thresholds.batteryHighV}V pode danificar módulos e bateria."
                )
                engineRunning && volts <= thresholds.batteryLowV && sustainedLow -> alerts += ObdAlert(
                    severity = AlertSeverity.CRITICAL,
                    title = "Alternador não está carregando (${"%.1f".format(volts)}V)",
                    detail = "Motor ligado com tensão baixa por mais de ${thresholds.lowBatterySustainMs / 1000}s."
                )
                !engineRunning && volts < 11.7 && sustainedLow -> alerts += ObdAlert(
                    severity = AlertSeverity.WARNING,
                    title = "Bateria fraca (${"%.1f".format(volts)}V)",
                    detail = "Tensão baixa com motor desligado."
                )
            }
        }

        return alerts
    }

    /**
     * Backoff exponencial que sobe para 60s após ~5 falhas ou sem pós-chave para
     * evitar paging RFCOMM agressivo atrapalhando o áudio A2DP no processador Taranis.
     */
    fun reconnectDelayMs(attempt: Int, powerConnected: Boolean = true): Long {
        if (!powerConnected) return 60_000L
        return when {
            attempt <= 0 -> 2_000L
            attempt == 1 -> 4_000L
            attempt == 2 -> 8_000L
            attempt == 3 -> 15_000L
            attempt == 4 -> 30_000L
            else -> 60_000L
        }
    }
}

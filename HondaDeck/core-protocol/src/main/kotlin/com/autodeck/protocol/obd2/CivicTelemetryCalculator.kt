package com.autodeck.protocol.obd2

import kotlin.math.PI
import kotlin.math.abs

enum class TransmissionType(val labelPt: String, val forwardGears: Int) {
    MANUAL_5("Manual (5 marchas)", 5),
    AUTO_4("Automático (4 marchas)", 4)
}

data class TireSpec(
    val widthMm: Int = 195,
    val aspectRatioPct: Int = 65,
    val rimInches: Int = 15
) {
    val diameterMm: Double
        get() = rimInches * 25.4 + 2.0 * (widthMm * (aspectRatioPct / 100.0))

    val circumferenceMeters: Double
        get() = PI * (diameterMm / 1000.0)

    val speedCorrectionFactor: Double
        get() = diameterMm / OEM_CIVIC_2005_LX_DIAMETER_MM

    val label: String
        get() = "$widthMm/$aspectRatioPct R$rimInches"

    companion object {
        // Pneu original de fábrica do Civic 2005 LX: 185/65 R15 (621,5 mm)
        const val OEM_CIVIC_2005_LX_DIAMETER_MM = 621.5

        val OEM_CIVIC_2005_LX = TireSpec(185, 65, 15)
        val CIVIC_2005_POWERCONTACT2_195_65_R15 = TireSpec(195, 65, 15)
    }
}

enum class ThermalZone(val labelPt: String) {
    COLD("Motor frio"),
    WARMING("Aquecendo"),
    OPTIMAL("Ideal"),
    ELEVATED("Atenção térmica"),
    CRITICAL("Superaquecimento")
}

enum class ElectricalHealth(val labelPt: String) {
    LOW_BATTERY("Bateria fraca"),
    ALTERNATOR_WEAK("Alternador baixo"),
    NORMAL("Sistema elétrico OK"),
    OVERCHARGE("Sobrecarga alternador")
}

enum class EcoZone(val labelPt: String) {
    CUTOFF("Cut-off (0 L/h)"),
    ECO("Econômico"),
    MODERATE("Moderado"),
    POWER("Alta carga")
}

enum class ShiftAdvice(val labelPt: String) {
    NONE(""),
    SHIFT_UP("Subir marcha"),
    SHIFT_DOWN("Reduzir marcha")
}

data class DiagnosticWarning(
    val code: String,
    val severity: AlertSeverity,
    val titlePt: String,
    val detailPt: String
)

/**
 * Conjunto completo de telemetria derivada pronta para ser consumida pela UI.
 * Mantém todos os campos parametrizados para o Honda Civic 2005 LX 1.7 + pneu 195/65 R15.
 */
data class DerivedTelemetry(
    val correctedSpeedKmh: Double? = null,
    val tireCorrectionFactor: Double = TireSpec.CIVIC_2005_POWERCONTACT2_195_65_R15.speedCorrectionFactor,
    val estimatedGear: Int? = null,
    val estimatedGearLabel: String = "--",
    val shiftAdvice: ShiftAdvice = ShiftAdvice.NONE,
    val thermalZone: ThermalZone? = null,
    val thermalStatusLabel: String = "--",
    val electricalHealth: ElectricalHealth? = null,
    val ecoZone: EcoZone = EcoZone.MODERATE,
    val ecoScorePct: Int = 50,
    val isFuelCutoff: Boolean = false,
    val fuelSystemStatusLabel: String = "--",
    val virtualTankCapacityLiters: Double = 50.0,
    val virtualTankRemainingLiters: Double = 50.0,
    val virtualTankPercent: Double = 100.0,
    val estimatedRangeKm: Double? = null,
    val tripDistanceKm: Double = 0.0,
    val tripFuelUsedLiters: Double = 0.0,
    val peakRpm: Double = 0.0,
    val peakSpeedKmh: Double = 0.0,
    val peakCoolantTempC: Double? = null,
    val preventiveWarnings: List<DiagnosticWarning> = emptyList()
)

object CivicTelemetryCalculator {

    private val MANUAL_5_TOTAL_RATIOS = doubleArrayOf(
        3.142 * 4.058,
        1.869 * 4.058,
        1.303 * 4.058,
        1.054 * 4.058,
        0.812 * 4.058
    )

    private val AUTO_4_TOTAL_RATIOS = doubleArrayOf(
        2.722 * 4.066,
        1.516 * 4.066,
        0.975 * 4.066,
        0.673 * 4.066
    )

    fun compute(
        snapshot: ObdSnapshot,
        tire: TireSpec = TireSpec.CIVIC_2005_POWERCONTACT2_195_65_R15,
        transmission: TransmissionType = TransmissionType.MANUAL_5,
        virtualTankCapacityLiters: Double = 50.0,
        virtualTankRemainingLiters: Double = 50.0,
        tripDistanceKm: Double = 0.0,
        tripFuelUsedLiters: Double = 0.0,
        peakRpm: Double = 0.0,
        peakSpeedKmh: Double = 0.0,
        peakCoolantTempC: Double? = null,
        pendingDtcCount: Int = 0,
        isReverseActive: Boolean = false
    ): DerivedTelemetry {
        val factor = tire.speedCorrectionFactor
        val correctedSpeed = snapshot.speedKmh?.let { it * factor }

        val (gearNum, gearLabel) = estimateGear(
            correctedSpeedKmh = correctedSpeed,
            rpm = snapshot.rpm,
            tire = tire,
            transmission = transmission,
            isReverseActive = isReverseActive
        )

        val shiftAdvice = evaluateShiftAdvice(
            gear = gearNum,
            transmission = transmission,
            correctedSpeedKmh = correctedSpeed,
            rpm = snapshot.rpm,
            throttlePct = snapshot.throttlePct,
            engineLoadPct = snapshot.engineLoadPct,
            isReverseActive = isReverseActive
        )

        val thermalZone = evaluateThermalZone(snapshot.coolantTempC)
        val thermalLabel = if (snapshot.coolantTempC != null && thermalZone != null) {
            "${snapshot.coolantTempC.toInt()}°C • ${thermalZone.labelPt}"
        } else {
            "--"
        }

        val electricalHealth = evaluateElectricalHealth(
            batteryVoltageV = snapshot.batteryVoltageV,
            rpm = snapshot.rpm
        )

        val isCutoff = snapshot.throttlePct != null && snapshot.throttlePct <= 2.5 &&
            snapshot.rpm != null && snapshot.rpm >= 1_500.0 &&
            correctedSpeed != null && correctedSpeed >= 15.0

        val ecoZone = evaluateEcoZone(
            isCutoff = isCutoff,
            instantKmPerL = snapshot.instantKmPerL,
            intakeMapKpa = snapshot.intakeMapKpa,
            throttlePct = snapshot.throttlePct,
            engineLoadPct = snapshot.engineLoadPct
        )

        val ecoScorePct = evaluateEcoScore(
            isCutoff = isCutoff,
            instantKmPerL = snapshot.instantKmPerL,
            intakeMapKpa = snapshot.intakeMapKpa,
            throttlePct = snapshot.throttlePct,
            engineLoadPct = snapshot.engineLoadPct
        )

        val capacity = virtualTankCapacityLiters.coerceAtLeast(1.0)
        val remaining = virtualTankRemainingLiters.coerceIn(0.0, capacity)
        val tankPct = (remaining / capacity) * 100.0
        val avg = snapshot.averageKmPerL
        val rangeKm = if (avg != null && avg > 1.0) remaining * avg else null

        val nextPeakRpm = maxOf(peakRpm, snapshot.rpm ?: 0.0)
        val nextPeakSpeed = maxOf(peakSpeedKmh, correctedSpeed ?: 0.0)
        val nextPeakTemp = when {
            snapshot.coolantTempC == null -> peakCoolantTempC
            peakCoolantTempC == null -> snapshot.coolantTempC
            else -> maxOf(peakCoolantTempC, snapshot.coolantTempC)
        }

        val warnings = evaluatePreventiveWarnings(
            snapshot = snapshot,
            correctedSpeedKmh = correctedSpeed,
            electricalHealth = electricalHealth,
            pendingDtcCount = pendingDtcCount
        )

        return DerivedTelemetry(
            correctedSpeedKmh = correctedSpeed,
            tireCorrectionFactor = factor,
            estimatedGear = gearNum,
            estimatedGearLabel = gearLabel,
            shiftAdvice = shiftAdvice,
            thermalZone = thermalZone,
            thermalStatusLabel = thermalLabel,
            electricalHealth = electricalHealth,
            ecoZone = ecoZone,
            ecoScorePct = ecoScorePct,
            isFuelCutoff = isCutoff,
            fuelSystemStatusLabel = formatFuelSystemStatus(snapshot.fuelSystemStatusCode),
            virtualTankCapacityLiters = capacity,
            virtualTankRemainingLiters = remaining,
            virtualTankPercent = tankPct,
            estimatedRangeKm = rangeKm,
            tripDistanceKm = tripDistanceKm.coerceAtLeast(0.0),
            tripFuelUsedLiters = tripFuelUsedLiters.coerceAtLeast(0.0),
            peakRpm = nextPeakRpm,
            peakSpeedKmh = nextPeakSpeed,
            peakCoolantTempC = nextPeakTemp,
            preventiveWarnings = warnings
        )
    }

    fun estimateGear(
        correctedSpeedKmh: Double?,
        rpm: Double?,
        tire: TireSpec = TireSpec.CIVIC_2005_POWERCONTACT2_195_65_R15,
        transmission: TransmissionType = TransmissionType.MANUAL_5,
        isReverseActive: Boolean = false
    ): Pair<Int?, String> {
        if (isReverseActive) return null to "R"
        if (correctedSpeedKmh == null || rpm == null) return null to "--"
        if (correctedSpeedKmh < 3.0) {
            return null to if (transmission == TransmissionType.MANUAL_5) "N" else "P/N"
        }
        if (rpm < 950.0 && correctedSpeedKmh >= 6.0) {
            return 0 to "N"
        }

        val observedRatio = rpm / correctedSpeedKmh
        val totalRatios = when (transmission) {
            TransmissionType.MANUAL_5 -> MANUAL_5_TOTAL_RATIOS
            TransmissionType.AUTO_4 -> AUTO_4_TOTAL_RATIOS
        }
        val denom = 60.0 * tire.circumferenceMeters

        var bestIndex = -1
        var bestRelativeError = Double.MAX_VALUE
        for (i in totalRatios.indices) {
            val expectedRpmPerKmh = (totalRatios[i] * 1000.0) / denom
            val relError = abs(observedRatio - expectedRpmPerKmh) / expectedRpmPerKmh
            if (relError < bestRelativeError) {
                bestRelativeError = relError
                bestIndex = i
            }
        }

        val maxTolerance = if (transmission == TransmissionType.AUTO_4) 0.28 else 0.22
        return if (bestIndex >= 0 && bestRelativeError <= maxTolerance) {
            val gear = bestIndex + 1
            gear to "${gear}ª"
        } else {
            0 to "N"
        }
    }

    private fun evaluateShiftAdvice(
        gear: Int?,
        transmission: TransmissionType,
        correctedSpeedKmh: Double?,
        rpm: Double?,
        throttlePct: Double?,
        engineLoadPct: Double?,
        isReverseActive: Boolean
    ): ShiftAdvice {
        if (isReverseActive || gear == null || gear <= 0 || correctedSpeedKmh == null || rpm == null) {
            return ShiftAdvice.NONE
        }
        if (correctedSpeedKmh < 12.0) return ShiftAdvice.NONE

        val maxGear = transmission.forwardGears
        if (gear < maxGear && ((rpm >= 2_900.0 && (throttlePct ?: 30.0) < 48.0) || rpm >= 4_500.0)) {
            return ShiftAdvice.SHIFT_UP
        }
        if (gear > 1 && rpm <= 1_250.0 && (engineLoadPct ?: 0.0) >= 65.0) {
            return ShiftAdvice.SHIFT_DOWN
        }
        return ShiftAdvice.NONE
    }

    fun evaluateThermalZone(coolantTempC: Double?): ThermalZone? {
        val t = coolantTempC ?: return null
        return when {
            t < 60.0 -> ThermalZone.COLD
            t < 80.0 -> ThermalZone.WARMING
            t <= 99.0 -> ThermalZone.OPTIMAL
            t < 105.0 -> ThermalZone.ELEVATED
            else -> ThermalZone.CRITICAL
        }
    }

    fun evaluateElectricalHealth(batteryVoltageV: Double?, rpm: Double?): ElectricalHealth? {
        val volts = batteryVoltageV ?: return null
        val engineRunning = (rpm ?: 0.0) > 400.0
        return when {
            volts >= 15.1 -> ElectricalHealth.OVERCHARGE
            engineRunning && volts < 13.0 -> ElectricalHealth.ALTERNATOR_WEAK
            !engineRunning && volts < 11.9 -> ElectricalHealth.LOW_BATTERY
            else -> ElectricalHealth.NORMAL
        }
    }

    private fun evaluateEcoZone(
        isCutoff: Boolean,
        instantKmPerL: Double?,
        intakeMapKpa: Double?,
        throttlePct: Double?,
        engineLoadPct: Double?
    ): EcoZone {
        if (isCutoff) return EcoZone.CUTOFF
        if ((intakeMapKpa != null && intakeMapKpa >= 75.0) ||
            (throttlePct != null && throttlePct >= 55.0) ||
            (engineLoadPct != null && engineLoadPct >= 75.0)
        ) {
            return EcoZone.POWER
        }
        if ((instantKmPerL != null && instantKmPerL >= 13.0) ||
            ((intakeMapKpa ?: 50.0) < 42.0 && (throttlePct ?: 20.0) < 25.0)
        ) {
            return EcoZone.ECO
        }
        return EcoZone.MODERATE
    }

    private fun evaluateEcoScore(
        isCutoff: Boolean,
        instantKmPerL: Double?,
        intakeMapKpa: Double?,
        throttlePct: Double?,
        engineLoadPct: Double?
    ): Int {
        if (isCutoff) return 100
        if (instantKmPerL != null) {
            return ((instantKmPerL / 18.0) * 100.0).toInt().coerceIn(5, 100)
        }
        val loadPenalty = engineLoadPct ?: throttlePct ?: ((intakeMapKpa ?: 45.0) * 0.9)
        return (100.0 - loadPenalty).toInt().coerceIn(5, 95)
    }

    fun formatFuelSystemStatus(code: Int?): String = when (code) {
        null -> "--"
        1 -> "Malha aberta (aquecimento)"
        2 -> "Malha fechada (OK)"
        4 -> "Malha aberta (carga/cut-off)"
        8 -> "Malha aberta (falha sistema)"
        16 -> "Malha fechada (falha O2)"
        else -> "Código $code"
    }

    private fun evaluatePreventiveWarnings(
        snapshot: ObdSnapshot,
        correctedSpeedKmh: Double?,
        electricalHealth: ElectricalHealth?,
        pendingDtcCount: Int
    ): List<DiagnosticWarning> {
        if (snapshot.isStale) return emptyList()
        val warnings = mutableListOf<DiagnosticWarning>()

        if (snapshot.milOn || snapshot.milDtcCount > 0) {
            warnings += DiagnosticWarning(
                code = "MIL_ACTIVE",
                severity = AlertSeverity.CRITICAL,
                titlePt = "Luz de injeção acesa (${snapshot.milDtcCount} falha(s))",
                detailPt = "Abra a aba Scanner para ler e diagnosticar os códigos DTC."
            )
        }

        if (pendingDtcCount > 0) {
            warnings += DiagnosticWarning(
                code = "PENDING_DTC",
                severity = AlertSeverity.WARNING,
                titlePt = "$pendingDtcCount falha(s) pendente(s) na ECU",
                detailPt = "Falha prévia detectada antes de acender a luz de injeção."
            )
        }

        snapshot.coolantTempC?.let { temp ->
            if (temp >= 105.0) {
                warnings += DiagnosticWarning(
                    code = "COOLANT_CRITICAL",
                    severity = AlertSeverity.CRITICAL,
                    titlePt = "Superaquecimento (${temp.toInt()}°C)",
                    detailPt = "Temperatura acima da faixa normal do D17. Verifique arrefecimento/ventoinha."
                )
            } else if (temp >= 100.0) {
                warnings += DiagnosticWarning(
                    code = "COOLANT_HIGH",
                    severity = AlertSeverity.WARNING,
                    titlePt = "Temperatura elevada (${temp.toInt()}°C)",
                    detailPt = "Acima da janela ideal do Civic D17 (80–99°C)."
                )
            }
        }

        if (snapshot.stftPct != null && snapshot.ltftPct != null) {
            val totalTrim = snapshot.stftPct + snapshot.ltftPct
            if (abs(totalTrim) >= 12.0) {
                val sign = if (totalTrim > 0) "+" else ""
                warnings += DiagnosticWarning(
                    code = "FUEL_TRIM_DRIFT",
                    severity = AlertSeverity.WARNING,
                    titlePt = "Mistura fora do padrão ($sign${"%.1f".format(totalTrim)}%)",
                    detailPt = "Combustível adulterado, bico sujo ou entrada falsa de ar."
                )
            }
        }

        val rpm = snapshot.rpm
        val map = snapshot.intakeMapKpa
        if (rpm != null && rpm in 550.0..1_050.0 && (correctedSpeedKmh ?: 0.0) < 2.0 && map != null && map > 42.0) {
            warnings += DiagnosticWarning(
                code = "HIGH_IDLE_MAP",
                severity = AlertSeverity.WARNING,
                titlePt = "Vácuo baixo na lenta (MAP ${map.toInt()} kPa)",
                detailPt = "Possível entrada falsa de ar no coletor ou válvula IAC suja."
            )
        }

        when (electricalHealth) {
            ElectricalHealth.OVERCHARGE -> warnings += DiagnosticWarning(
                code = "ALTERNATOR_HIGH",
                severity = AlertSeverity.CRITICAL,
                titlePt = "Sobrecarga no alternador (${"%.1f".format(snapshot.batteryVoltageV)}V)",
                detailPt = "Regulador de tensão acima de 15.1V pode danificar módulos."
            )
            ElectricalHealth.ALTERNATOR_WEAK -> warnings += DiagnosticWarning(
                code = "ALTERNATOR_WEAK",
                severity = AlertSeverity.WARNING,
                titlePt = "Alternador carregando pouco (${"%.1f".format(snapshot.batteryVoltageV)}V)",
                detailPt = "Tensão abaixo de 13.0V com motor ligado."
            )
            ElectricalHealth.LOW_BATTERY -> warnings += DiagnosticWarning(
                code = "BATTERY_LOW",
                severity = AlertSeverity.WARNING,
                titlePt = "Bateria fraca (${"%.1f".format(snapshot.batteryVoltageV)}V)",
                detailPt = "Tensão abaixo de 11.9V com motor desligado."
            )
            else -> Unit
        }

        return warnings
    }
}

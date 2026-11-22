package com.autodeck.protocol.obd2

data class FuelMetrics(
    val instantKmPerL: Double?,
    val averageKmPerL: Double?,
    val litersPerHour: Double?,
    val deltaDistanceKm: Double = 0.0,
    val deltaFuelLiters: Double = 0.0
)

data class TripFuelState(
    val distanceKm: Double,
    val fuelLiters: Double
)

/**
 * Calcula consumo instantâneo (km/L), vazão horária (L/h) e média da viagem via MAF direto
 * ou estimativa Speed-Density (MAP + IAT + RPM + Cilindrada) com correção de trims (STFT/LTFT),
 * correção de diâmetro do pneu e detecção de cut-off (freio motor).
 */
class FuelConsumptionCalculator(
    var fuelType: FuelType = FuelType.GASOLINE_E27,
    var engineDisplacementLiters: Double = 1.6,
    var volumetricEfficiency: Double = 0.85,
    var calibrationFactor: Double = 1.0,
    var speedCorrectionFactor: Double = 1.0
) {
    private var accumulatedDistanceKm = 0.0
    private var accumulatedFuelLiters = 0.0
    private var lastSampleTimestampMs: Long? = null

    fun estimateMafGramsPerSec(
        mafSensor: Double?,
        intakeMapKpa: Double?,
        intakeTempC: Double?,
        rpm: Double?
    ): Double? {
        if (mafSensor != null && mafSensor > 0.05) {
            return mafSensor
        }
        if (intakeMapKpa == null || rpm == null || rpm <= 0.0 || intakeMapKpa <= 0.0) {
            return null
        }
        val iatKelvin = (intakeTempC ?: 30.0) + 273.15
        if (iatKelvin <= 100.0) return null

        val imap = rpm * intakeMapKpa / iatKelvin
        val molarAirFactor = 28.97 / 8.314
        return (imap / 120.0) * volumetricEfficiency * engineDisplacementLiters * molarAirFactor
    }

    fun calculateLitersPerHour(
        mafGramsPerSec: Double?,
        stftPct: Double? = null,
        ltftPct: Double? = null,
        throttlePct: Double? = null,
        rpm: Double? = null,
        speedKmh: Double? = null
    ): Double? {
        // Corte de injeção (freio motor): borboleta fechada com giro alto e carro em movimento
        if (throttlePct != null && throttlePct <= 2.5 &&
            rpm != null && rpm >= 1_500.0 &&
            speedKmh != null && speedKmh >= 15.0
        ) {
            return 0.0
        }

        val maf = mafGramsPerSec ?: return null
        if (maf <= 0.05) return null

        val trimMultiplier = 1.0 + (((stftPct ?: 0.0) + (ltftPct ?: 0.0)) / 100.0).coerceIn(-0.35, 0.35)
        val fuelGramsPerSec = (maf / fuelType.afr) * trimMultiplier * calibrationFactor.coerceIn(0.5, 2.0)
        return ((fuelGramsPerSec * 3600.0) / fuelType.densityGramsPerLiter).coerceAtLeast(0.0)
    }

    fun calculateInstantKmPerL(
        speedKmh: Double?,
        mafGramsPerSec: Double?
    ): Double? {
        val speed = speedKmh ?: return null
        val lph = calculateLitersPerHour(mafGramsPerSec = mafGramsPerSec) ?: return null
        if (speed < 3.0) return null
        if (lph <= 0.001) return 50.0
        return (speed / lph).coerceIn(0.0, 50.0)
    }

    fun recordSample(
        speedKmh: Double?,
        mafSensor: Double?,
        intakeMapKpa: Double?,
        intakeTempC: Double?,
        rpm: Double?,
        timestampMs: Long
    ): Pair<Double?, Double?> {
        val metrics = recordSampleDetailed(
            speedKmh = speedKmh,
            mafSensor = mafSensor,
            intakeMapKpa = intakeMapKpa,
            intakeTempC = intakeTempC,
            rpm = rpm,
            throttlePct = null,
            stftPct = null,
            ltftPct = null,
            timestampMs = timestampMs
        )
        return metrics.instantKmPerL to metrics.averageKmPerL
    }

    fun recordSampleDetailed(
        speedKmh: Double?,
        mafSensor: Double?,
        intakeMapKpa: Double?,
        intakeTempC: Double?,
        rpm: Double?,
        throttlePct: Double?,
        stftPct: Double?,
        ltftPct: Double?,
        timestampMs: Long
    ): FuelMetrics {
        val effectiveSpeedKmh = speedKmh?.let { it * speedCorrectionFactor.coerceIn(0.85, 1.15) }
        val effectiveMaf = estimateMafGramsPerSec(mafSensor, intakeMapKpa, intakeTempC, rpm)
        val lph = calculateLitersPerHour(
            mafGramsPerSec = effectiveMaf,
            stftPct = stftPct,
            ltftPct = ltftPct,
            throttlePct = throttlePct,
            rpm = rpm,
            speedKmh = effectiveSpeedKmh
        )

        val instant = when {
            effectiveSpeedKmh == null || effectiveSpeedKmh < 3.0 || lph == null -> null
            lph <= 0.001 -> 50.0
            else -> (effectiveSpeedKmh / lph).coerceIn(0.0, 50.0)
        }

        val previousMs = lastSampleTimestampMs
        lastSampleTimestampMs = timestampMs

        var deltaDistanceKm = 0.0
        var deltaFuelLiters = 0.0
        if (previousMs != null && timestampMs > previousMs && effectiveSpeedKmh != null && lph != null) {
            val deltaHours = ((timestampMs - previousMs).coerceAtMost(10_000L)) / 3_600_000.0
            deltaDistanceKm = effectiveSpeedKmh * deltaHours
            deltaFuelLiters = lph * deltaHours
            accumulatedDistanceKm += deltaDistanceKm
            accumulatedFuelLiters += deltaFuelLiters
        }

        val average = if (accumulatedFuelLiters > 0.002 && accumulatedDistanceKm > 0.05) {
            (accumulatedDistanceKm / accumulatedFuelLiters).coerceIn(0.0, 50.0)
        } else {
            instant
        }

        return FuelMetrics(
            instantKmPerL = instant,
            averageKmPerL = average,
            litersPerHour = lph,
            deltaDistanceKm = deltaDistanceKm,
            deltaFuelLiters = deltaFuelLiters
        )
    }

    fun exportTripState(): TripFuelState {
        return TripFuelState(distanceKm = accumulatedDistanceKm, fuelLiters = accumulatedFuelLiters)
    }

    fun restoreTripState(distanceKm: Double, fuelLiters: Double) {
        accumulatedDistanceKm = distanceKm.coerceAtLeast(0.0)
        accumulatedFuelLiters = fuelLiters.coerceAtLeast(0.0)
        lastSampleTimestampMs = null
    }

    fun resetTrip() {
        accumulatedDistanceKm = 0.0
        accumulatedFuelLiters = 0.0
        lastSampleTimestampMs = null
    }
}

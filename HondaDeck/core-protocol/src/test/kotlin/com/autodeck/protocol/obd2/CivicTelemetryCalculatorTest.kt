package com.autodeck.protocol.obd2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CivicTelemetryCalculatorTest {

    @Test
    fun `computes PowerContact 2 195 65 R15 tire correction factor vs OEM 185 65 R15`() {
        val tire = TireSpec.CIVIC_2005_POWERCONTACT2_195_65_R15
        assertEquals(634.5, tire.diameterMm, 0.01)
        assertEquals(1.0209, tire.speedCorrectionFactor, 0.0005)
        assertEquals(1.0, TireSpec.OEM_CIVIC_2005_LX.speedCorrectionFactor, 0.0001)
    }

    @Test
    fun `estimates gears accurately for MANUAL_5, AUTO_4, Neutral and Reverse`() {
        val tire = TireSpec.CIVIC_2005_POWERCONTACT2_195_65_R15

        // 5a marcha manual (~27.55 RPM/(km/h)) -> 100 km/h corresp ~2755 RPM
        val fifth = CivicTelemetryCalculator.estimateGear(
            correctedSpeedKmh = 100.0,
            rpm = 2755.0,
            tire = tire,
            transmission = TransmissionType.MANUAL_5
        )
        assertEquals(5 to "5ª", fifth)

        // 2a marcha manual (~63.4 RPM/(km/h)) -> 40 km/h ~2536 RPM
        val second = CivicTelemetryCalculator.estimateGear(
            correctedSpeedKmh = 40.0,
            rpm = 2536.0,
            tire = tire,
            transmission = TransmissionType.MANUAL_5
        )
        assertEquals(2 to "2ª", second)

        // 4a marcha automático (~22.9 RPM/(km/h)) -> 100 km/h ~2290 RPM
        val autoFourth = CivicTelemetryCalculator.estimateGear(
            correctedSpeedKmh = 100.0,
            rpm = 2290.0,
            tire = tire,
            transmission = TransmissionType.AUTO_4
        )
        assertEquals(4 to "4ª", autoFourth)

        // Em movimento em ponto morto (banguela)
        val neutralRolling = CivicTelemetryCalculator.estimateGear(
            correctedSpeedKmh = 45.0,
            rpm = 750.0,
            tire = tire,
            transmission = TransmissionType.MANUAL_5
        )
        assertEquals(0 to "N", neutralRolling)

        // Câmera de ré ativa prevalece
        val reverse = CivicTelemetryCalculator.estimateGear(
            correctedSpeedKmh = 5.0,
            rpm = 1200.0,
            tire = tire,
            transmission = TransmissionType.MANUAL_5,
            isReverseActive = true
        )
        assertEquals(null to "R", reverse)
    }

    @Test
    fun `classifies D17 thermal zones and computes virtual tank percent and range`() {
        assertEquals(ThermalZone.COLD, CivicTelemetryCalculator.evaluateThermalZone(48.0))
        assertEquals(ThermalZone.WARMING, CivicTelemetryCalculator.evaluateThermalZone(70.0))
        assertEquals(ThermalZone.OPTIMAL, CivicTelemetryCalculator.evaluateThermalZone(96.0))
        assertEquals(ThermalZone.ELEVATED, CivicTelemetryCalculator.evaluateThermalZone(102.0))
        assertEquals(ThermalZone.CRITICAL, CivicTelemetryCalculator.evaluateThermalZone(108.0))

        val snap = ObdSnapshot(
            coolantTempC = 92.0,
            instantKmPerL = 14.0,
            averageKmPerL = 12.5,
            batteryVoltageV = 14.3,
            rpm = 2755.0,
            speedKmh = 98.0,
            engineLoadPct = 35.0,
            throttlePct = 18.0,
            intakeMapKpa = 38.0,
            fuelSystemStatusCode = 2
        )

        val derived = CivicTelemetryCalculator.compute(
            snapshot = snap,
            virtualTankCapacityLiters = 50.0,
            virtualTankRemainingLiters = 32.0,
            tripDistanceKm = 180.0,
            tripFuelUsedLiters = 14.4
        )

        assertNotNull(derived.correctedSpeedKmh)
        assertEquals(98.0 * TireSpec.CIVIC_2005_POWERCONTACT2_195_65_R15.speedCorrectionFactor, derived.correctedSpeedKmh!!, 0.1)
        assertEquals(5, derived.estimatedGear)
        assertEquals(ThermalZone.OPTIMAL, derived.thermalZone)
        assertEquals(64.0, derived.virtualTankPercent, 0.01)
        assertEquals(400.0, derived.estimatedRangeKm!!, 0.1)
        assertEquals("Malha fechada (OK)", derived.fuelSystemStatusLabel)
        assertTrue(derived.preventiveWarnings.isEmpty())
    }

    @Test
    fun `emits preventive diagnostic warnings for pending DTC, high idle MAP and fuel trim drift`() {
        val snap = ObdSnapshot(
            coolantTempC = 102.0,
            batteryVoltageV = 12.4,
            rpm = 750.0,
            speedKmh = 0.0,
            intakeMapKpa = 48.0,
            stftPct = 8.0,
            ltftPct = 7.0
        )

        val derived = CivicTelemetryCalculator.compute(
            snapshot = snap,
            pendingDtcCount = 1
        )

        val codes = derived.preventiveWarnings.map { it.code }.toSet()
        assertTrue(codes.contains("PENDING_DTC"))
        assertTrue(codes.contains("COOLANT_HIGH"))
        assertTrue(codes.contains("FUEL_TRIM_DRIFT"))
        assertTrue(codes.contains("HIGH_IDLE_MAP"))
        assertTrue(codes.contains("ALTERNATOR_WEAK"))
    }
}

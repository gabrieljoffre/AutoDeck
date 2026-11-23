package com.autodeck.protocol.obd2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelConsumptionCalculatorTest {

    @Test
    fun `calculates plausible instant consumption using MAF`() {
        val calc = FuelConsumptionCalculator(fuelType = FuelType.GASOLINE_E27)
        val kmPerL = calc.calculateInstantKmPerL(speedKmh = 80.0, mafGramsPerSec = 18.0)
        assertNotNull(kmPerL)
        assertTrue("Consumo esperado entre 10 e 14 km/L, obtido $kmPerL", kmPerL!! in 10.0..14.0)
    }

    @Test
    fun `returns null kmPerL and non-null litersPerHour when vehicle is stopped`() {
        val calc = FuelConsumptionCalculator()
        val metrics = calc.recordSampleDetailed(
            speedKmh = 0.0,
            mafSensor = 3.2,
            intakeMapKpa = null,
            intakeTempC = null,
            rpm = 800.0,
            throttlePct = 12.0,
            stftPct = 0.0,
            ltftPct = 0.0,
            timestampMs = 1_000L
        )
        assertNull(metrics.instantKmPerL)
        assertNotNull(metrics.litersPerHour)
        assertTrue(metrics.litersPerHour!! > 0.5)
    }

    @Test
    fun `detects deceleration fuel cut-off when throttle closed at high rpm`() {
        val calc = FuelConsumptionCalculator()
        val lph = calc.calculateLitersPerHour(
            mafGramsPerSec = 5.0,
            throttlePct = 1.0,
            rpm = 2_400.0,
            speedKmh = 60.0
        )
        assertEquals(0.0, lph!!, 0.0001)
    }

    @Test
    fun `falls back to Speed-Density when MAF sensor is absent`() {
        val calc = FuelConsumptionCalculator(
            fuelType = FuelType.GASOLINE_E27,
            engineDisplacementLiters = 1.6,
            volumetricEfficiency = 0.85
        )
        val estimatedMaf = calc.estimateMafGramsPerSec(
            mafSensor = null,
            intakeMapKpa = 55.0,
            intakeTempC = 35.0,
            rpm = 2400.0
        )
        assertNotNull(estimatedMaf)
        assertTrue("MAF estimado deveria estar entre 8 e 25 g/s, obtido $estimatedMaf", estimatedMaf!! in 8.0..25.0)
    }

    @Test
    fun `exports and restores trip state cleanly`() {
        val calc = FuelConsumptionCalculator(fuelType = FuelType.GASOLINE_E27)
        calc.recordSample(speedKmh = 72.0, mafSensor = 16.0, intakeMapKpa = null, intakeTempC = null, rpm = 2200.0, timestampMs = 1_000L)
        calc.recordSample(speedKmh = 72.0, mafSensor = 16.0, intakeMapKpa = null, intakeTempC = null, rpm = 2200.0, timestampMs = 6_000L)
        val exported = calc.exportTripState()
        assertTrue(exported.distanceKm > 0.05)
        assertTrue(exported.fuelLiters > 0.002)

        val restored = FuelConsumptionCalculator(fuelType = FuelType.GASOLINE_E27)
        restored.restoreTripState(exported.distanceKm, exported.fuelLiters)
        assertEquals(exported.distanceKm, restored.exportTripState().distanceKm, 0.0001)
        assertEquals(exported.fuelLiters, restored.exportTripState().fuelLiters, 0.0001)
    }
}

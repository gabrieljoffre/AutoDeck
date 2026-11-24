package com.autodeck.protocol.obd2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelLogCalculatorTest {

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `parses liters price odometer and full tank from speech`() {
        val p = FuelLogCalculator.parseUtterance("Abasteci 40 litros a 6,29 com 123.456 km, completei")
        assertNotNull(p)
        assertEquals(40.0, p!!.liters!!, 0.001)
        assertEquals(6.29, p.pricePerLiter!!, 0.001)
        assertEquals(123456.0, p.odometerKm!!, 0.1)
        assertTrue(p.fullTank)
    }

    @Test
    fun `parses total paid and derives liters`() {
        val p = FuelLogCalculator.parseUtterance("coloquei 250 reais de gasolina a 6,25 o litro, hodômetro 98500")
        assertNotNull(p)
        assertNull(p!!.liters)
        assertEquals(250.0, p.totalPaid!!, 0.001)
        assertEquals(6.25, p.pricePerLiter!!, 0.001)
        assertEquals(40.0, p.resolvedLiters!!, 0.001)
        assertEquals(98500.0, p.odometerKm!!, 0.1)
        assertFalse(p.fullTank)
    }

    @Test
    fun `partial wording wins over full keywords`() {
        val p = FuelLogCalculator.parseUtterance("não completei, só 20 litros a 6.10")
        assertNotNull(p)
        assertFalse(p!!.fullTank)
        assertEquals(6.10, p.pricePerLiter!!, 0.001)
    }

    @Test
    fun `returns null without liters or amount`() {
        assertNull(FuelLogCalculator.parseUtterance("como está o motor"))
    }

    @Test
    fun `full to full interval includes partial fills in between`() {
        val entries = listOf(
            FuelEntry(0, 10_000.0, 40.0, 6.0, fullTank = true),
            FuelEntry(day, 10_200.0, 10.0, 6.0, fullTank = false),
            FuelEntry(2 * day, 10_500.0, 30.0, 6.5, fullTank = true)
        )
        val ivs = FuelLogCalculator.intervals(entries)
        assertEquals(1, ivs.size)
        assertEquals(500.0, ivs[0].distanceKm, 0.001)
        assertEquals(40.0, ivs[0].liters, 0.001) // 10 parcial + 30 do tanque cheio
        assertEquals(12.5, ivs[0].kmPerL, 0.001)
        assertEquals(255.0, ivs[0].cost!!, 0.001)
    }

    @Test
    fun `calibration moves half way toward real consumption and is clamped`() {
        val iv = FuelInterval(0, 1, 400.0, liters = 40.0, cost = null, appEstimatedLiters = 32.0)
        val next = FuelLogCalculator.suggestCalibration(iv, currentFactor = 1.0)
        assertEquals(1.125, next!!, 0.001) // alvo 1,25; anda metade

        val typo = iv.copy(appEstimatedLiters = 5.0) // 8x: anotação errada, ignora
        assertNull(FuelLogCalculator.suggestCalibration(typo, 1.0))
    }

    @Test
    fun `summary uses distance weighted average and last 30 days spend`() {
        val now = 100 * day
        val entries = listOf(
            FuelEntry(now - 60 * day, 1_000.0, 40.0, 6.0, true),
            FuelEntry(now - 40 * day, 1_400.0, 40.0, 6.0, true), // 10 km/L
            FuelEntry(now - 5 * day, 2_000.0, 40.0, 6.5, true) // 15 km/L
        )
        val s = FuelLogCalculator.summarize(entries, 1.0, now)
        assertEquals(15.0, s.lastKmPerL!!, 0.001)
        assertEquals(12.5, s.averageKmPerL!!, 0.001) // 1000 km / 80 L
        assertEquals(260.0, s.spentLast30Days, 0.001)
        assertEquals(6.5, s.lastPricePerLiter!!, 0.001)
    }

    @Test
    fun `estimated liters in tank after last full fill`() {
        val entries = listOf(FuelEntry(0, 1_000.0, 45.0, 6.0, true, appTripKm = 200.0, appTripFuelL = 10.0))
        val left = FuelLogCalculator.estimatedLitersInTank(entries, currentAppTripKm = 320.0, tankCapacityL = 50.0, averageKmPerL = 12.0)
        assertEquals(40.0, left!!, 0.001) // 120 km / 12 km/L = 10 L gastos
    }
}

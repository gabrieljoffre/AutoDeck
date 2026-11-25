package com.autodeck.headunit.weather

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherServiceTest {

    @Test
    fun `parse Open-Meteo extrai agora detalhado, 24h horarias e 7 dias sem quebrar spokenSummary`() {
        val hTimes = JSONArray()
        val hTemps = JSONArray()
        val hCodes = JSONArray()
        val hRain = JSONArray()
        val hPrecip = JSONArray()

        // 2 horas passadas + 26 horas futuras para validar filtro e limite de 24h
        for (i in 0 until 28) {
            val day = 24 + (i / 24)
            val hour = i % 24
            hTimes.put("2026-11-${"%02d".format(day)}T${"%02d".format(hour)}:00")
            hTemps.put(20.0 + (i % 8))
            hCodes.put(if (i % 3 == 0) 61 else 2)
            hRain.put(if (i == 15) 65 else 15)
            hPrecip.put(if (i % 3 == 0) 1.4 else 0.0)
        }

        val dTimes = JSONArray()
        val dMax = JSONArray()
        val dMin = JSONArray()
        val dCodes = JSONArray()
        val dRain = JSONArray()
        val dPrecip = JSONArray()
        val dUv = JSONArray()
        val dSunrise = JSONArray()
        val dSunset = JSONArray()
        for (d in 0 until 7) {
            dTimes.put("2026-11-${"%02d".format(24 + d)}")
            dMax.put(28.0 + d * 0.5)
            dMin.put(17.0 + d * 0.2)
            dCodes.put(if (d == 1) 80 else 1)
            dRain.put(10 + d * 10)
            dPrecip.put(d * 0.5)
            dUv.put(7.5)
            dSunrise.put("2026-11-${"%02d".format(24 + d)}T05:42")
            dSunset.put("2026-11-${"%02d".format(24 + d)}T18:24")
        }

        val json = JSONObject().apply {
            put("current", JSONObject().apply {
                put("time", "2026-11-24T14:15")
                put("temperature_2m", 26.4)
                put("apparent_temperature", 28.1)
                put("relative_humidity_2m", 63)
                put("wind_speed_10m", 14.4)
                put("wind_direction_10m", 45)
                put("precipitation", 0.2)
                put("uv_index", 6.8)
                put("weather_code", 2)
                put("is_day", 1)
            })
            put("hourly", JSONObject().apply {
                put("time", hTimes)
                put("temperature_2m", hTemps)
                put("weather_code", hCodes)
                put("precipitation_probability", hRain)
                put("precipitation", hPrecip)
            })
            put("daily", JSONObject().apply {
                put("time", dTimes)
                put("temperature_2m_max", dMax)
                put("temperature_2m_min", dMin)
                put("weather_code", dCodes)
                put("precipitation_probability_max", dRain)
                put("precipitation_sum", dPrecip)
                put("uv_index_max", dUv)
                put("sunrise", dSunrise)
                put("sunset", dSunset)
            })
        }

        val state = WeatherService.parse(json, 1_760_000_000_000L)
        val now = state.now
        assertNotNull(now)
        assertEquals(26, now!!.tempC)
        assertEquals(28, now.feelsLikeC)
        assertEquals(WeatherKind.PARTLY_CLOUDY, now.kind)
        assertEquals(63, now.humidityPct)
        assertEquals(14, now.windKmh)
        assertEquals(45, now.windDirDeg)
        assertEquals("NE", now.windDirLabel)
        assertEquals(0.2, now.precipMm, 0.01)
        assertEquals(6.8, now.uvIndex, 0.01)
        assertEquals("05:42", now.sunrise)
        assertEquals("18:24", now.sunset)

        assertEquals(13, state.nextHours.size) // 15:00..23:00 (9) + 00:00..03:00 (4) = 13 restantes no array
        assertEquals("15:00", state.nextHours.first().hourLabel)
        assertEquals(65, state.nextHours.first().rainChancePct)

        assertEquals(7, state.nextDays.size)
        assertEquals("Hoje", state.nextDays.first().dayLabel)
        assertEquals(17, state.todayMinC)
        assertEquals(28, state.todayMaxC)

        val spoken = state.spokenSummary()
        assertNotNull(spoken)
        assertTrue(spoken!!.contains("Parcialmente nublado"))
        assertTrue(spoken.contains("26 graus"))
        assertTrue(spoken.contains("65%"))
    }
}

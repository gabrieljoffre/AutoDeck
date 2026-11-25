package com.autodeck.headunit.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class BrazilHolidaysAndAgendaTest {

    @Test
    fun `calcula Pascoa e feriados moveis corretamente para multiplos anos`() {
        assertEquals(LocalDate.of(2024, 3, 31), BrazilHolidays.easterSunday(2024))
        assertEquals(LocalDate.of(2025, 4, 20), BrazilHolidays.easterSunday(2025))
        assertEquals(LocalDate.of(2026, 4, 5), BrazilHolidays.easterSunday(2026))
        assertEquals(LocalDate.of(2027, 3, 28), BrazilHolidays.easterSunday(2027))

        val holidays2026 = BrazilHolidays.holidaysForYear(2026).associateBy { it.dateIso }
        assertEquals("Carnaval (Segunda)", holidays2026["2026-02-16"]?.name)
        assertEquals("Carnaval", holidays2026["2026-02-17"]?.name)
        assertEquals("Sexta-Feira Santa", holidays2026["2026-04-03"]?.name)
        assertEquals("Páscoa", holidays2026["2026-04-05"]?.name)
        assertEquals("Corpus Christi", holidays2026["2026-06-04"]?.name)
        assertTrue(holidays2026["2026-06-04"]?.movable == true)

        assertEquals("Dia da Consciência Negra", holidays2026["2026-11-20"]?.name)
        assertEquals("Natal", holidays2026["2026-12-25"]?.name)
    }

    @Test
    fun `formatAgendaSummary resume compromissos de hoje e amanha e trata falta de permissao`() {
        val zone = ZoneId.of("America/Sao_Paulo")
        val today = LocalDate.of(2026, 11, 20) // Consciência Negra

        val denied = CalendarRepository.formatAgendaSummary(
            state = CalendarState(permissionGranted = false),
            dayOffset = 0,
            today = today,
            zone = zone
        )
        assertTrue(denied.contains("não foi permitido"))
        assertTrue(denied.contains("Consciência Negra"))

        val startMs = LocalDate.of(2026, 11, 21).atTime(9, 30).atZone(zone).toInstant().toEpochMilli()
        val endMs = LocalDate.of(2026, 11, 21).atTime(10, 30).atZone(zone).toInstant().toEpochMilli()
        val state = CalendarState(
            permissionGranted = true,
            events = listOf(
                CalendarEvent(
                    id = "1",
                    title = "Revisão Civic",
                    startMs = startMs,
                    endMs = endMs,
                    allDay = false,
                    location = "Av. Brasil",
                    dateIso = "2026-11-21",
                    endDateIso = "2026-11-21"
                )
            )
        )

        val tomorrowSummary = CalendarRepository.formatAgendaSummary(state, dayOffset = 1, today = today, zone = zone)
        assertNotNull(tomorrowSummary)
        assertTrue(tomorrowSummary.contains("amanhã"))
        assertTrue(tomorrowSummary.contains("09:30: Revisão Civic em Av. Brasil"))
    }
}

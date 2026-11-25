package com.autodeck.headunit.calendar

import com.autodeck.protocol.phone.PhoneCalendarEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class PortugueseDateTimeParserTest {

    // Quarta-feira, 25/11/2026 às 10:00 (Anápolis-GO / America/Sao_Paulo)
    private val zone: ZoneId = ZoneId.of("America/Sao_Paulo")
    private val baseNow: ZonedDateTime = ZonedDateTime.of(2026, 11, 25, 10, 0, 0, 0, zone)

    @Test
    fun `interpreta amanha as 3 da tarde e sexta 9h corretamente`() {
        val amanha3 = PortugueseDateTimeParser.parseDateTime("amanhã às 3 da tarde", baseNow)
        assertNotNull(amanha3)
        assertEquals(LocalDate.of(2026, 11, 26), amanha3!!.dateTime.toLocalDate())
        assertEquals(LocalTime.of(15, 0), amanha3.dateTime.toLocalTime())
        assertTrue(amanha3.hasExplicitTime)

        val sexta9 = PortugueseDateTimeParser.parseDateTime("sexta 9h", baseNow)
        assertNotNull(sexta9)
        assertEquals(LocalDate.of(2026, 11, 27), sexta9!!.dateTime.toLocalDate())
        assertEquals(LocalTime.of(9, 0), sexta9.dateTime.toLocalTime())

        val sexta930 = PortugueseDateTimeParser.parseDateTime("sexta-feira às 9h30", baseNow)
        assertNotNull(sexta930)
        assertEquals(LocalDate.of(2026, 11, 27), sexta930!!.dateTime.toLocalDate())
        assertEquals(LocalTime.of(9, 30), sexta930.dateTime.toLocalTime())
    }

    @Test
    fun `interpreta horarios da noite, meio-dia, datas explicitas e duracoes relativas`() {
        val noite = PortugueseDateTimeParser.parseDateTime("hoje às 8 da noite", baseNow)
        assertNotNull(noite)
        assertEquals(LocalDate.of(2026, 11, 25), noite!!.dateTime.toLocalDate())
        assertEquals(LocalTime.of(20, 0), noite.dateTime.toLocalTime())

        val meioDia = PortugueseDateTimeParser.parseDateTime("depois de amanhã ao meio-dia", baseNow)
        assertNotNull(meioDia)
        assertEquals(LocalDate.of(2026, 11, 27), meioDia!!.dateTime.toLocalDate())
        assertEquals(LocalTime.of(12, 0), meioDia.dateTime.toLocalTime())

        val relMin = PortugueseDateTimeParser.parseDateTime("daqui a 20 minutos", baseNow)
        assertNotNull(relMin)
        assertEquals(LocalTime.of(10, 20), relMin!!.dateTime.toLocalTime())

        val relHoraMeia = PortugueseDateTimeParser.parseDateTime("em 1 hora e meia", baseNow)
        assertNotNull(relHoraMeia)
        assertEquals(LocalTime.of(11, 30), relHoraMeia!!.dateTime.toLocalTime())

        val explicitBr = PortugueseDateTimeParser.parseDateTime("02/12 às 14:45", baseNow)
        assertNotNull(explicitBr)
        assertEquals(LocalDate.of(2026, 12, 2), explicitBr!!.dateTime.toLocalDate())
        assertEquals(LocalTime.of(14, 45), explicitBr.dateTime.toLocalTime())

        val invalid = PortugueseDateTimeParser.parseDateTime("sem nenhuma data aqui", baseNow)
        assertNull(invalid)

        val spokenHours = PortugueseDateTimeParser.parseDateTime("amanhã às três da tarde", baseNow)
        assertNotNull(spokenHours)
        assertEquals(LocalDate.of(2026, 11, 26), spokenHours!!.dateTime.toLocalDate())
        assertEquals(LocalTime.of(15, 0), spokenHours.dateTime.toLocalTime())

        val spokenNight = PortugueseDateTimeParser.parseDateTime("hoje às oito da noite", baseNow)
        assertNotNull(spokenNight)
        assertEquals(LocalTime.of(20, 0), spokenNight!!.dateTime.toLocalTime())

        val periodOnly = PortugueseDateTimeParser.parseDateTime("amanhã à tarde", baseNow)
        assertNotNull(periodOnly)
        assertEquals(LocalDate.of(2026, 11, 26), periodOnly!!.dateTime.toLocalDate())
        assertEquals(LocalTime.of(15, 0), periodOnly.dateTime.toLocalTime())
    }

    @Test
    fun `interpreta intervalos de agenda e mescla eventos vindos do celular`() {
        val essaSemana = PortugueseDateTimeParser.parseDateRange("essa semana", baseNow)
        assertEquals(LocalDate.of(2026, 11, 25), essaSemana.startDate)
        assertEquals(LocalDate.of(2026, 11, 29), essaSemana.endDate) // domingo

        val proxSemana = PortugueseDateTimeParser.parseDateRange("próxima semana", baseNow)
        assertEquals(LocalDate.of(2026, 11, 30), proxSemana.startDate)
        assertEquals(LocalDate.of(2026, 12, 6), proxSemana.endDate)

        val prox3 = PortugueseDateTimeParser.parseDateRange("próximos 3 dias", baseNow)
        assertEquals(LocalDate.of(2026, 11, 25), prox3.startDate)
        assertEquals(LocalDate.of(2026, 11, 27), prox3.endDate)

        // Mescla eventos do celular mesmo quando o tablet não tem permissão local
        val tabletDenied = CalendarState(permissionGranted = false)
        val startMs = LocalDate.of(2026, 11, 27).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val endMs = LocalDate.of(2026, 11, 27).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val merged = CalendarRepository.mergeWithPhoneEvents(
            tabletState = tabletDenied,
            phoneEvents = listOf(
                PhoneCalendarEvent(
                    id = "ph1",
                    title = "Consulta Odontológica",
                    startMs = startMs,
                    endMs = endMs,
                    location = "Centro",
                    dateIso = "2026-11-27",
                    endDateIso = "2026-11-27"
                )
            ),
            zone = zone
        )
        assertTrue(merged.hasAnyCalendarSource)
        assertEquals(1, merged.events.size)
        assertEquals("phone", merged.events.first().source)

        val sextaRange = PortugueseDateTimeParser.parseDateRange("sexta", baseNow)
        val summary = CalendarRepository.formatAgendaRangeSummary(merged, sextaRange, zone)
        assertTrue("Resumo deve listar evento do celular: $summary", summary.contains("Consulta Odontológica"))
        assertTrue(summary.contains("09:00"))

        // Quando o celular tem permissão mas 0 eventos, não deve pedir permissão do tablet
        val emptyPhone = CalendarRepository.mergeWithPhoneEvents(
            tabletState = tabletDenied,
            phoneEvents = emptyList(),
            zone = zone,
            phoneCalendarAvailable = true
        )
        assertTrue(emptyPhone.hasAnyCalendarSource)
        val emptySummary = CalendarRepository.formatAgendaRangeSummary(emptyPhone, sextaRange, zone)
        assertTrue(emptySummary.contains("não tem compromissos"))
    }
}

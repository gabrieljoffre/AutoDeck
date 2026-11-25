package com.autodeck.headunit.calendar

import java.time.LocalDate

data class Holiday(
    val dateIso: String,
    val name: String,
    val movable: Boolean = false
)

/**
 * Feriados nacionais do Brasil (fixos + móveis calculados a partir da Páscoa).
 */
object BrazilHolidays {

    /** Domingo de Páscoa no calendário gregoriano (algoritmo de Meeus/Jones/Butcher). */
    fun easterSunday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = ((h + l - 7 * m + 114) % 31) + 1
        return LocalDate.of(year, month, day)
    }

    fun holidaysForYear(year: Int): List<Holiday> {
        val easter = easterSunday(year)
        val fixed = listOf(
            LocalDate.of(year, 1, 1) to "Confraternização Universal",
            LocalDate.of(year, 4, 21) to "Tiradentes",
            LocalDate.of(year, 5, 1) to "Dia do Trabalho",
            LocalDate.of(year, 9, 7) to "Independência do Brasil",
            LocalDate.of(year, 10, 12) to "Nossa Senhora Aparecida",
            LocalDate.of(year, 11, 2) to "Finados",
            LocalDate.of(year, 11, 15) to "Proclamação da República",
            LocalDate.of(year, 11, 20) to "Dia da Consciência Negra",
            LocalDate.of(year, 12, 25) to "Natal"
        ).map { (date, name) -> Holiday(date.toString(), name, movable = false) }

        val movable = listOf(
            easter.minusDays(48) to "Carnaval (Segunda)",
            easter.minusDays(47) to "Carnaval",
            easter.minusDays(2) to "Sexta-Feira Santa",
            easter to "Páscoa",
            easter.plusDays(60) to "Corpus Christi"
        ).map { (date, name) -> Holiday(date.toString(), name, movable = true) }

        return (fixed + movable).sortedBy { it.dateIso }
    }

    fun holidaysForYears(years: Iterable<Int>): List<Holiday> =
        years.distinct().sorted().flatMap(::holidaysForYear)

    fun holidayOn(date: LocalDate): Holiday? =
        holidaysForYear(date.year).firstOrNull { it.dateIso == date.toString() }
}

package com.autodeck.headunit.calendar

import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

data class ParsedDateRange(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val labelPt: String
)

data class ParsedDateTime(
    val dateTime: ZonedDateTime,
    val hasExplicitTime: Boolean,
    val labelPt: String
)

/**
 * Parser de datas, intervalos e horários em Português do Brasil para agenda, eventos e lembretes.
 *
 * Exemplos suportados:
 * - `"amanhã às 3 da tarde"` -> amanhã 15:00
 * - `"sexta 9h"` / `"sexta-feira às 9h30"` -> próxima sexta 09:00 / 09:30
 * - `"hoje às 8 da noite"` -> hoje 20:00
 * - `"depois de amanhã ao meio-dia"` -> +2 dias 12:00
 * - `"daqui a 20 minutos"` / `"em 1 hora e meia"` -> relativo ao instante atual
 * - `"dia 15 às 10h"`, `"25/11 14:30"`, `"2026-11-26 15:00"`
 * - Intervalos de agenda: `"hoje"`, `"amanhã"`, `"essa semana"`, `"próxima semana"`, `"fim de semana"`, `"próximos 3 dias"`, `"sexta"`
 */
object PortugueseDateTimeParser {

    private val PT_BR: Locale = Locale.forLanguageTag("pt-BR")
    private val DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy", PT_BR)
    private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm", PT_BR)

    private val MONTHS = mapOf(
        "janeiro" to 1, "jan" to 1,
        "fevereiro" to 2, "fev" to 2,
        "marco" to 3, "mar" to 3,
        "abril" to 4, "abr" to 4,
        "maio" to 5, "mai" to 5,
        "junho" to 6, "jun" to 6,
        "julho" to 7, "jul" to 7,
        "agosto" to 8, "ago" to 8,
        "setembro" to 9, "set" to 9,
        "outubro" to 10, "out" to 10,
        "novembro" to 11, "nov" to 11,
        "dezembro" to 12, "dez" to 12
    )

    private val WEEKDAYS = listOf(
        "segunda" to DayOfWeek.MONDAY,
        "terca" to DayOfWeek.TUESDAY,
        "quarta" to DayOfWeek.WEDNESDAY,
        "quinta" to DayOfWeek.THURSDAY,
        "sexta" to DayOfWeek.FRIDAY,
        "sabado" to DayOfWeek.SATURDAY,
        "domingo" to DayOfWeek.SUNDAY
    )

    private val NUMBER_WORDS = mapOf(
        "uma" to 1, "um" to 1,
        "duas" to 2, "dois" to 2,
        "tres" to 3,
        "quatro" to 4,
        "cinco" to 5,
        "seis" to 6,
        "sete" to 7,
        "oito" to 8,
        "nove" to 9,
        "dez" to 10,
        "onze" to 11,
        "doze" to 12,
        "quinze" to 15,
        "vinte" to 20,
        "trinta" to 30,
        "quarenta" to 40,
        "quarenta e cinco" to 45
    )

    private val ISO_DATE_REGEX = Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""")
    private val BR_DATE_REGEX = Regex("""\b(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?\b""")
    private val DAY_MONTH_WORD_REGEX = Regex("""\b(?:dia\s+)?(\d{1,2})\s+de\s+([a-z]+)(?:\s+de\s+(\d{4}))?\b""")
    private val DAY_ONLY_REGEX = Regex("""\bdia\s+(\d{1,2})\b""")
    private val NEXT_N_DAYS_REGEX = Regex("""\bproximos?\s+(\d{1,2})\s+dias?\b""")

    private val RELATIVE_DURATION_REGEX = Regex(
        """\b(?:daqui\s+(?:a\s+)?|em\s+)(?:(\d{1,3}|uma|um|duas|dois|tres|quatro|cinco|seis|sete|oito|nove|dez|quinze|vinte|trinta|quarenta\s+e\s+cinco)\s*(segundos?|seg|s|minutos?|min|m|horas?|hrs?|h)(?:\s+e\s+(meia|\d{1,2}\s*(?:minutos?|min)?))?|meia\s+hora)\b"""
    )

    // Exemplos: "14:30", "9h30", "9h", "3 da tarde", "as 3 da tarde", "9 horas", "9 e meia", "8 da noite"
    private val TIME_COLON_REGEX = Regex("""\b(\d{1,2}):(\d{2})(?:\s*(?:h|hs|horas?))?(?:\s*(?:da\s+)?(manha|tarde|noite|madrugada))?\b""")
    private val TIME_H_REGEX = Regex("""\b(\d{1,2})\s*(?:h|hs|horas?)(?:\s*(?:e\s+)?(\d{1,2}|meia))?(?:\s*(?:min|minutos?))?(?:\s*(?:da\s+)?(manha|tarde|noite|madrugada))?\b""")
    private val TIME_PERIOD_REGEX = Regex("""\b(?:as\s+)?(\d{1,2})(?:\s+e\s+(meia|\d{1,2}))?\s+(?:da\s+)(manha|tarde|noite|madrugada)\b""")
    private val TIME_AS_NUMBER_REGEX = Regex("""\bas\s+(\d{1,2})(?:\s+e\s+(meia|\d{1,2}))?\b""")

    fun normalize(input: String): String {
        val lower = input.lowercase(PT_BR)
        val nfd = Normalizer.normalize(lower, Normalizer.Form.NFD)
        return nfd
            .replace(Regex("""\p{M}+"""), "")
            .replace('-', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    /**
     * Interpreta um dia ou intervalo falado para a ferramenta `agenda(dia|intervalo)`.
     */
    fun parseDateRange(input: String?, now: ZonedDateTime = ZonedDateTime.now()): ParsedDateRange {
        val today = now.toLocalDate()
        val q = normalize(input.orEmpty())
        if (q.isEmpty() || q == "hoje") {
            return ParsedDateRange(today, today, "hoje")
        }
        if (q.contains("depois de amanha")) {
            val d = today.plusDays(2)
            return ParsedDateRange(d, d, "depois de amanhã (${d.format(DATE_FMT)})")
        }
        if (q.contains("amanha")) {
            val d = today.plusDays(1)
            return ParsedDateRange(d, d, "amanhã")
        }
        if (q.contains("ontem")) {
            val d = today.minusDays(1)
            return ParsedDateRange(d, d, "ontem")
        }
        if (q.contains("proxima semana") || q.contains("semana que vem")) {
            val nextMonday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
            val nextSunday = nextMonday.plusDays(6)
            return ParsedDateRange(nextMonday, nextSunday, "a próxima semana")
        }
        if (q.contains("essa semana") || q.contains("esta semana") || q == "semana" || q.contains("na semana")) {
            val endOfWeek = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
            return ParsedDateRange(today, endOfWeek, "esta semana")
        }
        if (q.contains("fim de semana") || q.contains("final de semana")) {
            val sat = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
            val sun = sat.plusDays(1)
            return ParsedDateRange(sat, sun, "o fim de semana")
        }
        NEXT_N_DAYS_REGEX.find(q)?.let { m ->
            val n = (m.groupValues[1].toIntOrNull() ?: 3).coerceIn(1, 30)
            val end = today.plusDays((n - 1).toLong())
            return ParsedDateRange(today, end, "os próximos $n dias")
        }
        if (q.contains("este mes") || q.contains("esse mes") || q == "mes") {
            val end = today.withDayOfMonth(today.lengthOfMonth())
            return ParsedDateRange(today, end, "este mês")
        }

        // Data específica ou dia da semana
        parseDateOnly(q, today)?.let { (date, label) ->
            return ParsedDateRange(date, date, label)
        }

        return ParsedDateRange(today, today, "hoje")
    }

    /**
     * Interpreta uma expressão de data + hora (ou duração relativa) em português do Brasil.
     * Retorna `null` se não houver nenhuma pista temporal reconhecível.
     */
    fun parseDateTime(input: String, now: ZonedDateTime = ZonedDateTime.now()): ParsedDateTime? {
        val q = normalize(input)
        if (q.isEmpty()) return null

        // 1) Expressão relativa ("daqui a 20 minutos", "em 1 hora e meia", "em meia hora")
        RELATIVE_DURATION_REGEX.find(q)?.let { m ->
            val full = m.value
            val dt = if (full.contains("meia hora")) {
                now.plusMinutes(30)
            } else {
                val qtyRaw = m.groupValues[1].trim()
                val unit = m.groupValues[2].trim()
                val extra = m.groupValues[3].trim()
                val qty = parseNumberToken(qtyRaw) ?: return@let
                var res = when {
                    unit.startsWith("h") -> now.plusHours(qty.toLong())
                    unit.startsWith("s") -> now.plusSeconds(qty.toLong())
                    else -> now.plusMinutes(qty.toLong())
                }
                if (extra.isNotEmpty()) {
                    res = if (extra == "meia") {
                        if (unit.startsWith("h")) res.plusMinutes(30) else res.plusSeconds(30)
                    } else {
                        val extraMin = Regex("""\d+""").find(extra)?.value?.toLongOrNull() ?: 0L
                        res.plusMinutes(extraMin)
                    }
                }
                res
            }
            val label = formatFriendlyLabel(dt, now.toLocalDate())
            return ParsedDateTime(dateTime = dt, hasExplicitTime = true, labelPt = label)
        }

        val parsedDate = parseDateOnly(q, now.toLocalDate())
        val parsedTime = parseTimeOnly(q)

        if (parsedDate == null && parsedTime == null) return null

        val hasExplicitTime = parsedTime != null
        val time = parsedTime ?: LocalTime.of(9, 0)
        var date = parsedDate?.first ?: now.toLocalDate()

        // Se o usuário falou só a hora (ex.: "às 15h") e esse horário já passou hoje, assume amanhã
        if (parsedDate == null && parsedTime != null) {
            val candidateToday = date.atTime(time).atZone(now.zone)
            if (candidateToday.isBefore(now.minusMinutes(1))) {
                date = date.plusDays(1)
            }
        }

        // Se o usuário falou o dia da semana de hoje ("sexta 9h" numa sexta às 14h), avança para a próxima semana se já passou
        if (parsedDate != null && parsedTime != null && parsedDate.first == now.toLocalDate() &&
            !q.contains("hoje") && !ISO_DATE_REGEX.containsMatchIn(q) && !BR_DATE_REGEX.containsMatchIn(q)
        ) {
            val candidate = date.atTime(time).atZone(now.zone)
            if (candidate.isBefore(now.minusMinutes(1))) {
                date = date.plusDays(7)
            }
        }

        val finalDt = date.atTime(time).atZone(now.zone)
        return ParsedDateTime(
            dateTime = finalDt,
            hasExplicitTime = hasExplicitTime,
            labelPt = formatFriendlyLabel(finalDt, now.toLocalDate())
        )
    }

    private fun parseDateOnly(q: String, today: LocalDate): Pair<LocalDate, String>? {
        ISO_DATE_REGEX.find(q)?.let { m ->
            val y = m.groupValues[1].toIntOrNull() ?: return@let
            val mo = m.groupValues[2].toIntOrNull() ?: return@let
            val d = m.groupValues[3].toIntOrNull() ?: return@let
            runCatching { LocalDate.of(y, mo, d) }.getOrNull()?.let { date ->
                return date to date.format(DATE_FMT)
            }
        }

        BR_DATE_REGEX.find(q)?.let { m ->
            val d = m.groupValues[1].toIntOrNull() ?: return@let
            val mo = m.groupValues[2].toIntOrNull() ?: return@let
            val rawY = m.groupValues[3]
            val y = when {
                rawY.length == 4 -> rawY.toIntOrNull() ?: today.year
                rawY.length == 2 -> 2000 + (rawY.toIntOrNull() ?: (today.year % 100))
                else -> {
                    val thisYear = runCatching { LocalDate.of(today.year, mo, d) }.getOrNull()
                    if (thisYear != null && thisYear.isBefore(today.minusDays(1))) today.year + 1 else today.year
                }
            }
            runCatching { LocalDate.of(y, mo, d) }.getOrNull()?.let { date ->
                return date to date.format(DATE_FMT)
            }
        }

        DAY_MONTH_WORD_REGEX.find(q)?.let { m ->
            val d = m.groupValues[1].toIntOrNull() ?: return@let
            val monthNum = MONTHS[m.groupValues[2]] ?: return@let
            val y = m.groupValues[3].toIntOrNull() ?: run {
                val cand = runCatching { LocalDate.of(today.year, monthNum, d) }.getOrNull()
                if (cand != null && cand.isBefore(today.minusDays(1))) today.year + 1 else today.year
            }
            runCatching { LocalDate.of(y, monthNum, d) }.getOrNull()?.let { date ->
                return date to date.format(DATE_FMT)
            }
        }

        if (q.contains("depois de amanha")) {
            val d = today.plusDays(2)
            return d to "depois de amanhã (${d.format(DATE_FMT)})"
        }
        if (q.contains("amanha")) {
            return today.plusDays(1) to "amanhã"
        }
        if (q.contains("hoje")) {
            return today to "hoje"
        }
        if (q.contains("ontem")) {
            return today.minusDays(1) to "ontem"
        }

        val forceNext = q.contains("proximo") || q.contains("proxima") || q.contains("que vem")
        for ((word, dow) in WEEKDAYS) {
            if (Regex("""\b$word(?:\s+feira)?\b""").containsMatchIn(q)) {
                val date = if (forceNext || today.dayOfWeek == dow && !q.contains("hoje")) {
                    if (today.dayOfWeek == dow && !forceNext) today else today.with(TemporalAdjusters.next(dow))
                } else {
                    today.with(TemporalAdjusters.nextOrSame(dow))
                }
                return date to "${weekdayNamePt(dow)} (${date.format(DATE_FMT)})"
            }
        }

        DAY_ONLY_REGEX.find(q)?.let { m ->
            val d = m.groupValues[1].toIntOrNull() ?: return@let
            if (d in 1..31) {
                var target = runCatching { LocalDate.of(today.year, today.monthValue, d) }.getOrNull()
                if (target == null || target.isBefore(today)) {
                    val nextMonth = today.plusMonths(1)
                    target = runCatching { LocalDate.of(nextMonth.year, nextMonth.monthValue, d) }.getOrNull()
                }
                if (target != null) return target to target.format(DATE_FMT)
            }
        }

        return null
    }

    private fun parseTimeOnly(q: String): LocalTime? {
        val normTime = replaceSpokenHoursWithDigits(q)
        if (Regex("""\bmeio\s+dia\b""").containsMatchIn(normTime)) {
            return if (normTime.contains("meio dia e meia")) LocalTime.of(12, 30) else LocalTime.of(12, 0)
        }
        if (Regex("""\bmeia\s+noite\b""").containsMatchIn(normTime)) {
            return if (normTime.contains("meia noite e meia")) LocalTime.of(0, 30) else LocalTime.of(0, 0)
        }

        TIME_COLON_REGEX.find(normTime)?.let { m ->
            val rawH = m.groupValues[1].toIntOrNull() ?: return@let
            val min = m.groupValues[2].toIntOrNull() ?: return@let
            val period = m.groupValues[3]
            val h = adjustHourForPeriod(rawH, period)
            if (h in 0..23 && min in 0..59) return LocalTime.of(h, min)
        }

        TIME_H_REGEX.find(normTime)?.let { m ->
            val rawH = m.groupValues[1].toIntOrNull() ?: return@let
            val rawMin = m.groupValues[2]
            val period = m.groupValues[3]
            val min = when {
                rawMin.isEmpty() -> 0
                rawMin == "meia" -> 30
                else -> rawMin.toIntOrNull() ?: 0
            }
            val h = adjustHourForPeriod(rawH, period)
            if (h in 0..23 && min in 0..59) return LocalTime.of(h, min)
        }

        TIME_PERIOD_REGEX.find(normTime)?.let { m ->
            val rawH = m.groupValues[1].toIntOrNull() ?: return@let
            val rawMin = m.groupValues[2]
            val period = m.groupValues[3]
            val min = when {
                rawMin.isEmpty() -> 0
                rawMin == "meia" -> 30
                else -> rawMin.toIntOrNull() ?: 0
            }
            val h = adjustHourForPeriod(rawH, period)
            if (h in 0..23 && min in 0..59) return LocalTime.of(h, min)
        }

        TIME_AS_NUMBER_REGEX.find(normTime)?.let { m ->
            val rawH = m.groupValues[1].toIntOrNull() ?: return@let
            val rawMin = m.groupValues[2]
            val min = when {
                rawMin.isEmpty() -> 0
                rawMin == "meia" -> 30
                else -> rawMin.toIntOrNull() ?: 0
            }
            if (rawH in 0..23 && min in 0..59) return LocalTime.of(rawH, min)
        }

        // Fallback para período do dia sem hora numérica ("amanhã à tarde", "sexta de manhã", "hoje à noite")
        return when {
            Regex("""\b(?:de|da|pela)\s+manha\b""").containsMatchIn(normTime) -> LocalTime.of(9, 0)
            Regex("""\b(?:a|de|da|pela)\s+tarde\b""").containsMatchIn(normTime) -> LocalTime.of(15, 0)
            Regex("""\b(?:a|de|da|pela)\s+noite\b""").containsMatchIn(normTime) -> LocalTime.of(20, 0)
            else -> null
        }
    }

    private fun replaceSpokenHoursWithDigits(q: String): String {
        val hourWords = mapOf(
            "uma" to "1", "um" to "1",
            "duas" to "2", "dois" to "2",
            "tres" to "3",
            "quatro" to "4",
            "cinco" to "5",
            "seis" to "6",
            "sete" to "7",
            "oito" to "8",
            "nove" to "9",
            "dez" to "10",
            "onze" to "11",
            "doze" to "12"
        )
        val pattern = Regex(
            """\b(uma|um|duas|dois|tres|quatro|cinco|seis|sete|oito|nove|dez|onze|doze)(?=\s*(?:h|hs|horas?|e\s+meia|da\s+(?:manha|tarde|noite|madrugada))\b)"""
        )
        return pattern.replace(q) { m -> hourWords[m.groupValues[1]] ?: m.value }
    }

    private fun adjustHourForPeriod(hour: Int, period: String): Int {
        return when (period) {
            "tarde" -> if (hour in 1..11) hour + 12 else hour
            "noite" -> if (hour in 1..11) hour + 12 else hour
            "manha" -> if (hour == 12) 0 else hour
            "madrugada" -> if (hour == 12) 0 else hour
            else -> hour
        }
    }

    private fun parseNumberToken(token: String): Int? =
        token.toIntOrNull() ?: NUMBER_WORDS[token]

    private fun formatFriendlyLabel(dt: ZonedDateTime, today: LocalDate): String {
        val d = dt.toLocalDate()
        val dayStr = when (d) {
            today -> "hoje"
            today.plusDays(1) -> "amanhã"
            today.plusDays(2) -> "depois de amanhã (${d.format(DATE_FMT)})"
            else -> "${weekdayNamePt(d.dayOfWeek)} (${d.format(DATE_FMT)})"
        }
        return "$dayStr às ${dt.format(TIME_FMT)}"
    }

    private fun weekdayNamePt(dow: DayOfWeek): String = when (dow) {
        DayOfWeek.MONDAY -> "segunda-feira"
        DayOfWeek.TUESDAY -> "terça-feira"
        DayOfWeek.WEDNESDAY -> "quarta-feira"
        DayOfWeek.THURSDAY -> "quinta-feira"
        DayOfWeek.FRIDAY -> "sexta-feira"
        DayOfWeek.SATURDAY -> "sábado"
        DayOfWeek.SUNDAY -> "domingo"
    }
}

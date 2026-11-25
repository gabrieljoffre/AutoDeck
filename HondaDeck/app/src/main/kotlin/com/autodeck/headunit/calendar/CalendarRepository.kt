package com.autodeck.headunit.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.autodeck.protocol.phone.PhoneCalendarEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

data class CalendarEvent(
    val id: String,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val allDay: Boolean,
    val location: String = "",
    val colorHex: String = "#22D3EE",
    val dateIso: String = "",
    val endDateIso: String = "",
    val source: String = "tablet" // "tablet" | "phone"
)

data class CalendarState(
    val permissionGranted: Boolean = false,
    val events: List<CalendarEvent> = emptyList(),
    val updatedAtMs: Long = 0L,
    val statusText: String = "",
    val phoneEventsCount: Int = 0,
    val phoneCalendarAvailable: Boolean = false
) {
    val hasAnyCalendarSource: Boolean get() = permissionGranted || phoneCalendarAvailable || phoneEventsCount > 0
}

/**
 * Lê e cria eventos sincronizados da agenda do tablet (Google Agenda via `CalendarContract`)
 * e mescla eventos vindos do celular Samsung via HondaDeck Ponte.
 */
object CalendarRepository {

    private val PT_BR: Locale = Locale.forLanguageTag("pt-BR")
    private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", PT_BR)
    private val DAY_MONTH_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM", PT_BR)

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun hasWritePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun defaultWindowBounds(nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val startMs = today.minusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = today.plusDays(46).atStartOfDay(zone).toInstant().toEpochMilli()
        return startMs to endMs
    }

    suspend fun load(context: Context, nowMs: Long = System.currentTimeMillis()): CalendarState {
        val zone = ZoneId.systemDefault()
        val (windowStartMs, windowEndMs) = defaultWindowBounds(nowMs, zone)
        return loadRange(context, windowStartMs, windowEndMs, nowMs)
    }

    suspend fun loadRange(
        context: Context,
        windowStartMs: Long,
        windowEndMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): CalendarState =
        withContext(Dispatchers.IO) {
            if (!hasPermission(context)) {
                return@withContext CalendarState(
                    permissionGranted = false,
                    events = emptyList(),
                    updatedAtMs = nowMs,
                    statusText = "Permitir acesso à agenda"
                )
            }

            val zone = ZoneId.systemDefault()

            val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(uriBuilder, windowStartMs)
            ContentUris.appendId(uriBuilder, windowEndMs)

            val projection = arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.DISPLAY_COLOR,
                CalendarContract.Instances.CALENDAR_COLOR
            )

            val events = mutableListOf<CalendarEvent>()
            runCatching {
                context.contentResolver.query(
                    uriBuilder.build(),
                    projection,
                    "${CalendarContract.Instances.VISIBLE} = 1",
                    null,
                    "${CalendarContract.Instances.BEGIN} ASC"
                )?.use { cursor ->
                    val idxId = cursor.getColumnIndex(CalendarContract.Instances.EVENT_ID)
                    val idxTitle = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
                    val idxBegin = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
                    val idxEnd = cursor.getColumnIndex(CalendarContract.Instances.END)
                    val idxAllDay = cursor.getColumnIndex(CalendarContract.Instances.ALL_DAY)
                    val idxLoc = cursor.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
                    val idxDispColor = cursor.getColumnIndex(CalendarContract.Instances.DISPLAY_COLOR)
                    val idxCalColor = cursor.getColumnIndex(CalendarContract.Instances.CALENDAR_COLOR)

                    while (cursor.moveToNext()) {
                        val id = if (idxId >= 0) cursor.getLong(idxId).toString() else ""
                        val title = if (idxTitle >= 0) cursor.getString(idxTitle).orEmpty().trim() else ""
                        if (title.isBlank()) continue
                        val startMs = if (idxBegin >= 0) cursor.getLong(idxBegin) else 0L
                        val endMs = if (idxEnd >= 0) cursor.getLong(idxEnd) else startMs
                        val allDay = if (idxAllDay >= 0) cursor.getInt(idxAllDay) == 1 else false
                        val location = if (idxLoc >= 0) cursor.getString(idxLoc).orEmpty().trim() else ""

                        val rawColor = when {
                            idxDispColor >= 0 && !cursor.isNull(idxDispColor) -> cursor.getInt(idxDispColor)
                            idxCalColor >= 0 && !cursor.isNull(idxCalColor) -> cursor.getInt(idxCalColor)
                            else -> 0
                        }
                        val colorHex = if (rawColor != 0) {
                            String.format(Locale.US, "#%06X", rawColor and 0xFFFFFF)
                        } else {
                            "#22D3EE"
                        }

                        val (dateIso, endDateIso) = resolveDateRange(startMs, endMs, allDay, zone)
                        events += CalendarEvent(
                            id = "${id}_$startMs",
                            title = title,
                            startMs = startMs,
                            endMs = endMs,
                            allDay = allDay,
                            location = location,
                            colorHex = colorHex,
                            dateIso = dateIso,
                            endDateIso = endDateIso,
                            source = "tablet"
                        )
                    }
                }
            }

            CalendarState(
                permissionGranted = true,
                events = events,
                updatedAtMs = nowMs,
                statusText = if (events.isEmpty()) "Sem eventos próximos" else "${events.size} eventos"
            )
        }

    /**
     * Cria um evento no calendário Google principal do tablet (`WRITE_CALENDAR`).
     * Retorna falha se o tablet não tiver permissão ou não tiver conta Google/calendário gravável.
     */
    suspend fun createEvent(
        context: Context,
        title: String,
        startMs: Long,
        endMs: Long,
        location: String = "",
        reminderMinutes: Int? = null
    ): Result<CalendarEvent> = withContext(Dispatchers.IO) {
        if (!hasWritePermission(context)) {
            return@withContext Result.failure(SecurityException("Permissão WRITE_CALENDAR não concedida no tablet."))
        }
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Título do evento não informado."))
        }
        val calendarId = findPrimaryWritableCalendarId(context)
            ?: return@withContext Result.failure(IllegalStateException("Nenhuma conta Google Agenda configurada no tablet."))

        val safeEndMs = endMs.coerceAtLeast(startMs + 15 * 60_000L)
        runCatching {
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, cleanTitle)
                put(CalendarContract.Events.DTSTART, startMs)
                put(CalendarContract.Events.DTEND, safeEndMs)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                if (location.isNotBlank()) {
                    put(CalendarContract.Events.EVENT_LOCATION, location.trim())
                }
            }
            val insertedUri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: throw IllegalStateException("Falha ao inserir evento na agenda do tablet.")
            val eventId = ContentUris.parseId(insertedUri)

            if (reminderMinutes != null && reminderMinutes >= 0) {
                val remValues = ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, eventId)
                    put(CalendarContract.Reminders.MINUTES, reminderMinutes)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                }
                runCatching {
                    context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, remValues)
                }
            }

            val zone = ZoneId.systemDefault()
            val (dateIso, endDateIso) = resolveDateRange(startMs, safeEndMs, false, zone)
            CalendarEvent(
                id = "${eventId}_$startMs",
                title = cleanTitle,
                startMs = startMs,
                endMs = safeEndMs,
                allDay = false,
                location = location.trim(),
                colorHex = "#22D3EE",
                dateIso = dateIso,
                endDateIso = endDateIso,
                source = "tablet"
            )
        }
    }

    private fun findPrimaryWritableCalendarId(context: Context): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE
        )
        var bestId: Long? = null
        var bestRank = -1
        runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
                arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
                null
            )?.use { c ->
                val idxId = c.getColumnIndex(CalendarContract.Calendars._ID)
                val idxType = c.getColumnIndex(CalendarContract.Calendars.ACCOUNT_TYPE)
                val idxPrimary = c.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)
                val idxVisible = c.getColumnIndex(CalendarContract.Calendars.VISIBLE)
                while (c.moveToNext()) {
                    val id = if (idxId >= 0) c.getLong(idxId) else continue
                    val accType = if (idxType >= 0) c.getString(idxType).orEmpty() else ""
                    val isPrimary = if (idxPrimary >= 0) c.getInt(idxPrimary) == 1 else false
                    val visible = if (idxVisible >= 0) c.getInt(idxVisible) == 1 else true
                    val isGoogle = accType.equals("com.google", ignoreCase = true)
                    val rank = (if (isGoogle) 4 else 0) + (if (isPrimary) 2 else 0) + (if (visible) 1 else 0)
                    if (rank > bestRank) {
                        bestRank = rank
                        bestId = id
                    }
                }
            }
        }
        return bestId
    }

    /**
     * Mescla os eventos do tablet com os eventos vindos do celular (Ponte),
     * deduplicando compromissos iguais (mesmo título normalizado e horário dentro de 2 min).
     */
    fun mergeWithPhoneEvents(
        tabletState: CalendarState,
        phoneEvents: List<PhoneCalendarEvent>,
        zone: ZoneId = ZoneId.systemDefault(),
        phoneCalendarAvailable: Boolean = phoneEvents.isNotEmpty()
    ): CalendarState {
        if (phoneEvents.isEmpty()) {
            return if (phoneCalendarAvailable) {
                tabletState.copy(
                    phoneCalendarAvailable = true,
                    statusText = if (!tabletState.permissionGranted && tabletState.events.isEmpty()) {
                        "Sem eventos no celular"
                    } else {
                        tabletState.statusText
                    }
                )
            } else {
                tabletState
            }
        }
        val converted = phoneEvents.map { pev ->
            val (dIso, eIso) = if (pev.dateIso.isNotBlank()) {
                pev.dateIso to pev.endDateIso.ifBlank { pev.dateIso }
            } else {
                resolveDateRange(pev.startMs, pev.endMs, pev.allDay, zone)
            }
            CalendarEvent(
                id = pev.id,
                title = pev.title,
                startMs = pev.startMs,
                endMs = pev.endMs,
                allDay = pev.allDay,
                location = pev.location,
                colorHex = pev.colorHex.ifBlank { "#A78BFA" },
                dateIso = dIso,
                endDateIso = eIso,
                source = "phone"
            )
        }

        val merged = tabletState.events.toMutableList()
        var addedFromPhone = 0
        for (pev in converted) {
            val normTitle = normalizeTitle(pev.title)
            val duplicate = merged.any { existing ->
                normalizeTitle(existing.title) == normTitle &&
                    (existing.allDay == pev.allDay) &&
                    (abs(existing.startMs - pev.startMs) <= 120_000L || (existing.allDay && existing.dateIso == pev.dateIso))
            }
            if (!duplicate) {
                merged += pev
                addedFromPhone++
            }
        }

        val sorted = merged.sortedBy { it.startMs }
        val status = when {
            sorted.isEmpty() && !tabletState.permissionGranted -> "Sem eventos no celular"
            sorted.isEmpty() -> "Sem eventos próximos"
            !tabletState.permissionGranted -> "${sorted.size} eventos (do celular)"
            addedFromPhone > 0 -> "${sorted.size} eventos (tablet + celular)"
            else -> "${sorted.size} eventos"
        }
        return tabletState.copy(
            events = sorted,
            phoneEventsCount = converted.size,
            phoneCalendarAvailable = true,
            statusText = status
        )
    }

    internal fun resolveDateRange(
        startMs: Long,
        endMs: Long,
        allDay: Boolean,
        zone: ZoneId = ZoneId.systemDefault()
    ): Pair<String, String> {
        return if (allDay) {
            val startDate = Instant.ofEpochMilli(startMs).atZone(ZoneOffset.UTC).toLocalDate()
            val rawEnd = Instant.ofEpochMilli(endMs).atZone(ZoneOffset.UTC).toLocalDate()
            val inclusiveEnd = if (rawEnd.isAfter(startDate)) rawEnd.minusDays(1) else startDate
            startDate.toString() to inclusiveEnd.toString()
        } else {
            val startDate = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
            val endDate = Instant.ofEpochMilli(endMs.coerceAtLeast(startMs)).atZone(zone).toLocalDate()
            startDate.toString() to endDate.toString()
        }
    }

    /**
     * Formata resumo falado da agenda para o Copiloto (hoje: `dayOffset = 0`, amanhã: `dayOffset = 1`).
     */
    fun formatAgendaSummary(
        state: CalendarState,
        dayOffset: Int = 0,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        val targetDate = today.plusDays(dayOffset.toLong())
        val dayWord = when (dayOffset) {
            0 -> "hoje"
            1 -> "amanhã"
            else -> targetDate.toString()
        }
        return formatAgendaRangeSummary(
            state = state,
            range = ParsedDateRange(targetDate, targetDate, dayWord),
            zone = zone
        )
    }

    /**
     * Formata resumo falado da agenda para um dia específico ou intervalo de dias.
     */
    fun formatAgendaRangeSummary(
        state: CalendarState,
        range: ParsedDateRange,
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        val singleDay = range.startDate == range.endDate
        val startIso = range.startDate.toString()
        val endIso = range.endDate.toString()

        val holidaysInRange = buildList {
            var d = range.startDate
            while (!d.isAfter(range.endDate)) {
                BrazilHolidays.holidayOn(d)?.let { add(d to it) }
                d = d.plusDays(1)
            }
        }

        if (!state.hasAnyCalendarSource) {
            val holidayNote = holidaysInRange.firstOrNull()?.let { (_, h) ->
                if (singleDay) " Hoje é feriado de ${h.name}." else " Há feriado de ${h.name} no período."
            }.orEmpty()
            return "O acesso à agenda ainda não foi permitido. Toque na data no topo da tela para permitir ou conecte o celular na Ponte.$holidayNote"
        }

        val matching = state.events.filter { ev ->
            val evStart = ev.dateIso.ifBlank {
                Instant.ofEpochMilli(ev.startMs).atZone(zone).toLocalDate().toString()
            }
            val evEnd = ev.endDateIso.ifBlank { evStart }
            evEnd >= startIso && evStart <= endIso
        }

        if (matching.isEmpty()) {
            val holidayNote = holidaysInRange.firstOrNull()?.let { (_, h) ->
                " É feriado de ${h.name}."
            }.orEmpty()
            return "Você não tem compromissos na agenda para ${range.labelPt}.$holidayNote"
        }

        val maxItems = if (singleDay) 5 else 8
        val items = matching.take(maxItems).joinToString("; ") { ev ->
            val evDate = runCatching { LocalDate.parse(ev.dateIso) }.getOrNull()
                ?: Instant.ofEpochMilli(ev.startMs).atZone(zone).toLocalDate()
            val datePrefix = if (!singleDay) "${evDate.format(DAY_MONTH_FMT)} " else ""
            val whenStr = if (ev.allDay) {
                "dia inteiro"
            } else {
                Instant.ofEpochMilli(ev.startMs).atZone(zone).format(TIME_FMT)
            }
            val locStr = if (ev.location.isNotBlank()) " em ${ev.location}" else ""
            "$datePrefix$whenStr: ${ev.title}$locStr"
        }
        val extra = if (matching.size > maxItems) " e mais ${matching.size - maxItems}" else ""
        val holidayPrefix = if (holidaysInRange.isNotEmpty()) {
            val names = holidaysInRange.joinToString(", ") { (d, h) ->
                if (singleDay) h.name else "${h.name} (${d.format(DAY_MONTH_FMT)})"
            }
            "Feriado: $names. "
        } else {
            ""
        }
        val countWord = if (matching.size == 1) "compromisso" else "compromissos"
        return "${holidayPrefix}Sua agenda para ${range.labelPt} tem ${matching.size} $countWord: $items$extra."
    }

    private fun normalizeTitle(s: String): String {
        val nfd = Normalizer.normalize(s.lowercase(PT_BR), Normalizer.Form.NFD)
        return nfd.replace(Regex("""\p{M}+"""), "").replace(Regex("""\s+"""), " ").trim()
    }
}

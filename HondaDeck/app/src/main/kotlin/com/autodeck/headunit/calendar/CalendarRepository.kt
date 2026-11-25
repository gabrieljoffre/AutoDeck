package com.autodeck.headunit.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

data class CalendarEvent(
    val id: String,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val allDay: Boolean,
    val location: String = "",
    val colorHex: String = "#22D3EE",
    val dateIso: String = "",
    val endDateIso: String = ""
)

data class CalendarState(
    val permissionGranted: Boolean = false,
    val events: List<CalendarEvent> = emptyList(),
    val updatedAtMs: Long = 0L,
    val statusText: String = ""
)

/**
 * Lê eventos sincronizados da agenda do tablet (Google Agenda via `CalendarContract.Instances`)
 * na janela de -7 a +45 dias.
 */
object CalendarRepository {

    private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag("pt-BR"))

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun load(context: Context, nowMs: Long = System.currentTimeMillis()): CalendarState =
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
            val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            val windowStartMs = today.minusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
            val windowEndMs = today.plusDays(46).atStartOfDay(zone).toInstant().toEpochMilli()

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
                            endDateIso = endDateIso
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
        val targetIso = targetDate.toString()
        val dayWord = when (dayOffset) {
            0 -> "hoje"
            1 -> "amanhã"
            else -> targetIso
        }
        val holiday = BrazilHolidays.holidayOn(targetDate)

        if (!state.permissionGranted) {
            val holidayNote = holiday?.let { " Hoje é feriado de ${it.name}." }.orEmpty()
            return "O acesso à agenda ainda não foi permitido. Toque na data no topo da tela para permitir.$holidayNote"
        }

        val matching = state.events.filter { ev ->
            val startIso = ev.dateIso.ifBlank {
                Instant.ofEpochMilli(ev.startMs).atZone(zone).toLocalDate().toString()
            }
            val endIso = ev.endDateIso.ifBlank { startIso }
            targetIso in startIso..endIso
        }

        if (matching.isEmpty()) {
            val holidayNote = holiday?.let { " É feriado de ${it.name}." }.orEmpty()
            return "Você não tem compromissos na agenda para $dayWord.$holidayNote"
        }

        val items = matching.take(5).joinToString("; ") { ev ->
            val whenStr = if (ev.allDay) {
                "dia inteiro"
            } else {
                Instant.ofEpochMilli(ev.startMs).atZone(zone).format(TIME_FMT)
            }
            val locStr = if (ev.location.isNotBlank()) " em ${ev.location}" else ""
            "$whenStr: ${ev.title}$locStr"
        }
        val extra = if (matching.size > 5) " e mais ${matching.size - 5}" else ""
        val holidayPrefix = holiday?.let { "Feriado: ${it.name}. " }.orEmpty()
        return "${holidayPrefix}Sua agenda para $dayWord tem ${matching.size} ${if (matching.size == 1) "compromisso" else "compromissos"}: $items$extra."
    }
}

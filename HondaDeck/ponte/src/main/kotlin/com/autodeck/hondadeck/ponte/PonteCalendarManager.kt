package com.autodeck.hondadeck.ponte

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.autodeck.protocol.phone.PhoneCalendarEvent
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.TimeZone

/**
 * Lê e cria eventos na agenda do celular (`READ_CALENDAR` / `WRITE_CALENDAR`),
 * usado pela Ponte quando o tablet não tem conta Google configurada ou para
 * unificar os compromissos do celular no painel HondaDeck.
 */
class PonteCalendarManager(private val context: Context) {

    fun hasReadPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun hasWritePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun listEvents(startMs: Long, endMs: Long): Result<List<PhoneCalendarEvent>> {
        if (!hasReadPermission()) {
            return Result.failure(SecurityException("Permissão de leitura de agenda (READ_CALENDAR) não concedida no celular."))
        }
        val zone = ZoneId.systemDefault()
        val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(uriBuilder, startMs)
        ContentUris.appendId(uriBuilder, endMs)

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

        val events = mutableListOf<PhoneCalendarEvent>()
        return runCatching {
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
                    val eventId = if (idxId >= 0) cursor.getLong(idxId).toString() else ""
                    val title = if (idxTitle >= 0) cursor.getString(idxTitle).orEmpty().trim() else ""
                    if (title.isBlank()) continue
                    val begin = if (idxBegin >= 0) cursor.getLong(idxBegin) else 0L
                    val end = if (idxEnd >= 0) cursor.getLong(idxEnd) else begin
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
                        "#A78BFA"
                    }

                    val (dateIso, endDateIso) = resolveDateRange(begin, end, allDay, zone)
                    events += PhoneCalendarEvent(
                        id = "phone_${eventId}_$begin",
                        title = title,
                        startMs = begin,
                        endMs = end,
                        allDay = allDay,
                        location = location,
                        colorHex = colorHex,
                        dateIso = dateIso,
                        endDateIso = endDateIso
                    )
                }
            }
            events
        }
    }

    fun createEvent(
        title: String,
        startMs: Long,
        endMs: Long,
        location: String = "",
        reminderMinutes: Int? = null
    ): Result<PhoneCalendarEvent> {
        if (!hasWritePermission()) {
            return Result.failure(SecurityException("Permissão de escrita na agenda (WRITE_CALENDAR) não concedida no celular."))
        }
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) {
            return Result.failure(IllegalArgumentException("Título do evento não pode estar vazio."))
        }
        val calendarId = findPrimaryWritableCalendarId()
            ?: return Result.failure(IllegalStateException("Nenhum calendário gravável (Google Agenda) encontrado no celular."))

        val safeEndMs = endMs.coerceAtLeast(startMs + 15 * 60_000L)
        return runCatching {
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
                ?: throw IllegalStateException("Não foi possível salvar o evento na agenda do celular.")
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
            PhoneCalendarEvent(
                id = "phone_${eventId}_$startMs",
                title = cleanTitle,
                startMs = startMs,
                endMs = safeEndMs,
                allDay = false,
                location = location.trim(),
                colorHex = "#A78BFA",
                dateIso = dateIso,
                endDateIso = endDateIso
            )
        }
    }

    private fun findPrimaryWritableCalendarId(): Long? {
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

    private fun resolveDateRange(startMs: Long, endMs: Long, allDay: Boolean, zone: ZoneId): Pair<String, String> {
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
}

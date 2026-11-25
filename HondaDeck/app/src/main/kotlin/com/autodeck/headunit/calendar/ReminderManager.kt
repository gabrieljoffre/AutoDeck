package com.autodeck.headunit.calendar

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.autodeck.headunit.MainActivity
import com.autodeck.headunit.R
import com.autodeck.protocol.phone.PhoneLinkCodec
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class LocalReminder(
    val id: Int,
    val text: String,
    val triggerAtMs: Long,
    val createdAtMs: Long = System.currentTimeMillis(),
    val fired: Boolean = false
)

/**
 * Gerencia lembretes locais no tablet:
 * - Persiste lembretes em `reminders.json`.
 * - Agenda alarme no `AlarmManager` (entregue ao [ReminderAlarmReceiver]) e também suporta
 *   verificação periódica no runtime para aviso falado (TTS) + notificação local + toast.
 */
class ReminderManager(
    private val context: Context,
    private val file: File = File(context.filesDir, "local_reminders.json")
) {
    private val lock = Any()
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    fun scheduleReminder(text: String, triggerAtMs: Long, nowMs: Long = System.currentTimeMillis()): Result<LocalReminder> {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) {
            return Result.failure(IllegalArgumentException("Informe o texto do lembrete."))
        }
        val safeTrigger = triggerAtMs.coerceAtLeast(nowMs + 5_000L)
        val reminder = LocalReminder(
            id = ((safeTrigger / 1000L) % Int.MAX_VALUE).toInt().let { if (it <= 0) 1001 else it },
            text = cleanText,
            triggerAtMs = safeTrigger,
            createdAtMs = nowMs,
            fired = false
        )
        synchronized(lock) {
            val current = readLocked().filter { !it.fired && it.triggerAtMs > nowMs - 60_000L }
            writeLocked(current + reminder)
        }
        scheduleAlarm(reminder)
        return Result.success(reminder)
    }

    fun listActive(nowMs: Long = System.currentTimeMillis()): List<LocalReminder> = synchronized(lock) {
        readLocked()
            .filter { !it.fired && it.triggerAtMs >= nowMs - 60_000L }
            .sortedBy { it.triggerAtMs }
    }

    /**
     * Retorna lembretes cujo horário já chegou e marca-os como disparados (para disparo no app com TTS).
     */
    fun popDueReminders(nowMs: Long = System.currentTimeMillis()): List<LocalReminder> = synchronized(lock) {
        val all = readLocked()
        val due = all.filter { !it.fired && it.triggerAtMs <= nowMs }
        if (due.isEmpty()) return emptyList()
        val dueIds = due.map { it.id }.toSet()
        val updated = all.map { r -> if (r.id in dueIds) r.copy(fired = true) else r }
            .filter { !it.fired || nowMs - it.triggerAtMs < 24L * 3_600_000L }
        writeLocked(updated)
        due
    }

    fun markFired(id: Int) = synchronized(lock) {
        val all = readLocked()
        val updated = all.map { if (it.id == id) it.copy(fired = true) else it }
        writeLocked(updated)
    }

    private fun scheduleAlarm(reminder: LocalReminder) {
        val am = alarmManager ?: return
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ReminderAlarmReceiver.ACTION_REMINDER_TRIGGER
            putExtra(ReminderAlarmReceiver.EXTRA_REMINDER_ID, reminder.id)
            putExtra(ReminderAlarmReceiver.EXTRA_REMINDER_TEXT, reminder.text)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getBroadcast(context, reminder.id, intent, flags)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAtMs, pi)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, reminder.triggerAtMs, pi)
            }
        }
    }

    private fun readLocked(): List<LocalReminder> {
        if (!file.exists() || file.length() == 0L) return emptyList()
        return runCatching {
            val raw = file.readText(Charsets.UTF_8)
            val list = PhoneLinkCodec.parseJsonValue(raw) as? List<*> ?: return emptyList()
            list.mapNotNull { item ->
                val m = item as? Map<*, *> ?: return@mapNotNull null
                val id = (m["id"] as? Number)?.toInt() ?: return@mapNotNull null
                val text = m["text"]?.toString()?.trim().orEmpty()
                val trigger = (m["triggerAtMs"] as? Number)?.toLong() ?: return@mapNotNull null
                if (text.isBlank()) return@mapNotNull null
                LocalReminder(
                    id = id,
                    text = text,
                    triggerAtMs = trigger,
                    createdAtMs = (m["createdAtMs"] as? Number)?.toLong() ?: 0L,
                    fired = m["fired"] as? Boolean ?: false
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun writeLocked(items: List<LocalReminder>) {
        runCatching {
            file.parentFile?.mkdirs()
            val raw = items.map { r ->
                linkedMapOf<String, Any?>(
                    "id" to r.id,
                    "text" to r.text,
                    "triggerAtMs" to r.triggerAtMs,
                    "createdAtMs" to r.createdAtMs,
                    "fired" to r.fired
                )
            }
            file.writeText(PhoneLinkCodec.encodeValue(raw), Charsets.UTF_8)
        }
    }

    companion object {
        const val CHANNEL_ID = "hondadeck_reminders"
        private val TIME_FMT = DateTimeFormatter.ofPattern("dd/MM 'às' HH:mm", Locale.forLanguageTag("pt-BR"))

        fun formatTriggerTime(triggerAtMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
            Instant.ofEpochMilli(triggerAtMs).atZone(zone).format(TIME_FMT)

        fun showNotification(context: Context, id: Int, text: String) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Lembretes HondaDeck",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Lembretes criados pelo copiloto do painel"
                }
                nm.createNotificationChannel(channel)
            }
            val openIntent = PendingIntent.getActivity(
                context,
                id,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Lembrete HondaDeck")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openIntent)
                .build()
            runCatching { nm.notify(7000 + (id % 1000), notif) }
        }
    }
}

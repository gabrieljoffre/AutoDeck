package com.autodeck.headunit.calendar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.autodeck.headunit.service.HeadUnitRuntime

/**
 * Recebe alarmes de lembretes agendados pelo copiloto, exibe notificação local
 * e avisa o [HeadUnitRuntime] para falar o lembrete em voz alta no carro.
 */
class ReminderAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REMINDER_TRIGGER) return
        val id = intent.getIntExtra(EXTRA_REMINDER_ID, 0)
        val text = intent.getStringExtra(EXTRA_REMINDER_TEXT)?.trim().orEmpty()
        if (text.isBlank()) return

        runCatching { ReminderManager(context.applicationContext).markFired(id) }
        ReminderManager.showNotification(context.applicationContext, id, text)
        runCatching {
            HeadUnitRuntime.get(context.applicationContext).onReminderTriggered(id, text)
        }
    }

    companion object {
        const val ACTION_REMINDER_TRIGGER = "com.autodeck.headunit.REMINDER_TRIGGER"
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_REMINDER_TEXT = "reminder_text"
    }
}

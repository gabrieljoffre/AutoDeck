package com.autodeck.headunit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.autodeck.headunit.data.HeadUnitPreferences
import com.autodeck.headunit.service.HeadUnitForegroundService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val config = HeadUnitPreferences(context).load()
        if (!config.setupCompleted || !config.autoStartOnPower) return

        // No Android 10+, iniciar o ForegroundService sempre é permitido no BOOT_COMPLETED;
        // abrir Activity só funciona se o app tiver SYSTEM_ALERT_WINDOW ou for o Launcher HOME.
        runCatching { HeadUnitForegroundService.start(context) }

        if (Settings.canDrawOverlays(context)) {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            runCatching { context.startActivity(launchIntent) }
        }
    }
}

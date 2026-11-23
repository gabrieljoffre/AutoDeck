package com.autodeck.headunit.media

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Serviço do sistema Android que dá acesso em tempo real às sessões de mídia
 * (Spotify, YouTube Music, etc.) e notificações de mensagens (WhatsApp)
 * com suporte a resposta direta via RemoteInput.
 */
class HondaNotificationMediaService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        MediaAndNotificationController.get(applicationContext).onListenerConnected(
            ComponentName(this, HondaNotificationMediaService::class.java)
        )
    }

    override fun onListenerDisconnected() {
        MediaAndNotificationController.get(applicationContext).onListenerDisconnected()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName ?: return
        if (pkg !in MESSAGING_PACKAGES) return
        if ((sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        MediaAndNotificationController.get(applicationContext).onMessagingNotificationPosted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName in MESSAGING_PACKAGES) {
            MediaAndNotificationController.get(applicationContext).onMessagingNotificationRemoved(sbn.key)
        }
    }

    companion object {
        private val MESSAGING_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "com.google.android.apps.messaging"
        )

        fun componentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, HondaNotificationMediaService::class.java)

        fun isPermissionGranted(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val target = componentName(context).flattenToString()
            return flat.split(':').any { it.equals(target, ignoreCase = true) }
        }

        fun buildSettingsIntent(): Intent =
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}

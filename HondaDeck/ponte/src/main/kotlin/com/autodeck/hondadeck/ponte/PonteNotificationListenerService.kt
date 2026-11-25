package com.autodeck.hondadeck.ponte

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.autodeck.protocol.phone.PhoneMessageNotification
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Ponte em memória entre o [PonteNotificationListenerService] e o servidor TCP da Ponte.
 * Guarda os RemoteInputs ativos para permitir `messages.reply(id, texto)`.
 */
object PonteNotificationBridge {

    data class StoredNotification(
        val notification: PhoneMessageNotification,
        val replyAction: Notification.Action?,
        val remoteInput: RemoteInput?
    )

    sealed interface NotifEvent {
        data class Received(val message: PhoneMessageNotification) : NotifEvent
        data class Removed(val id: String) : NotifEvent
    }

    private val active = ConcurrentHashMap<String, StoredNotification>()

    private val _events = MutableSharedFlow<NotifEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<NotifEvent> = _events.asSharedFlow()

    @Volatile
    var isConnected: Boolean = false
        internal set

    fun listMessages(): List<PhoneMessageNotification> =
        active.values
            .map { it.notification }
            .sortedByDescending { it.timestampMs }
            .take(25)

    internal fun onPosted(entry: StoredNotification) {
        active[entry.notification.id] = entry
        _events.tryEmit(NotifEvent.Received(entry.notification))
    }

    internal fun onRemoved(id: String) {
        if (active.remove(id) != null) {
            _events.tryEmit(NotifEvent.Removed(id))
        }
    }

    fun dismiss(id: String): Boolean {
        val removed = active.remove(id) != null
        if (removed) {
            _events.tryEmit(NotifEvent.Removed(id))
        }
        return removed
    }

    fun reply(context: Context, id: String, text: String): Result<String> {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) {
            return Result.failure(IllegalArgumentException("Texto da resposta vazio"))
        }
        val entry = active[id]
            ?: active.values.maxByOrNull { it.notification.timestampMs }
            ?: return Result.failure(IllegalStateException("Nenhuma mensagem disponível para responder"))

        val action = entry.replyAction
        val remoteInput = entry.remoteInput
        if (action == null || remoteInput == null) {
            return Result.failure(IllegalStateException("Essa notificação não suporta resposta rápida"))
        }

        return runCatching {
            val fillIn = Intent()
            val bundle = Bundle().apply {
                putCharSequence(remoteInput.resultKey, cleanText)
            }
            RemoteInput.addResultsToIntent(arrayOf(remoteInput), fillIn, bundle)
            action.actionIntent.send(context.applicationContext, 0, fillIn)
            active.remove(entry.notification.id)
            _events.tryEmit(NotifEvent.Removed(entry.notification.id))
            "Resposta enviada para ${entry.notification.sender}"
        }
    }
}

/**
 * Escuta notificações de WhatsApp, WhatsApp Business e SMS/Mensagens no celular Samsung
 * e repassa em tempo real ao tablet via [PonteNotificationBridge].
 */
class PonteNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        PonteNotificationBridge.isConnected = true
        runCatching {
            activeNotifications?.forEach { processPosted(it) }
        }
        Log.i(TAG, "NotificationListenerService da Ponte conectado.")
    }

    override fun onListenerDisconnected() {
        PonteNotificationBridge.isConnected = false
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        processPosted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName in MESSAGING_PACKAGES) {
            PonteNotificationBridge.onRemoved(sbn.key)
        }
    }

    private fun processPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg !in MESSAGING_PACKAGES) return
        val notif = sbn.notification ?: return
        if ((notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = notif.extras ?: return
        var sender = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()
            .orEmpty()
            .ifBlank { extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty() }
        var text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
            .orEmpty()
            .ifBlank { extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty() }

        // Tenta extrair última mensagem de MessagingStyle (mais precisa em grupos/múltiplas msgs)
        @Suppress("DEPRECATION")
        val messagesParcel = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (!messagesParcel.isNullOrEmpty()) {
            val lastBundle = messagesParcel.lastOrNull() as? Bundle
            val lastText = lastBundle?.getCharSequence("text")?.toString()?.trim().orEmpty()
            var lastSender = lastBundle?.getCharSequence("sender")?.toString()?.trim().orEmpty()
            if (lastSender.isEmpty() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                val person = lastBundle?.getParcelable<android.app.Person>("sender_person")
                lastSender = person?.name?.toString()?.trim().orEmpty()
            }
            if (lastText.isNotEmpty()) text = lastText
            if (sender.isEmpty() && lastSender.isNotEmpty()) sender = lastSender
        }

        if (sender.isBlank() || text.isBlank()) return
        if (isSystemNoise(text)) return

        val (replyAction, remoteInput) = findReplyAction(notif)
        val item = PhoneMessageNotification(
            id = sbn.key,
            packageName = pkg,
            appLabel = appLabelFor(pkg),
            sender = sender,
            text = text,
            timestampMs = sbn.postTime,
            canReply = replyAction != null && remoteInput != null
        )
        PonteNotificationBridge.onPosted(
            PonteNotificationBridge.StoredNotification(
                notification = item,
                replyAction = replyAction,
                remoteInput = remoteInput
            )
        )
    }

    private fun findReplyAction(notif: Notification): Pair<Notification.Action?, RemoteInput?> {
        val actions = notif.actions ?: return null to null
        for (action in actions) {
            val inputs = action.remoteInputs ?: continue
            val ri = inputs.firstOrNull { it.allowFreeFormInput } ?: inputs.firstOrNull()
            if (ri != null) return action to ri
        }
        return null to null
    }

    private fun isSystemNoise(text: String): Boolean {
        val low = text.lowercase()
        return low.contains("verificando novas mensagens") ||
            low.contains("checking for new messages") ||
            low.contains("backup em andamento") ||
            low.contains("chamada de voz em andamento")
    }

    private fun appLabelFor(pkg: String): String = when (pkg) {
        "com.whatsapp" -> "WhatsApp"
        "com.whatsapp.w4b" -> "WhatsApp Business"
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.android.mms" -> "SMS"
        else -> "Mensagem"
    }

    companion object {
        private const val TAG = "PonteNotifListener"

        val MESSAGING_PACKAGES: Set<String> = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.android.mms"
        )

        fun isPermissionGranted(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val target = ComponentName(context.applicationContext, PonteNotificationListenerService::class.java)
                .flattenToString()
            val shortName = "${context.packageName}/.${PonteNotificationListenerService::class.java.simpleName}"
            return flat.split(':').any {
                it.equals(target, ignoreCase = true) || it.equals(shortName, ignoreCase = true)
            }
        }
    }
}

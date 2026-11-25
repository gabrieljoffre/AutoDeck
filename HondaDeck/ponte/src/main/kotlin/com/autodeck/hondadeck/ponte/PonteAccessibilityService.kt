package com.autodeck.hondadeck.ponte

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Controlador da janela de 15 segundos para automação segura do WhatsApp.
 *
 * REGRA DE SEGURANÇA: o serviço de acessibilidade só toca em qualquer botão do WhatsApp
 * quando [arm] foi chamado explicitamente por um comando da Ponte e dentro da janela de 15 s.
 * Fora dessa janela, nenhum evento é processado.
 */
object PonteAccessibilityController {

    enum class ActionMode {
        SEND_TEXT,
        SEND_VOICE,
        VOICE_CALL
    }

    data class PendingAction(
        val mode: ActionMode,
        val armedAtElapsedMs: Long,
        val expiresAtElapsedMs: Long,
        val completion: CompletableDeferred<Boolean> = CompletableDeferred()
    ) {
        fun isActive(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Boolean =
            nowElapsedMs in armedAtElapsedMs..expiresAtElapsedMs && !completion.isCompleted
    }

    const val WINDOW_MS: Long = 15_000L

    private val pendingRef = AtomicReference<PendingAction?>(null)

    @Volatile
    var isServiceConnected: Boolean = false
        internal set

    fun arm(mode: ActionMode, windowMs: Long = WINDOW_MS): PendingAction {
        val now = SystemClock.elapsedRealtime()
        val action = PendingAction(
            mode = mode,
            armedAtElapsedMs = now,
            expiresAtElapsedMs = now + windowMs
        )
        pendingRef.getAndSet(action)?.completion?.complete(false)
        return action
    }

    fun currentActive(nowElapsedMs: Long = SystemClock.elapsedRealtime()): PendingAction? {
        val cur = pendingRef.get() ?: return null
        if (!cur.isActive(nowElapsedMs)) {
            pendingRef.compareAndSet(cur, null)
            return null
        }
        return cur
    }

    fun markCompleted(action: PendingAction, success: Boolean) {
        pendingRef.compareAndSet(action, null)
        action.completion.complete(success)
    }

    fun disarm() {
        pendingRef.getAndSet(null)?.completion?.complete(false)
    }

    fun isEnabledInSettings(context: Context): Boolean {
        val expected = ComponentName(context.applicationContext, PonteAccessibilityService::class.java)
            .flattenToString()
        val shortName = "${context.packageName}/.${PonteAccessibilityService::class.java.simpleName}"
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { entry ->
            entry.equals(expected, ignoreCase = true) || entry.equals(shortName, ignoreCase = true)
        }
    }
}

/**
 * AccessibilityService da Ponte para tocar no botão Enviar (texto ou áudio) ou Ligar do WhatsApp
 * exclusivamente durante a janela armada de 15 segundos, voltando à tela anterior logo após.
 */
class PonteAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastActionClickAtMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        PonteAccessibilityController.isServiceConnected = true
        Log.i(TAG, "Serviço de Acessibilidade da Ponte conectado.")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        PonteAccessibilityController.isServiceConnected = false
        PonteAccessibilityController.disarm()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg !in WHATSAPP_PACKAGES) return

        // Só atua se a Ponte acabou de pedir uma ação (janela de 15 s)
        val pending = PonteAccessibilityController.currentActive() ?: return

        val now = SystemClock.elapsedRealtime()
        if (now - lastActionClickAtMs < CLICK_DEBOUNCE_MS) return

        val root = rootInActiveWindow ?: return
        try {
            val handled = when (pending.mode) {
                PonteAccessibilityController.ActionMode.SEND_TEXT -> tryClickSendButton(root, pkg)
                PonteAccessibilityController.ActionMode.SEND_VOICE -> tryClickSendMediaOrConfirm(root, pkg)
                PonteAccessibilityController.ActionMode.VOICE_CALL -> tryClickVoiceCallOrConfirm(root, pkg)
            }
            if (handled) {
                lastActionClickAtMs = now
                if (pending.mode == PonteAccessibilityController.ActionMode.SEND_TEXT) {
                    PonteAccessibilityController.markCompleted(pending, true)
                    scheduleReturnToPreviousScreen()
                } else if (pending.mode == PonteAccessibilityController.ActionMode.SEND_VOICE) {
                    // Em áudio compartilhado pode haver tela intermediária de confirmação; checa uma segunda vez
                    mainHandler.postDelayed({
                        val still = PonteAccessibilityController.currentActive()
                        if (still === pending) {
                            val r2 = rootInActiveWindow
                            if (r2 != null) {
                                tryClickDialogConfirm(r2)
                            }
                            PonteAccessibilityController.markCompleted(pending, true)
                            scheduleReturnToPreviousScreen()
                        }
                    }, 900L)
                } else {
                    // Ligação de voz: pode aparecer diálogo "Fazer chamada de voz?" -> confirma se aparecer
                    mainHandler.postDelayed({
                        val still = PonteAccessibilityController.currentActive()
                        if (still === pending) {
                            val r2 = rootInActiveWindow
                            if (r2 != null) {
                                tryClickCallConfirm(r2)
                            }
                            PonteAccessibilityController.markCompleted(pending, true)
                        }
                    }, 700L)
                }
            }
        } catch (err: Throwable) {
            Log.w(TAG, "Erro ao inspecionar janela do WhatsApp: ${err.message}")
        }
    }

    private fun tryClickSendButton(root: AccessibilityNodeInfo, pkg: String): Boolean {
        val candidateIds = listOf(
            "$pkg:id/send",
            "com.whatsapp:id/send",
            "com.whatsapp.w4b:id/send"
        )
        for (id in candidateIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (n in nodes) {
                if (clickNodeOrParent(n)) {
                    Log.i(TAG, "Botão Enviar clicado por ID ($id).")
                    return true
                }
            }
        }
        return clickByDescription(root, SEND_DESCRIPTIONS)
    }

    private fun tryClickSendMediaOrConfirm(root: AccessibilityNodeInfo, pkg: String): Boolean {
        val candidateIds = listOf(
            "$pkg:id/send",
            "$pkg:id/send_media_btn",
            "com.whatsapp:id/send",
            "com.whatsapp:id/send_media_btn",
            "com.whatsapp.w4b:id/send",
            "com.whatsapp.w4b:id/send_media_btn"
        )
        for (id in candidateIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (n in nodes) {
                if (clickNodeOrParent(n)) {
                    Log.i(TAG, "Botão Enviar áudio clicado por ID ($id).")
                    return true
                }
            }
        }
        if (clickByDescription(root, SEND_DESCRIPTIONS)) return true
        return tryClickDialogConfirm(root)
    }

    private fun tryClickDialogConfirm(root: AccessibilityNodeInfo): Boolean {
        val okButtons = root.findAccessibilityNodeInfosByViewId("android:id/button1")
        for (btn in okButtons) {
            val txt = btn.text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (txt.isEmpty() || txt in CONFIRM_SEND_TEXTS) {
                if (clickNodeOrParent(btn)) {
                    Log.i(TAG, "Confirmação de envio clicada (button1).")
                    return true
                }
            }
        }
        return false
    }

    private fun tryClickVoiceCallOrConfirm(root: AccessibilityNodeInfo, pkg: String): Boolean {
        if (tryClickCallConfirm(root)) return true
        val callIds = listOf(
            "$pkg:id/menuitem_call",
            "$pkg:id/voice_call_btn",
            "com.whatsapp:id/menuitem_call",
            "com.whatsapp:id/voice_call_btn",
            "com.whatsapp.w4b:id/menuitem_call"
        )
        for (id in callIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (n in nodes) {
                if (clickNodeOrParent(n)) {
                    Log.i(TAG, "Botão ligação de voz clicado por ID ($id).")
                    return true
                }
            }
        }
        return clickByDescription(root, VOICE_CALL_DESCRIPTIONS)
    }

    private fun tryClickCallConfirm(root: AccessibilityNodeInfo): Boolean {
        val okButtons = root.findAccessibilityNodeInfosByViewId("android:id/button1")
        for (btn in okButtons) {
            val txt = btn.text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (txt in CALL_CONFIRM_TEXTS) {
                if (clickNodeOrParent(btn)) {
                    Log.i(TAG, "Diálogo de confirmação de ligação clicado ($txt).")
                    return true
                }
            }
        }
        return false
    }

    private fun clickByDescription(root: AccessibilityNodeInfo, targets: Set<String>): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 250) {
            val node = queue.removeFirst()
            visited++
            val desc = node.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
            if (desc.isNotEmpty() && targets.any { desc == it || desc.startsWith("$it ") }) {
                if (clickNodeOrParent(node)) {
                    Log.i(TAG, "Botão clicado por contentDescription='$desc'.")
                    return true
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return false
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo?): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var hops = 0
        while (cur != null && hops < 4) {
            if (cur.isClickable && cur.isEnabled) {
                if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            }
            cur = cur.parent
            hops++
        }
        return false
    }

    private fun scheduleReturnToPreviousScreen() {
        // Volta à tela anterior após o WhatsApp processar o envio
        mainHandler.postDelayed({
            runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }
        }, 700L)
        mainHandler.postDelayed({
            runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }
        }, 1_150L)
    }

    companion object {
        private const val TAG = "PonteAccessibility"
        private const val CLICK_DEBOUNCE_MS = 450L

        private val WHATSAPP_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b"
        )
        private val SEND_DESCRIPTIONS = setOf("enviar", "send", "enviar mensagem")
        private val CONFIRM_SEND_TEXTS = setOf("enviar", "send", "ok")
        private val VOICE_CALL_DESCRIPTIONS = setOf(
            "ligação de voz",
            "ligacao de voz",
            "chamada de voz",
            "voice call",
            "ligar"
        )
        private val CALL_CONFIRM_TEXTS = setOf("ligar", "chamar", "call", "continuar")
    }
}

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
        val completion: CompletableDeferred<Boolean> = CompletableDeferred(),
        /** Nome do contato ou número a procurar se o WhatsApp abrir a tela "Enviar para..." (seletor de contatos). */
        val targetQuery: String = ""
    ) {
        fun isActive(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Boolean =
            nowElapsedMs in armedAtElapsedMs..expiresAtElapsedMs && !completion.isCompleted
    }

    const val WINDOW_MS: Long = 15_000L

    private val pendingRef = AtomicReference<PendingAction?>(null)

    @Volatile
    var isServiceConnected: Boolean = false
        internal set

    fun arm(mode: ActionMode, windowMs: Long = WINDOW_MS, targetQuery: String = ""): PendingAction {
        val now = SystemClock.elapsedRealtime()
        val action = PendingAction(
            mode = mode,
            armedAtElapsedMs = now,
            expiresAtElapsedMs = now + windowMs,
            targetQuery = targetQuery.trim()
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

    /** Ação de áudio para a qual o contato já foi tocado no seletor (evita desmarcar com um 2º toque). */
    @Volatile
    private var tappedPickerFor: PonteAccessibilityController.PendingAction? = null
    private var lastActionClickAtMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        PonteAccessibilityController.isServiceConnected = true
        instance = this
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
            if (pending.mode == PonteAccessibilityController.ActionMode.SEND_VOICE &&
                !hasSendButton(root, pkg) && handleSharePicker(root, pkg, pending.targetQuery)
            ) {
                lastActionClickAtMs = now
                scheduleVoiceRecheck(pkg)
                return
            }
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
                    finishVoiceAfterSend(pending, pkg)
                } else {
                    // Ligação de voz: o WhatsApp pode abrir um menu (Ligação de voz / vídeo / link) e depois
                    // um diálogo "Fazer chamada de voz?". Escolhe "Ligação de voz", confirma e conclui.
                    mainHandler.postDelayed({
                        val still = PonteAccessibilityController.currentActive()
                        if (still === pending) {
                            val r2 = rootInActiveWindow
                            val pickedMenu = r2 != null && tryClickVoiceMenuItem(r2)
                            mainHandler.postDelayed({
                                if (PonteAccessibilityController.currentActive() === pending) {
                                    rootInActiveWindow?.let { tryClickCallConfirm(it) }
                                    PonteAccessibilityController.markCompleted(pending, true)
                                    Log.i(TAG, "Ligação de voz do WhatsApp iniciada (menu=$pickedMenu).")
                                }
                            }, if (pickedMenu) 900L else 100L)
                        }
                    }, 700L)
                }
            }
        } catch (err: Throwable) {
            Log.w(TAG, "Erro ao inspecionar janela do WhatsApp: ${err.message}")
        }
    }

    /**
     * O WhatsApp nem sempre emite eventos depois que a busca é preenchida; refaz a checagem sozinho
     * (busca -> seleciona o contato -> toca em Enviar) enquanto a ação de áudio estiver armada.
     */
    private fun scheduleVoiceRecheck(pkg: String) {
        mainHandler.postDelayed({
            val pending = PonteAccessibilityController.currentActive() ?: return@postDelayed
            if (pending.mode != PonteAccessibilityController.ActionMode.SEND_VOICE) return@postDelayed
            val root = findWhatsAppRoot(pkg) ?: run { scheduleVoiceRecheck(pkg); return@postDelayed }
            val now = SystemClock.elapsedRealtime()
            try {
                if (!hasSendButton(root, pkg) && handleSharePicker(root, pkg, pending.targetQuery)) {
                    lastActionClickAtMs = now
                    scheduleVoiceRecheck(pkg)
                } else if (tryClickSendMediaOrConfirm(root, pkg)) {
                    lastActionClickAtMs = now
                    finishVoiceAfterSend(pending, pkg)
                } else {
                    scheduleVoiceRecheck(pkg)
                }
            } catch (err: Throwable) {
                Log.w(TAG, "Rechecagem do envio de áudio: ${err.message}")
            }
        }, 900L)
    }

    /** Toque real (gesto) no centro do elemento, para linhas de lista que não aceitam ACTION_CLICK. */
    private fun tapOnNode(node: AccessibilityNodeInfo): Boolean {
        val r = android.graphics.Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        val path = android.graphics.Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        val ok = dispatchGesture(gesture, null, null)
        Log.i(TAG, "Toque por gesto em (${r.centerX()}, ${r.centerY()}): $ok")
        return ok
    }

    /** Janela do WhatsApp mesmo com o teclado aberto (o teclado pode ser a "janela ativa"). */
    private fun findWhatsAppRoot(pkg: String): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        if (active?.packageName?.toString() == pkg) return active
        return runCatching { windows.mapNotNull { it.root }.firstOrNull { it.packageName?.toString() == pkg } }.getOrNull() ?: active
    }

    /** Depois do toque em Enviar: pode haver uma tela de confirmação; confirma, conclui e volta. */
    private fun finishVoiceAfterSend(pending: PonteAccessibilityController.PendingAction, pkg: String) {
        mainHandler.postDelayed({
            val still = PonteAccessibilityController.currentActive()
            if (still === pending) {
                val r2 = rootInActiveWindow
                if (r2 != null && !tryClickDialogConfirm(r2)) {
                    // Pode ter aberto a pré-visualização com outro botão Enviar
                    tryClickSendMediaOrConfirm(r2, pkg)
                }
                PonteAccessibilityController.markCompleted(pending, true)
                Log.i(TAG, "Mensagem de voz enviada pelo WhatsApp.")
                scheduleReturnToPreviousScreen()
            }
        }, 1_100L)
    }

    private fun hasSendButton(root: AccessibilityNodeInfo, pkg: String): Boolean =
        listOf("$pkg:id/send", "$pkg:id/send_media_btn").any { id ->
            root.findAccessibilityNodeInfosByViewId(id).any { it.isVisibleToUser }
        }

    /**
     * Tela "Enviar para..." do WhatsApp (o WhatsApp ignora o destinatário ao receber um arquivo):
     * abre a busca, digita o contato/número e seleciona o primeiro resultado. O botão Enviar que
     * aparece depois é tocado pelo fluxo normal. Retorna true se executou algum passo.
     */
    private fun handleSharePicker(root: AccessibilityNodeInfo, pkg: String, query: String): Boolean {
        if (query.isBlank()) return false
        val rows = root.findAccessibilityNodeInfosByViewId("$pkg:id/contactpicker_row_name")
        val searchField = root.findAccessibilityNodeInfosByViewId("$pkg:id/search_src_text").firstOrNull()
            ?: root.findAccessibilityNodeInfosByViewId("$pkg:id/search_input").firstOrNull()
        val isPicker = rows.isNotEmpty() || searchField != null ||
            root.findAccessibilityNodeInfosByViewId("$pkg:id/menuitem_search").isNotEmpty()
        if (!isPicker) return false

        if (searchField == null) {
            val searchBtn = root.findAccessibilityNodeInfosByViewId("$pkg:id/menuitem_search").firstOrNull()
            if (searchBtn != null && clickNodeOrParent(searchBtn)) {
                Log.i(TAG, "Seletor de contatos: abrindo a busca.")
                return true
            }
            return clickByDescription(root, setOf("pesquisar", "search", "buscar"))
        }
        val current = searchField.text?.toString().orEmpty()
        val digits = { t: String -> t.filter { it.isDigit() } }
        val qDigits = digits(query)
        // O WhatsApp reformata números ("+55 (62) 98202-4049"): compara só os dígitos para não redigitar sem fim.
        val alreadyTyped = if (qDigits.length >= 8) digits(current).endsWith(qDigits.takeLast(8))
            else current.trim().equals(query.trim(), ignoreCase = true)
        if (!alreadyTyped) {
            val args = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
            }
            searchField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.i(TAG, "Seletor de contatos: buscando \"$query\".")
            return true
        }
        Log.i(TAG, "Seletor: texto=\"$current\" linhas=${rows.size} (${rows.joinToString { it.text?.toString().orEmpty() }})")
        val norm = { t: String -> t.lowercase().filter { it.isLetterOrDigit() } }
        val target = rows.firstOrNull { norm(it.text?.toString().orEmpty()).contains(norm(query)) } ?: rows.firstOrNull()
        // A linha clicável do WhatsApp fica ~6 níveis acima do nome do contato.
        val action = PonteAccessibilityController.currentActive()
        if (action != null && tappedPickerFor === action) return false // já selecionou; aguarda o botão Enviar
        if (target != null && !target.isSelected && (clickNodeOrParent(target, maxHops = 8) || tapOnNode(target))) {
            tappedPickerFor = action
            Log.i(TAG, "Seletor de contatos: selecionado \"${target.text}\".")
            return true
        }
        return false
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

    /** Item "Ligação de voz" do menu de chamadas do WhatsApp (é texto, não descrição). */
    private fun tryClickVoiceMenuItem(root: AccessibilityNodeInfo): Boolean {
        val labels = listOf("Ligação de voz", "Ligacao de voz", "Chamada de voz", "Voice call")
        for (label in labels) {
            val nodes = root.findAccessibilityNodeInfosByText(label)
            for (n in nodes) {
                val t = n.text?.toString()?.trim().orEmpty()
                if (!t.equals(label, ignoreCase = true)) continue
                if (clickNodeOrParent(n, maxHops = 5) || tapOnNode(n)) {
                    Log.i(TAG, "Menu de chamadas: \"$label\" escolhido.")
                    return true
                }
            }
        }
        return false
    }

    private fun tryClickVoiceCallOrConfirm(root: AccessibilityNodeInfo, pkg: String): Boolean {
        if (tryClickVoiceMenuItem(root)) return true
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

    private fun clickNodeOrParent(node: AccessibilityNodeInfo?, maxHops: Int = 4): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var hops = 0
        while (cur != null && hops < maxHops) {
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

    /** Encerra uma chamada do WhatsApp em andamento tocando em "Sair da ligação". */
    fun endWhatsAppCall(): Boolean {
        val roots = runCatching { windows.mapNotNull { it.root } }.getOrDefault(emptyList()) + listOfNotNull(rootInActiveWindow)
        for (root in roots) {
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg !in WHATSAPP_PACKAGES) continue
            for (id in listOf("$pkg:id/end_call_button", "$pkg:id/footer_end_call_btn", "$pkg:id/end_call_btn")) {
                for (n in root.findAccessibilityNodeInfosByViewId(id)) {
                    if (clickNodeOrParent(n) || tapOnNode(n)) {
                        Log.i(TAG, "Chamada do WhatsApp encerrada ($id).")
                        return true
                    }
                }
            }
            if (clickByDescription(root, setOf("sair da ligação", "encerrar ligação", "encerrar chamada", "desligar", "end call", "leave call"))) return true
        }
        return false
    }

    companion object {
        /** Instância conectada (para ações diretas, como encerrar a chamada do WhatsApp). */
        @Volatile
        var instance: PonteAccessibilityService? = null
        private const val TAG = "PonteAccessibility"
        private const val CLICK_DEBOUNCE_MS = 650L

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

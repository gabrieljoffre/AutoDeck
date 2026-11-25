package com.autodeck.hondadeck.ponte

import android.app.KeyguardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.ContactsContract
import android.util.Base64
import androidx.core.content.FileProvider
import com.autodeck.protocol.phone.BrazilPhoneNumber
import com.autodeck.protocol.phone.PhoneLinkProtocol
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.URLEncoder

data class WhatsAppActionOutcome(
    val ok: Boolean,
    val message: String,
    val errorCode: String? = null
)

/**
 * Executa ações de WhatsApp no celular Samsung:
 * - Envio de texto (`https://api.whatsapp.com/send?phone=55...&text=...` + janela de 15 s da acessibilidade).
 * - Envio de mensagem de voz (`ACTION_SEND` com `jid` e FileProvider + janela de 15 s da acessibilidade).
 * - Ligação de voz (`vnd.android.cursor.item/vnd.com.whatsapp.voip.call` com ACTION_VIEW; fallback via chat + botão ligar).
 *
 * Verifica se o celular está bloqueado e devolve [PhoneLinkProtocol.ERR_PHONE_LOCKED] para o copiloto avisar o motorista.
 */
class PonteWhatsAppManager(
    private val context: Context,
    private val contactsManager: PonteContactsManager
) {
    private val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    fun isDeviceLocked(): Boolean {
        val keyguardLocked = runCatching { keyguardManager?.isKeyguardLocked == true }.getOrDefault(false)
        val screenOff = runCatching { powerManager?.isInteractive == false }.getOrDefault(false)
        return keyguardLocked || screenOff
    }

    fun resolveInstalledWhatsAppPackage(): String? {
        val pm = context.packageManager
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            val found = runCatching {
                pm.getPackageInfo(pkg, 0)
                true
            }.getOrDefault(false)
            if (found) return pkg
        }
        return null
    }

    suspend fun sendText(number: String, text: String, contactName: String = ""): WhatsAppActionOutcome {
        if (isDeviceLocked()) {
            return WhatsAppActionOutcome(
                ok = false,
                message = "Celular bloqueado. Desbloqueie o celular ou ative o Smart Lock com o Bluetooth do carro.",
                errorCode = PhoneLinkProtocol.ERR_PHONE_LOCKED
            )
        }
        val pkg = resolveInstalledWhatsAppPackage() ?: return WhatsAppActionOutcome(
            ok = false,
            message = "WhatsApp não está instalado no celular.",
            errorCode = PhoneLinkProtocol.ERR_WHATSAPP_NOT_INSTALLED
        )
        if (!PonteAccessibilityController.isEnabledInSettings(context)) {
            return WhatsAppActionOutcome(
                ok = false,
                message = "Ative o serviço de Acessibilidade do HondaDeck Ponte no celular para enviar automaticamente.",
                errorCode = PhoneLinkProtocol.ERR_ACCESSIBILITY_DISABLED
            )
        }
        val digits = BrazilPhoneNumber.toWhatsAppDigits(number) ?: return WhatsAppActionOutcome(
            ok = false,
            message = "Número de telefone inválido: $number",
            errorCode = PhoneLinkProtocol.ERR_INVALID_PARAMS
        )
        val cleanText = text.trim()
        if (cleanText.isEmpty()) {
            return WhatsAppActionOutcome(
                ok = false,
                message = "Texto da mensagem vazio.",
                errorCode = PhoneLinkProtocol.ERR_INVALID_PARAMS
            )
        }

        val pending = PonteAccessibilityController.arm(PonteAccessibilityController.ActionMode.SEND_TEXT)
        val encodedText = URLEncoder.encode(cleanText, Charsets.UTF_8.name()).replace("+", "%20")
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$digits&text=$encodedText")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val launched = runCatching { context.startActivity(intent); true }.getOrDefault(false)
        if (!launched) {
            PonteAccessibilityController.disarm()
            return WhatsAppActionOutcome(
                ok = false,
                message = "Não foi possível abrir o WhatsApp no celular.",
                errorCode = PhoneLinkProtocol.ERR_INTERNAL
            )
        }

        val confirmed = withTimeoutOrNull(9_000L) { pending.completion.await() } == true
        val label = contactName.ifBlank { BrazilPhoneNumber.formatDisplay("+$digits") }
        return if (confirmed) {
            WhatsAppActionOutcome(ok = true, message = "Mensagem enviada no WhatsApp para $label.")
        } else {
            WhatsAppActionOutcome(
                ok = true,
                message = "Conversa aberta no WhatsApp para $label (aguardando confirmação do envio)."
            )
        }
    }

    suspend fun sendVoice(
        number: String,
        audioBase64: String,
        mimeType: String,
        fileName: String,
        contactName: String = ""
    ): WhatsAppActionOutcome {
        if (isDeviceLocked()) {
            return WhatsAppActionOutcome(
                ok = false,
                message = "Celular bloqueado. Desbloqueie o celular ou ative o Smart Lock com o Bluetooth do carro.",
                errorCode = PhoneLinkProtocol.ERR_PHONE_LOCKED
            )
        }
        val pkg = resolveInstalledWhatsAppPackage() ?: return WhatsAppActionOutcome(
            ok = false,
            message = "WhatsApp não está instalado no celular.",
            errorCode = PhoneLinkProtocol.ERR_WHATSAPP_NOT_INSTALLED
        )
        if (!PonteAccessibilityController.isEnabledInSettings(context)) {
            return WhatsAppActionOutcome(
                ok = false,
                message = "Ative o serviço de Acessibilidade do HondaDeck Ponte no celular para enviar áudios automaticamente.",
                errorCode = PhoneLinkProtocol.ERR_ACCESSIBILITY_DISABLED
            )
        }
        val jid = BrazilPhoneNumber.toWhatsAppJid(number) ?: return WhatsAppActionOutcome(
            ok = false,
            message = "Número de telefone inválido: $number",
            errorCode = PhoneLinkProtocol.ERR_INVALID_PARAMS
        )
        val audioBytes = runCatching { Base64.decode(audioBase64, Base64.DEFAULT) }.getOrNull()
        if (audioBytes == null || audioBytes.isEmpty()) {
            return WhatsAppActionOutcome(
                ok = false,
                message = "Arquivo de áudio vazio ou inválido.",
                errorCode = PhoneLinkProtocol.ERR_INVALID_PARAMS
            )
        }

        val outDir = File(context.cacheDir, "voice_out").apply { mkdirs() }
        // Remove arquivos antigos para não acumular cache
        outDir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 60 * 60_000L }?.forEach {
            runCatching { it.delete() }
        }
        val safeName = fileName.replace(Regex("""[^a-zA-Z0-9._-]"""), "_").ifBlank {
            "audio_${System.currentTimeMillis()}.m4a"
        }
        val outFile = File(outDir, safeName)
        runCatching { outFile.writeBytes(audioBytes) }.getOrElse { err ->
            return WhatsAppActionOutcome(
                ok = false,
                message = "Erro ao gravar áudio no celular: ${err.message}",
                errorCode = PhoneLinkProtocol.ERR_INTERNAL
            )
        }

        val contentUri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
        }.getOrElse { err ->
            return WhatsAppActionOutcome(
                ok = false,
                message = "Erro ao preparar compartilhamento do áudio: ${err.message}",
                errorCode = PhoneLinkProtocol.ERR_INTERNAL
            )
        }

        val resolvedMime = mimeType.trim().ifBlank { "audio/mp4" }
        // Se o WhatsApp abrir o seletor "Enviar para...", a acessibilidade busca por este nome/número.
        val searchQuery = contactName.ifBlank { contactsManager.resolveDisplayNameByNumber(number).orEmpty() }
            .ifBlank { number.filter { it.isDigit() }.takeLast(11) }
        val pending = PonteAccessibilityController.arm(
            PonteAccessibilityController.ActionMode.SEND_VOICE,
            windowMs = 30_000L,
            targetQuery = searchQuery
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = resolvedMime
            setPackage(pkg)
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra("jid", jid)
            // Compartilhamento direto (Direct Share): o WhatsApp abre a conversa do jid quando ele é um atalho publicado.
            putExtra(Intent.EXTRA_SHORTCUT_ID, jid)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching {
            context.grantUriPermission(pkg, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
        if (!launched) {
            PonteAccessibilityController.disarm()
            return WhatsAppActionOutcome(
                ok = false,
                message = "Não foi possível compartilhar o áudio no WhatsApp.",
                errorCode = PhoneLinkProtocol.ERR_INTERNAL
            )
        }

        val confirmed = withTimeoutOrNull(28_000L) { pending.completion.await() } == true
        val label = contactName.ifBlank { BrazilPhoneNumber.formatDisplay(number) }
        return if (confirmed) {
            WhatsAppActionOutcome(ok = true, message = "Mensagem de voz enviada no WhatsApp para $label.")
        } else {
            WhatsAppActionOutcome(
                ok = false,
                message = "Não consegui confirmar o envio do áudio para $label; confira o WhatsApp no celular.",
                errorCode = PhoneLinkProtocol.ERR_INTERNAL
            )
        }
    }

    suspend fun voiceCall(
        number: String,
        contactName: String = "",
        voipDataId: Long? = null
    ): WhatsAppActionOutcome {
        if (isDeviceLocked()) {
            return WhatsAppActionOutcome(
                ok = false,
                message = "Celular bloqueado. Desbloqueie o celular ou ative o Smart Lock com o Bluetooth do carro.",
                errorCode = PhoneLinkProtocol.ERR_PHONE_LOCKED
            )
        }
        val pkg = resolveInstalledWhatsAppPackage() ?: return WhatsAppActionOutcome(
            ok = false,
            message = "WhatsApp não está instalado no celular.",
            errorCode = PhoneLinkProtocol.ERR_WHATSAPP_NOT_INSTALLED
        )
        val digits = BrazilPhoneNumber.toWhatsAppDigits(number) ?: return WhatsAppActionOutcome(
            ok = false,
            message = "Número de telefone inválido: $number",
            errorCode = PhoneLinkProtocol.ERR_INVALID_PARAMS
        )
        val label = contactName.ifBlank { BrazilPhoneNumber.formatDisplay("+$digits") }

        // 1. Caminho nativo via linha de dados do contato (vnd.android.cursor.item/vnd.com.whatsapp.voip.call)
        val resolvedVoipId = voipDataId ?: contactsManager.findWhatsAppVoipDataIdByNumber("+$digits")
        if (resolvedVoipId != null && resolvedVoipId > 0L) {
            val dataUri = ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, resolvedVoipId)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(dataUri, PonteContactsManager.MIME_WHATSAPP_VOIP_CALL)
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val canResolve = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
            if (canResolve) {
                val ok = runCatching { context.startActivity(intent); true }.getOrDefault(false)
                if (ok) {
                    return WhatsAppActionOutcome(ok = true, message = "Iniciando ligação de voz no WhatsApp para $label.")
                }
            }
        }

        // 2. Fallback: abrir a conversa no WhatsApp e tocar no botão de ligação de voz via acessibilidade
        if (!PonteAccessibilityController.isEnabledInSettings(context)) {
            return WhatsAppActionOutcome(
                ok = false,
                message = "Contato sem atalho direto de chamada VoIP e a Acessibilidade do Ponte está desativada.",
                errorCode = PhoneLinkProtocol.ERR_ACCESSIBILITY_DISABLED
            )
        }
        val pending = PonteAccessibilityController.arm(PonteAccessibilityController.ActionMode.VOICE_CALL)
        val chatIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$digits")).apply {
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val launched = runCatching { context.startActivity(chatIntent); true }.getOrDefault(false)
        if (!launched) {
            PonteAccessibilityController.disarm()
            return WhatsAppActionOutcome(
                ok = false,
                message = "Não foi possível abrir a conversa no WhatsApp.",
                errorCode = PhoneLinkProtocol.ERR_INTERNAL
            )
        }

        val confirmed = withTimeoutOrNull(9_000L) { pending.completion.await() } == true
        return if (confirmed) {
            WhatsAppActionOutcome(ok = true, message = "Ligação de voz no WhatsApp iniciada para $label.")
        } else {
            WhatsAppActionOutcome(
                ok = true,
                message = "Conversa aberta no WhatsApp para ligar para $label."
            )
        }
    }
}

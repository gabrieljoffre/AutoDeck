package com.autodeck.headunit.agent

import android.app.Application
import com.autodeck.headunit.agent.copilot.ActionJsonParser
import com.autodeck.headunit.agent.live.GeminiLiveTools
import com.autodeck.headunit.agent.live.LiveFunctionCall
import com.autodeck.headunit.data.HeadUnitConfig
import com.autodeck.headunit.data.VoiceAssistantMode
import com.autodeck.headunit.media.MediaPlaybackState
import com.autodeck.headunit.media.WhatsAppNotificationItem
import com.autodeck.protocol.coordinator.HeadUnitState
import com.autodeck.protocol.coordinator.MainTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCopilotToolsTest {

    private class FakePhoneHost : CarAgentHostBridge {
        var sentWhatsApp: Pair<String, String>? = null
        var sentAudioTo: String? = null
        var dialedTo: String? = null
        var calledWhatsAppTo: String? = null
        var answered = false
        var ended = false
        var replied: Pair<String?, String>? = null

        override fun getConfig(): HeadUnitConfig = HeadUnitConfig(voiceAssistantMode = VoiceAssistantMode.LOCAL_ONLY)
        override fun getHeadUnitState(): HeadUnitState = HeadUnitState()
        override fun getMediaState(): MediaPlaybackState = MediaPlaybackState()
        override fun getWhatsAppMessages(): List<WhatsAppNotificationItem> = emptyList()
        override fun controlMusic(action: String, query: String?): String = action
        override fun navigateTo(destination: String?): String = "Mapa"
        override fun switchTab(tab: MainTab): String = tab.name
        override fun toggleReverseCamera(): String = "Cam"
        override fun replyLatestWhatsApp(replyText: String): String = "Respondido"
        override fun markMessagesRead() = Unit

        override fun isPhoneConnected(): Boolean = true
        override fun getPhoneSummary(): String = "Conectado a Samsung S23"
        override fun getLatestPhoneMessageSummary(): Pair<String, String> = "msg-1" to "Ana"

        override suspend fun resolvePhoneRecipient(recipient: String): ResolvedPhoneRecipient =
            ResolvedPhoneRecipient(
                ok = true,
                displayName = "Ana",
                normalizedNumber = "+5562999998888",
                hasWhatsApp = true
            )

        override suspend fun sendWhatsAppTextViaPhone(recipient: String, text: String): String {
            sentWhatsApp = recipient to text
            return "Mensagem enviada no WhatsApp para Ana."
        }

        override suspend fun recordAndSendWhatsAppVoiceViaPhone(recipient: String): String {
            sentAudioTo = recipient
            return "Mensagem de voz enviada no WhatsApp para Ana."
        }

        override suspend fun dialPhoneViaPhone(recipient: String): String {
            dialedTo = recipient
            return "Ligando para Ana."
        }

        override suspend fun callWhatsAppViaPhone(recipient: String): String {
            calledWhatsAppTo = recipient
            return "Iniciando ligação de voz no WhatsApp para Ana."
        }

        override suspend fun answerPhoneCall(): String {
            answered = true
            return "Chamada atendida."
        }

        override suspend fun endPhoneCall(): String {
            ended = true
            return "Chamada encerrada."
        }

        override fun readPhoneMessages(): String = "Mensagem de Ana no WhatsApp: Cadê você?"

        override suspend fun replyPhoneMessage(messageId: String?, text: String): String {
            replied = messageId to text
            return "Resposta enviada para Ana."
        }
    }

    @Test
    fun `copiloto sempre confirma destinatario e texto antes de enviar WhatsApp e executa apos sim`() = runBlocking {
        val host = FakePhoneHost()
        val agent = GeminiCarAgent(
            appContext = Application(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            host = host,
            mainDispatcher = Dispatchers.Unconfined
        )

        // 1) Pedido de envio NÃO envia de imediato; pergunta confirmação
        agent.sendUserPrompt("manda whatsapp para Ana dizendo tô chegando", speakReply = false)
        assertNull("Não pode enviar antes de confirmar", host.sentWhatsApp)
        val confirmPrompt = agent.state.value.messages.last().text
        assertTrue("Deve confirmar destinatário e texto: $confirmPrompt", confirmPrompt.contains("Ana"))
        assertTrue("Deve repetir o texto: $confirmPrompt", confirmPrompt.contains("to chegando"))

        // 2) Após "sim", executa o envio
        agent.sendUserPrompt("sim", speakReply = false)
        assertEquals("+5562999998888" to "to chegando", host.sentWhatsApp)

        // 3) Cancelamento com "não" não envia áudio
        agent.sendUserPrompt("mandar áudio no whatsapp para Ana", speakReply = false)
        assertNull(host.sentAudioTo)
        agent.sendUserPrompt("não", speakReply = false)
        assertNull(host.sentAudioTo)

        // 4) Confirmação de áudio com "sim" grava e envia
        agent.sendUserPrompt("mandar áudio no whatsapp para Ana", speakReply = false)
        agent.sendUserPrompt("pode mandar", speakReply = false)
        assertEquals("+5562999998888", host.sentAudioTo)

        // 5) Ligações, atender e desligar
        agent.sendUserPrompt("ligar para Ana", speakReply = false)
        assertEquals("ana", host.dialedTo)
        agent.sendUserPrompt("ligar no whatsapp para Ana", speakReply = false)
        assertEquals("ana", host.calledWhatsAppTo)
        agent.sendUserPrompt("atender", speakReply = false)
        assertTrue(host.answered)
        agent.sendUserPrompt("desligar", speakReply = false)
        assertTrue(host.ended)
    }

    @Test
    fun `GeminiLiveTools declara as 8 ferramentas da Ponte e exige confirmado true para enviar`() = runBlocking {
        val decls = GeminiLiveTools.buildFunctionDeclarations()
        val names = (0 until decls.length()).map { decls.getJSONObject(it).getString("name") }.toSet()
        val expected = setOf(
            "enviar_whatsapp",
            "enviar_audio_whatsapp",
            "ligar",
            "ligar_whatsapp",
            "atender",
            "desligar",
            "ler_mensagens",
            "responder_mensagem"
        )
        for (name in expected) {
            assertTrue("Ferramenta '$name' ausente em GeminiLiveTools", name in names)
        }

        val host = FakePhoneHost()
        // Sem confirmado=true -> retorna instrução de confirmação e NÃO envia
        val askConfirm = GeminiLiveTools.executeTool(
            call = LiveFunctionCall("1", "enviar_whatsapp", """{"contato":"Ana","texto":"tô chegando"}"""),
            host = host,
            onClearMemory = {}
        )
        assertNull(host.sentWhatsApp)
        assertTrue(askConfirm.result.contains("Mando para Ana"))

        // Com confirmado=true -> envia
        val sent = GeminiLiveTools.executeTool(
            call = LiveFunctionCall("2", "enviar_whatsapp", """{"contato":"Ana","texto":"tô chegando","confirmado":true}"""),
            host = host,
            onClearMemory = {}
        )
        assertEquals("Ana" to "tô chegando", host.sentWhatsApp)
        assertTrue(sent.result.contains("Ana"))

        // responder_mensagem exige confirmação também
        val askReply = GeminiLiveTools.executeTool(
            call = LiveFunctionCall("3", "responder_mensagem", """{"mensagem":"Já chego"}"""),
            host = host,
            onClearMemory = {}
        )
        assertNull(host.replied)
        assertTrue(askReply.result.contains("Respondo para Ana"))

        GeminiLiveTools.executeTool(
            call = LiveFunctionCall("4", "responder_mensagem", """{"mensagem":"Já chego","confirmado":true}"""),
            host = host,
            onClearMemory = {}
        )
        assertEquals(null to "Já chego", host.replied)

        // ActionJsonParser reconhece novas ações
        assertTrue(ActionJsonParser.isValidAction("SEND_WHATSAPP"))
        assertTrue(ActionJsonParser.isValidAction("SEND_WHATSAPP_AUDIO"))
        assertTrue(ActionJsonParser.isValidAction("CALL_PHONE"))
        assertTrue(ActionJsonParser.isValidAction("CALL_WHATSAPP"))
        assertTrue(ActionJsonParser.isValidAction("ANSWER_CALL"))
        assertTrue(ActionJsonParser.isValidAction("END_CALL"))
        assertFalse(ActionJsonParser.isValidAction("ACAO_ALEATORIA"))
    }
}

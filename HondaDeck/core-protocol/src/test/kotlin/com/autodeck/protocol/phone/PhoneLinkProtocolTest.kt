package com.autodeck.protocol.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLinkProtocolTest {

    @Test
    fun `serializa e desserializa requisicao com caracteres especiais e acentos`() {
        val req = PhoneLinkMessage.Request(
            id = "req-42",
            method = PhoneLinkProtocol.METHOD_WHATSAPP_SEND_TEXT,
            params = linkedMapOf(
                "number" to "+5562999998888",
                "text" to "Oi Ana! \"Tô chegando\" em 5 min.\nAbraço!",
                "contactName" to "Ana Cláudia"
            )
        )
        val wire = PhoneLinkCodec.encode(req)
        assertFalse("Mensagem linha-a-linha não pode ter quebra literal", wire.contains('\n'))

        val decoded = PhoneLinkCodec.decode(wire) as? PhoneLinkMessage.Request
        assertNotNull(decoded)
        assertEquals("req-42", decoded!!.id)
        assertEquals(PhoneLinkProtocol.METHOD_WHATSAPP_SEND_TEXT, decoded.method)
        assertEquals("+5562999998888", decoded.params["number"])
        assertEquals("Oi Ana! \"Tô chegando\" em 5 min.\nAbraço!", decoded.params["text"])
        assertEquals("Ana Cláudia", decoded.params["contactName"])
    }

    @Test
    fun `serializa e desserializa resposta com lista de contatos e erro`() {
        val contact = PhoneContact(
            id = "c1",
            name = "Ana Silva",
            phone = "(62) 99999-8888",
            normalizedPhone = "+5562999998888",
            hasWhatsApp = true,
            whatsAppVoipDataId = 12345L
        )
        val okRes = PhoneLinkMessage.Response(
            id = "req-1",
            ok = true,
            result = linkedMapOf("contacts" to listOf(PhoneLinkCodec.contactToMap(contact)))
        )
        val decodedOk = PhoneLinkCodec.decode(PhoneLinkCodec.encode(okRes)) as? PhoneLinkMessage.Response
        assertNotNull(decodedOk)
        assertTrue(decodedOk!!.ok)
        val list = decodedOk.result["contacts"] as? List<*>
        assertNotNull(list)
        assertEquals(1, list!!.size)
        val parsedContact = PhoneLinkCodec.mapToContact(list[0])
        assertEquals(contact, parsedContact)

        val errRes = PhoneLinkMessage.Response(
            id = "req-2",
            ok = false,
            error = "Celular bloqueado",
            errorCode = PhoneLinkProtocol.ERR_PHONE_LOCKED
        )
        val decodedErr = PhoneLinkCodec.decode(PhoneLinkCodec.encode(errRes)) as? PhoneLinkMessage.Response
        assertNotNull(decodedErr)
        assertFalse(decodedErr!!.ok)
        assertEquals("Celular bloqueado", decodedErr.error)
        assertEquals(PhoneLinkProtocol.ERR_PHONE_LOCKED, decodedErr.errorCode)
    }

    @Test
    fun `serializa e desserializa eventos de chamada, notificacao e descoberta UDP`() {
        val callInfo = PhoneCallInfo(
            status = PhoneCallStatus.RINGING,
            number = "+5562988887777",
            displayName = "Mãe",
            isWhatsApp = false,
            startedAtMs = 1_760_000_000_000L
        )
        val callEvent = PhoneLinkMessage.Event(
            event = PhoneLinkProtocol.EVENT_CALL_STATE,
            data = PhoneLinkCodec.callInfoToMap(callInfo)
        )
        val decodedCallEvent = PhoneLinkCodec.decode(PhoneLinkCodec.encode(callEvent)) as? PhoneLinkMessage.Event
        assertNotNull(decodedCallEvent)
        assertEquals(PhoneLinkProtocol.EVENT_CALL_STATE, decodedCallEvent!!.event)
        assertEquals(callInfo, PhoneLinkCodec.mapToCallInfo(decodedCallEvent.data))

        val notif = PhoneMessageNotification(
            id = "0|com.whatsapp|123",
            packageName = "com.whatsapp",
            appLabel = "WhatsApp",
            sender = "João",
            text = "Cadê você?",
            timestampMs = 1_760_000_123_456L,
            canReply = true
        )
        val msgEvent = PhoneLinkMessage.Event(
            event = PhoneLinkProtocol.EVENT_MESSAGE_RECEIVED,
            data = PhoneLinkCodec.messageToMap(notif)
        )
        val decodedMsgEvent = PhoneLinkCodec.decode(PhoneLinkCodec.encode(msgEvent)) as? PhoneLinkMessage.Event
        assertNotNull(decodedMsgEvent)
        assertEquals(notif, PhoneLinkCodec.mapToMessage(decodedMsgEvent!!.data))

        val probe = PhoneLinkCodec.encodeUdpProbe("HondaDeck Tab A7")
        assertTrue(PhoneLinkCodec.isUdpProbe(probe))
        assertFalse(PhoneLinkCodec.isUdpProbe("{\"magic\":\"OUTRO\"}"))

        val announce = UdpDiscoveryAnnouncement(name = "Galaxy S23", port = 5290, deviceId = "dev-1")
        val decodedAnnounce = PhoneLinkCodec.decodeUdpAnnouncement(PhoneLinkCodec.encodeUdpAnnouncement(announce))
        assertEquals(announce, decodedAnnounce)
    }

    @Test
    fun `retorna null para linhas invalidas sem lancar excecao`() {
        assertNull(PhoneLinkCodec.decode(""))
        assertNull(PhoneLinkCodec.decode("   "))
        assertNull(PhoneLinkCodec.decode("{invalido"))
        assertNull(PhoneLinkCodec.decode("{\"type\":\"desconhecido\"}"))
        assertNull(PhoneLinkCodec.decode("{\"type\":\"req\",\"method\":\"ping\"}"))
    }
}

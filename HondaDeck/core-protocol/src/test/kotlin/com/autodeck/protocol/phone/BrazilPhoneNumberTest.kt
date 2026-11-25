package com.autodeck.protocol.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrazilPhoneNumberTest {

    @Test
    fun `normaliza numeros locais sem DDD aplicando DDD padrao 62`() {
        assertEquals("+5562999998888", BrazilPhoneNumber.normalize("99999-8888"))
        assertEquals("+5562999998888", BrazilPhoneNumber.normalize("999998888"))
        assertEquals("+556233211234", BrazilPhoneNumber.normalize("3321-1234"))
        assertEquals("+5511988887777", BrazilPhoneNumber.normalize("98888-7777", defaultDdd = "11"))
    }

    @Test
    fun `normaliza numeros com DDD, zero inicial, operadora CSP e codigo de pais`() {
        assertEquals("+5562999998888", BrazilPhoneNumber.normalize("(62) 99999-8888"))
        assertEquals("+5562999998888", BrazilPhoneNumber.normalize("62 9 9999-8888"))
        assertEquals("+5562999998888", BrazilPhoneNumber.normalize("062999998888"))
        assertEquals("+5562999998888", BrazilPhoneNumber.normalize("015 62 99999-8888"))
        assertEquals("+5562999998888", BrazilPhoneNumber.normalize("021 62 99999-8888"))
        assertEquals("+5562999998888", BrazilPhoneNumber.normalize("+55 (62) 99999-8888"))
        assertEquals("+5562999998888", BrazilPhoneNumber.normalize("5562999998888"))
        assertEquals("+5562999998888", BrazilPhoneNumber.normalize("005562999998888"))
        assertEquals("+551133224455", BrazilPhoneNumber.normalize("(11) 3322-4455"))
    }

    @Test
    fun `preserva internacionais nao brasileiros e numeros de emergencia para discagem`() {
        assertEquals("+14155550199", BrazilPhoneNumber.normalize("+1 (415) 555-0199"))
        assertEquals("190", BrazilPhoneNumber.normalizeForDial("190"))
        assertEquals("192", BrazilPhoneNumber.normalizeForDial("192"))
        assertNull(BrazilPhoneNumber.normalize("190"))
        assertNull(BrazilPhoneNumber.normalize("abc"))
        assertNull(BrazilPhoneNumber.normalize(""))
    }

    @Test
    fun `gera digitos e JID do WhatsApp e formata exibicao`() {
        assertEquals("5562999998888", BrazilPhoneNumber.toWhatsAppDigits("(62) 99999-8888"))
        assertEquals("5562999998888@s.whatsapp.net", BrazilPhoneNumber.toWhatsAppJid("99999-8888"))
        assertEquals("+55 (62) 99999-8888", BrazilPhoneNumber.formatDisplay("62999998888"))
        assertEquals("+55 (62) 3321-1234", BrazilPhoneNumber.formatDisplay("33211234"))
    }

    @Test
    fun `identifica numeros vs nomes e compara equivalencia com ou sem nono digito`() {
        assertTrue(BrazilPhoneNumber.looksLikePhoneNumber("(62) 99999-8888"))
        assertTrue(BrazilPhoneNumber.looksLikePhoneNumber("190"))
        assertFalse(BrazilPhoneNumber.looksLikePhoneNumber("Ana Maria"))
        assertFalse(BrazilPhoneNumber.looksLikePhoneNumber("Casa 2"))

        assertTrue(BrazilPhoneNumber.sameNumber("99999-8888", "+55 (62) 99999-8888"))
        assertTrue(BrazilPhoneNumber.sameNumber("62999998888", "6299998888"))
        assertFalse(BrazilPhoneNumber.sameNumber("62999998888", "11999998888"))
    }
}

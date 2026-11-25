package com.autodeck.protocol.phone

/**
 * Normalização de números telefônicos brasileiros para formato E.164 (`+55<DDD><número>`).
 *
 * Regras:
 * - Remove espaços, parênteses, traços, pontos e barras.
 * - Aceita prefixo internacional `+55`, `55`, `0055`.
 * - Aceita código de operadora (CSP) nacional `0XX<DDD><número>` (13 ou 14 dígitos começando por `0`).
 * - Aceita prefixo `0<DDD><número>` (11 ou 12 dígitos começando por `0`).
 * - Aceita `<DDD><número>` (10 ou 11 dígitos).
 * - Aceita número local sem DDD (8 ou 9 dígitos), preenchendo com o DDD padrão (`62` — Anápolis/GO).
 * - Preserva números internacionais de outros países (com `+` explícito diferente de `+55`).
 */
object BrazilPhoneNumber {

    const val DEFAULT_DDD: String = "62"
    const val COUNTRY_CODE: String = "55"

    private val NON_DIGIT = Regex("""\D+""")

    /**
     * Normaliza um número brasileiro para `+55DDDNUMERO`.
     * Retorna `null` se a entrada não contiver um número válido.
     */
    fun normalize(raw: String, defaultDdd: String = DEFAULT_DDD): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val cleanDdd = defaultDdd.replace(NON_DIGIT, "").take(2).ifEmpty { DEFAULT_DDD }
        val hasExplicitPlus = trimmed.startsWith("+")
        var digits = trimmed.replace(NON_DIGIT, "")
        if (digits.isEmpty()) return null

        // Números internacionais explícitos de fora do Brasil (ex.: +1 415 555 0100)
        if (hasExplicitPlus && !digits.startsWith(COUNTRY_CODE)) {
            return if (digits.length in 7..15) "+$digits" else null
        }

        // Prefixo internacional 00...
        if (!hasExplicitPlus && digits.startsWith("00") && digits.length > 10) {
            digits = digits.drop(2)
            if (!digits.startsWith(COUNTRY_CODE)) {
                return if (digits.length in 7..15) "+$digits" else null
            }
        }

        // Já tem código do Brasil 55 + DDD (2) + número (8 ou 9) => 12 ou 13 dígitos
        if (digits.startsWith(COUNTRY_CODE) && digits.length in 12..13) {
            val ddd = digits.substring(2, 4)
            if (isValidDdd(ddd)) {
                return "+$digits"
            }
        }

        // Código de operadora (CSP) nacional: 0 + operadora (2 dígitos) + DDD (2 dígitos) + número (8 ou 9 dígitos) => 13 ou 14 dígitos
        if (digits.startsWith("0") && digits.length in 13..14) {
            val withoutCsp = digits.substring(3)
            val ddd = withoutCsp.substring(0, 2)
            if (isValidDdd(ddd)) {
                return "+$COUNTRY_CODE$withoutCsp"
            }
        }

        // Prefixo 0 + DDD (2 dígitos) + número (8 ou 9 dígitos) => 11 ou 12 dígitos
        if (digits.startsWith("0") && digits.length in 11..12) {
            val withoutZero = digits.substring(1)
            val ddd = withoutZero.substring(0, 2)
            if (isValidDdd(ddd)) {
                return "+$COUNTRY_CODE$withoutZero"
            }
        }

        // DDD (2 dígitos) + número (8 ou 9 dígitos) => 10 ou 11 dígitos
        if (digits.length in 10..11) {
            val ddd = digits.substring(0, 2)
            if (isValidDdd(ddd)) {
                return "+$COUNTRY_CODE$digits"
            }
        }

        // Número local sem DDD (8 fixo ou 9 celular)
        if (digits.length in 8..9) {
            return "+$COUNTRY_CODE$cleanDdd$digits"
        }

        return null
    }

    /**
     * Normaliza para discagem telefônica. Além de números brasileiros normais,
     * aceita números curtos/serviços de emergência (3 a 5 dígitos, ex.: 190, 192, 193).
     */
    fun normalizeForDial(raw: String, defaultDdd: String = DEFAULT_DDD): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val digits = trimmed.replace(NON_DIGIT, "")
        if (digits.length in 3..5 && !trimmed.startsWith("+")) {
            return digits
        }
        return normalize(trimmed, defaultDdd)
    }

    /**
     * Retorna apenas os dígitos para uso na URL do WhatsApp (`https://api.whatsapp.com/send?phone=...`).
     * Ex.: `5562999998888`.
     */
    fun toWhatsAppDigits(raw: String, defaultDdd: String = DEFAULT_DDD): String? =
        normalize(raw, defaultDdd)?.removePrefix("+")

    /**
     * Retorna o JID usado pelo WhatsApp em `ACTION_SEND` (`5562999998888@s.whatsapp.net`).
     */
    fun toWhatsAppJid(raw: String, defaultDdd: String = DEFAULT_DDD): String? =
        toWhatsAppDigits(raw, defaultDdd)?.let { "$it@s.whatsapp.net" }

    /**
     * Indica se o texto informado parece um número telefônico (em vez de um nome de contato).
     */
    fun looksLikePhoneNumber(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return false
        val digits = trimmed.replace(NON_DIGIT, "")
        if (digits.length < 3) return false
        val letters = trimmed.count { it.isLetter() }
        return letters == 0 && (digits.length in 3..5 || digits.length >= 8)
    }

    /**
     * Formata `+5562999998888` para exibição amigável (`+55 (62) 99999-8888`).
     */
    fun formatDisplay(raw: String, defaultDdd: String = DEFAULT_DDD): String {
        val norm = normalize(raw, defaultDdd) ?: return raw.trim()
        if (!norm.startsWith("+$COUNTRY_CODE")) return norm
        val national = norm.removePrefix("+$COUNTRY_CODE")
        return when (national.length) {
            11 -> "+55 (${national.substring(0, 2)}) ${national.substring(2, 7)}-${national.substring(7)}"
            10 -> "+55 (${national.substring(0, 2)}) ${national.substring(2, 6)}-${national.substring(6)}"
            else -> norm
        }
    }

    /**
     * Compara dois números considerando normalização (e também tolerando o dígito 9 opcional em celulares BR).
     */
    fun sameNumber(a: String, b: String, defaultDdd: String = DEFAULT_DDD): Boolean {
        val na = normalize(a, defaultDdd) ?: return false
        val nb = normalize(b, defaultDdd) ?: return false
        if (na == nb) return true
        if (na.startsWith("+55") && nb.startsWith("+55")) {
            val da = na.removePrefix("+55")
            val db = nb.removePrefix("+55")
            if (da.length >= 10 && db.length >= 10 && da.substring(0, 2) == db.substring(0, 2)) {
                return da.takeLast(8) == db.takeLast(8)
            }
        }
        return false
    }

    private fun isValidDdd(ddd: String): Boolean {
        if (ddd.length != 2) return false
        val n = ddd.toIntOrNull() ?: return false
        return n in 11..99 && ddd[0] != '0' && ddd[1] != '0'
    }
}

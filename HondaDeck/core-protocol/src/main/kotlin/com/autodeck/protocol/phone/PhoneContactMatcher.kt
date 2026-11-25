package com.autodeck.protocol.phone

import java.text.Normalizer
import java.util.Locale

sealed interface ContactMatchResult {
    data class Exact(val contact: PhoneContact) : ContactMatchResult
    data class Ambiguous(val candidates: List<PhoneContact>) : ContactMatchResult
    data object NotFound : ContactMatchResult
}

/**
 * Busca tolerante em memória sobre contatos sincronizados do celular:
 * - Ignora acentos, maiúsculas/minúsculas e pontuação.
 * - Pesquisa em nome completo, apelidos (`nicknames`), partes/tokens do nome,
 *   números de telefone (todos os números do contato) e sinônimos afetivos/familiares em pt-BR
 *   ("mãe" <-> "mamãe"/"mainha", "amor" <-> "mozão"/"vida"/"esposa"/"marido", etc.).
 * - Detecta ambiguidade ("Ana" com 3 contatos distintos -> retorna `Ambiguous`).
 */
object PhoneContactMatcher {

    private val PT_BR: Locale = Locale.forLanguageTag("pt-BR")
    private val NON_DIGIT = Regex("""\D+""")
    private val LEADING_ARTICLES = Regex("""^(?:o|a|os|as|meu|minha|do|da|pro|pra|para)\s+""")

    // Grupos de sinônimos afetivos/familiares comuns na agenda brasileira
    private val RELATION_GROUPS: List<Set<String>> = listOf(
        setOf("mae", "mamae", "mainha", "dona mae", "mamis"),
        setOf("pai", "papai", "painho", "papis"),
        setOf("amor", "meu amor", "mozao", "mozinho", "vida", "minha vida", "esposa", "marido", "namorada", "namorado", "bem", "meu bem"),
        setOf("vo", "avo", "vovo", "vovozinha", "vovozinho"),
        setOf("irmao", "mano", "brother"),
        setOf("irma", "mana")
    )

    fun normalize(input: String): String {
        val lower = input.lowercase(PT_BR)
        val nfd = Normalizer.normalize(lower, Normalizer.Form.NFD)
        return nfd
            .replace(Regex("""\p{M}+"""), "")
            .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    /** Remove artigos iniciais falados ("a mãe", "meu amor", "o joão"). */
    fun cleanQuery(raw: String): String {
        val norm = normalize(raw)
        val stripped = norm.replaceFirst(LEADING_ARTICLES, "").trim()
        return stripped.ifEmpty { norm }
    }

    /**
     * Retorna lista ordenada dos contatos que casam com `query` (melhor score primeiro).
     */
    fun search(contacts: List<PhoneContact>, query: String, limit: Int = 10): List<PhoneContact> {
        val raw = query.trim()
        if (raw.isEmpty() || contacts.isEmpty()) return emptyList()

        val normFull = normalize(raw)
        val q = cleanQuery(raw)
        val queryTokens = q.split(' ').filter { it.isNotBlank() }
        val digitsQuery = raw.replace(NON_DIGIT, "")
        val relatedQueries = expandRelatedTerms(q)

        val scored = ArrayList<Pair<Int, PhoneContact>>()

        for (contact in contacts) {
            val score = scoreContact(contact, normFull, q, queryTokens, digitsQuery, relatedQueries)
            if (score > 0) {
                scored += score to contact
            }
        }

        if (scored.isEmpty()) return emptyList()
        val maxScore = scored.maxOf { it.first }
        // Quando há correspondências fortes por palavra/apelido/prefixo, descarta matches fracos no meio de outra palavra
        // (ex.: buscar "Ana" retorna "Ana Silva", "Ana Paula", mas não "Juliana").
        val minKeepScore = if (maxScore >= 750) 650 else 1

        return scored
            .filter { it.first >= minKeepScore }
            .sortedWith(
                compareByDescending<Pair<Int, PhoneContact>> { it.first }
                    .thenByDescending { it.second.starred }
                    .thenByDescending { it.second.timesContacted }
                    .thenByDescending { it.second.lastTimeContactedMs }
                    .thenByDescending { it.second.hasWhatsApp }
                    .thenBy { normalize(it.second.name) }
            )
            .map { it.second }
            .distinctBy { it.id.ifBlank { "${normalize(it.name)}|${it.normalizedPhone}" } }
            .take(limit)
    }

    /**
     * Resolve uma consulta para um único contato ou sinaliza ambiguidade para o copiloto perguntar qual.
     */
    fun resolve(contacts: List<PhoneContact>, query: String, maxAmbiguous: Int = 5): ContactMatchResult {
        val matches = search(contacts, query, limit = maxAmbiguous + 2)
        if (matches.isEmpty()) return ContactMatchResult.NotFound
        if (matches.size == 1) return ContactMatchResult.Exact(matches.first())

        val q = cleanQuery(query)
        val normFull = normalize(query)

        // Verifica se existe um ÚNICO contato com match exato de nome completo ou apelido
        val exactMatches = matches.filter { c ->
            val normName = normalize(c.name)
            val normNicks = c.nicknames.map { normalize(it) }
            normName == q || normName == normFull || q in normNicks || normFull in normNicks
        }
        if (exactMatches.size == 1) {
            return ContactMatchResult.Exact(exactMatches.first())
        }

        // Verifica se todos os resultados são o mesmo número/contato duplicado
        val distinctIdentities = matches.distinctBy { normalize(it.name) }
        if (distinctIdentities.size == 1) {
            return ContactMatchResult.Exact(matches.first())
        }

        return ContactMatchResult.Ambiguous(matches.take(maxAmbiguous))
    }

    /**
     * Seleciona os ~30 contatos mais frequentes/favoritos para incluir no `systemInstruction` do Gemini.
     */
    fun topFrequentContacts(contacts: List<PhoneContact>, limit: Int = 30): List<PhoneContact> {
        if (contacts.isEmpty()) return emptyList()
        return contacts
            .filter { it.name.isNotBlank() && (it.normalizedPhone.isNotBlank() || it.phones.isNotEmpty()) }
            .sortedWith(
                compareByDescending<PhoneContact> { it.starred }
                    .thenByDescending { it.timesContacted }
                    .thenByDescending { it.lastTimeContactedMs }
                    .thenByDescending { it.nicknames.isNotEmpty() }
                    .thenByDescending { it.hasWhatsApp }
                    .thenBy { normalize(it.name) }
            )
            .distinctBy { it.id.ifBlank { normalize(it.name) } }
            .take(limit)
    }

    /**
     * Formata os contatos principais em linhas compactas para o `systemInstruction`.
     */
    fun formatForSystemPrompt(contacts: List<PhoneContact>, limit: Int = 30): String {
        val top = topFrequentContacts(contacts, limit)
        if (top.isEmpty()) return "Nenhum contato sincronizado do celular ainda."
        return top.joinToString("\n") { c ->
            val nickPart = if (c.nicknames.isNotEmpty()) " (apelido: ${c.nicknames.joinToString(", ")})" else ""
            val waTag = if (c.hasWhatsApp) " [WhatsApp]" else ""
            val starTag = if (c.starred) " ★" else ""
            val nums = c.allNumbers().map { it.normalized }.distinct().take(2).joinToString(" / ")
            "- ${c.name}$nickPart$starTag: $nums$waTag"
        }
    }

    /**
     * Formata resultados da ferramenta `buscar_contato(nome)` para o copiloto.
     */
    fun formatToolSearchResult(query: String, contacts: List<PhoneContact>): String {
        val resolved = resolve(contacts, query)
        return when (resolved) {
            ContactMatchResult.NotFound ->
                "Nenhum contato encontrado para \"$query\"."

            is ContactMatchResult.Exact -> {
                val c = resolved.contact
                val nicks = if (c.nicknames.isNotEmpty()) " | Apelidos: ${c.nicknames.joinToString(", ")}" else ""
                val nums = c.allNumbers().joinToString(", ") { n ->
                    val lbl = n.label.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                    val wa = if (n.hasWhatsApp || c.hasWhatsApp) " [WhatsApp]" else ""
                    "${BrazilPhoneNumber.formatDisplay(n.normalized)}$lbl$wa"
                }
                "Contato encontrado: ${c.name}$nicks | Telefone(s): $nums."
            }

            is ContactMatchResult.Ambiguous -> {
                val lines = resolved.candidates.mapIndexed { idx, c ->
                    val nicks = if (c.nicknames.isNotEmpty()) " (${c.nicknames.joinToString(", ")})" else ""
                    val wa = if (c.hasWhatsApp) " [WhatsApp]" else ""
                    "${idx + 1}. ${c.name}$nicks — ${BrazilPhoneNumber.formatDisplay(c.normalizedPhone)}$wa"
                }.joinToString("\n")
                "AMBIGUIDADE: Encontrei ${resolved.candidates.size} contatos para \"$query\". Pergunte ao motorista qual deles ele quer:\n$lines"
            }
        }
    }

    private fun scoreContact(
        contact: PhoneContact,
        normFull: String,
        q: String,
        queryTokens: List<String>,
        digitsQuery: String,
        relatedQueries: Set<String>
    ): Int {
        val normName = normalize(contact.name)
        val nameTokens = normName.split(' ').filter { it.isNotBlank() }
        val normNicknames = contact.nicknames.map { normalize(it) }.filter { it.isNotEmpty() }
        val nickTokens = normNicknames.flatMap { it.split(' ') }.filter { it.isNotBlank() }

        var best = 0

        // 1) Match exato do nome completo ou apelido
        if (normName == normFull || normName == q) {
            best = maxOf(best, 1000)
        }
        if (normNicknames.any { it == normFull || it == q }) {
            best = maxOf(best, 980)
        }

        // 2) Match exato de sinônimo afetivo/familiar em nome ou apelido (ex.: "mãe" -> "Mamãe")
        if (relatedQueries.isNotEmpty()) {
            if (relatedQueries.any { rel -> normName == rel || rel in normNicknames }) {
                best = maxOf(best, 920)
            } else if (relatedQueries.any { rel ->
                    rel in nameTokens || rel in nickTokens ||
                        (rel.contains(' ') && (normName.contains(rel) || normNicknames.any { it.contains(rel) }))
                }
            ) {
                best = maxOf(best, 760)
            }
        }

        // 3) Todos os tokens da consulta presentes em nome + apelidos
        if (queryTokens.isNotEmpty()) {
            val combinedTokens = nameTokens + nickTokens
            val allExactTokens = queryTokens.all { qt -> combinedTokens.any { it == qt } }
            val allPrefixTokens = !allExactTokens && queryTokens.all { qt -> combinedTokens.any { it.startsWith(qt) } }
            if (allExactTokens) {
                // Primeiro nome exato ou múltiplos tokens exatos
                val isFirstName = queryTokens.size == 1 && nameTokens.firstOrNull() == queryTokens.first()
                best = maxOf(best, if (queryTokens.size >= 2) 880 else if (isFirstName) 840 else 800)
            } else if (allPrefixTokens) {
                best = maxOf(best, if (queryTokens.size >= 2) 720 else 650)
            }
        }

        // 4) Prefixo do nome ou apelido
        if (q.length >= 2) {
            if (normName.startsWith(q)) best = maxOf(best, 740)
            if (normNicknames.any { it.startsWith(q) }) best = maxOf(best, 730)
        }

        // 5) Substring no nome ou apelido (evita matches de 1 caractere)
        if (q.length >= 2) {
            if (normName.contains(q)) best = maxOf(best, 520)
            if (normNicknames.any { it.contains(q) }) best = maxOf(best, 540)
        }

        // 6) Match por número de telefone (4+ dígitos)
        if (digitsQuery.length >= 4) {
            val nums = contact.allNumbers()
            val phoneMatch = nums.any { num ->
                val d = num.normalized.replace(NON_DIGIT, "")
                val rd = num.raw.replace(NON_DIGIT, "")
                d.contains(digitsQuery) || rd.contains(digitsQuery) ||
                    BrazilPhoneNumber.sameNumber(num.normalized, digitsQuery)
            }
            if (phoneMatch) {
                best = maxOf(best, 700)
            }
        }

        return best
    }

    private fun expandRelatedTerms(q: String): Set<String> {
        if (q.isEmpty()) return emptySet()
        val out = LinkedHashSet<String>()
        for (group in RELATION_GROUPS) {
            if (q in group) {
                out.addAll(group)
            }
        }
        out.remove(q)
        return out
    }
}

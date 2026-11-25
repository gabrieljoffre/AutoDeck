package com.autodeck.headunit.agent.copilot

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Abstração simples de HTTP GET síncrono para permitir testes determinísticos sem rede.
 */
fun interface HttpGetter {
    fun get(url: String): String
}

/**
 * Implementação padrão de [HttpGetter] baseada em `HttpURLConnection` (JDK puro, sem Android).
 */
class UrlConnectionHttpGetter(
    private val connectTimeoutMs: Int = 6_000,
    private val readTimeoutMs: Int = 8_000,
    private val userAgent: String = "HondaDeck/3.0 (Android; Copilot)"
) : HttpGetter {
    override fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                throw IllegalStateException("HTTP $code ao consultar $url: ${err.take(120)}")
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

data class NewsItem(
    val title: String,
    val source: String,
    val pubDate: String
)

/**
 * Ferramentas gratuitas de conhecimento da internet para o Copiloto (Google News RSS,
 * Wikipédia em português, Open-Meteo) e declarações `functionDeclarations` para a API Gemini.
 */
class WebKnowledgeTools(
    private val http: HttpGetter = UrlConnectionHttpGetter()
) {

    /**
     * Busca as 6 principais notícias no Google News RSS (pt-BR) contendo título, fonte e data,
     * formatadas de modo conciso para leitura por voz ou síntese pelo modelo.
     */
    fun buscarNoticias(q: String): String {
        val query = q.trim().ifEmpty { "Anápolis Goiás" }
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val url = "https://news.google.com/rss/search?q=$encoded&hl=pt-BR&gl=BR&ceid=BR:pt-419"
        val xml = runCatching { http.get(url) }.getOrElse { err ->
            return "Não foi possível consultar notícias agora (${err.message ?: "erro de rede"})."
        }
        val items = parseNewsRss(xml, limit = 6)
        if (items.isEmpty()) {
            return "Nenhuma notícia recente encontrada para \"$query\"."
        }
        return buildString {
            append("Principais notícias sobre ").append(query).append(":\n")
            items.forEachIndexed { idx, item ->
                append(idx + 1).append(". ").append(item.title)
                val meta = listOfNotNull(
                    item.source.takeIf { it.isNotBlank() },
                    item.pubDate.takeIf { it.isNotBlank() }
                ).joinToString(", ")
                if (meta.isNotEmpty()) {
                    append(" (").append(meta).append(")")
                }
                if (idx < items.lastIndex) append('\n')
            }
        }
    }

    /**
     * Busca resumo enciclopédico na Wikipédia em português (OpenSearch + REST Summary v1),
     * encurtado para leitura confortável em voz alta no carro.
     */
    fun buscarWikipedia(q: String): String {
        val query = q.trim()
        if (query.isEmpty()) return "Informe um termo para pesquisar na Wikipédia."

        val encodedSearch = URLEncoder.encode(query, Charsets.UTF_8.name())
        val openSearchUrl =
            "https://pt.wikipedia.org/w/api.php?action=opensearch&search=$encodedSearch&limit=1&format=json"

        val resolvedTitle = runCatching {
            val rawSearch = http.get(openSearchUrl)
            val arr = CopilotJson.parseArrOrNull(rawSearch)
            if (arr != null) {
                // Formato OpenSearch: ["termo", ["Título 1"], ["desc"], ["url"]]
                val titles = arr.optArr(1)
                if (titles != null && titles.size == 0) {
                    return "Não encontrei artigo na Wikipédia sobre \"$query\"."
                }
                titles?.optString(0)?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        }.getOrNull() ?: query

        val encodedTitle = URLEncoder.encode(resolvedTitle.replace(' ', '_'), Charsets.UTF_8.name())
            .replace("+", "%20")
        val summaryUrl = "https://pt.wikipedia.org/api/rest_v1/page/summary/$encodedTitle"

        val summaryRaw = runCatching { http.get(summaryUrl) }.getOrElse { err ->
            return "Não foi possível consultar a Wikipédia agora (${err.message ?: "erro de rede"})."
        }

        val obj = CopilotJson.parseObjOrNull(summaryRaw)
            ?: return "Não encontrei resumo na Wikipédia para \"$query\"."

        val title = obj.optStrictString("title")?.trim().orEmpty().ifEmpty { resolvedTitle }
        val description = obj.optStrictString("description")?.trim().orEmpty()
        val extract = obj.optStrictString("extract")?.trim().orEmpty()

        if (extract.isEmpty()) {
            return if (description.isNotEmpty()) {
                "$title: $description."
            } else {
                "Não encontrei resumo disponível na Wikipédia para \"$title\"."
            }
        }

        val shortExtract = formatShortForVoice(extract, maxChars = 340, maxSentences = 3)
        val header = if (description.isNotEmpty() &&
            !shortExtract.lowercase(PT_BR).contains(description.lowercase(PT_BR))
        ) {
            "$title ($description): "
        } else if (!shortExtract.lowercase(PT_BR).startsWith(title.lowercase(PT_BR))) {
            "$title: "
        } else {
            ""
        }
        return (header + shortExtract).trim()
    }

    /**
     * Consulta opcional de clima via Open-Meteo (padrão: Anápolis-GO, -16.3281, -48.9534),
     * útil caso o chamador queira resolver `clima(cidade?)` pelo mesmo [HttpGetter].
     */
    fun buscarClima(cidade: String? = null): String {
        val cleanCity = cidade?.trim().orEmpty()
        val (cityLabel, lat, lon) = resolveCityCoords(cleanCity)
        val latStr = "%.2f".format(Locale.US, lat)
        val lonStr = "%.2f".format(Locale.US, lon)
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$latStr&longitude=$lonStr" +
            "&current=temperature_2m,apparent_temperature,weather_code" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=2"

        val raw = runCatching { http.get(url) }.getOrElse {
            return "Não foi possível obter o clima de $cityLabel agora."
        }
        val root = CopilotJson.parseObjOrNull(raw)
            ?: return "Dados de clima indisponíveis para $cityLabel."
        val cur = root.optObj("current")
            ?: return "Dados atuais de clima indisponíveis para $cityLabel."

        val temp = cur.optDouble("temperature_2m")?.roundToInt()
        val feels = cur.optDouble("apparent_temperature")?.roundToInt() ?: temp
        val label = wmoLabelPt(cur.optInt("weather_code") ?: -1)

        val daily = root.optObj("daily")
        val minToday = daily?.optArr("temperature_2m_min")?.optDouble(0)?.roundToInt()
        val maxToday = daily?.optArr("temperature_2m_max")?.optDouble(0)?.roundToInt()
        val rainToday = daily?.optArr("precipitation_probability_max")?.optInt(0)
        val rainTomorrow = daily?.optArr("precipitation_probability_max")?.optInt(1)

        return buildString {
            append("Clima em ").append(cityLabel).append(": ").append(label)
            if (temp != null) {
                append(", ").append(temp).append("°C agora")
                if (feels != null && feels != temp) append(" (sensação de ").append(feels).append("°C)")
            }
            if (minToday != null && maxToday != null) {
                append(", mínima de ").append(minToday).append("°C e máxima de ").append(maxToday).append("°C")
            }
            if (rainToday != null) {
                append(". Chance de chuva hoje: ").append(rainToday).append("%")
                if (rainTomorrow != null) append(", amanhã: ").append(rainTomorrow).append("%")
            }
            append('.')
        }
    }

    fun parseNewsRss(xml: String, limit: Int = 6): List<NewsItem> {
        if (xml.isBlank() || limit <= 0) return emptyList()
        val results = ArrayList<NewsItem>(limit)
        for (itemMatch in RSS_ITEM_REGEX.findAll(xml)) {
            val block = itemMatch.groupValues[1]
            val rawTitle = extractXmlTag(block, "title").let(::unescapeXmlAndHtml).trim()
            if (rawTitle.isEmpty()) continue

            var source = extractXmlTag(block, "source").let(::unescapeXmlAndHtml).trim()
            var cleanTitle = rawTitle
            if (source.isNotEmpty() && cleanTitle.endsWith(" - $source")) {
                cleanTitle = cleanTitle.removeSuffix(" - $source").trim()
            } else if (source.isEmpty() && cleanTitle.contains(" - ")) {
                val lastDash = cleanTitle.lastIndexOf(" - ")
                if (lastDash > 8 && lastDash + 3 < cleanTitle.length) {
                    source = cleanTitle.substring(lastDash + 3).trim()
                    cleanTitle = cleanTitle.substring(0, lastDash).trim()
                }
            }

            val rawPubDate = extractXmlTag(block, "pubDate").let(::unescapeXmlAndHtml).trim()
            val formattedDate = formatRssDate(rawPubDate)

            results += NewsItem(
                title = cleanTitle,
                source = source,
                pubDate = formattedDate
            )
            if (results.size >= limit) break
        }
        return results
    }

    private fun resolveCityCoords(city: String): Triple<String, Double, Double> {
        val norm = CopilotIntentParser.normalize(city)
        if (norm.isEmpty() || norm == "anapolis" || norm == "anapolis go" || norm == "anapolis-go") {
            return Triple("Anápolis", ANAPOLIS_LAT, ANAPOLIS_LON)
        }
        val encoded = URLEncoder.encode(city, Charsets.UTF_8.name())
        val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=pt&format=json"
        val resolved = runCatching {
            val raw = http.get(geoUrl)
            val first = CopilotJson.parseObjOrNull(raw)?.optArr("results")?.optObj(0)
            if (first != null) {
                val lat = first.optDouble("latitude")
                val lon = first.optDouble("longitude")
                val name = first.optStrictString("name") ?: city
                if (lat != null && lon != null) Triple(name, lat, lon) else null
            } else {
                null
            }
        }.getOrNull()
        return resolved ?: Triple("Anápolis", ANAPOLIS_LAT, ANAPOLIS_LON)
    }

    companion object {
        const val ANAPOLIS_LAT: Double = -16.3281
        const val ANAPOLIS_LON: Double = -48.9534

        private val PT_BR: Locale = Locale.forLanguageTag("pt-BR")
        private val RSS_ITEM_REGEX = Regex("""(?is)<item\b[^>]*>([\s\S]*?)</item>""")
        private val CDATA_REGEX = Regex("""(?is)^<!\[CDATA\[([\s\S]*?)]]>$""")
        private val HTML_TAG_REGEX = Regex("""<[^>]+>""")
        private val OUT_DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", PT_BR)

        /**
         * Array JSON das 5 declarações de função para a API Gemini (`functionDeclarations`).
         */
        val FUNCTION_DECLARATIONS_ARRAY_JSON: String = """
            [
              {
                "name": "buscar_noticias",
                "description": "Busca as principais notícias e manchetes recentes em português no Google Notícias.",
                "parameters": {
                  "type": "OBJECT",
                  "properties": {
                    "consulta": {
                      "type": "STRING",
                      "description": "Assunto, cidade ou tema das notícias (ex.: Anápolis, trânsito, futebol, economia)."
                    }
                  },
                  "required": ["consulta"]
                }
              },
              {
                "name": "buscar_wikipedia",
                "description": "Busca um resumo enciclopédico na Wikipédia em português sobre pessoas, lugares, história ou conceitos.",
                "parameters": {
                  "type": "OBJECT",
                  "properties": {
                    "consulta": {
                      "type": "STRING",
                      "description": "Termo ou nome a pesquisar na Wikipédia (ex.: Ayrton Senna, Anápolis)."
                    }
                  },
                  "required": ["consulta"]
                }
              },
              {
                "name": "clima",
                "description": "Consulta o clima atual, temperatura e previsão de chuva (padrão: Anápolis-GO ou posição do carro).",
                "parameters": {
                  "type": "OBJECT",
                  "properties": {
                    "cidade": {
                      "type": "STRING",
                      "description": "Nome opcional da cidade (se omitido, usa Anápolis-GO / localização atual)."
                    }
                  }
                }
              },
              {
                "name": "buscar_lugar",
                "description": "Procura estabelecimentos, endereços ou pontos de interesse perto do carro (postos, oficinas, farmácias).",
                "parameters": {
                  "type": "OBJECT",
                  "properties": {
                    "consulta": {
                      "type": "STRING",
                      "description": "Tipo de local ou nome do estabelecimento (ex.: posto de combustível, oficina mecânica)."
                    }
                  },
                  "required": ["consulta"]
                }
              },
              {
                "name": "dados_do_carro",
                "description": "Obtém telemetria OBD-II atual do Honda Civic (temperatura, voltagem, RPM, velocidade, consumo, tanque, falhas DTC), diário de abastecimento e manutenção.",
                "parameters": {
                  "type": "OBJECT",
                  "properties": {}
                }
              }
            ]
        """.trimIndent()

        /**
         * Objeto JSON `{"functionDeclarations": [...]}` pronto para inserir no array `"tools"` da API Gemini.
         */
        val FUNCTION_DECLARATIONS_JSON: String =
            """{"functionDeclarations":$FUNCTION_DECLARATIONS_ARRAY_JSON}"""

        /**
         * Array `"tools"` completo (`[{"functionDeclarations": [...]}]`) para `generateContent`.
         */
        val GEMINI_TOOLS_JSON: String = "[$FUNCTION_DECLARATIONS_JSON]"

        fun functionDeclarationsJson(): String = FUNCTION_DECLARATIONS_JSON

        /**
         * Encurta um texto longo para leitura confortável por voz (TTS automotivo),
         * cortando em limites de frase sempre que possível.
         */
        fun formatShortForVoice(
            text: String,
            maxChars: Int = 340,
            maxSentences: Int = 3
        ): String {
            val cleaned = text
                .replace(HTML_TAG_REGEX, " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
            if (cleaned.isEmpty()) return ""
            if (cleaned.length <= maxChars) {
                val sentences = splitSentences(cleaned)
                return if (sentences.size <= maxSentences) {
                    cleaned
                } else {
                    sentences.take(maxSentences).joinToString(" ")
                }
            }

            val sentences = splitSentences(cleaned)
            val chosen = StringBuilder()
            var count = 0
            for (s in sentences) {
                if (count >= maxSentences) break
                val candidateLen = if (chosen.isEmpty()) s.length else chosen.length + 1 + s.length
                if (candidateLen > maxChars) break
                if (chosen.isNotEmpty()) chosen.append(' ')
                chosen.append(s)
                count++
            }
            if (chosen.isNotEmpty()) return chosen.toString()

            val cut = cleaned.substring(0, maxChars)
            val lastSpace = cut.lastIndexOf(' ')
            val base = if (lastSpace > maxChars / 2) cut.substring(0, lastSpace) else cut
            return base.trimEnd('.', ',', ';', ':') + "..."
        }

        private fun splitSentences(text: String): List<String> =
            text.split(Regex("""(?<=[.!?])\s+""")).map { it.trim() }.filter { it.isNotEmpty() }

        private fun extractXmlTag(block: String, tagName: String): String {
            val regex = Regex("""(?is)<$tagName(?:\s[^>]*)?>([\s\S]*?)</$tagName>""")
            val raw = regex.find(block)?.groupValues?.get(1)?.trim() ?: return ""
            val cdata = CDATA_REGEX.find(raw)
            return if (cdata != null) cdata.groupValues[1].trim() else raw
        }

        private fun unescapeXmlAndHtml(raw: String): String {
            if (raw.isEmpty()) return ""
            return raw
                .replace(HTML_TAG_REGEX, "")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ")
                .replace(Regex("""&#x([0-9a-fA-F]+);""")) { m ->
                    m.groupValues[1].toIntOrNull(16)?.toChar()?.toString().orEmpty()
                }
                .replace(Regex("""&#(\d+);""")) { m ->
                    m.groupValues[1].toIntOrNull()?.toChar()?.toString().orEmpty()
                }
                .replace(Regex("""\s+"""), " ")
                .trim()
        }

        private fun formatRssDate(raw: String): String {
            if (raw.isBlank()) return ""
            return runCatching {
                val zdt = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME)
                zdt.format(OUT_DATE_FMT)
            }.getOrElse { raw }
        }

        private fun wmoLabelPt(code: Int): String = when (code) {
            0 -> "Céu limpo"
            1, 2 -> "Parcialmente nublado"
            3 -> "Nublado"
            45, 48 -> "Neblina"
            in 51..57 -> "Garoa"
            in 61..67 -> "Chuva"
            in 80..82 -> "Pancadas de chuva"
            in 71..77, 85, 86 -> "Neve"
            in 95..99 -> "Tempestade"
            else -> "Tempo estável"
        }
    }
}

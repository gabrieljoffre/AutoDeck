package com.autodeck.headunit.agent.copilot

import java.text.Normalizer
import java.util.Locale

/**
 * Referência leve de favorito (Casa, Trabalho, Posto etc.) usada pelo parser de intenções
 * sem acoplamento a classes de armazenamento ou Android.
 */
data class FavoriteRef(
    val id: String = "",
    val name: String,
    val address: String = "",
    val lat: Double? = null,
    val lon: Double? = null,
    val voice: String = ""
)

enum class VolumeDirection {
    UP,
    DOWN,
    SET,
    MUTE,
    UNMUTE
}

enum class TelemetryKind {
    COOLANT_TEMP,
    BATTERY_VOLTAGE,
    FUEL_CONSUMPTION,
    SPEED_RPM,
    DIAGNOSTICS_DTC,
    ENGINE_SUMMARY
}

enum class CopilotTab(val webMode: String) {
    HOME("home"),
    NAVIGATION("map"),
    MUSIC("music"),
    AGENT_VOICE("home"),
    OBD_SCANNER("settings"),
    SETTINGS("settings")
}

sealed interface CopilotIntent {
    data class Navigate(
        val dest: String,
        val favorite: FavoriteRef? = null,
        val forceExternalMaps: Boolean = false
    ) : CopilotIntent

    data class MapSearch(val query: String) : CopilotIntent
    data class MusicSearch(val query: String) : CopilotIntent
    data object MusicNext : CopilotIntent
    data object MusicPrev : CopilotIntent
    data object MusicPause : CopilotIntent
    data object MusicPlay : CopilotIntent
    data object OpenSpotify : CopilotIntent
    data object OpenMap : CopilotIntent
    data class ReplyWhatsApp(val text: String) : CopilotIntent
    data object ReadWhatsApp : CopilotIntent
    data object Weather : CopilotIntent
    data object Report : CopilotIntent
    data class LogFuel(val raw: String) : CopilotIntent
    data object FuelSummary : CopilotIntent

    data class Volume(
        val direction: VolumeDirection,
        val value: Int? = null
    ) : CopilotIntent {
        val up: Boolean get() = direction == VolumeDirection.UP
        val down: Boolean get() = direction == VolumeDirection.DOWN
        val isUp: Boolean get() = direction == VolumeDirection.UP
        val isDown: Boolean get() = direction == VolumeDirection.DOWN
        val isMute: Boolean get() = direction == VolumeDirection.MUTE

        companion object {
            fun up(): Volume = Volume(VolumeDirection.UP, null)
            fun down(): Volume = Volume(VolumeDirection.DOWN, null)
            fun set(pct: Int): Volume = Volume(VolumeDirection.SET, pct.coerceIn(0, 100))
            fun mute(): Volume = Volume(VolumeDirection.MUTE, 0)
            fun unmute(): Volume = Volume(VolumeDirection.UNMUTE, null)
        }
    }

    data object OpenCamera : CopilotIntent
    data object CloseCamera : CopilotIntent
    data class AskGeminiApp(val question: String) : CopilotIntent
    data class Telemetry(val kind: TelemetryKind) : CopilotIntent
    data class SwitchTab(val tab: CopilotTab) : CopilotIntent
    data object Unknown : CopilotIntent
}

/**
 * Classificador determinístico offline de intenções por voz/texto em português do Brasil.
 *
 * Regras principais:
 * - Normaliza para minúsculas e sem acentos antes de comparar E extrai argumentos do texto normalizado.
 * - Usa fronteiras de palavra (\b) para evitar falsos positivos ("replay", "display", "freio", "rede").
 * - Prioridade: WhatsApp (responder > ler) -> Abastecimento -> Rota/Favoritos/Busca de lugar -> Volume/Câmera -> Música -> Relatório/Clima/Telemetria -> Abas.
 * - "próxima"/"anterior" só disparam troca de faixa com contexto explícito de música ou frase curta isolada.
 */
object CopilotIntentParser {

    private val PT_BR: Locale = Locale.forLanguageTag("pt-BR")

    private val WAKE_PREFIX = Regex("""^(?:ei\s+|oi\s+|ola\s+)?ok\s+honda[\s,.:;!?-]*""")

    // Perguntar ao app Gemini / Google do tablet
    private val ASK_GEMINI_PATTERNS = listOf(
        Regex("""^(?:por\s+favor\s+)?(?:pergunte|pergunta|perguntar|fale|fala)\s+(?:ao|pro|para\s+o|no)\s+gemini\b[\s,:;-]*(.*)$"""),
        Regex("""^(?:por\s+favor\s+)?(?:pesquise|pesquisa|pesquisar|busque|busca|buscar|procure|procura|procurar)\s+(?:no|na|pelo)\s+(?:google(?!\s+maps\b)|gemini|internet)\b[\s,:;-]*(.*)$"""),
        Regex("""^(?:abrir|abre|abra)\s+(?:o\s+)?(?:app\s+do\s+)?gemini\b[\s,:;-]*(.*)$""")
    )

    // Responder WhatsApp (sempre antes do ramo geral de WhatsApp!)
    private val REPLY_WHATSAPP_PATTERNS = listOf(
        Regex("""^(?:por\s+favor\s+)?(?:responder|responda|responde)\s+(?:(?:o|a|ao|no|na|pro|pra|para\s+o|para\s+a)\s+)?(?:whatsapp|zap|(?:ultima\s+)?(?:mensagem|recado)(?:\s+(?:do|no|pelo)\s+(?:whatsapp|zap))?)(?:\s+(?:dizendo|falando|com))?[\s:,-]+(.+)$"""),
        Regex("""^(?:por\s+favor\s+)?(?:responder|responda|responde)(?:\s+(?:dizendo|falando))?[\s:,-]+(.+)$"""),
        Regex("""^(?:por\s+favor\s+)?(?:mandar|manda|mande|enviar|envia|envie)\s+(?:uma\s+)?(?:resposta|mensagem)\s+(?:(?:no|pelo|pro)\s+(?:whatsapp|zap)\s+)?(?:(?:dizendo|falando)[\s:,-]+|:\s*)(.+)$""")
    )

    private val READ_WHATSAPP_REGEX = Regex(
        """\b(?:ler|leia|le|ouvir|ouca|ver|mostrar|tem)\s+(?:as\s+|os\s+|a\s+|ultima\s+|novas\s+)?(?:mensagens|mensagem|whatsapp|zap|notificacoes|notificacao|recados|recado)\b|\b(?:whatsapp|mensagens\s+do\s+whatsapp|novas\s+mensagens)\b"""
    )

    // Diário de abastecimento
    private val LOG_FUEL_DIRECT_REGEX = Regex(
        """\b(?:abasteci|abastecer|registrar\s+abastecimento|registra\s+abastecimento|anotar\s+abastecimento|anota\s+abastecimento|enchi\s+o\s+tanque|completei\s+o\s+tanque)\b"""
    )
    private val LOG_FUEL_PUT_VERB = Regex("""\b(?:coloquei|botei|pus)\b""")
    private val LOG_FUEL_UNIT = Regex("""\b(?:litros?|reais|gasolina|etanol|alcool|combustivel)\b""")

    private val FUEL_SUMMARY_REGEX = Regex(
        """\b(?:quanto\s+gastei|gasto\s+(?:com|de)\s+(?:combustivel|gasolina|etanol|abastecimento)|gastos\s+com\s+combustivel|consumo\s+real|media\s+real|diario\s+de\s+abastecimento|historico\s+de\s+abastecimento|resumo\s+de\s+abastecimento)\b"""
    )

    // Mapa e navegação
    private val OPEN_MAP_EXACT = Regex(
        """^(?:(?:abrir|abre|abra|mostrar|mostra|mostre|ver|ir\s+para\s+o)\s+(?:o\s+)?)?(?:mapa|maps|google\s+maps|tela\s+de\s+mapa|navegacao)$"""
    )
    private val OPEN_SPOTIFY_REGEX = Regex(
        """^(?:(?:abrir|abre|abra|mostrar|mostra|mostre|ir\s+para\s+o)\s+(?:o\s+)?)?spotify$|\b(?:abrir|abre|abra)\s+(?:o\s+)?spotify\b"""
    )

    private val MUSIC_CONTEXT_WORDS = Regex(
        """\b(?:spotify|musica|musicas|faixa|faixas|som|cancao|playlist|playlists|album|artista|cantor|cantora|banda)\b"""
    )

    private val MAP_SEARCH_PATTERNS = listOf(
        Regex("""^(?:por\s+favor\s+)?(?:procure|procura|procurar|busque|busca|buscar|encontre|encontra|encontrar|ache|acha|achar|localize|localizar)\s+(?:no\s+mapa\s+|por\s+|perto\s+daqui\s+)?(.+)$"""),
        Regex("""^(?:onde\s+tem|onde\s+ha|onde\s+fica|onde\s+encontro|tem\s+algum|tem\s+alguma)\s+(.+)$""")
    )

    private val NAVIGATE_REGEX = Regex(
        """^(?:por\s+favor\s+)?(?:ir\s+para|ir\s+pra|ir\s+pro|ir\s+ate|voltar\s+para|va\s+para|va\s+ate|vamos\s+para|navegar\s+para|navegar\s+ate|navegue\s+para|rota\s+para|rota\s+ate|tracar\s+rota\s+para|trace\s+rota\s+para|me\s+leva\s+(?:para|pra|pro|ate|no|na)|me\s+leve\s+(?:para|pra|pro|ate|no|na)|levar\s+para|como\s+chegar\s+(?:em|no|na|ao|a)|caminho\s+para)\s+(.+)$"""
    )

    private val EXTERNAL_MAPS_SUFFIX = Regex("""\s+(?:no|pelo|via)\s+(?:google\s+maps|maps|waze)$""")
    private val UI_SCREEN_NAMES = setOf(
        "spotify", "mapa", "maps", "google maps", "configuracoes", "configuracao",
        "ajustes", "inicio", "home", "painel", "painel principal", "camera", "camera de re"
    )

    // Volume
    private val VOLUME_UNMUTE_REGEX = Regex(
        """\b(?:desmutar|tirar\s+do\s+mudo|sair\s+do\s+mudo|ativar\s+(?:o\s+)?som|ligar\s+(?:o\s+)?som)\b"""
    )
    private val VOLUME_MUTE_REGEX = Regex(
        """\b(?:mutar|deixar\s+mudo|modo\s+mudo|silenciar|tirar\s+o\s+som|desligar\s+o\s+som|zerar\s+(?:o\s+)?volume)\b|^mudo$"""
    )
    private val VOLUME_SET_REGEX = Regex(
        """\bvolume\b(?:\s+(?:em|no|na|para|a))?\s*(\d{1,3})\s*%?"""
    )
    private val VOLUME_MAX_REGEX = Regex("""\b(?:volume|som)\s+(?:no\s+)?maximo\b""")
    private val VOLUME_MIN_REGEX = Regex("""\b(?:volume|som)\s+(?:no\s+)?minimo\b""")
    private val VOLUME_UP_REGEX = Regex(
        """\b(?:aumentar|aumenta|aumente|subir|sobe|suba|erguer|ergue)\s+(?:um\s+pouco\s+)?(?:o\s+)?(?:volume|som|audio)\b|\b(?:volume|som)\s+(?:mais\s+alto|up|mais)\b|^(?:mais\s+alto|aumentar\s+volume|sobe\s+o\s+som)$"""
    )
    private val VOLUME_DOWN_REGEX = Regex(
        """\b(?:abaixar|abaixa|abaixe|baixar|baixa|baixe|diminuir|diminui|diminua|reduzir|reduza)\s+(?:um\s+pouco\s+)?(?:o\s+)?(?:volume|som|audio)\b|\b(?:volume|som)\s+(?:mais\s+baixo|down|menos)\b|^(?:mais\s+baixo|abaixar\s+volume|diminuir\s+volume|baixa\s+o\s+som)$"""
    )

    // Câmera de ré (sempre exige palavra inteira "camera", nunca "re" isolado!)
    private val CAMERA_WORD_REGEX = Regex("""\bcamera\b""")
    private val CAMERA_CLOSE_REGEX = Regex(
        """\b(?:fechar|fecha|feche|desligar|desliga|desligue|sair|saia|ocultar|esconder|encerrar)\b.*\bcamera\b|\bcamera\b.*\b(?:fechar|desligar|off)\b"""
    )

    // Música
    private val NEGATED_PAUSE_REGEX = Regex("""\bnao\s+(?:pausar|pausa|pause|parar|pare|para)\b""")
    private val MUSIC_PAUSE_REGEX = Regex(
        """\b(?:pausar|pausa|pause)\b|\b(?:parar|pare|para)\s+(?:a\s+|o\s+)?(?:musica|som|spotify|reproducao|faixa|player)\b"""
    )
    private val MUSIC_NEXT_SHORT = Regex("""^(?:proxima|proximo|pular|pula|avancar|avanca|next|skip)$""")
    private val MUSIC_NEXT_CONTEXT = Regex(
        """\b(?:proxima|proximo|pular|pula|avancar|avanca|passar|passa)\s+(?:a\s+|de\s+|para\s+a\s+)?(?:musica|faixa|som|cancao|track)\b|\b(?:musica|faixa)\s+seguinte\b"""
    )
    private val MUSIC_PREV_SHORT = Regex("""^(?:anterior|previous|prev)$""")
    private val MUSIC_PREV_CONTEXT = Regex(
        """\b(?:musica|faixa|som|cancao|track)\s+anterior\b|\b(?:anterior|voltar|volta)\s+(?:a\s+|de\s+|para\s+a\s+)?(?:musica|faixa|som|cancao|track)\b"""
    )

    private val MUSIC_SEARCH_PATTERNS = listOf(
        Regex("""^(?:por\s+favor\s+)?(?:tocar|toca|toque|colocar|coloca|coloque|ouvir|ouca|escute|escutar|reproduzir|reproduza|play\s+em|dar\s+play\s+em)\s+(.+)$"""),
        Regex("""^(?:por\s+favor\s+)?(?:procure|procura|procurar|busque|busca|buscar)\s+(.+?)\s+no\s+spotify$"""),
        Regex("""^(?:por\s+favor\s+)?(?:procure|procura|procurar|busque|busca|buscar)\s+(?:a\s+|uma\s+)?(?:musica|faixa|playlist|banda|artista)\s+(.+)$""")
    )

    private val MUSIC_GENERIC_OBJECT = setOf(
        "musica", "uma musica", "a musica", "as musicas", "musicas",
        "som", "o som", "um som", "spotify", "o spotify", "faixa", "a faixa", "play"
    )

    private val MUSIC_PLAY_REGEX = Regex(
        """^(?:tocar|toca|toque|play|dar\s+play|continuar|retomar)$|\b(?:continuar|retomar|voltar\s+a\s+tocar)\s+(?:a\s+|o\s+)?(?:musica|som|spotify|reproducao)\b|\b(?:dar\s+play|solta\s+o\s+som|tocar\s+musica|tocar\s+uma\s+musica|colocar\s+musica)\b"""
    )

    // Relatório, clima e telemetria
    private val REPORT_REGEX = Regex(
        """\b(?:relatorio|checkup|check\s+up|balanco\s+do\s+carro|resumo\s+geral|resumo\s+completo|status\s+completo|resumo\s+do\s+(?:carro|civic|veiculo))\b"""
    )

    private val WEATHER_REGEX = Regex(
        """\b(?:clima|chuva|chover|chovendo|garoa|tempestade|nublado|ensolarado|previsao\s+do\s+tempo)\b|\b(?:como\s+esta\s+o\s+tempo|qual\s+o\s+tempo(?:\s+hoje|\s+agora|\s+la\s+fora)?|tempo\s+(?:la\s+fora|hoje|agora|em\s+\w+))\b|\btemperatura\s+(?:la\s+fora|externa|ambiente|hoje|agora|na\s+rua|em\s+\w+)\b|\bla\s+fora\b"""
    )

    private val COOLANT_REGEX = Regex("""\b(?:temperatura|esquentando|superaquecendo|arrefecimento|radiador|agua\s+do\s+motor)\b""")
    private val VOLTAGE_REGEX = Regex("""\b(?:voltagem|bateria|alternador|tensao)\b""")
    private val CONSUMPTION_REGEX = Regex("""\b(?:consumo|combustivel|autonomia|tanque|km\s+por\s+litro|quilometros\s+por\s+litro)\b""")
    private val DTC_REGEX = Regex("""\b(?:falha|falhas|dtc|codigo\s+de\s+erro|codigos\s+de\s+erro|codigo\s+de\s+falha|codigos\s+de\s+falha|luz\s+da\s+injecao|injecao\s+eletronica)\b""")
    private val SPEED_RPM_REGEX = Regex("""\b(?:velocidade|rpm|rotacao|giro\s+do\s+motor|marcha)\b""")
    private val ENGINE_SUMMARY_REGEX = Regex("""\b(?:motor|diagnostico|como\s+esta\s+o\s+carro|como\s+esta\s+o\s+civic|status\s+do\s+carro|scanner)\b""")

    // Abas
    private val TAB_SETTINGS_REGEX = Regex("""\b(?:configuracoes|configuracao|ajustes|preferencias)\b""")
    private val TAB_SCANNER_REGEX = Regex("""\b(?:abrir\s+scanner|tela\s+de\s+obd|painel\s+obd)\b""")
    private val TAB_COPILOT_REGEX = Regex("""\b(?:abrir\s+copiloto|tela\s+do\s+copiloto)\b""")
    private val TAB_HOME_REGEX = Regex("""^(?:inicio|home|painel)$|\b(?:tela\s+inicial|painel\s+principal|ir\s+para\s+o\s+inicio|voltar\s+ao\s+painel|abrir\s+painel)\b""")

    /**
     * Normaliza o texto para minúsculas, sem acentos, sem pontuação nas pontas e com espaços únicos.
     */
    fun normalize(text: String): String {
        val lower = text.lowercase(PT_BR)
        val nfd = Normalizer.normalize(lower, Normalizer.Form.NFD)
        val noAccents = nfd.replace(Regex("""\p{M}+"""), "")
        return noAccents
            .replace(Regex("""[?!.;]+$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    fun parse(text: String, favorites: List<FavoriteRef> = emptyList()): CopilotIntent {
        val rawTrimmed = text.trim()
        if (rawTrimmed.isEmpty()) return CopilotIntent.Unknown

        val fullNorm = normalize(rawTrimmed)
        val q = fullNorm.replaceFirst(WAKE_PREFIX, "").trim()
        if (q.isEmpty()) return CopilotIntent.Unknown

        // 1. Pergunta explícita para o app Gemini / Google do tablet
        for (pat in ASK_GEMINI_PATTERNS) {
            val m = pat.find(q)
            if (m != null) {
                return CopilotIntent.AskGeminiApp(m.groupValues[1].trim())
            }
        }

        // 2. Responder WhatsApp ANTES de ler WhatsApp e antes de rota/música
        for (pat in REPLY_WHATSAPP_PATTERNS) {
            val m = pat.find(q)
            if (m != null) {
                val reply = m.groupValues[1].trim()
                if (reply.isNotEmpty()) return CopilotIntent.ReplyWhatsApp(reply)
            }
        }

        // 3. Ler WhatsApp
        if (READ_WHATSAPP_REGEX.containsMatchIn(q)) {
            return CopilotIntent.ReadWhatsApp
        }

        // 4. Abastecimento (registro e resumo)
        if (LOG_FUEL_DIRECT_REGEX.containsMatchIn(q) ||
            (LOG_FUEL_PUT_VERB.containsMatchIn(q) && LOG_FUEL_UNIT.containsMatchIn(q))
        ) {
            return CopilotIntent.LogFuel(rawTrimmed)
        }
        if (FUEL_SUMMARY_REGEX.containsMatchIn(q)) {
            return CopilotIntent.FuelSummary
        }

        // 5. Favoritos por comando de voz customizado exato (ex.: "ir para casa", "bora trabalhar")
        matchFavoriteByVoicePhrase(q, favorites)?.let { fav ->
            return CopilotIntent.Navigate(dest = normalize(fav.name).ifEmpty { q }, favorite = fav)
        }

        // 6. Abrir Spotify ou Mapa (sem destino) antes do genérico "ir para ..."
        if (OPEN_SPOTIFY_REGEX.containsMatchIn(q)) {
            return CopilotIntent.OpenSpotify
        }
        if (OPEN_MAP_EXACT.matches(q)) {
            return CopilotIntent.OpenMap
        }

        // 7. Rota / Navegação (PRIORIDADE ROTA > MÚSICA: "ir para a próxima rua" cai aqui!)
        // Exceção: frases explícitas de controle de faixa ("ir para a próxima música", "voltar para a música anterior")
        if (!MUSIC_NEXT_CONTEXT.containsMatchIn(q) && !MUSIC_PREV_CONTEXT.containsMatchIn(q)) {
            NAVIGATE_REGEX.find(q)?.let { m ->
                var rawDest = m.groupValues[1].trim()
                val forceExternal = EXTERNAL_MAPS_SUFFIX.containsMatchIn(rawDest)
                if (forceExternal) {
                    rawDest = rawDest.replaceFirst(EXTERNAL_MAPS_SUFFIX, "").trim()
                }
                if (rawDest.isNotEmpty() && rawDest !in UI_SCREEN_NAMES) {
                    val matchedFav = matchFavoriteByDestination(rawDest, favorites)
                    val finalDest = if (matchedFav != null) {
                        stripLeadingArticles(rawDest).ifEmpty { normalize(matchedFav.name) }
                    } else {
                        rawDest
                    }
                    return CopilotIntent.Navigate(
                        dest = finalDest,
                        favorite = matchedFav,
                        forceExternalMaps = forceExternal
                    )
                }
            }
        }

        // Nome exato de favorito isolado (ex.: dizer apenas "casa" ou "trabalho")
        matchFavoriteExactName(q, favorites)?.let { fav ->
            return CopilotIntent.Navigate(dest = normalize(fav.name), favorite = fav)
        }

        // 8. Busca de lugares no mapa ("procure um posto", "onde tem uma farmácia")
        // (\"tem algum código de falha\" é diagnóstico do carro, não busca de lugar)
        if (!MUSIC_CONTEXT_WORDS.containsMatchIn(q) && !DTC_REGEX.containsMatchIn(q)) {
            for (pat in MAP_SEARCH_PATTERNS) {
                val m = pat.find(q)
                if (m != null) {
                    val cleaned = cleanPlaceSearchQuery(m.groupValues[1])
                    if (cleaned.isNotEmpty() && cleaned !in UI_SCREEN_NAMES) {
                        return CopilotIntent.MapSearch(cleaned)
                    }
                }
            }
        }

        // 9. Volume (antes de música para "colocar volume no 50" ou "abaixar o som" não virar busca musical)
        parseVolumeIntent(q)?.let { return it }

        // 10. Câmera de ré (OPEN_CAMERA sempre abre; CLOSE_CAMERA fecha)
        if (CAMERA_WORD_REGEX.containsMatchIn(q)) {
            return if (CAMERA_CLOSE_REGEX.containsMatchIn(q)) {
                CopilotIntent.CloseCamera
            } else {
                CopilotIntent.OpenCamera
            }
        }

        // 11. Controles de música (próxima/anterior antes de pause para "voltar para a música anterior" não casar "para a música")
        if (MUSIC_NEXT_SHORT.matches(q) || MUSIC_NEXT_CONTEXT.containsMatchIn(q)) {
            return CopilotIntent.MusicNext
        }
        if (MUSIC_PREV_SHORT.matches(q) || MUSIC_PREV_CONTEXT.containsMatchIn(q)) {
            return CopilotIntent.MusicPrev
        }
        if (!NEGATED_PAUSE_REGEX.containsMatchIn(q) && MUSIC_PAUSE_REGEX.containsMatchIn(q)) {
            return CopilotIntent.MusicPause
        }
        if (MUSIC_PLAY_REGEX.containsMatchIn(q)) {
            return CopilotIntent.MusicPlay
        }
        for (pat in MUSIC_SEARCH_PATTERNS) {
            val m = pat.find(q)
            if (m != null) {
                val query = cleanMusicQuery(m.groupValues[1])
                return if (query.isEmpty() || query in MUSIC_GENERIC_OBJECT) {
                    CopilotIntent.MusicPlay
                } else {
                    CopilotIntent.MusicSearch(query)
                }
            }
        }

        // 12. Relatório completo do carro
        if (REPORT_REGEX.containsMatchIn(q)) {
            return CopilotIntent.Report
        }

        // 13. Clima (antes de temperatura do motor!)
        if (WEATHER_REGEX.containsMatchIn(q)) {
            return CopilotIntent.Weather
        }

        // 14. Telemetria específica
        when {
            COOLANT_REGEX.containsMatchIn(q) -> return CopilotIntent.Telemetry(TelemetryKind.COOLANT_TEMP)
            VOLTAGE_REGEX.containsMatchIn(q) -> return CopilotIntent.Telemetry(TelemetryKind.BATTERY_VOLTAGE)
            CONSUMPTION_REGEX.containsMatchIn(q) -> return CopilotIntent.Telemetry(TelemetryKind.FUEL_CONSUMPTION)
            DTC_REGEX.containsMatchIn(q) -> return CopilotIntent.Telemetry(TelemetryKind.DIAGNOSTICS_DTC)
            SPEED_RPM_REGEX.containsMatchIn(q) -> return CopilotIntent.Telemetry(TelemetryKind.SPEED_RPM)
            ENGINE_SUMMARY_REGEX.containsMatchIn(q) -> return CopilotIntent.Telemetry(TelemetryKind.ENGINE_SUMMARY)
        }

        // 15. Troca de aba/tela
        when {
            TAB_SETTINGS_REGEX.containsMatchIn(q) -> return CopilotIntent.SwitchTab(CopilotTab.SETTINGS)
            TAB_SCANNER_REGEX.containsMatchIn(q) -> return CopilotIntent.SwitchTab(CopilotTab.OBD_SCANNER)
            TAB_COPILOT_REGEX.containsMatchIn(q) -> return CopilotIntent.SwitchTab(CopilotTab.AGENT_VOICE)
            TAB_HOME_REGEX.containsMatchIn(q) -> return CopilotIntent.SwitchTab(CopilotTab.HOME)
        }

        return CopilotIntent.Unknown
    }

    private fun parseVolumeIntent(q: String): CopilotIntent.Volume? {
        if (VOLUME_UNMUTE_REGEX.containsMatchIn(q)) return CopilotIntent.Volume.unmute()
        if (VOLUME_MUTE_REGEX.containsMatchIn(q)) return CopilotIntent.Volume.mute()
        VOLUME_SET_REGEX.find(q)?.let { m ->
            val pct = m.groupValues[1].toIntOrNull()
            if (pct != null) return CopilotIntent.Volume.set(pct)
        }
        if (VOLUME_MAX_REGEX.containsMatchIn(q)) return CopilotIntent.Volume.set(100)
        if (VOLUME_MIN_REGEX.containsMatchIn(q)) return CopilotIntent.Volume.set(10)
        if (VOLUME_UP_REGEX.containsMatchIn(q)) return CopilotIntent.Volume.up()
        if (VOLUME_DOWN_REGEX.containsMatchIn(q)) return CopilotIntent.Volume.down()
        return null
    }

    private fun matchFavoriteByVoicePhrase(q: String, favorites: List<FavoriteRef>): FavoriteRef? {
        for (fav in favorites) {
            val v = normalize(fav.voice)
            if (v.isNotEmpty() && q == v) return fav
        }
        return null
    }

    private fun matchFavoriteExactName(q: String, favorites: List<FavoriteRef>): FavoriteRef? {
        for (fav in favorites) {
            val n = normalize(fav.name)
            if (n.isNotEmpty() && q == n) return fav
        }
        return null
    }

    private fun matchFavoriteByDestination(rawDest: String, favorites: List<FavoriteRef>): FavoriteRef? {
        val cleaned = stripLeadingArticles(rawDest)
        for (fav in favorites) {
            val favName = normalize(fav.name)
            val favId = normalize(fav.id)
            val favVoiceDest = normalize(fav.voice).replaceFirst(
                Regex("""^(?:ir\s+para|ir\s+pra|ir\s+pro|navegar\s+para|rota\s+para)\s+"""),
                ""
            ).let(::stripLeadingArticles)
            if ((favName.isNotEmpty() && (cleaned == favName || rawDest == favName)) ||
                (favId.isNotEmpty() && cleaned == favId) ||
                (favVoiceDest.isNotEmpty() && cleaned == favVoiceDest)
            ) {
                return fav
            }
        }
        return null
    }

    private fun stripLeadingArticles(s: String): String =
        s.replaceFirst(Regex("""^(?:o|a|os|as|um|uma|uns|umas|meu|minha|pro|pra)\s+"""), "").trim()

    private fun cleanPlaceSearchQuery(raw: String): String {
        return raw.trim()
            .replaceFirst(Regex("""^(?:por\s+)?(?:um|uma|uns|umas|o|a|os|as|algum|alguma)\s+"""), "")
            .replaceFirst(
                Regex("""\s+(?:perto(?:\s+daqui|\s+de\s+mim)?|proximo(?:\s+daqui)?|por\s+aqui|aqui\s+perto|no\s+mapa)$"""),
                ""
            )
            .trim()
    }

    private fun cleanMusicQuery(raw: String): String {
        var s = raw.trim()
            .replaceFirst(Regex("""\s+(?:no|pelo|na)\s+spotify$"""), "")
            .trim()
        if (s in MUSIC_GENERIC_OBJECT) return s
        s = s.replaceFirst(Regex("""^(?:a\s+|uma\s+)?musica\s+(?:do|da|de|dos|das)?\s*"""), "").trim()
        return s
    }
}

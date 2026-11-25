package com.autodeck.protocol.apps

import java.text.Normalizer
import java.util.Locale

data class LaunchableApp(
    val packageName: String,
    val label: String
)

sealed interface AppMatchResult {
    data class Matched(val app: LaunchableApp) : AppMatchResult
    data class Ambiguous(val candidates: List<LaunchableApp>) : AppMatchResult
    data object NotFound : AppMatchResult
}

/**
 * Resolve o nome falado de um aplicativo ("YouTube Music", "câmera", "Waze", "zap", "Chrome")
 * para um aplicativo instalado no tablet ou no celular, de forma tolerante a acentos e apelidos pt-BR.
 */
object AppLaunchMatcher {

    private val PT_BR: Locale = Locale.forLanguageTag("pt-BR")
    private val STRIP_PREFIX = Regex("""^(?:o|a|os|as|app|aplicativo|programa)\s+""")

    // Apelidos falados em pt-BR -> pistas de pacote e rótulos alternativos
    private data class AppAlias(
        val spokenAliases: Set<String>,
        val packageHints: Set<String>,
        val labelHints: Set<String>
    )

    private val ALIASES: List<AppAlias> = listOf(
        AppAlias(
            spokenAliases = setOf("youtube music", "yt music", "musica do youtube", "youtube musica"),
            packageHints = setOf("com.google.android.apps.youtube.music"),
            labelHints = setOf("yt music", "youtube music")
        ),
        AppAlias(
            spokenAliases = setOf("youtube", "yt"),
            packageHints = setOf("com.google.android.youtube"),
            labelHints = setOf("youtube")
        ),
        AppAlias(
            spokenAliases = setOf("waze"),
            packageHints = setOf("com.waze"),
            labelHints = setOf("waze")
        ),
        AppAlias(
            spokenAliases = setOf("google maps", "maps", "mapa do google"),
            packageHints = setOf("com.google.android.apps.maps"),
            labelHints = setOf("maps", "google maps")
        ),
        AppAlias(
            spokenAliases = setOf("spotify"),
            packageHints = setOf("com.spotify.music"),
            labelHints = setOf("spotify")
        ),
        AppAlias(
            spokenAliases = setOf("deezer"),
            packageHints = setOf("deezer.android.app"),
            labelHints = setOf("deezer")
        ),
        AppAlias(
            spokenAliases = setOf("camera", "foto", "fotos camera", "tirar foto"),
            packageHints = setOf(
                "com.sec.android.app.camera",
                "com.android.camera",
                "com.android.camera2",
                "com.google.android.GoogleCamera",
                "net.sourceforge.opencamera"
            ),
            labelHints = setOf("camera", "open camera")
        ),
        AppAlias(
            spokenAliases = setOf("whatsapp", "zap", "zapzap", "whats"),
            packageHints = setOf("com.whatsapp", "com.whatsapp.w4b"),
            labelHints = setOf("whatsapp", "whatsapp business")
        ),
        AppAlias(
            spokenAliases = setOf("whatsapp business", "zap business"),
            packageHints = setOf("com.whatsapp.w4b"),
            labelHints = setOf("whatsapp business")
        ),
        AppAlias(
            spokenAliases = setOf("chrome", "navegador", "google chrome", "internet"),
            packageHints = setOf("com.android.chrome", "com.sec.android.app.sbrowser"),
            labelHints = setOf("chrome", "internet", "samsung internet")
        ),
        AppAlias(
            spokenAliases = setOf("calculadora", "calc"),
            packageHints = setOf("com.sec.android.app.popupcalculator", "com.google.android.calculator", "com.android.calculator2"),
            labelHints = setOf("calculadora", "calculator")
        ),
        AppAlias(
            spokenAliases = setOf("galeria", "fotos", "google fotos"),
            packageHints = setOf("com.sec.android.gallery3d", "com.google.android.apps.photos"),
            labelHints = setOf("galeria", "fotos", "photos", "gallery")
        ),
        AppAlias(
            spokenAliases = setOf("agenda", "calendario", "google agenda"),
            packageHints = setOf("com.google.android.calendar", "com.samsung.android.calendar"),
            labelHints = setOf("agenda", "calendario", "calendar")
        ),
        AppAlias(
            spokenAliases = setOf("relogio", "alarme", "despertador"),
            packageHints = setOf("com.sec.android.app.clockpackage", "com.google.android.deskclock"),
            labelHints = setOf("relogio", "clock")
        ),
        AppAlias(
            spokenAliases = setOf("configuracoes", "configuracoes do android", "ajustes do android", "config"),
            packageHints = setOf("com.android.settings"),
            labelHints = setOf("config", "configuracoes", "settings")
        ),
        AppAlias(
            spokenAliases = setOf("telegram"),
            packageHints = setOf("org.telegram.messenger"),
            labelHints = setOf("telegram")
        ),
        AppAlias(
            spokenAliases = setOf("instagram", "insta"),
            packageHints = setOf("com.instagram.android"),
            labelHints = setOf("instagram")
        ),
        AppAlias(
            spokenAliases = setOf("netflix"),
            packageHints = setOf("com.netflix.mediaclient"),
            labelHints = setOf("netflix")
        ),
        AppAlias(
            spokenAliases = setOf("uber"),
            packageHints = setOf("com.ubercab"),
            labelHints = setOf("uber")
        ),
        AppAlias(
            spokenAliases = setOf("99", "noventa e nove", "99 pop"),
            packageHints = setOf("com.taxis99"),
            labelHints = setOf("99")
        ),
        AppAlias(
            spokenAliases = setOf("gemini", "google gemini"),
            packageHints = setOf("com.google.android.apps.bard"),
            labelHints = setOf("gemini")
        ),
        AppAlias(
            spokenAliases = setOf("play store", "loja de aplicativos"),
            packageHints = setOf("com.android.vending"),
            labelHints = setOf("google play store", "play store")
        )
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

    fun cleanQuery(raw: String): String {
        var s = normalize(raw)
        repeat(2) {
            s = s.replaceFirst(STRIP_PREFIX, "").trim()
        }
        return s
    }

    fun search(apps: List<LaunchableApp>, query: String, limit: Int = 5): List<LaunchableApp> {
        val q = cleanQuery(query)
        if (q.isEmpty() || apps.isEmpty()) return emptyList()
        val queryTokens = q.split(' ').filter { it.isNotBlank() }

        val matchedAliases = ALIASES.filter { q in it.spokenAliases }
        val scored = ArrayList<Pair<Int, LaunchableApp>>()

        for (app in apps) {
            val score = scoreApp(app, q, queryTokens, matchedAliases)
            if (score > 0) {
                scored += score to app
            }
        }

        return scored
            .sortedWith(
                compareByDescending<Pair<Int, LaunchableApp>> { it.first }
                    .thenBy { normalize(it.second.label).length }
                    .thenBy { normalize(it.second.label) }
            )
            .map { it.second }
            .distinctBy { it.packageName }
            .take(limit)
    }

    fun resolve(apps: List<LaunchableApp>, query: String): AppMatchResult {
        val q = cleanQuery(query)
        if (q.isEmpty() || apps.isEmpty()) return AppMatchResult.NotFound

        val matchedAliases = ALIASES.filter { q in it.spokenAliases }
        val queryTokens = q.split(' ').filter { it.isNotBlank() }

        val scored = apps.mapNotNull { app ->
            val s = scoreApp(app, q, queryTokens, matchedAliases)
            if (s > 0) s to app else null
        }.sortedWith(
            compareByDescending<Pair<Int, LaunchableApp>> { it.first }
                .thenBy { normalize(it.second.label).length }
                .thenBy { normalize(it.second.label) }
        )

        if (scored.isEmpty()) return AppMatchResult.NotFound
        if (scored.size == 1) return AppMatchResult.Matched(scored.first().second)

        val topScore = scored.first().first
        val secondScore = scored[1].first
        // Se o primeiro tem pontuação claramente superior (ex.: match exato ou alias de pacote exato)
        if (topScore >= 900 && topScore > secondScore) {
            return AppMatchResult.Matched(scored.first().second)
        }
        if (topScore - secondScore >= 120) {
            return AppMatchResult.Matched(scored.first().second)
        }

        val tied = scored.filter { it.first >= topScore - 80 }.map { it.second }.distinctBy { it.packageName }
        return if (tied.size == 1) {
            AppMatchResult.Matched(tied.first())
        } else {
            AppMatchResult.Ambiguous(tied.take(4))
        }
    }

    private fun scoreApp(
        app: LaunchableApp,
        q: String,
        queryTokens: List<String>,
        matchedAliases: List<AppAlias>
    ): Int {
        val normLabel = normalize(app.label)
        val labelTokens = normLabel.split(' ').filter { it.isNotBlank() }
        val pkgLower = app.packageName.lowercase(Locale.ROOT)
        var best = 0

        // 1) Nome exato do app
        if (normLabel == q) best = maxOf(best, 1000)

        // 2) Alias explícito por packageName ou labelHint
        for (alias in matchedAliases) {
            if (alias.packageHints.any { it.equals(app.packageName, ignoreCase = true) }) {
                best = maxOf(best, 980)
            }
            if (alias.labelHints.any { normLabel == it }) {
                best = maxOf(best, 950)
            } else if (alias.labelHints.any { normLabel.contains(it) }) {
                best = maxOf(best, 860)
            }
        }

        // 3) Todos os tokens da busca presentes no rótulo
        if (queryTokens.isNotEmpty()) {
            val allExact = queryTokens.all { qt -> labelTokens.any { it == qt } }
            val allPrefix = !allExact && queryTokens.all { qt -> labelTokens.any { it.startsWith(qt) } }
            if (allExact) {
                best = maxOf(best, if (queryTokens.size == labelTokens.size) 940 else 820)
            } else if (allPrefix) {
                best = maxOf(best, 700)
            }
        }

        // 4) Prefixo ou substring do rótulo
        if (q.length >= 2) {
            if (normLabel.startsWith(q)) best = maxOf(best, 760)
            else if (normLabel.contains(q)) best = maxOf(best, 600)
        }

        // 5) Segmento do nome do pacote (ex.: "waze" em "com.waze", "camera" em "com.sec.android.app.camera")
        if (q.length >= 3 && !q.contains(' ')) {
            val pkgSegments = pkgLower.split('.')
            if (pkgSegments.any { it == q }) {
                best = maxOf(best, 780)
            } else if (pkgSegments.any { it.contains(q) }) {
                best = maxOf(best, 500)
            }
        }

        return best
    }
}

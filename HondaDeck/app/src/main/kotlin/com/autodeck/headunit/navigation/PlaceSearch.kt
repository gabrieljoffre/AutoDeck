package com.autodeck.headunit.navigation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

data class PlaceSearchResult(
    val name: String,
    val address: String,
    val lat: Double,
    val lon: Double,
    val distanceKm: Double
)

data class PlaceSearchState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<PlaceSearchResult> = emptyList()
)

/** Sugestão local (favorito ou pesquisa recente) usada para mesclar resultados instantaneamente. */
data class LocalPlaceHint(
    val name: String,
    val address: String,
    val lat: Double,
    val lon: Double,
    val isFavorite: Boolean = false
)

/**
 * Busca inteligente de lugares sem chave de API:
 * - Consulta Photon (`https://photon.komoot.io/api/`) com viés na posição atual (tolerante a erros de
 *   digitação e nomes incompletos).
 * - Complementa com Nominatim (OpenStreetMap) com intervalo mínimo de 1 s quando o Photon retornar
 *   poucos resultados ou falhar.
 * - Mescla e prioriza favoritos e pesquisas recentes correspondentes (também tolerante a acentos e
 *   pequenos erros de digitação).
 * - Cache em memória (TTL 10 min) e ordenação por relevância + proximidade.
 */
class PlaceSearch(
    private val httpFetcher: suspend (String) -> String = { url -> defaultHttpGet(url) },
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val minIntervalMs: Long = 1_000L
) {
    private data class CacheEntry(
        val savedAtMs: Long,
        val results: List<PlaceSearchResult>
    )

    private val nominatimMutex = Mutex()
    private var lastNominatimCallMs = 0L

    private val cache = object : LinkedHashMap<String, CacheEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    suspend fun search(
        query: String,
        originLat: Double,
        originLon: Double,
        localHints: List<LocalPlaceHint> = emptyList()
    ): List<PlaceSearchResult> {
        val raw = query.trim()
        if (raw.length < 2) return emptyList()

        val localHits = matchLocalHints(raw, localHints, originLat, originLon)
        val effectiveQuery = expandQuery(raw)
        val cacheKey = buildCacheKey(effectiveQuery, originLat, originLon)
        val now = clockMs()

        val cachedRemote = synchronized(cache) {
            val hit = cache[cacheKey]
            if (hit != null && now - hit.savedAtMs <= CACHE_TTL_MS) {
                hit.results.map { r ->
                    r.copy(distanceKm = calcDistanceKm(originLat, originLon, r.lat, r.lon))
                }.sortedBy { it.distanceKm }
            } else {
                null
            }
        }
        if (cachedRemote != null) {
            return mergeResults(localHits, cachedRemote)
        }

        // 1) Photon (rápido, tolerante a typos/incompletos, com viés na posição atual)
        var photonResults: List<PlaceSearchResult> = emptyList()
        var alreadyGotNominatimArray = false
        val photonUrl = buildPhotonUrl(effectiveQuery, originLat, originLon)
        val photonBody = runCatching { httpFetcher(photonUrl) }.getOrNull()
        if (!photonBody.isNullOrBlank()) {
            val trimmedBody = photonBody.trimStart()
            if (trimmedBody.startsWith("[")) {
                // Compatibilidade com fetcher de teste que devolve JSONArray no formato Nominatim
                photonResults = runCatching { parseResults(trimmedBody, originLat, originLon) }.getOrDefault(emptyList())
                alreadyGotNominatimArray = true
            } else {
                photonResults = runCatching { parsePhotonResults(trimmedBody, originLat, originLon) }.getOrDefault(emptyList())
            }
        }

        // 2) Complemento via Nominatim se Photon falhou ou trouxe menos de 4 resultados úteis
        var remote = photonResults
        if (!alreadyGotNominatimArray && photonResults.size < 4) {
            val nomUrl = buildUrl(effectiveQuery, originLat, originLon)
            val nomBody = runCatching { fetchNominatimRateLimited(nomUrl) }.getOrNull()
            var nomParsed = if (!nomBody.isNullOrBlank()) {
                runCatching { parseResults(nomBody, originLat, originLon) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }

            // Se atalho local ("posto"/"oficina") só retornou locais distantes (>25 km), tenta busca restrita perto
            if (effectiveQuery != raw && (nomParsed.isEmpty() || nomParsed.first().distanceKm > 25.0)) {
                val nearUrl = buildUrl(raw, originLat, originLon, radiusDeg = 0.15, bounded = true)
                val nearParsed = runCatching {
                    parseResults(fetchNominatimRateLimited(nearUrl), originLat, originLon)
                }.getOrDefault(emptyList())
                if (nearParsed.isNotEmpty()) {
                    nomParsed = deduplicateAndSort(nearParsed + nomParsed)
                }
            }

            remote = deduplicateAndSort(photonResults + nomParsed)
        }

        synchronized(cache) {
            cache[cacheKey] = CacheEntry(clockMs(), remote)
        }
        return mergeResults(localHits, remote)
    }

    private suspend fun fetchNominatimRateLimited(url: String): String = nominatimMutex.withLock {
        val elapsed = clockMs() - lastNominatimCallMs
        if (lastNominatimCallMs > 0L && elapsed in 0 until minIntervalMs) {
            delay(minIntervalMs - elapsed)
        }
        lastNominatimCallMs = clockMs()
        httpFetcher(url)
    }

    companion object {
        private const val USER_AGENT = "HondaDeck/3.0 (Android)"
        private const val CACHE_TTL_MS = 10 * 60_000L
        private const val MAX_CACHE_ENTRIES = 64
        private const val MAX_RESULTS = 8

        /** Expande atalhos comuns de direção para consultas melhores. */
        fun expandQuery(raw: String): String {
            val trimmed = raw.trim()
            val norm = normalizeText(trimmed)
            return when (norm) {
                "posto", "postos", "posto de gasolina", "gasolina" -> "posto de combustível"
                "oficina", "oficinas", "mecanico", "mecanica" -> "oficina mecânica"
                else -> trimmed
            }
        }

        /** URL do Photon (Komoot) com viés geográfico na posição atual. */
        fun buildPhotonUrl(
            query: String,
            lat: Double,
            lon: Double,
            limit: Int = MAX_RESULTS
        ): String {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val latStr = String.format(Locale.US, "%.5f", lat)
            val lonStr = String.format(Locale.US, "%.5f", lon)
            return "https://photon.komoot.io/api/?q=$encoded&lat=$latStr&lon=$lonStr&lang=default&limit=$limit"
        }

        /** URL do Nominatim (OpenStreetMap) usada como complemento/fallback. */
        fun buildUrl(
            query: String,
            lat: Double,
            lon: Double,
            radiusDeg: Double = 0.35,
            bounded: Boolean = false
        ): String {
            val left = String.format(Locale.US, "%.5f", lon - radiusDeg)
            val top = String.format(Locale.US, "%.5f", lat + radiusDeg)
            val right = String.format(Locale.US, "%.5f", lon + radiusDeg)
            val bottom = String.format(Locale.US, "%.5f", lat - radiusDeg)
            val encoded = URLEncoder.encode(query, "UTF-8")
            val b = if (bounded) 1 else 0
            return "https://nominatim.openstreetmap.org/search?format=jsonv2" +
                "&q=$encoded&limit=$MAX_RESULTS&countrycodes=br&accept-language=pt-BR" +
                "&viewbox=$left,$top,$right,$bottom&bounded=$b"
        }

        /** Faz parse do GeoJSON retornado pelo Photon, formatando nome + bairro/cidade + distância. */
        fun parsePhotonResults(
            json: String,
            originLat: Double,
            originLon: Double
        ): List<PlaceSearchResult> {
            val root = JSONObject(json)
            val features = root.optJSONArray("features") ?: return emptyList()
            val out = mutableListOf<PlaceSearchResult>()
            for (i in 0 until features.length()) {
                val feat = features.optJSONObject(i) ?: continue
                val geom = feat.optJSONObject("geometry") ?: continue
                val coords = geom.optJSONArray("coordinates") ?: continue
                if (coords.length() < 2) continue
                val lon = coords.optDouble(0, Double.NaN)
                val lat = coords.optDouble(1, Double.NaN)
                if (!lat.isFinite() || !lon.isFinite()) continue

                val props = feat.optJSONObject("properties") ?: JSONObject()
                val rawName = props.optString("name").trim()
                val street = props.optString("street").trim()
                val house = props.optString("housenumber").trim()
                val district = props.optString("district").trim()
                    .ifBlank { props.optString("suburb").trim() }
                    .ifBlank { props.optString("locality").trim() }
                val city = props.optString("city").trim()
                    .ifBlank { props.optString("town").trim() }
                    .ifBlank { props.optString("village").trim() }
                    .ifBlank { props.optString("county").trim() }
                val state = props.optString("state").trim()

                val streetFull = when {
                    street.isNotBlank() && house.isNotBlank() -> "$street, $house"
                    street.isNotBlank() -> street
                    else -> ""
                }
                val name = rawName
                    .ifBlank { streetFull }
                    .ifBlank { district }
                    .ifBlank { city }
                    .ifBlank { "Local" }

                val addrParts = mutableListOf<String>()
                if (streetFull.isNotBlank() && !streetFull.equals(name, ignoreCase = true)) {
                    addrParts += streetFull
                }
                if (district.isNotBlank() && !district.equals(name, ignoreCase = true)) {
                    addrParts += district
                }
                val cityState = when {
                    city.isNotBlank() && state.isNotBlank() -> "$city - ${abbreviateBrState(state)}"
                    city.isNotBlank() -> city
                    state.isNotBlank() -> state
                    else -> ""
                }
                if (cityState.isNotBlank() && !city.equals(name, ignoreCase = true)) {
                    addrParts += cityState
                }

                val distKm = calcDistanceKm(originLat, originLon, lat, lon)
                out += PlaceSearchResult(
                    name = name,
                    address = addrParts.distinct().joinToString(", "),
                    lat = lat,
                    lon = lon,
                    distanceKm = distKm
                )
            }
            return deduplicateAndSort(out)
        }

        /** Faz parse do JSONArray retornado pelo Nominatim. */
        fun parseResults(
            json: String,
            originLat: Double,
            originLon: Double
        ): List<PlaceSearchResult> {
            val arr = JSONArray(json)
            val out = mutableListOf<PlaceSearchResult>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val lat = obj.optString("lat").toDoubleOrNull() ?: continue
                val lon = obj.optString("lon").toDoubleOrNull() ?: continue
                val displayName = obj.optString("display_name").trim()
                val rawName = obj.optString("name").trim()
                val name = rawName.ifBlank { displayName.substringBefore(",").trim() }
                    .ifBlank { "Local" }
                val address = formatShortAddress(name, displayName)
                val distKm = calcDistanceKm(originLat, originLon, lat, lon)
                out += PlaceSearchResult(
                    name = name,
                    address = address,
                    lat = lat,
                    lon = lon,
                    distanceKm = distKm
                )
            }
            return deduplicateAndSort(out)
        }

        /** Filtra favoritos e recentes tolerando acentos, nomes incompletos e pequenos erros de digitação. */
        fun matchLocalHints(
            query: String,
            hints: List<LocalPlaceHint>,
            originLat: Double,
            originLon: Double
        ): List<PlaceSearchResult> {
            if (hints.isEmpty()) return emptyList()
            val qNorm = normalizeText(query)
            if (qNorm.length < 2) return emptyList()
            val qTokens = qNorm.split(" ").filter { it.isNotBlank() }

            val scored = mutableListOf<Pair<Int, PlaceSearchResult>>()
            for (h in hints) {
                if (!h.lat.isFinite() || !h.lon.isFinite() || (h.lat == 0.0 && h.lon == 0.0)) continue
                val nameNorm = normalizeText(h.name)
                val addrNorm = normalizeText(h.address)
                val combined = "$nameNorm $addrNorm".trim()
                val score = fuzzyScore(qNorm, qTokens, nameNorm, combined)
                if (score > 0) {
                    val bonus = if (h.isFavorite) 20 else 0
                    val distKm = calcDistanceKm(originLat, originLon, h.lat, h.lon)
                    scored += (score + bonus) to PlaceSearchResult(
                        name = h.name,
                        address = h.address,
                        lat = h.lat,
                        lon = h.lon,
                        distanceKm = distKm
                    )
                }
            }
            return scored
                .sortedWith(compareByDescending<Pair<Int, PlaceSearchResult>> { it.first }.thenBy { it.second.distanceKm })
                .map { it.second }
        }

        private fun mergeResults(
            localHits: List<PlaceSearchResult>,
            remoteHits: List<PlaceSearchResult>
        ): List<PlaceSearchResult> {
            val seen = LinkedHashSet<String>()
            val merged = mutableListOf<PlaceSearchResult>()
            for (item in localHits) {
                val key = dedupKey(item)
                if (seen.add(key)) merged += item
            }
            for (item in remoteHits) {
                val key = dedupKey(item)
                if (seen.add(key)) merged += item
            }
            return merged.take(MAX_RESULTS)
        }

        private fun deduplicateAndSort(list: List<PlaceSearchResult>): List<PlaceSearchResult> =
            list.distinctBy { dedupKey(it) }
                .sortedBy { it.distanceKm }
                .take(MAX_RESULTS)

        private fun dedupKey(r: PlaceSearchResult): String =
            "${normalizeText(r.name)}|${(r.lat * 1000).roundToInt()}|${(r.lon * 1000).roundToInt()}"

        private fun fuzzyScore(
            qNorm: String,
            qTokens: List<String>,
            nameNorm: String,
            combined: String
        ): Int {
            if (nameNorm.startsWith(qNorm)) return 100
            if (nameNorm.contains(qNorm)) return 80
            if (combined.contains(qNorm)) return 60
            val words = combined.split(" ").filter { it.isNotBlank() }
            if (words.isEmpty() || qTokens.isEmpty()) return 0
            var matched = 0
            for (qt in qTokens) {
                val hit = words.any { w ->
                    w.startsWith(qt) || (qt.length >= 4 && w.length >= 4 && editDistanceAtMostOne(qt, w.take(qt.length + 1)))
                }
                if (hit) matched++
            }
            return if (matched == qTokens.size) 50 else 0
        }

        private fun editDistanceAtMostOne(a: String, b: String): Boolean {
            if (a == b) return true
            if (abs(a.length - b.length) > 1) return false
            var i = 0
            var j = 0
            var edits = 0
            while (i < a.length && j < b.length) {
                if (a[i] == b[j]) {
                    i++
                    j++
                } else {
                    edits++
                    if (edits > 1) return false
                    if (a.length > b.length) i++
                    else if (b.length > a.length) j++
                    else {
                        i++
                        j++
                    }
                }
            }
            if (i < a.length || j < b.length) edits++
            return edits <= 1
        }

        private fun abbreviateBrState(state: String): String = when (normalizeText(state)) {
            "goias" -> "GO"
            "distrito federal" -> "DF"
            "sao paulo" -> "SP"
            "rio de janeiro" -> "RJ"
            "minas gerais" -> "MG"
            "bahia" -> "BA"
            "parana" -> "PR"
            "santa catarina" -> "SC"
            "rio grande do sul" -> "RS"
            "mato grosso" -> "MT"
            "mato grosso do sul" -> "MS"
            "tocantins" -> "TO"
            else -> state
        }

        internal fun normalizeText(raw: String): String =
            Normalizer.normalize(raw.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
                .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
                .replace(Regex("\\s+"), " ")
                .trim()

        private fun formatShortAddress(name: String, displayName: String): String {
            if (displayName.isBlank()) return ""
            val parts = displayName.split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
            if (parts.isEmpty()) return ""
            val withoutTitle = if (parts.first().equals(name, ignoreCase = true)) parts.drop(1) else parts
            return withoutTitle.take(3).joinToString(", ")
        }

        private fun buildCacheKey(query: String, lat: Double, lon: Double): String {
            val qNorm = normalizeText(query)
            val latBucket = (lat * 100.0).roundToInt()
            val lonBucket = (lon * 100.0).roundToInt()
            return "$qNorm|$latBucket|$lonBucket"
        }

        private fun calcDistanceKm(originLat: Double, originLon: Double, lat: Double, lon: Double): Double =
            roundKm(
                InAppRouteNavigator.haversineM(
                    RoutePoint(originLat, originLon),
                    RoutePoint(lat, lon)
                ) / 1000.0
            )

        private fun roundKm(km: Double): Double = (km * 10.0).roundToInt() / 10.0

        private suspend fun defaultHttpGet(url: String): String = withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (conn.responseCode !in 200..299) {
                    error("PlaceSearch HTTP ${conn.responseCode} em $url")
                }
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
    }
}

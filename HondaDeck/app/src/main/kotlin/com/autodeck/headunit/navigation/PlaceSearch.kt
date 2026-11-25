package com.autodeck.headunit.navigation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.LinkedHashMap
import java.util.Locale
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

/**
 * Busca de lugares via Nominatim (OpenStreetMap) com:
 * - intervalo mínimo de 1 s entre chamadas HTTP (política do Nominatim),
 * - cache em memória por consulta + região,
 * - cálculo de distância até a posição atual e ordenação por proximidade,
 * - expansão de termos curtos ("posto" -> "posto de combustível", "oficina" -> "oficina mecânica").
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

    private val mutex = Mutex()
    private var lastNetworkCallMs = 0L

    private val cache = object : LinkedHashMap<String, CacheEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    suspend fun search(
        query: String,
        originLat: Double,
        originLon: Double
    ): List<PlaceSearchResult> {
        val raw = query.trim()
        if (raw.length < 2) return emptyList()

        val effectiveQuery = expandQuery(raw)
        val cacheKey = buildCacheKey(effectiveQuery, originLat, originLon)
        val now = clockMs()

        synchronized(cache) {
            val hit = cache[cacheKey]
            if (hit != null && now - hit.savedAtMs <= CACHE_TTL_MS) {
                return hit.results.map { r ->
                    r.copy(
                        distanceKm = roundKm(
                            InAppRouteNavigator.haversineM(
                                RoutePoint(originLat, originLon),
                                RoutePoint(r.lat, r.lon)
                            ) / 1000.0
                        )
                    )
                }.sortedBy { it.distanceKm }
            }
        }

        val url = buildUrl(effectiveQuery, originLat, originLon)
        val body = fetchRateLimited(url)
        var parsed = parseResults(body, originLat, originLon)

        // Se atalho local ("posto"/"oficina") só retornou locais distantes (>25 km), tenta busca restrita perto
        if (effectiveQuery != raw && (parsed.isEmpty() || parsed.first().distanceKm > 25.0)) {
            val nearUrl = buildUrl(raw, originLat, originLon, radiusDeg = 0.15, bounded = true)
            val nearParsed = runCatching { parseResults(fetchRateLimited(nearUrl), originLat, originLon) }
                .getOrDefault(emptyList())
            if (nearParsed.isNotEmpty()) {
                parsed = (nearParsed + parsed)
                    .distinctBy { "${it.name.lowercase(Locale.ROOT)}|${(it.lat * 1000).roundToInt()}|${(it.lon * 1000).roundToInt()}" }
                    .sortedBy { it.distanceKm }
                    .take(8)
            }
        }

        synchronized(cache) {
            cache[cacheKey] = CacheEntry(clockMs(), parsed)
        }
        return parsed
    }

    private suspend fun fetchRateLimited(url: String): String = mutex.withLock {
        val elapsed = clockMs() - lastNetworkCallMs
        if (lastNetworkCallMs > 0L && elapsed in 0 until minIntervalMs) {
            delay(minIntervalMs - elapsed)
        }
        lastNetworkCallMs = clockMs()
        httpFetcher(url)
    }

    companion object {
        private const val USER_AGENT = "HondaDeck/3.0 (Android)"
        private const val CACHE_TTL_MS = 10 * 60_000L
        private const val MAX_CACHE_ENTRIES = 64

        /** Expande atalhos comuns de direção para consultas melhores no Nominatim. */
        fun expandQuery(raw: String): String {
            val trimmed = raw.trim()
            val norm = Normalizer.normalize(trimmed.lowercase(Locale.ROOT), Normalizer.Form.NFD)
                .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
            return when (norm) {
                "posto", "postos", "posto de gasolina", "gasolina" -> "posto de combustível"
                "oficina", "oficinas", "mecanico", "mecanica" -> "oficina mecânica"
                else -> trimmed
            }
        }

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
                "&q=$encoded&limit=8&countrycodes=br&accept-language=pt-BR" +
                "&viewbox=$left,$top,$right,$bottom&bounded=$b"
        }

        fun parseResults(
            json: String,
            originLat: Double,
            originLon: Double
        ): List<PlaceSearchResult> {
            val arr = JSONArray(json)
            val origin = RoutePoint(originLat, originLon)
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
                val distKm = roundKm(InAppRouteNavigator.haversineM(origin, RoutePoint(lat, lon)) / 1000.0)
                out += PlaceSearchResult(
                    name = name,
                    address = address,
                    lat = lat,
                    lon = lon,
                    distanceKm = distKm
                )
            }
            return out.sortedBy { it.distanceKm }
        }

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
            val qNorm = query.trim().lowercase(Locale.ROOT)
            val latBucket = (lat * 100.0).roundToInt()
            val lonBucket = (lon * 100.0).roundToInt()
            return "$qNorm|$latBucket|$lonBucket"
        }

        private fun roundKm(km: Double): Double = (km * 10.0).roundToInt() / 10.0

        private suspend fun defaultHttpGet(url: String): String = withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (conn.responseCode !in 200..299) {
                    error("Nominatim respondeu ${conn.responseCode}")
                }
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
    }
}

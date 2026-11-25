package com.autodeck.headunit.navigation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class RoutePoint(val lat: Double, val lon: Double)

enum class ManeuverIcon { STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, UTURN, ROUNDABOUT, ARRIVE }

data class RouteStep(
    val instructionPt: String,
    val streetName: String,
    val icon: ManeuverIcon,
    /** Distância acumulada (m) do início da rota até esta manobra. */
    val startDistanceM: Double
)

data class InAppRouteState(
    val destinationLabel: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val polyline: List<RoutePoint> = emptyList(),
    val steps: List<RouteStep> = emptyList(),
    val totalDistanceM: Double = 0.0,
    val totalDurationS: Double = 0.0,
    val remainingDistanceM: Double = 0.0,
    val remainingDurationS: Double = 0.0,
    val nextStep: RouteStep? = null,
    val distanceToNextM: Double = 0.0,
    val arrived: Boolean = false
) {
    val isActive: Boolean get() = polyline.size >= 2 && !arrived
}

/**
 * Navegação curva-a-curva dentro do HondaDeck, sem Google Maps:
 * - Busca de endereço: Nominatim (OpenStreetMap)
 * - Rota e manobras: OSRM (router.project-osrm.org)
 * O progresso é calculado localmente a cada atualização de GPS.
 */
class InAppRouteNavigator(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(InAppRouteState())
    val state: StateFlow<InAppRouteState> = _state.asStateFlow()

    private var cumulative: DoubleArray = DoubleArray(0)
    private var destination: RoutePoint? = null
    private var routeJob: Job? = null
    private var lastRerouteAtMs = 0L

    fun startNavigation(query: String, from: RoutePoint) {
        val q = query.trim()
        if (q.isEmpty()) return
        routeJob?.cancel()
        _state.value = InAppRouteState(destinationLabel = q, isLoading = true)
        routeJob = scope.launch {
            val result = runCatching {
                val dest = geocode(q, from) ?: error("Endereço não encontrado")
                destination = dest.first
                _state.update { it.copy(destinationLabel = dest.second) }
                fetchRoute(from, dest.first)
            }
            result.onSuccess { applyRoute(it, from) }
                .onFailure { err ->
                    _state.update {
                        it.copy(isLoading = false, error = err.message ?: "Falha ao calcular rota")
                    }
                }
        }
    }

    fun stopNavigation() {
        routeJob?.cancel()
        destination = null
        cumulative = DoubleArray(0)
        _state.value = InAppRouteState()
    }

    /** Atualiza progresso; recalcula a rota se o carro sair dela (> 70 m) com intervalo mínimo de 20 s. */
    fun onLocation(pos: RoutePoint) {
        val s = _state.value
        if (!s.isActive || cumulative.isEmpty()) return
        val (idx, offRouteM) = nearestVertex(s.polyline, pos)
        val traveled = cumulative[idx]
        val remaining = (s.totalDistanceM - traveled).coerceAtLeast(0.0)
        val next = s.steps.firstOrNull { it.startDistanceM > traveled + 8.0 }
        val arrived = remaining < 25.0
        _state.update {
            it.copy(
                remainingDistanceM = remaining,
                remainingDurationS = if (it.totalDistanceM > 0) it.totalDurationS * remaining / it.totalDistanceM else 0.0,
                nextStep = next,
                distanceToNextM = ((next?.startDistanceM ?: it.totalDistanceM) - traveled).coerceAtLeast(0.0),
                arrived = arrived
            )
        }
        val now = System.currentTimeMillis()
        val dest = destination
        if (offRouteM > 70.0 && dest != null && now - lastRerouteAtMs > 20_000L) {
            lastRerouteAtMs = now
            routeJob?.cancel()
            routeJob = scope.launch {
                runCatching { fetchRoute(pos, dest) }.onSuccess { applyRoute(it, pos) }
            }
        }
    }

    private fun applyRoute(route: ParsedRoute, from: RoutePoint) {
        cumulative = cumulativeDistances(route.polyline)
        _state.update {
            it.copy(
                isLoading = false,
                error = null,
                polyline = route.polyline,
                steps = route.steps,
                totalDistanceM = route.distanceM,
                totalDurationS = route.durationS,
                remainingDistanceM = route.distanceM,
                remainingDurationS = route.durationS,
                arrived = false
            )
        }
        onLocation(from)
    }

    // ---------- Rede ----------

    private suspend fun geocode(query: String, near: RoutePoint): Pair<RoutePoint, String>? = withContext(Dispatchers.IO) {
        val box = "${near.lon - 0.35},${near.lat + 0.35},${near.lon + 0.35},${near.lat - 0.35}"
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&countrycodes=br" +
            "&accept-language=pt-BR&viewbox=$box&bounded=0&q=" + URLEncoder.encode(query, "UTF-8")
        val arr = JSONArray(httpGet(url))
        if (arr.length() == 0) return@withContext null
        val first = arr.getJSONObject(0)
        val label = first.optString("name").ifBlank { first.optString("display_name").substringBefore(",") }
        RoutePoint(first.getString("lat").toDouble(), first.getString("lon").toDouble()) to label.ifBlank { query }
    }

    private suspend fun fetchRoute(from: RoutePoint, to: RoutePoint): ParsedRoute = withContext(Dispatchers.IO) {
        val url = "https://router.project-osrm.org/route/v1/driving/" +
            "${from.lon},${from.lat};${to.lon},${to.lat}?overview=full&geometries=geojson&steps=true"
        parseOsrm(httpGet(url))
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            setRequestProperty("User-Agent", "HondaDeck/3.0 (Android)")
        }
        return try {
            if (conn.responseCode !in 200..299) error("Servidor de rotas indisponível (${conn.responseCode})")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    data class ParsedRoute(
        val polyline: List<RoutePoint>,
        val steps: List<RouteStep>,
        val distanceM: Double,
        val durationS: Double
    )

    companion object {
        fun parseOsrm(json: String): ParsedRoute {
            val root = JSONObject(json)
            if (root.optString("code") != "Ok") error("Rota não encontrada")
            val route = root.getJSONArray("routes").getJSONObject(0)
            val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
            val polyline = (0 until coords.length()).map {
                val c = coords.getJSONArray(it)
                RoutePoint(c.getDouble(1), c.getDouble(0))
            }
            val steps = mutableListOf<RouteStep>()
            var acc = 0.0
            val legs = route.getJSONArray("legs")
            for (l in 0 until legs.length()) {
                val st = legs.getJSONObject(l).getJSONArray("steps")
                for (i in 0 until st.length()) {
                    val step = st.getJSONObject(i)
                    val man = step.getJSONObject("maneuver")
                    val type = man.optString("type")
                    val modifier = man.optString("modifier")
                    val name = step.optString("name")
                    if (type != "depart") {
                        steps += RouteStep(
                            instructionPt = instructionPt(type, modifier, man.optInt("exit", 0)),
                            streetName = name,
                            icon = iconFor(type, modifier),
                            startDistanceM = acc
                        )
                    }
                    acc += step.optDouble("distance", 0.0)
                }
            }
            return ParsedRoute(polyline, steps, route.optDouble("distance"), route.optDouble("duration"))
        }

        fun iconFor(type: String, modifier: String): ManeuverIcon = when {
            type == "arrive" -> ManeuverIcon.ARRIVE
            type == "roundabout" || type == "rotary" || type == "roundabout turn" -> ManeuverIcon.ROUNDABOUT
            modifier == "uturn" -> ManeuverIcon.UTURN
            modifier == "sharp left" -> ManeuverIcon.SHARP_LEFT
            modifier == "left" -> ManeuverIcon.LEFT
            modifier == "slight left" -> ManeuverIcon.SLIGHT_LEFT
            modifier == "sharp right" -> ManeuverIcon.SHARP_RIGHT
            modifier == "right" -> ManeuverIcon.RIGHT
            modifier == "slight right" -> ManeuverIcon.SLIGHT_RIGHT
            else -> ManeuverIcon.STRAIGHT
        }

        fun instructionPt(type: String, modifier: String, exit: Int): String {
            val dir = when (modifier) {
                "left" -> "à esquerda"
                "right" -> "à direita"
                "slight left" -> "levemente à esquerda"
                "slight right" -> "levemente à direita"
                "sharp left" -> "acentuadamente à esquerda"
                "sharp right" -> "acentuadamente à direita"
                "uturn" -> "o retorno"
                else -> "em frente"
            }
            return when (type) {
                "arrive" -> "Você chegou ao destino"
                "roundabout", "rotary" -> if (exit > 0) "Na rotatória, pegue a ${exit}ª saída" else "Entre na rotatória"
                "merge" -> "Entre na via $dir"
                "on ramp" -> "Pegue a rampa $dir"
                "off ramp" -> "Pegue a saída $dir"
                "fork" -> "Na bifurcação, mantenha-se $dir"
                "end of road" -> "No fim da via, vire $dir"
                "continue", "new name" -> if (modifier == "straight" || modifier.isBlank()) "Siga em frente" else "Continue $dir"
                else -> when (modifier) {
                    "uturn" -> "Faça o retorno"
                    "straight", "" -> "Siga em frente"
                    else -> "Vire $dir"
                }
            }
        }

        fun haversineM(a: RoutePoint, b: RoutePoint): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLon = Math.toRadians(b.lon - a.lon)
            val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * r * atan2(sqrt(h), sqrt(1 - h))
        }

        fun cumulativeDistances(poly: List<RoutePoint>): DoubleArray {
            val out = DoubleArray(poly.size)
            for (i in 1 until poly.size) out[i] = out[i - 1] + haversineM(poly[i - 1], poly[i])
            return out
        }

        /** Índice do vértice mais próximo e a distância até ele (m). */
        fun nearestVertex(poly: List<RoutePoint>, pos: RoutePoint): Pair<Int, Double> {
            var best = 0
            var bestD = Double.MAX_VALUE
            poly.forEachIndexed { i, p ->
                val d = haversineM(p, pos)
                if (d < bestD) {
                    bestD = d
                    best = i
                }
            }
            return best to bestD
        }

        fun formatDistance(m: Double): String = when {
            m >= 10_000 -> "${(m / 1000).toInt()} km"
            m >= 1_000 -> String.format(java.util.Locale.forLanguageTag("pt-BR"), "%.1f km", m / 1000)
            m >= 100 -> "${(m / 50).toInt() * 50} m"
            else -> "${(m / 10).toInt() * 10} m"
        }
    }
}

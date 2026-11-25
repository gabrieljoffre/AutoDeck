package com.autodeck.headunit.weather

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Categoria visual do tempo; a UI escolhe o ícone a partir dela. */
enum class WeatherKind(val labelPt: String) {
    CLEAR("Céu limpo"),
    PARTLY_CLOUDY("Parcialmente nublado"),
    CLOUDY("Nublado"),
    FOG("Neblina"),
    DRIZZLE("Garoa"),
    RAIN("Chuva"),
    SHOWERS("Pancadas de chuva"),
    THUNDER("Tempestade"),
    SNOW("Neve"),
    UNKNOWN("--");

    companion object {
        /** Códigos WMO usados pelo Open-Meteo. */
        fun fromWmo(code: Int): WeatherKind = when (code) {
            0 -> CLEAR
            1, 2 -> PARTLY_CLOUDY
            3 -> CLOUDY
            45, 48 -> FOG
            in 51..57 -> DRIZZLE
            in 61..67 -> RAIN
            in 80..82 -> SHOWERS
            in 71..77, 85, 86 -> SNOW
            in 95..99 -> THUNDER
            else -> UNKNOWN
        }
    }
}

data class WeatherNow(
    val tempC: Int,
    val feelsLikeC: Int,
    val kind: WeatherKind,
    val isDay: Boolean,
    val humidityPct: Int = 0,
    val windKmh: Int = 0,
    val windDirDeg: Int = 0,
    val windDirLabel: String = "",
    val rainChancePct: Int = 0,
    val precipMm: Double = 0.0,
    val uvIndex: Double = 0.0,
    val sunrise: String = "",
    val sunset: String = ""
)

data class HourForecast(
    val hourLabel: String,
    val tempC: Int,
    val kind: WeatherKind,
    val rainChancePct: Int,
    val precipMm: Double = 0.0
)

data class DayForecast(
    val dayLabel: String,
    val minC: Int,
    val maxC: Int,
    val kind: WeatherKind,
    val rainChancePct: Int,
    val dateIso: String = "",
    val precipMm: Double = 0.0,
    val uvMax: Double = 0.0,
    val sunrise: String = "",
    val sunset: String = ""
)

data class WeatherState(
    val now: WeatherNow? = null,
    val todayMinC: Int? = null,
    val todayMaxC: Int? = null,
    val nextHours: List<HourForecast> = emptyList(),
    val nextDays: List<DayForecast> = emptyList(),
    val updatedAtMs: Long = 0L,
    val statusText: String = "Aguardando GPS para o clima"
) {
    /** Frase curta para o Copilot ler em voz alta ou mandar ao Gemini. */
    fun spokenSummary(): String? {
        val n = now ?: return null
        val range = if (todayMinC != null && todayMaxC != null) ", mínima de $todayMinC e máxima de $todayMaxC graus" else ""
        val rain = nextHours.take(6).maxOfOrNull { it.rainChancePct }?.takeIf { it >= 30 }
            ?.let { ". Chance de chuva de até $it% nas próximas horas" } ?: ""
        return "${n.kind.labelPt}, ${n.tempC} graus agora (sensação de ${n.feelsLikeC})$range$rain."
    }
}

/**
 * Clima atual e previsão pelo Open-Meteo (gratuito, sem chave).
 * Só a posição arredondada (~1 km) sai do tablet.
 */
object WeatherService {

    private val ptBr: Locale = Locale.forLanguageTag("pt-BR")
    private val COMPASS_PT = arrayOf("N", "NE", "L", "SE", "S", "SO", "O", "NO")

    internal fun compassLabel(deg: Int): String {
        val norm = ((deg % 360) + 360) % 360
        val idx = ((norm + 22.5) / 45.0).toInt() % 8
        return COMPASS_PT[idx]
    }

    suspend fun fetch(latitude: Double, longitude: Double): WeatherState = withContext(Dispatchers.IO) {
        val lat = "%.2f".format(Locale.US, latitude)
        val lon = "%.2f".format(Locale.US, longitude)
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,wind_direction_10m,precipitation,uv_index,weather_code,is_day" +
            "&hourly=temperature_2m,weather_code,precipitation_probability,precipitation" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,uv_index_max,sunrise,sunset" +
            "&timezone=auto&forecast_days=7"

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
        }
        val raw = try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
        parse(JSONObject(raw), System.currentTimeMillis())
    }

    internal fun parse(json: JSONObject, nowMs: Long): WeatherState {
        val cur = json.getJSONObject("current")
        val currentTime = cur.optString("time") // ex.: 2026-11-24T14:15 (fuso local)

        val hourly = json.getJSONObject("hourly")
        val hTimes = hourly.getJSONArray("time")
        val hTemps = hourly.getJSONArray("temperature_2m")
        val hCodes = hourly.getJSONArray("weather_code")
        val hRain = hourly.optJSONArray("precipitation_probability")
        val hPrecip = hourly.optJSONArray("precipitation")
        val hours = mutableListOf<HourForecast>()
        var currentHourRainChance: Int? = null
        val currentHourPrefix = currentTime.take(13) // "2026-11-24T14"
        for (i in 0 until hTimes.length()) {
            val t = hTimes.getString(i)
            val chance = hRain?.optInt(i, 0) ?: 0
            if (currentHourRainChance == null && currentHourPrefix.isNotEmpty() && t.startsWith(currentHourPrefix)) {
                currentHourRainChance = chance
            }
            if (t <= currentTime) continue // strings ISO no mesmo fuso comparam em ordem
            hours += HourForecast(
                hourLabel = t.substringAfter('T').take(5),
                tempC = hTemps.getDouble(i).roundToInt(),
                kind = WeatherKind.fromWmo(hCodes.optInt(i, -1)),
                rainChancePct = chance,
                precipMm = hPrecip?.optDouble(i, 0.0)?.takeIf { it.isFinite() } ?: 0.0
            )
            if (hours.size == 24) break
        }

        val daily = json.getJSONObject("daily")
        val dTimes = daily.getJSONArray("time")
        val dMax = daily.getJSONArray("temperature_2m_max")
        val dMin = daily.getJSONArray("temperature_2m_min")
        val dCodes = daily.getJSONArray("weather_code")
        val dRain = daily.optJSONArray("precipitation_probability_max")
        val dPrecipSum = daily.optJSONArray("precipitation_sum")
        val dUvMax = daily.optJSONArray("uv_index_max")
        val dSunrise = daily.optJSONArray("sunrise")
        val dSunset = daily.optJSONArray("sunset")
        val days = (0 until dTimes.length()).map { i ->
            val iso = dTimes.getString(i)
            val date = LocalDate.parse(iso)
            val label = if (i == 0) "Hoje" else date.dayOfWeek.getDisplayName(TextStyle.SHORT, ptBr)
                .replace(".", "").replaceFirstChar { it.titlecase(ptBr) }
            DayForecast(
                dayLabel = label,
                minC = dMin.getDouble(i).roundToInt(),
                maxC = dMax.getDouble(i).roundToInt(),
                kind = WeatherKind.fromWmo(dCodes.optInt(i, -1)),
                rainChancePct = dRain?.optInt(i, 0) ?: 0,
                dateIso = iso,
                precipMm = dPrecipSum?.optDouble(i, 0.0)?.takeIf { it.isFinite() } ?: 0.0,
                uvMax = dUvMax?.optDouble(i, 0.0)?.takeIf { it.isFinite() } ?: 0.0,
                sunrise = dSunrise?.optString(i, "")?.substringAfter('T', "")?.take(5).orEmpty(),
                sunset = dSunset?.optString(i, "")?.substringAfter('T', "")?.take(5).orEmpty()
            )
        }

        val windDir = cur.optDouble("wind_direction_10m", 0.0).takeIf { it.isFinite() }?.roundToInt() ?: 0
        val today = days.firstOrNull()
        val now = WeatherNow(
            tempC = cur.getDouble("temperature_2m").roundToInt(),
            feelsLikeC = cur.optDouble("apparent_temperature", cur.getDouble("temperature_2m")).roundToInt(),
            kind = WeatherKind.fromWmo(cur.optInt("weather_code", -1)),
            isDay = cur.optInt("is_day", 1) == 1,
            humidityPct = cur.optDouble("relative_humidity_2m", 0.0).takeIf { it.isFinite() }?.roundToInt() ?: 0,
            windKmh = cur.optDouble("wind_speed_10m", 0.0).takeIf { it.isFinite() }?.roundToInt() ?: 0,
            windDirDeg = ((windDir % 360) + 360) % 360,
            windDirLabel = compassLabel(windDir),
            rainChancePct = cur.optInt("precipitation_probability", currentHourRainChance ?: hours.firstOrNull()?.rainChancePct ?: 0),
            precipMm = cur.optDouble("precipitation", 0.0).takeIf { it.isFinite() } ?: 0.0,
            uvIndex = cur.optDouble("uv_index", today?.uvMax ?: 0.0).takeIf { it.isFinite() } ?: 0.0,
            sunrise = today?.sunrise.orEmpty(),
            sunset = today?.sunset.orEmpty()
        )

        return WeatherState(
            now = now,
            todayMinC = today?.minC,
            todayMaxC = today?.maxC,
            nextHours = hours,
            nextDays = days,
            updatedAtMs = nowMs,
            statusText = "Atualizado"
        )
    }
}

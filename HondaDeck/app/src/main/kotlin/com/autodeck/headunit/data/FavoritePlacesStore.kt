package com.autodeck.headunit.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

data class FavoritePlace(
    val id: String,
    val name: String,
    val address: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val voice: String = "",
    val icon: String = "star"
)

data class RecentPlace(
    val name: String,
    val address: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0
)

/**
 * Armazena locais favoritos e os últimos 15 destinos recentes em JSON nas preferências
 * privadas (`hondadeck_places`).
 */
class FavoritePlacesStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadFavorites(): List<FavoritePlace> = decodeFavorites(prefs.getString(KEY_FAVORITES, null))

    fun saveFavorites(items: List<FavoritePlace>) {
        prefs.edit { putString(KEY_FAVORITES, encodeFavorites(items.take(MAX_FAVORITES))) }
    }

    fun upsertFavorite(item: FavoritePlace): List<FavoritePlace> {
        val current = loadFavorites().toMutableList()
        val cleanId = item.id.trim().ifBlank { generateId(item.name) }
        val normalized = item.copy(
            id = cleanId,
            name = item.name.trim(),
            address = item.address.trim(),
            voice = item.voice.trim().ifBlank { "ir para ${item.name.trim().lowercase(Locale.forLanguageTag("pt-BR"))}" },
            icon = normalizeIcon(item.icon)
        )
        val idx = current.indexOfFirst { it.id == cleanId }
        if (idx >= 0) {
            current[idx] = normalized
        } else {
            if (current.size >= MAX_FAVORITES) {
                current.removeAt(current.lastIndex)
            }
            current.add(normalized)
        }
        saveFavorites(current)
        return current
    }

    fun deleteFavorite(id: String): List<FavoritePlace> {
        val next = loadFavorites().filterNot { it.id == id }
        saveFavorites(next)
        return next
    }

    fun loadRecentPlaces(): List<RecentPlace> = decodeRecentPlaces(prefs.getString(KEY_RECENTS, null))

    fun saveRecentPlaces(items: List<RecentPlace>) {
        prefs.edit { putString(KEY_RECENTS, encodeRecentPlaces(items.take(MAX_RECENTS))) }
    }

    fun addRecentPlace(place: RecentPlace): List<RecentPlace> {
        val cleanName = place.name.trim()
        if (cleanName.isBlank()) return loadRecentPlaces()
        val normalized = place.copy(name = cleanName, address = place.address.trim())
        val current = loadRecentPlaces()
            .filterNot { it.name.equals(cleanName, ignoreCase = true) }
        val next = (listOf(normalized) + current).take(MAX_RECENTS)
        saveRecentPlaces(next)
        return next
    }

    companion object {
        const val PREFS_NAME = "hondadeck_places"
        private const val KEY_FAVORITES = "favorites_json"
        private const val KEY_RECENTS = "recent_places_json"
        const val MAX_FAVORITES = 12
        const val MAX_RECENTS = 15

        private val VALID_ICONS = setOf("home", "work", "fuel", "star")

        fun normalizeIcon(icon: String?): String {
            val v = icon?.trim()?.lowercase(Locale.ROOT).orEmpty()
            return if (v in VALID_ICONS) v else "star"
        }

        fun generateId(name: String): String {
            val slug = name.trim()
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .take(18)
            val suffix = UUID.randomUUID().toString().substring(0, 6)
            return if (slug.isNotBlank()) "$slug-$suffix" else "fav-$suffix"
        }

        fun encodeFavorites(items: List<FavoritePlace>): String {
            val arr = JSONArray()
            items.forEach { f ->
                arr.put(
                    JSONObject().apply {
                        put("id", f.id)
                        put("name", f.name)
                        put("address", f.address)
                        put("lat", f.lat)
                        put("lon", f.lon)
                        put("voice", f.voice)
                        put("icon", normalizeIcon(f.icon))
                    }
                )
            }
            return arr.toString()
        }

        fun decodeFavorites(raw: String?): List<FavoritePlace> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val name = o.optString("name").trim()
                    if (name.isBlank()) return@mapNotNull null
                    val id = o.optString("id").trim().ifBlank { generateId(name) }
                    FavoritePlace(
                        id = id,
                        name = name,
                        address = o.optString("address").trim(),
                        lat = o.optDouble("lat", 0.0).takeIf { it.isFinite() } ?: 0.0,
                        lon = o.optDouble("lon", 0.0).takeIf { it.isFinite() } ?: 0.0,
                        voice = o.optString("voice").trim(),
                        icon = normalizeIcon(o.optString("icon", "star"))
                    )
                }
            }.getOrDefault(emptyList())
        }

        fun encodeRecentPlaces(items: List<RecentPlace>): String {
            val arr = JSONArray()
            items.take(MAX_RECENTS).forEach { r ->
                arr.put(
                    JSONObject().apply {
                        put("name", r.name)
                        put("address", r.address)
                        put("lat", r.lat)
                        put("lon", r.lon)
                    }
                )
            }
            return arr.toString()
        }

        fun decodeRecentPlaces(raw: String?): List<RecentPlace> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val name = o.optString("name").trim()
                    if (name.isBlank()) return@mapNotNull null
                    RecentPlace(
                        name = name,
                        address = o.optString("address").trim(),
                        lat = o.optDouble("lat", 0.0).takeIf { it.isFinite() } ?: 0.0,
                        lon = o.optDouble("lon", 0.0).takeIf { it.isFinite() } ?: 0.0
                    )
                }.take(MAX_RECENTS)
            }.getOrDefault(emptyList())
        }
    }
}

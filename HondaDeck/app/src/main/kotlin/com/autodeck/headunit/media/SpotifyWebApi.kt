package com.autodeck.headunit.media

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

enum class SpotifyItemKind(val labelPt: String) {
    TRACK("Música"),
    PLAYLIST("Playlist"),
    ALBUM("Álbum"),
    ARTIST("Artista")
}

data class SpotifyItem(
    val kind: SpotifyItemKind,
    val name: String,
    val subtitle: String,
    val uri: String,
    val imageUrl: String?
)

data class SpotifySearchResults(
    val tracks: List<SpotifyItem> = emptyList(),
    val playlists: List<SpotifyItem> = emptyList(),
    val albums: List<SpotifyItem> = emptyList(),
    val artists: List<SpotifyItem> = emptyList()
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && playlists.isEmpty() && albums.isEmpty() && artists.isEmpty()
}

class SpotifyTokenMissingException : Exception("Autorize o Spotify para buscar")

/**
 * Cliente mínimo da Web API do Spotify (busca e playlists do usuário).
 * O token vem da tela oficial de autorização e fica só na memória e nas preferências privadas do app.
 */
class SpotifyWebApi(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("hondadeck_spotify", Context.MODE_PRIVATE)
    private var accessToken: String? = prefs.getString(KEY_TOKEN, null)
    private var expiresAtMs: Long = prefs.getLong(KEY_EXPIRES, 0L)

    val hasValidToken: Boolean
        get() = !accessToken.isNullOrBlank() && System.currentTimeMillis() < expiresAtMs - 60_000L

    fun setToken(token: String, expiresInSec: Int) {
        accessToken = token
        expiresAtMs = System.currentTimeMillis() + expiresInSec.coerceAtLeast(60) * 1000L
        prefs.edit().putString(KEY_TOKEN, token).putLong(KEY_EXPIRES, expiresAtMs).apply()
    }

    fun clearToken() {
        accessToken = null
        expiresAtMs = 0L
        prefs.edit().remove(KEY_TOKEN).remove(KEY_EXPIRES).apply()
    }

    suspend fun search(query: String): SpotifySearchResults = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val json = JSONObject(get("https://api.spotify.com/v1/search?q=$q&type=track,playlist,album,artist&limit=8"))
        SpotifySearchResults(
            tracks = parseItems(json.optJSONObject("tracks"), SpotifyItemKind.TRACK),
            playlists = parseItems(json.optJSONObject("playlists"), SpotifyItemKind.PLAYLIST),
            albums = parseItems(json.optJSONObject("albums"), SpotifyItemKind.ALBUM),
            artists = parseItems(json.optJSONObject("artists"), SpotifyItemKind.ARTIST)
        )
    }

    /** Todas as playlists do usuário (até 200), na ordem da biblioteca. */
    suspend fun myPlaylists(): List<SpotifyItem> = withContext(Dispatchers.IO) {
        val out = mutableListOf<SpotifyItem>()
        var url: String? = "https://api.spotify.com/v1/me/playlists?limit=50"
        while (url != null && out.size < 200) {
            val json = JSONObject(get(url))
            out += parseItems(json, SpotifyItemKind.PLAYLIST)
            url = json.optString("next").takeIf { it.isNotBlank() && it != "null" }
        }
        out
    }

    /** Faixas tocadas recentemente pelo usuário, sem duplicar URI. */
    suspend fun recentlyPlayed(limit: Int = 20): List<SpotifyItem> = withContext(Dispatchers.IO) {
        val n = limit.coerceIn(1, 50)
        val json = JSONObject(get("https://api.spotify.com/v1/me/player/recently-played?limit=$n"))
        val arr = json.optJSONArray("items") ?: return@withContext emptyList()
        val seen = HashSet<String>()
        val out = mutableListOf<SpotifyItem>()
        for (i in 0 until arr.length()) {
            val trackObj = arr.optJSONObject(i)?.optJSONObject("track") ?: continue
            val item = parseItem(trackObj, SpotifyItemKind.TRACK) ?: continue
            if (seen.add(item.uri)) {
                out += item
            }
        }
        out
    }

    private fun get(url: String): String {
        val token = accessToken?.takeIf { hasValidToken } ?: throw SpotifyTokenMissingException()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            val code = conn.responseCode
            if (code == 401) {
                clearToken()
                throw SpotifyTokenMissingException()
            }
            if (code !in 200..299) {
                val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                Log.w(TAG, "Web API $code: ${body.take(200)}")
                error("Spotify respondeu $code")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "SpotifyWebApi"
        private const val KEY_TOKEN = "web_token"
        private const val KEY_EXPIRES = "web_token_expires"

        /** Escopos pedidos na autorização (App Remote + leitura/modificação de playlists/biblioteca e recentes). */
        val SCOPES = arrayOf(
            "app-remote-control",
            "user-read-playback-state",
            "user-modify-playback-state",
            "playlist-read-private",
            "playlist-read-collaborative",
            "user-library-read",
            "user-read-recently-played",
            "user-library-modify"
        )

        /** Playlists cujo nome começa com "Honda" (sem diferenciar maiúsculas/acentos de espaço). */
        fun filterHonda(items: List<SpotifyItem>): List<SpotifyItem> =
            items.filter { it.name.trimStart().startsWith("honda", ignoreCase = true) }

        fun parseItems(page: JSONObject?, kind: SpotifyItemKind): List<SpotifyItem> {
            val arr = page?.optJSONArray("items") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                parseItem(arr.optJSONObject(i), kind)
            }
        }

        private fun parseItem(o: JSONObject?, kind: SpotifyItemKind): SpotifyItem? {
            if (o == null) return null
            val uri = o.optString("uri").takeIf { it.startsWith("spotify:") } ?: return null
            val images = when (kind) {
                SpotifyItemKind.TRACK -> o.optJSONObject("album")?.optJSONArray("images")
                else -> o.optJSONArray("images")
            }
            // Pega a menor imagem com pelo menos ~150px (a lista vem da maior para a menor)
            var image: String? = null
            if (images != null) {
                for (k in 0 until images.length()) {
                    val img = images.optJSONObject(k) ?: continue
                    val w = img.optInt("width", 0)
                    if (image == null || w >= 150 || w == 0) image = img.optString("url")
                }
            }
            val artists = o.optJSONArray("artists")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") }.joinToString(", ")
            }.orEmpty()
            val subtitle = when (kind) {
                SpotifyItemKind.TRACK -> artists
                SpotifyItemKind.ALBUM -> artists
                SpotifyItemKind.PLAYLIST -> o.optJSONObject("owner")?.optString("display_name").orEmpty()
                SpotifyItemKind.ARTIST -> "Artista"
            }
            return SpotifyItem(kind, o.optString("name"), subtitle, uri, image)
        }
    }
}

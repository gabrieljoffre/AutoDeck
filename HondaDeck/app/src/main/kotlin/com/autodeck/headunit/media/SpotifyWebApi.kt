package com.autodeck.headunit.media

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
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

/** Dispositivo do Spotify Connect (tablet, celular, caixa de som...). */
data class SpotifyDevice(
    val id: String,
    val name: String,
    val type: String,
    val isActive: Boolean
)

/** Retrato do player da conta (Web API `/v1/me/player`), independente de qual aparelho está tocando. */
data class SpotifyPlayerSnapshot(
    val title: String,
    val artist: String,
    val album: String,
    val imageUrl: String?,
    val trackUri: String,
    val isPlaying: Boolean,
    val progressMs: Long,
    val durationMs: Long,
    val shuffle: Boolean,
    val repeatMode: String,
    val device: SpotifyDevice?
)

class SpotifyTokenMissingException : Exception("Autorize o Spotify para buscar")

/** Nenhum aparelho ativo no Spotify Connect (404 NO_ACTIVE_DEVICE). */
class SpotifyNoDeviceException : Exception("Nenhum aparelho Spotify ativo")

/** A conta não é Premium (403): a Web API não aceita comandos de reprodução. */
class SpotifyPremiumRequiredException : Exception("Controle do Spotify exige conta Premium")

/**
 * Cliente mínimo da Web API do Spotify (busca, playlists e controle do player via Spotify Connect).
 * O token vem da tela oficial de autorização e fica só na memória e nas preferências privadas do app.
 * Use [get] para compartilhar a mesma instância (e o mesmo token) entre runtime e controlador de mídia.
 */
class SpotifyWebApi(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("hondadeck_spotify", Context.MODE_PRIVATE)
    private var accessToken: String? = prefs.getString(KEY_TOKEN, null)
    private var expiresAtMs: Long = prefs.getLong(KEY_EXPIRES, 0L)

    val hasValidToken: Boolean
        get() = !accessToken.isNullOrBlank() && System.currentTimeMillis() < expiresAtMs - 60_000L

    /** Verdadeiro se o usuário já autorizou o app alguma vez (permite renovar o token sem insistir em quem nunca autorizou). */
    val wasAuthorizedBefore: Boolean
        get() = prefs.getBoolean(KEY_EVER_AUTHORIZED, false) || !accessToken.isNullOrBlank()

    /** Milissegundos até o token expirar (negativo se já expirou; Long.MIN_VALUE/2 sem token). */
    val msUntilExpiry: Long
        get() = if (accessToken.isNullOrBlank()) Long.MIN_VALUE / 2 else expiresAtMs - System.currentTimeMillis()

    fun setToken(token: String, expiresInSec: Int) {
        accessToken = token
        expiresAtMs = System.currentTimeMillis() + expiresInSec.coerceAtLeast(60) * 1000L
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putLong(KEY_EXPIRES, expiresAtMs)
            .putBoolean(KEY_EVER_AUTHORIZED, true)
            .apply()
    }

    fun clearToken() {
        accessToken = null
        expiresAtMs = 0L
        prefs.edit().remove(KEY_TOKEN).remove(KEY_EXPIRES).apply()
    }

    suspend fun search(query: String): SpotifySearchResults = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val json = JSONObject(request("GET", "https://api.spotify.com/v1/search?q=$q&type=track,playlist,album,artist&limit=8").body)
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
            val json = JSONObject(request("GET", url).body)
            out += parseItems(json, SpotifyItemKind.PLAYLIST)
            url = json.optString("next").takeIf { it.isNotBlank() && it != "null" }
        }
        out
    }

    /** Faixas tocadas recentemente pelo usuário, sem duplicar URI. */
    suspend fun recentlyPlayed(limit: Int = 20): List<SpotifyItem> = withContext(Dispatchers.IO) {
        val n = limit.coerceIn(1, 50)
        val json = JSONObject(request("GET", "https://api.spotify.com/v1/me/player/recently-played?limit=$n").body)
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

    // ------------------------------------------------------------------
    // Spotify Connect: estado e controle do player da conta (qualquer aparelho)
    // ------------------------------------------------------------------

    /** Estado do player da conta; null quando não há reprodução/aparelho ativo (HTTP 204). */
    suspend fun playerState(): SpotifyPlayerSnapshot? = withContext(Dispatchers.IO) {
        val res = request("GET", "https://api.spotify.com/v1/me/player")
        if (res.code == 204 || res.body.isBlank()) null else parsePlayerState(res.body)
    }

    suspend fun devices(): List<SpotifyDevice> = withContext(Dispatchers.IO) {
        parseDevices(request("GET", "https://api.spotify.com/v1/me/player/devices").body)
    }

    suspend fun resume(deviceId: String? = null) = playerCommand("PUT", "play", deviceId, null)

    suspend fun pause(deviceId: String? = null) = playerCommand("PUT", "pause", deviceId, null)

    suspend fun next(deviceId: String? = null) = playerCommand("POST", "next", deviceId, null)

    suspend fun previous(deviceId: String? = null) = playerCommand("POST", "previous", deviceId, null)

    suspend fun seek(positionMs: Long, deviceId: String? = null) =
        playerCommand("PUT", "seek", deviceId, null, "position_ms=${positionMs.coerceAtLeast(0L)}")

    suspend fun setShuffle(on: Boolean, deviceId: String? = null) =
        playerCommand("PUT", "shuffle", deviceId, null, "state=$on")

    /** [mode]: "off", "track" (repetir faixa) ou "context" (repetir playlist/álbum). */
    suspend fun setRepeat(mode: String, deviceId: String? = null) =
        playerCommand("PUT", "repeat", deviceId, null, "state=$mode")

    /** Toca uma faixa (`spotify:track:`) ou um contexto (playlist/álbum/artista). */
    suspend fun play(uri: String, deviceId: String? = null) =
        playerCommand("PUT", "play", deviceId, playBody(uri))

    /** Transfere a reprodução para o aparelho [deviceId] (Spotify Connect). */
    suspend fun transferTo(deviceId: String, startPlaying: Boolean) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("device_ids", JSONArray().put(deviceId)).put("play", startPlaying).toString()
        request("PUT", "https://api.spotify.com/v1/me/player", body)
        Unit
    }

    private suspend fun playerCommand(
        method: String,
        path: String,
        deviceId: String?,
        body: String?,
        extraQuery: String? = null
    ) = withContext(Dispatchers.IO) {
        val params = listOfNotNull(
            extraQuery,
            deviceId?.takeIf { it.isNotBlank() }?.let { "device_id=${URLEncoder.encode(it, "UTF-8")}" }
        ).joinToString("&")
        val url = "https://api.spotify.com/v1/me/player/$path" + if (params.isNotEmpty()) "?$params" else ""
        request(method, url, body)
        Unit
    }

    private data class HttpResult(val code: Int, val body: String)

    private fun request(method: String, url: String, body: String? = null): HttpResult {
        val token = accessToken?.takeIf { hasValidToken } ?: throw SpotifyTokenMissingException()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            requestMethod = method
            setRequestProperty("Authorization", "Bearer $token")
            if (method != "GET") {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (method != "GET") {
                conn.outputStream.use { out -> if (body != null) out.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code == 401) {
                clearToken()
                throw SpotifyTokenMissingException()
            }
            if (code !in 200..299) {
                val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                Log.w(TAG, "Web API $method ${url.substringBefore('?')} -> $code: ${errBody.take(200)}")
                if (code == 404 && errBody.contains("NO_ACTIVE_DEVICE", ignoreCase = true)) throw SpotifyNoDeviceException()
                if (code == 403 && errBody.contains("PREMIUM", ignoreCase = true)) throw SpotifyPremiumRequiredException()
                error("Spotify respondeu $code")
            }
            if (code == 204) return HttpResult(code, "")
            return HttpResult(code, conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "SpotifyWebApi"
        private const val KEY_TOKEN = "web_token"
        private const val KEY_EXPIRES = "web_token_expires"
        private const val KEY_EVER_AUTHORIZED = "web_ever_authorized"

        @Volatile
        private var shared: SpotifyWebApi? = null

        /** Instância única do app (mesmo token em runtime, mídia e Spotify Connect). */
        fun get(context: Context): SpotifyWebApi =
            shared ?: synchronized(this) {
                shared ?: SpotifyWebApi(context.applicationContext).also { shared = it }
            }

        /** Escopos pedidos na autorização (App Remote + player Connect + playlists/biblioteca e recentes). */
        val SCOPES = arrayOf(
            "app-remote-control",
            "user-read-playback-state",
            "user-modify-playback-state",
            "user-read-currently-playing",
            "playlist-read-private",
            "playlist-read-collaborative",
            "user-library-read",
            "user-read-recently-played",
            "user-library-modify"
        )

        /** Playlists cujo nome começa com "Honda" (sem diferenciar maiúsculas/acentos de espaço). */
        fun filterHonda(items: List<SpotifyItem>): List<SpotifyItem> =
            items.filter { it.name.trimStart().startsWith("honda", ignoreCase = true) }

        /** Corpo do `PUT /me/player/play`: faixa vai em `uris`, demais tipos em `context_uri`. */
        fun playBody(uri: String): String =
            if (uri.startsWith("spotify:track:") || uri.startsWith("spotify:episode:")) {
                JSONObject().put("uris", JSONArray().put(uri)).toString()
            } else {
                JSONObject().put("context_uri", uri).toString()
            }

        fun parseDevices(json: String): List<SpotifyDevice> {
            val arr = runCatching { JSONObject(json).optJSONArray("devices") }.getOrNull() ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
                SpotifyDevice(
                    id = id,
                    name = o.optString("name"),
                    type = o.optString("type"),
                    isActive = o.optBoolean("is_active", false)
                )
            }
        }

        /** Converte a resposta de `GET /me/player`; null se não houver faixa (ex.: anúncio/podcast sem item). */
        fun parsePlayerState(json: String): SpotifyPlayerSnapshot? {
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
            val item = root.optJSONObject("item") ?: return null
            val uri = item.optString("uri")
            val images = item.optJSONObject("album")?.optJSONArray("images")
                ?: item.optJSONObject("show")?.optJSONArray("images")
            var image: String? = null
            if (images != null) {
                for (k in 0 until images.length()) {
                    val img = images.optJSONObject(k) ?: continue
                    val w = img.optInt("width", 0)
                    if (image == null || w in 200..700) image = img.optString("url")
                }
            }
            val artists = item.optJSONArray("artists")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") }.joinToString(", ")
            }.orEmpty().ifBlank { item.optJSONObject("show")?.optString("publisher").orEmpty() }
            val dev = root.optJSONObject("device")?.let { d ->
                SpotifyDevice(
                    id = d.optString("id").takeIf { it != "null" }.orEmpty(),
                    name = d.optString("name"),
                    type = d.optString("type"),
                    isActive = d.optBoolean("is_active", true)
                )
            }
            val repeat = when (root.optString("repeat_state")) {
                "track" -> "one"
                "context" -> "all"
                else -> "off"
            }
            return SpotifyPlayerSnapshot(
                title = item.optString("name"),
                artist = artists,
                album = item.optJSONObject("album")?.optString("name").orEmpty(),
                imageUrl = image,
                trackUri = uri,
                isPlaying = root.optBoolean("is_playing", false),
                progressMs = root.optLong("progress_ms", 0L),
                durationMs = item.optLong("duration_ms", 0L),
                shuffle = root.optBoolean("shuffle_state", false),
                repeatMode = repeat,
                device = dev
            )
        }

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

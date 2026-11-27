package com.autodeck.headunit.data

import android.content.Context
import android.content.SharedPreferences
import com.autodeck.headunit.BuildConfig

/**
 * Chaves de API que vêm do `local.properties` no build feito em casa (instalação pelo USB).
 *
 * O APK do GitHub é compilado SEM chaves (o repositório é público). Para o botão
 * "Atualizar app" não apagar Gemini/TomTom/Spotify, a primeira execução de um APK com chaves
 * guarda uma cópia nas preferências privadas do app; um APK sem chaves usa essa cópia.
 * Ordem: chave do APK atual -> cópia guardada -> vazio.
 */
object BakedKeys {
    private const val PREFS_NAME = "hondadeck_baked_keys"

    @Volatile
    private var prefs: SharedPreferences? = null

    private fun buildValues(): Map<String, String> = mapOf(
        GEMINI to BuildConfig.GEMINI_API_KEY,
        MAPS_JS to BuildConfig.MAPS_JS_API_KEY,
        TOMTOM to BuildConfig.TOMTOM_API_KEY,
        SPOTIFY_CLIENT_ID to BuildConfig.SPOTIFY_CLIENT_ID
    )

    /** Chamar cedo (Activity/Service). Idempotente. */
    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = p.edit()
            var changed = false
            for ((name, value) in buildValues()) {
                val v = value.trim()
                if (v.isNotEmpty() && p.getString(name, null) != v) {
                    editor.putString(name, v)
                    changed = true
                }
            }
            if (changed) editor.apply()
            prefs = p
        }
    }

    private fun resolve(name: String, buildValue: String): String =
        buildValue.trim().ifEmpty { prefs?.getString(name, "")?.trim().orEmpty() }

    val gemini: String get() = resolve(GEMINI, BuildConfig.GEMINI_API_KEY)
    val mapsJs: String get() = resolve(MAPS_JS, BuildConfig.MAPS_JS_API_KEY)
    val tomtom: String get() = resolve(TOMTOM, BuildConfig.TOMTOM_API_KEY)
    val spotifyClientId: String get() = resolve(SPOTIFY_CLIENT_ID, BuildConfig.SPOTIFY_CLIENT_ID)

    private const val GEMINI = "gemini"
    private const val MAPS_JS = "maps_js"
    private const val TOMTOM = "tomtom"
    private const val SPOTIFY_CLIENT_ID = "spotify_client_id"
}

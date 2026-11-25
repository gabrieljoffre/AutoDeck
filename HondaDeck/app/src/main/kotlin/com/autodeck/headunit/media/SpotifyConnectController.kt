package com.autodeck.headunit.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Controle do Spotify via Web API (`/v1/me/player`, Spotify Connect).
 *
 * Papéis:
 *  - Mostrar e controlar a reprodução quando ela está em OUTRO aparelho (ex.: música tocando no celular) ou
 *    quando o App Remote do tablet não está conectado.
 *  - Servir de fallback para play/pause/próxima/anterior/seek/shuffle/repeat/tocar URI, com transferência
 *    automática para o tablet quando não há aparelho ativo.
 *
 * Quando a música toca no próprio tablet e o App Remote está conectado, ele continua sendo a fonte principal.
 */
class SpotifyConnectController(
    private val web: SpotifyWebApi,
    private val scope: CoroutineScope,
    private val remoteConnected: () -> Boolean,
    private val onState: (MediaPlaybackState.() -> MediaPlaybackState) -> Unit,
    private val onMessage: (String) -> Unit,
    private val onNoDevice: () -> Unit,
    private val localNames: () -> Set<String> = { defaultLocalNames() }
) {
    private val _snapshot = MutableStateFlow<SpotifyPlayerSnapshot?>(null)
    val snapshot: StateFlow<SpotifyPlayerSnapshot?> = _snapshot.asStateFlow()

    /** Verdadeiro quando a última consulta ao player da conta funcionou (token válido + rede). */
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private var pollJob: Job? = null
    private var lastCoverUrl: String? = null
    private var lastCover: Bitmap? = null

    @Volatile
    private var lastPushedRemote = false

    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val playingFast = _snapshot.value?.isPlaying == true
                if (web.hasValidToken) {
                    pollOnce()
                } else {
                    _active.value = false
                }
                delay(if (playingFast) POLL_PLAYING_MS else POLL_IDLE_MS)
            }
        }
    }

    /** Consulta o player da conta agora (ex.: logo após autorizar ou depois de um comando). */
    fun refreshNow() {
        scope.launch(Dispatchers.IO) { if (web.hasValidToken) pollOnce() }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    /** O dispositivo ativo é o próprio tablet? (nome do aparelho ou tipo "Tablet"). */
    fun isLocalDevice(device: SpotifyDevice?): Boolean {
        if (device == null) return false
        if (device.type.equals("Tablet", ignoreCase = true)) return true
        val names = localNames()
        return names.any { it.isNotBlank() && it.equals(device.name, ignoreCase = true) }
    }

    /**
     * Esta classe deve assumir o estado e os controles agora?
     * Sim quando a música toca em outro aparelho, ou quando o App Remote local não está conectado
     * mas a conta está tocando em algum lugar.
     */
    fun takesOver(): Boolean {
        val snap = _snapshot.value ?: return false
        return !isLocalDevice(snap.device) || !remoteConnected()
    }

    /** Sem App Remote e com token válido: a Web API é o caminho de controle. */
    fun canControlViaWeb(): Boolean = web.hasValidToken

    suspend fun pollOnce() {
        try {
            val snap = web.playerState()
            _active.value = true
            _snapshot.value = snap
            publish(snap)
        } catch (_: SpotifyTokenMissingException) {
            _active.value = false
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao consultar o player do Spotify: ${e.message}")
        }
    }

    private fun publish(snap: SpotifyPlayerSnapshot?) {
        if (snap == null || !takesOver()) {
            if (lastPushedRemote) {
                lastPushedRemote = false
                onState { copy(remoteDevice = "") }
            }
            return
        }
        lastPushedRemote = true
        val local = isLocalDevice(snap.device)
        val deviceName = snap.device?.name.orEmpty()
        val cover = if (snap.imageUrl != null && snap.imageUrl == lastCoverUrl) lastCover else null
        onState {
            val coverChanged = (cover != null) != hasCover || (cover != null && snap.trackUri != trackUri)
            copy(
                title = snap.title,
                artist = snap.artist,
                album = snap.album,
                contextLabel = if (deviceName.isNotBlank()) "Tocando em $deviceName" else "",
                coverBitmap = cover ?: coverBitmap.takeIf { snap.trackUri == trackUri },
                hasCover = cover != null || (hasCover && snap.trackUri == trackUri),
                coverVersion = if (coverChanged) coverVersion + 1 else coverVersion,
                isPlaying = snap.isPlaying,
                positionMs = snap.progressMs,
                durationMs = snap.durationMs,
                shuffle = snap.shuffle,
                repeatMode = snap.repeatMode,
                trackUri = snap.trackUri,
                activePackage = "com.spotify.music",
                appLabel = "Spotify",
                hasActiveTrack = true,
                canSeek = true,
                remoteDevice = if (local) "" else deviceName.ifBlank { "outro aparelho" },
                lastUpdatedAtElapsedMs = SystemClock.elapsedRealtime()
            )
        }
        val url = snap.imageUrl
        if (url != null && url != lastCoverUrl) {
            lastCoverUrl = url
            scope.launch(Dispatchers.IO) {
                val bmp = fetchBitmap(url)
                if (bmp != null && lastCoverUrl == url) {
                    lastCover = bmp
                    onState { copy(coverBitmap = bmp, hasCover = true, coverVersion = coverVersion + 1) }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Comandos
    // ------------------------------------------------------------------

    fun toggle(currentlyPlaying: Boolean) {
        if (currentlyPlaying) pause() else resume()
    }

    fun resume() = exec("play") { dev -> web.resume(dev) }

    fun pause() = exec("pause", optimistic = { copy(isPlaying = false) }) { dev -> web.pause(dev) }

    fun next() = exec("next") { dev -> web.next(dev) }

    fun previous() = exec("previous") { dev -> web.previous(dev) }

    fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        exec("seek", optimistic = { copy(positionMs = target) }) { dev -> web.seek(target, dev) }
    }

    fun toggleShuffle() {
        val next = !(_snapshot.value?.shuffle ?: false)
        exec("shuffle", optimistic = { copy(shuffle = next) }) { dev -> web.setShuffle(next, dev) }
    }

    fun toggleRepeat() {
        val next = when (_snapshot.value?.repeatMode) {
            "off", null -> "context"
            "all" -> "track"
            else -> "off"
        }
        val ui = when (next) {
            "track" -> "one"
            "context" -> "all"
            else -> "off"
        }
        exec("repeat", optimistic = { copy(repeatMode = ui) }) { dev -> web.setRepeat(next, dev) }
    }

    /** Toca [uri] preferindo o próprio tablet como destino (transfere se preciso). */
    fun playUri(uri: String) = exec("play-uri", preferLocalDevice = true) { dev -> web.play(uri, dev) }

    /** Traz a reprodução que está em outro aparelho para o tablet e continua tocando. */
    fun transferHere() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val target = pickDevice(preferLocal = true)
                if (target == null) {
                    onNoDevice()
                    return@runCatching
                }
                web.transferTo(target.id, startPlaying = true)
                onMessage("Tocando no tablet")
            }.onFailure { handleFailure(it) }
            delay(700L)
            pollOnce()
        }
    }

    private fun exec(
        label: String,
        optimistic: (MediaPlaybackState.() -> MediaPlaybackState)? = null,
        preferLocalDevice: Boolean = false,
        block: suspend (deviceId: String?) -> Unit
    ) {
        if (optimistic != null) onState(optimistic)
        scope.launch(Dispatchers.IO) {
            val firstDevice = if (preferLocalDevice) {
                runCatching { pickDevice(preferLocal = true) }.getOrNull()?.id
            } else {
                null
            }
            val result = runCatching { block(firstDevice) }
            val failure = result.exceptionOrNull()
            if (failure is SpotifyNoDeviceException) {
                // Nenhum aparelho ativo: acorda o tablet (ou o primeiro disponível) e repete o comando
                val retried = runCatching {
                    val target = pickDevice(preferLocal = true)
                    if (target == null) {
                        onNoDevice()
                        return@runCatching
                    }
                    web.transferTo(target.id, startPlaying = label != "pause")
                    delay(500L)
                    block(target.id)
                }
                retried.exceptionOrNull()?.let { handleFailure(it) }
            } else if (failure != null) {
                handleFailure(failure)
            }
            delay(450L)
            pollOnce()
        }
    }

    private suspend fun pickDevice(preferLocal: Boolean): SpotifyDevice? {
        val list = web.devices()
        if (list.isEmpty()) return null
        if (preferLocal) list.firstOrNull { isLocalDevice(it) }?.let { return it }
        return list.firstOrNull { it.isActive } ?: list.first()
    }

    private fun handleFailure(err: Throwable) {
        Log.w(TAG, "Comando Spotify Connect falhou: ${err.javaClass.simpleName}: ${err.message}")
        when (err) {
            is SpotifyTokenMissingException -> onMessage("Autorize o Spotify para controlar a música")
            is SpotifyPremiumRequiredException -> onMessage("O controle remoto do Spotify exige conta Premium")
            is SpotifyNoDeviceException -> onNoDevice()
            else -> onMessage("Não foi possível controlar o Spotify agora")
        }
    }

    private suspend fun fetchBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6_000
                readTimeout = 8_000
            }
            try {
                conn.inputStream.use { BitmapFactory.decodeStream(it) }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    companion object {
        private const val TAG = "SpotifyConnect"
        private const val POLL_PLAYING_MS = 4_000L
        private const val POLL_IDLE_MS = 9_000L

        fun defaultLocalNames(): Set<String> = setOfNotNull(
            Build.MODEL,
            Build.DEVICE,
            "${Build.MANUFACTURER} ${Build.MODEL}",
            "HondaDeck"
        ).map { it.trim() }.filter { it.isNotBlank() }.toSet()
    }
}

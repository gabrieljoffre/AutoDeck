package com.autodeck.headunit.media

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.autodeck.headunit.BuildConfig
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import com.spotify.protocol.types.Image
import com.spotify.protocol.types.PlayerState
import com.spotify.protocol.types.Repeat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SpotifyLinkStatus(val labelPt: String) {
    NOT_CONFIGURED("Spotify SDK: Client ID não configurado"),
    NOT_INSTALLED("Spotify não instalado"),
    CONNECTING("Conectando ao Spotify..."),
    CONNECTED("Spotify conectado"),
    NEEDS_AUTH("Toque para autorizar o Spotify"),
    FAILED("Falha ao conectar ao Spotify")
}

/**
 * Ponte com o SDK oficial Spotify App Remote: recebe faixa, capa, tempo e estado,
 * e controla play/pause/próxima/anterior/seek sem precisar de acesso a notificações.
 *
 * O Client ID vem de local.properties (spotify.clientId) via BuildConfig, nunca do código-fonte.
 */
class SpotifyRemoteBridge(
    private val onState: (MediaPlaybackState.() -> MediaPlaybackState) -> Unit
) {
    private var remote: SpotifyAppRemote? = null
    private var lastImageUri: String? = null
    private var lastCover: Bitmap? = null
    private var lastTrackUri: String? = null

    private val _status = MutableStateFlow(
        if (isConfigured) SpotifyLinkStatus.CONNECTING else SpotifyLinkStatus.NOT_CONFIGURED
    )
    val status: StateFlow<SpotifyLinkStatus> = _status.asStateFlow()

    val isConnected: Boolean get() = remote?.isConnected == true

    /** Abre a tela oficial de autorização do Spotify (registrada pela MainActivity). */
    var authRequester: (() -> Unit)? = null
    private var autoAuthAsked = false

    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var retryAttempt = 0
    private var retryScheduled = false
    private var connectStartedAtMs = 0L
    private var watchdogRunning = false

    /** Última causa de falha (para diagnóstico e texto na interface). */
    @Volatile
    var lastFailure: String = ""
        private set

    private val watchdog = object : Runnable {
        override fun run() {
            val ctx = appContext
            if (ctx != null && isConfigured) {
                val st = _status.value
                val r = remote
                when {
                    // Conexão caiu sem aviso (Spotify fechado/atualizado/sem rede)
                    r != null && !r.isConnected -> handleDisconnected("conexão perdida")
                    // Tentativa travada em "Conectando…"
                    st == SpotifyLinkStatus.CONNECTING && r == null &&
                        SystemClock.elapsedRealtime() - connectStartedAtMs > CONNECT_TIMEOUT_MS -> {
                        Log.w(TAG, "Conexão com o Spotify travou; tentando de novo.")
                        scheduleRetry(immediate = true)
                    }
                    st == SpotifyLinkStatus.FAILED && !retryScheduled -> scheduleRetry()
                }
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private fun ensureWatchdog() {
        if (watchdogRunning) return
        watchdogRunning = true
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    private fun handleDisconnected(reason: String) {
        Log.w(TAG, "Spotify App Remote desconectado ($reason); reconectando.")
        val old = remote
        remote = null
        runCatching { old?.let { SpotifyAppRemote.disconnect(it) } }
        lastFailure = reason
        _status.value = SpotifyLinkStatus.FAILED
        scheduleRetry(immediate = true)
    }

    /** Agenda nova tentativa automática com recuo progressivo (2 s, 5 s, 10 s, 20 s, 30 s). */
    private fun scheduleRetry(immediate: Boolean = false) {
        if (retryScheduled) return
        val ctx = appContext ?: return
        retryScheduled = true
        val delayMs = if (immediate) 800L else RETRY_DELAYS_MS[retryAttempt.coerceAtMost(RETRY_DELAYS_MS.lastIndex)]
        retryAttempt++
        handler.postDelayed({
            retryScheduled = false
            if (!isConnected && _status.value != SpotifyLinkStatus.NEEDS_AUTH) {
                connectInternal(ctx, interactive = false)
            }
        }, delayMs)
    }

    /** Conecta usando um contexto de Activity (necessário para a tela de autorização do Spotify). */
    fun connect(context: Context) {
        appContext = context.applicationContext
        ensureWatchdog()
        if (!isConfigured) {
            _status.value = SpotifyLinkStatus.NOT_CONFIGURED
            return
        }
        if (isConnected) return
        if (_status.value == SpotifyLinkStatus.NEEDS_AUTH) {
            authRequester?.let { it(); return }
        }
        // Chamada explícita (abrir o app / tocar em conectar): zera o recuo e tenta já
        retryAttempt = 0
        connectInternal(context, interactive = true)
    }

    private fun connectInternal(context: Context, interactive: Boolean) {
        if (isConnected) return
        if (!SpotifyAppRemote.isSpotifyInstalled(context)) {
            _status.value = SpotifyLinkStatus.NOT_INSTALLED
            return
        }
        _status.value = SpotifyLinkStatus.CONNECTING
        connectStartedAtMs = SystemClock.elapsedRealtime()
        val params = ConnectionParams.Builder(com.autodeck.headunit.data.BakedKeys.spotifyClientId)
            .setRedirectUri(BuildConfig.SPOTIFY_REDIRECT_URI)
            // Tentativas automáticas em segundo plano não devem abrir a tela de autorização sozinhas
            .showAuthView(interactive)
            .build()
        runCatching {
            SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
                override fun onConnected(appRemote: SpotifyAppRemote) {
                    remote = appRemote
                    retryAttempt = 0
                    lastFailure = ""
                    _status.value = SpotifyLinkStatus.CONNECTED
                    appRemote.playerApi.subscribeToPlayerState()
                        .setEventCallback { ps -> apply(ps) }
                        .setErrorCallback { err ->
                            Log.w(TAG, "Erro no estado do player Spotify: ${err.javaClass.simpleName}: ${err.message}")
                            if (remote?.isConnected != true) handleDisconnected(err.javaClass.simpleName)
                        }
                    appRemote.playerApi.subscribeToPlayerContext().setEventCallback { ctx ->
                        val title = ctx.title.orEmpty()
                        if (title.isNotBlank()) {
                            onState { copy(contextLabel = title) }
                        }
                    }
                }

                override fun onFailure(throwable: Throwable) {
                    val kind = throwable.javaClass.simpleName
                    Log.w(TAG, "Spotify App Remote falhou: $kind: ${throwable.message}", throwable)
                    remote = null
                    lastFailure = "$kind: ${throwable.message.orEmpty()}"
                    when {
                        throwable is UserNotAuthorizedException -> {
                            _status.value = SpotifyLinkStatus.NEEDS_AUTH
                            // Primeira vez: abre a autorização sozinho; depois, só quando o usuário tocar no aviso
                            if (!autoAuthAsked) {
                                autoAuthAsked = true
                                authRequester?.invoke()
                            }
                        }
                        kind == "CouldNotFindSpotifyApp" -> _status.value = SpotifyLinkStatus.NOT_INSTALLED
                        else -> {
                            // Spotify fechado, deslogado, offline, sessão expirada...: tenta de novo sozinho
                            _status.value = SpotifyLinkStatus.FAILED
                            scheduleRetry()
                        }
                    }
                }
            })
        }.onFailure {
            Log.w(TAG, "Exceção ao conectar no Spotify: ${it.message}", it)
            lastFailure = it.javaClass.simpleName
            _status.value = SpotifyLinkStatus.FAILED
            scheduleRetry()
        }
    }

    /** Chamado após a tela de autorização devolver um token válido. */
    fun onAuthorized(context: Context) {
        retryAttempt = 0
        if (_status.value == SpotifyLinkStatus.NEEDS_AUTH) _status.value = SpotifyLinkStatus.CONNECTING
        connect(context)
    }

    fun onAuthFailed(reason: String) {
        Log.w(TAG, "Autorização do Spotify não concluída: $reason")
        if (!isConnected) _status.value = SpotifyLinkStatus.NEEDS_AUTH
    }

    fun disconnect() {
        remote?.let { SpotifyAppRemote.disconnect(it) }
        remote = null
        if (isConfigured) _status.value = SpotifyLinkStatus.CONNECTING
    }

    /** Tenta reconectar já (usado quando o app volta ao primeiro plano ou o Spotify abre). */
    fun reconnectNow() {
        val ctx = appContext ?: return
        if (!isConfigured || isConnected) return
        if (_status.value == SpotifyLinkStatus.NEEDS_AUTH) return
        retryAttempt = 0
        connectInternal(ctx, interactive = false)
    }

    fun play(uri: String) = remote?.playerApi?.play(uri)
    fun resume() = remote?.playerApi?.resume()
    fun pause() = remote?.playerApi?.pause()
    fun next() = remote?.playerApi?.skipNext()
    fun previous() = remote?.playerApi?.skipPrevious()
    fun seekTo(ms: Long) = remote?.playerApi?.seekTo(ms.coerceAtLeast(0L))
    fun toggleShuffle() = remote?.playerApi?.toggleShuffle()
    fun toggleRepeat() = remote?.playerApi?.toggleRepeat()

    fun toggleLike(trackUri: String, currentlyLiked: Boolean) {
        val uri = trackUri.takeIf { it.isNotBlank() } ?: lastTrackUri ?: return
        val userApi = remote?.userApi ?: return
        val call = if (currentlyLiked) userApi.removeFromLibrary(uri) else userApi.addToLibrary(uri)
        call.setResultCallback {
            onState { copy(liked = !currentlyLiked) }
        }
    }

    private fun apply(ps: PlayerState) {
        val track = ps.track ?: return
        val imageUri = track.imageUri?.raw
        val cover = if (imageUri == lastImageUri) lastCover else null
        val uri = track.uri.orEmpty()
        val repeatStr = when (ps.playbackOptions?.repeatMode) {
            Repeat.ONE -> "one"
            Repeat.ALL -> "all"
            else -> "off"
        }
        val shuffling = ps.playbackOptions?.isShuffling == true
        onState {
            val coverChanged = (cover != null) != hasCover || (cover != null && uri != trackUri)
            val nextCoverVer = if (coverChanged) coverVersion + 1 else coverVersion
            copy(
                title = track.name.orEmpty(),
                artist = track.artist?.name.orEmpty(),
                album = track.album?.name.orEmpty(),
                coverBitmap = cover,
                hasCover = cover != null,
                coverVersion = nextCoverVer,
                isPlaying = !ps.isPaused,
                positionMs = ps.playbackPosition,
                durationMs = track.duration,
                shuffle = shuffling,
                repeatMode = repeatStr,
                trackUri = uri,
                activePackage = "com.spotify.music",
                appLabel = "Spotify",
                hasActiveTrack = true,
                canSeek = true,
                lastUpdatedAtElapsedMs = SystemClock.elapsedRealtime()
            )
        }
        if (uri.isNotBlank() && uri != lastTrackUri) {
            lastTrackUri = uri
            remote?.userApi?.getLibraryState(uri)?.setResultCallback { ls ->
                onState { copy(liked = ls.isAdded) }
            }
        }
        if (imageUri != null && imageUri != lastImageUri) {
            lastImageUri = imageUri
            remote?.imagesApi?.getImage(track.imageUri, Image.Dimension.LARGE)?.setResultCallback { bmp ->
                lastCover = bmp
                onState { copy(coverBitmap = bmp, hasCover = bmp != null, coverVersion = coverVersion + 1) }
            }
        }
    }

    companion object {
        private const val TAG = "SpotifyRemoteBridge"
        private const val WATCHDOG_INTERVAL_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private val RETRY_DELAYS_MS = longArrayOf(2_000L, 5_000L, 10_000L, 20_000L, 30_000L)
        val isConfigured: Boolean get() = com.autodeck.headunit.data.BakedKeys.spotifyClientId.isNotBlank()
    }
}

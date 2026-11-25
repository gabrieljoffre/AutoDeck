package com.autodeck.headunit.media

import android.content.Context
import android.graphics.Bitmap
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

    /** Conecta usando um contexto de Activity (necessário para a tela de autorização do Spotify). */
    fun connect(context: Context) {
        if (!isConfigured) {
            _status.value = SpotifyLinkStatus.NOT_CONFIGURED
            return
        }
        if (isConnected) return
        if (_status.value == SpotifyLinkStatus.NEEDS_AUTH) {
            authRequester?.let { it(); return }
        }
        if (!SpotifyAppRemote.isSpotifyInstalled(context)) {
            _status.value = SpotifyLinkStatus.NOT_INSTALLED
            return
        }
        _status.value = SpotifyLinkStatus.CONNECTING
        val params = ConnectionParams.Builder(BuildConfig.SPOTIFY_CLIENT_ID)
            .setRedirectUri(BuildConfig.SPOTIFY_REDIRECT_URI)
            .showAuthView(true)
            .build()
        SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                remote = appRemote
                _status.value = SpotifyLinkStatus.CONNECTED
                appRemote.playerApi.subscribeToPlayerState().setEventCallback { ps -> apply(ps) }
                appRemote.playerApi.subscribeToPlayerContext().setEventCallback { ctx ->
                    val title = ctx.title.orEmpty()
                    if (title.isNotBlank()) {
                        onState { copy(contextLabel = title) }
                    }
                }
            }

            override fun onFailure(throwable: Throwable) {
                Log.w(TAG, "Spotify App Remote falhou: ${throwable.javaClass.simpleName}: ${throwable.message}", throwable)
                remote = null
                if (throwable is UserNotAuthorizedException) {
                    _status.value = SpotifyLinkStatus.NEEDS_AUTH
                    // Primeira vez: abre a autorização sozinho; depois, só quando o usuário tocar no aviso
                    if (!autoAuthAsked) {
                        autoAuthAsked = true
                        authRequester?.invoke()
                    }
                } else {
                    _status.value = SpotifyLinkStatus.FAILED
                }
            }
        })
    }

    /** Chamado após a tela de autorização devolver um token válido. */
    fun onAuthorized(context: Context) {
        _status.value = SpotifyLinkStatus.CONNECTING
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
        val isConfigured: Boolean get() = BuildConfig.SPOTIFY_CLIENT_ID.isNotBlank()
    }
}

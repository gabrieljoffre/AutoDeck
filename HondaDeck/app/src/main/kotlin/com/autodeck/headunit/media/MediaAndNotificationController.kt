package com.autodeck.headunit.media

import android.annotation.SuppressLint
import android.app.Notification
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class InstalledAppOption(
    val packageName: String,
    val label: String
)

data class MediaPlaybackState(
    val title: String = "Toque para abrir o Spotify",
    val artist: String = "Nenhuma faixa ativa",
    val album: String = "",
    val coverBitmap: Bitmap? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val activePackage: String = "com.spotify.music",
    val appLabel: String = "Spotify",
    val hasNotificationAccess: Boolean = true,
    val lastUpdatedAtElapsedMs: Long = 0L
)

data class WhatsAppNotificationItem(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val sender: String,
    val message: String,
    val timestampMs: Long,
    val replyAction: Notification.Action? = null,
    val remoteInput: RemoteInput? = null,
    val isReadByAgent: Boolean = false
) {
    val canReply: Boolean
        get() = replyAction != null && remoteInput != null
}

/**
 * Controla a reprodução de música do tablet (Spotify por padrão ou app configurado),
 * captura metadados/capa em tempo real via MediaSessionManager + broadcasts do Spotify
 * e gerencia mensagens recebidas (WhatsApp) com resposta direta via RemoteInput.
 */
class MediaAndNotificationController private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val sessionManager = appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager

    private val _mediaState = MutableStateFlow(
        MediaPlaybackState(
            hasNotificationAccess = true
        )
    )
    val mediaState: StateFlow<MediaPlaybackState> = _mediaState.asStateFlow()

    private val _messages = MutableStateFlow<List<WhatsAppNotificationItem>>(emptyList())
    val messages: StateFlow<List<WhatsAppNotificationItem>> = _messages.asStateFlow()

    private var preferredMusicPackage: String = "com.spotify.music"
    private var preferredMusicLabel: String = "Spotify"
    private var listenerComponent: ComponentName? = null
    private var activeController: MediaController? = null
    private var sessionsListenerRegistered = false
    private var spotifyBroadcastRegistered = false
    private var tickerJob: Job? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            syncFromController(activeController)
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            syncFromController(activeController)
        }

        override fun onSessionDestroyed() {
            refreshActiveMediaSessions()
        }
    }

    private val activeSessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        bindBestController(controllers.orEmpty())
    }

    private val spotifyBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "com.spotify.music.metadatachanged" -> {
                    val track = intent.getStringExtra("track").orEmpty()
                    val artist = intent.getStringExtra("artist").orEmpty()
                    val album = intent.getStringExtra("album").orEmpty()
                    val lengthMs = intent.getIntExtra("length", 0).toLong().coerceAtLeast(0L)
                    if (track.isNotBlank()) {
                        _mediaState.update {
                            it.copy(
                                title = track,
                                artist = artist.ifBlank { "Spotify" },
                                album = album,
                                durationMs = if (lengthMs > 0L) lengthMs else it.durationMs,
                                activePackage = "com.spotify.music",
                                appLabel = "Spotify",
                                lastUpdatedAtElapsedMs = SystemClock.elapsedRealtime()
                            )
                        }
                    }
                }
                "com.spotify.music.playbackstatechanged" -> {
                    val playing = intent.getBooleanExtra("playing", false)
                    val pos = intent.getIntExtra("playbackPosition", 0).toLong().coerceAtLeast(0L)
                    _mediaState.update {
                        it.copy(
                            isPlaying = playing,
                            positionMs = pos,
                            lastUpdatedAtElapsedMs = SystemClock.elapsedRealtime()
                        )
                    }
                }
            }
        }
    }

    init {
        registerSpotifyBroadcasts()
        startProgressTicker()
        refreshAccessAndSessions()
    }

    fun setPreferredMusicApp(packageName: String, label: String) {
        preferredMusicPackage = packageName
        preferredMusicLabel = label
        if (_mediaState.value.title == "Toque para abrir o Spotify" || _mediaState.value.lastUpdatedAtElapsedMs == 0L) {
            _mediaState.update {
                it.copy(
                    title = "Toque para abrir $label",
                    activePackage = packageName,
                    appLabel = label
                )
            }
        }
        refreshActiveMediaSessions()
    }

    fun onListenerConnected(component: ComponentName) {
        listenerComponent = component
        _mediaState.update { it.copy(hasNotificationAccess = true) }
        registerSessionsListener(component)
        refreshActiveMediaSessions()
    }

    fun onListenerDisconnected() {
        listenerComponent = null
        sessionsListenerRegistered = false
        _mediaState.update { it.copy(hasNotificationAccess = true) }
    }

    fun refreshAccessAndSessions() {
        val granted = HondaNotificationMediaService.isPermissionGranted(appContext)
        _mediaState.update { it.copy(hasNotificationAccess = true) }
        if (granted) {
            val comp = listenerComponent ?: HondaNotificationMediaService.componentName(appContext)
            listenerComponent = comp
            registerSessionsListener(comp)
            refreshActiveMediaSessions()
        }
    }

    fun togglePlayPause() {
        val ctrl = activeController
        if (ctrl != null) {
            val playing = ctrl.playbackState?.state == PlaybackState.STATE_PLAYING
            if (playing) ctrl.transportControls.pause() else ctrl.transportControls.play()
        } else {
            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }
    }

    fun play() {
        val ctrl = activeController
        if (ctrl != null) {
            ctrl.transportControls.play()
        } else {
            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        }
    }

    fun pause() {
        val ctrl = activeController
        if (ctrl != null) {
            ctrl.transportControls.pause()
        } else {
            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
        }
    }

    fun skipToNext() {
        val ctrl = activeController
        if (ctrl != null) {
            ctrl.transportControls.skipToNext()
        } else {
            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        }
    }

    fun skipToPrevious() {
        val ctrl = activeController
        if (ctrl != null) {
            ctrl.transportControls.skipToPrevious()
        } else {
            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }
    }

    fun seekTo(positionMs: Long) {
        activeController?.transportControls?.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun warmupPreferredMusicApp(autoPlay: Boolean) {
        val pkg = preferredMusicPackage.ifBlank { "com.spotify.music" }
        runCatching {
            val launchIntent = appContext.packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null && autoPlay) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(launchIntent)
            }
        }
        if (autoPlay) {
            scope.launch {
                delay(1_200L)
                play()
            }
        }
    }

    fun launchPreferredMusicApp(): Boolean {
        val pkg = preferredMusicPackage.ifBlank { "com.spotify.music" }
        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(launchIntent)
        return true
    }

    fun searchAndPlayInSpotify(query: String): Boolean {
        val intent = Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage(preferredMusicPackage.ifBlank { "com.spotify.music" })
            putExtra(android.app.SearchManager.QUERY, query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            appContext.startActivity(intent)
            true
        }.getOrElse {
            launchPreferredMusicApp()
        }
    }

    fun onMessagingNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras ?: return
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString()?.trim().orEmpty()
        if (sender.isBlank() || text.isBlank()) return

        var replyAction: Notification.Action? = null
        var replyInput: RemoteInput? = null
        val actions = sbn.notification.actions.orEmpty()
        for (action in actions) {
            val inputs = action.remoteInputs
            if (!inputs.isNullOrEmpty()) {
                replyAction = action
                replyInput = inputs.first()
                break
            }
        }

        val appLabel = when (sbn.packageName) {
            "com.whatsapp", "com.whatsapp.w4b" -> "WhatsApp"
            "org.telegram.messenger" -> "Telegram"
            else -> "Mensagem"
        }

        val item = WhatsAppNotificationItem(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel,
            sender = sender,
            message = text,
            timestampMs = sbn.postTime,
            replyAction = replyAction,
            remoteInput = replyInput
        )

        _messages.update { current ->
            val filtered = current.filterNot { it.key == item.key }
            (listOf(item) + filtered).take(15)
        }
    }

    fun onMessagingNotificationRemoved(key: String) {
        _messages.update { list -> list.filterNot { it.key == key } }
    }

    fun markMessageRead(key: String) {
        _messages.update { list ->
            list.map { if (it.key == key) it.copy(isReadByAgent = true) else it }
        }
    }

    fun dismissMessage(key: String) {
        _messages.update { list -> list.filterNot { it.key == key } }
    }

    fun replyToMessage(item: WhatsAppNotificationItem, replyText: String): Boolean {
        val action = item.replyAction ?: return false
        val remoteInput = item.remoteInput ?: return false
        return runCatching {
            val intent = Intent()
            val bundle = Bundle().apply {
                putCharSequence(remoteInput.resultKey, replyText)
            }
            RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, bundle)
            action.actionIntent.send(appContext, 0, intent)
            markMessageRead(item.key)
            true
        }.getOrDefault(false)
    }

    fun launchWhatsApp(): Boolean {
        val pm = appContext.packageManager
        val intent = pm.getLaunchIntentForPackage("com.whatsapp")
            ?: pm.getLaunchIntentForPackage("com.whatsapp.w4b")
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
        return true
    }

    fun listInstalledMusicApps(): List<InstalledAppOption> {
        val candidates = listOf(
            "com.spotify.music" to "Spotify",
            "com.google.android.apps.youtube.music" to "YouTube Music",
            "deezer.android.app" to "Deezer",
            "com.amazon.mp3" to "Amazon Music",
            "com.apple.android.music" to "Apple Music",
            "com.sec.android.app.music" to "Samsung Music",
            "org.videolan.vlc" to "VLC"
        )
        return filterInstalledApps(candidates)
    }

    fun listInstalledMapApps(): List<InstalledAppOption> {
        val candidates = listOf(
            "com.google.android.apps.maps" to "Google Maps",
            "com.waze" to "Waze",
            "INTERNAL_MAP" to "Mapa Interno (HondaDeck)"
        )
        return candidates.filter { (pkg, _) ->
            pkg == "INTERNAL_MAP" || isPackageInstalled(pkg)
        }.map { (pkg, label) ->
            InstalledAppOption(pkg, label)
        }.ifEmpty {
            listOf(
                InstalledAppOption("com.google.android.apps.maps", "Google Maps"),
                InstalledAppOption("INTERNAL_MAP", "Mapa Interno (HondaDeck)")
            )
        }
    }

    private fun filterInstalledApps(candidates: List<Pair<String, String>>): List<InstalledAppOption> {
        val installed = candidates.filter { (pkg, _) -> isPackageInstalled(pkg) }
            .map { InstalledAppOption(it.first, it.second) }
        return installed.ifEmpty {
            listOf(InstalledAppOption("com.spotify.music", "Spotify"))
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.getPackageInfo(packageName, 0)
            }
            true
        }.getOrDefault(false)
    }

    private fun registerSessionsListener(component: ComponentName) {
        if (sessionsListenerRegistered) return
        val mgr = sessionManager ?: return
        runCatching {
            mgr.addOnActiveSessionsChangedListener(activeSessionsListener, component)
            sessionsListenerRegistered = true
        }
    }

    private fun refreshActiveMediaSessions() {
        val mgr = sessionManager ?: return
        val comp = listenerComponent ?: HondaNotificationMediaService.componentName(appContext)
        runCatching {
            val controllers = mgr.getActiveSessions(comp)
            bindBestController(controllers)
        }
    }

    private fun bindBestController(controllers: List<MediaController>) {
        val chosen = controllers.firstOrNull { it.packageName == preferredMusicPackage }
            ?: controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull()

        if (activeController?.sessionToken != chosen?.sessionToken) {
            runCatching { activeController?.unregisterCallback(controllerCallback) }
            activeController = chosen
            runCatching { chosen?.registerCallback(controllerCallback) }
        }
        syncFromController(chosen)
    }

    private fun syncFromController(controller: MediaController?) {
        if (controller == null) return
        val meta = controller.metadata ?: return
        val pb = controller.playbackState
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: return
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: preferredMusicLabel
        val album = meta.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val cover = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)
        val isPlaying = pb?.state == PlaybackState.STATE_PLAYING
        val position = computeCurrentPosition(pb, duration)

        val pkg = controller.packageName
        val label = resolveAppLabel(pkg)

        _mediaState.update {
            it.copy(
                title = title,
                artist = artist,
                album = album,
                coverBitmap = cover ?: it.coverBitmap,
                isPlaying = isPlaying,
                positionMs = position,
                durationMs = duration,
                activePackage = pkg,
                appLabel = label,
                lastUpdatedAtElapsedMs = SystemClock.elapsedRealtime()
            )
        }
    }

    private fun computeCurrentPosition(pb: PlaybackState?, durationMs: Long): Long {
        if (pb == null) return 0L
        var pos = pb.position
        if (pb.state == PlaybackState.STATE_PLAYING && pb.lastPositionUpdateTime > 0L) {
            val delta = SystemClock.elapsedRealtime() - pb.lastPositionUpdateTime
            pos += (delta * pb.playbackSpeed).toLong()
        }
        return if (durationMs > 0L) pos.coerceIn(0L, durationMs) else pos.coerceAtLeast(0L)
    }

    private fun resolveAppLabel(packageName: String): String {
        return when (packageName) {
            "com.spotify.music" -> "Spotify"
            "com.google.android.apps.youtube.music" -> "YouTube Music"
            "deezer.android.app" -> "Deezer"
            else -> runCatching {
                val info = appContext.packageManager.getApplicationInfo(packageName, 0)
                appContext.packageManager.getApplicationLabel(info).toString()
            }.getOrDefault(preferredMusicLabel)
        }
    }

    private fun startProgressTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(1_000L)
                val ctrl = activeController
                if (ctrl != null) {
                    val pb = ctrl.playbackState
                    if (pb?.state == PlaybackState.STATE_PLAYING) {
                        val dur = _mediaState.value.durationMs
                        _mediaState.update {
                            it.copy(positionMs = computeCurrentPosition(pb, dur))
                        }
                    }
                } else if (_mediaState.value.isPlaying) {
                    _mediaState.update { cur ->
                        val next = (cur.positionMs + 1_000L)
                        val clamped = if (cur.durationMs > 0L) next.coerceAtMost(cur.durationMs) else next
                        cur.copy(positionMs = clamped)
                    }
                }
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerSpotifyBroadcasts() {
        if (spotifyBroadcastRegistered) return
        val filter = IntentFilter().apply {
            addAction("com.spotify.music.metadatachanged")
            addAction("com.spotify.music.playbackstatechanged")
        }
        ContextCompat.registerReceiver(
            appContext,
            spotifyBroadcastReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        spotifyBroadcastRegistered = true
    }

    private fun dispatchMediaKey(keyCode: Int) {
        val manager = audioManager ?: return
        val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        manager.dispatchMediaKeyEvent(down)
        manager.dispatchMediaKeyEvent(up)
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: MediaAndNotificationController? = null

        fun get(context: Context): MediaAndNotificationController {
            return instance ?: synchronized(this) {
                instance ?: MediaAndNotificationController(context.applicationContext).also { instance = it }
            }
        }
    }
}

package com.autodeck.headunit.service

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import com.autodeck.headunit.MainActivity
import com.autodeck.headunit.agent.AgentUiState
import com.autodeck.headunit.agent.CarAgentHostBridge
import com.autodeck.headunit.agent.GeminiCarAgent
import com.autodeck.headunit.agent.copilot.CopilotIntentParser
import com.autodeck.headunit.agent.copilot.FavoriteRef
import com.autodeck.headunit.agent.copilot.VolumeDirection
import com.autodeck.headunit.data.FavoritePlace
import com.autodeck.headunit.data.FavoritePlacesStore
import com.autodeck.headunit.data.HeadUnitConfig
import com.autodeck.headunit.data.HeadUnitPreferences
import com.autodeck.headunit.data.RecentPlace
import com.autodeck.headunit.hardware.AudioBtMonitor
import com.autodeck.headunit.hardware.AudioRouteStatus
import com.autodeck.headunit.hardware.BluetoothClassicObdTransport
import com.autodeck.headunit.hardware.BluetoothDirectory
import com.autodeck.headunit.hardware.CameraHardwareStatus
import com.autodeck.headunit.hardware.IgnitionBluetoothMonitor
import com.autodeck.headunit.hardware.IgnitionStatus
import com.autodeck.headunit.hardware.ObdTransport
import com.autodeck.headunit.hardware.PairedBluetoothDevice
import com.autodeck.headunit.hardware.SimulatedCameraSource
import com.autodeck.headunit.hardware.SimulatedObdTransport
import com.autodeck.headunit.hardware.UsbDeviceSummary
import com.autodeck.headunit.hardware.UsbHubCoordinator
import com.autodeck.headunit.hardware.UvcUsbCameraSource
import com.autodeck.headunit.media.InstalledAppOption
import com.autodeck.headunit.media.MediaAndNotificationController
import com.autodeck.headunit.media.MediaPlaybackState
import com.autodeck.headunit.media.SpotifyItem
import com.autodeck.headunit.media.SpotifyLinkStatus
import com.autodeck.headunit.media.SpotifySearchResults
import com.autodeck.headunit.media.SpotifyTokenMissingException
import com.autodeck.headunit.media.SpotifyWebApi
import com.autodeck.headunit.media.WhatsAppNotificationItem
import com.autodeck.headunit.navigation.GpsLocationState
import com.autodeck.headunit.navigation.GpsNavigationTracker
import com.autodeck.headunit.navigation.InAppRouteNavigator
import com.autodeck.headunit.navigation.InAppRouteState
import com.autodeck.headunit.navigation.PlaceSearch
import com.autodeck.headunit.navigation.PlaceSearchState
import com.autodeck.headunit.navigation.RoutePoint
import com.autodeck.headunit.web.WebResourceRouter
import com.autodeck.protocol.camera.LumaGrid
import com.autodeck.protocol.camera.ReverseStateMachine
import com.autodeck.protocol.camera.ReverseTimingConfig
import com.autodeck.protocol.camera.SignalAnalysis
import com.autodeck.protocol.camera.VideoSignalDetector
import com.autodeck.protocol.coordinator.HeadUnitCoordinator
import com.autodeck.protocol.coordinator.HeadUnitState
import com.autodeck.protocol.coordinator.LinkStatus
import com.autodeck.protocol.coordinator.MainTab
import com.autodeck.protocol.obd2.CivicTelemetryCalculator
import com.autodeck.protocol.obd2.DerivedTelemetry
import com.autodeck.protocol.obd2.DtcCategory
import com.autodeck.protocol.obd2.DtcCode
import com.autodeck.protocol.obd2.FuelConsumptionCalculator
import com.autodeck.protocol.obd2.ObdPid
import com.autodeck.protocol.obd2.ObdPollingPlanner
import com.autodeck.protocol.obd2.ObdPollingProfile
import com.autodeck.protocol.obd2.ObdResponseParser
import com.autodeck.protocol.obd2.ObdSnapshot
import com.autodeck.protocol.obd2.SupportedPidCapabilities
import com.autodeck.protocol.obd2.TireSpec
import com.autodeck.protocol.obd2.TransmissionType
import com.autodeck.headunit.data.FuelLogStore
import com.autodeck.protocol.obd2.FuelEntry
import com.autodeck.protocol.obd2.FuelLogCalculator
import com.autodeck.protocol.obd2.FuelLogSummary
import com.autodeck.headunit.weather.WeatherService
import com.autodeck.headunit.weather.WeatherState
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

data class WebBridgeEvent(
    val name: String,
    val text: String = "",
    val mode: String = ""
)

data class CameraDebugState(
    val analysis: SignalAnalysis? = null,
    val lastGrid: LumaGrid? = null
)

data class HeadUnitUiState(
    val headUnit: HeadUnitState = HeadUnitState(),
    val config: HeadUnitConfig = HeadUnitConfig(),
    val usbDevices: List<UsbDeviceSummary> = emptyList(),
    val pairedBluetoothDevices: List<PairedBluetoothDevice> = emptyList(),
    val audioRoute: AudioRouteStatus = AudioRouteStatus(),
    val cameraHardware: CameraHardwareStatus = CameraHardwareStatus(),
    val mediaState: MediaPlaybackState = MediaPlaybackState(),
    val whatsAppMessages: List<WhatsAppNotificationItem> = emptyList(),
    val gpsState: GpsLocationState = GpsLocationState(),
    val route: InAppRouteState = InAppRouteState(),
    val spotifyStatus: SpotifyLinkStatus = SpotifyLinkStatus.NOT_CONFIGURED,
    val spotifyHondaPlaylists: List<SpotifyItem> = emptyList(),
    val spotifyAllPlaylists: List<SpotifyItem> = emptyList(),
    val spotifyRecent: List<SpotifyItem> = emptyList(),
    val spotifySearchQuery: String = "",
    val spotifySearchLoading: Boolean = false,
    val spotifySearchResults: SpotifySearchResults = SpotifySearchResults(),
    val spotifySearchError: String? = null,
    val weather: WeatherState = WeatherState(),
    val fuelEntries: List<FuelEntry> = emptyList(),
    val fuelSummary: FuelLogSummary = FuelLogSummary(),
    val ignitionStatus: IgnitionStatus = IgnitionStatus(),
    val agentState: AgentUiState = AgentUiState(),
    val installedMusicApps: List<InstalledAppOption> = emptyList(),
    val installedMapApps: List<InstalledAppOption> = emptyList(),
    val obdCycleHz: Double = 0.0,
    val masterVolumePct: Int = 70,
    val isAudioMuted: Boolean = false,
    val bootSplashTrigger: Int = 0,
    val obdMessage: String = "OBD-II aguardando conexão",
    val simulatedReverseActive: Boolean = false,
    val dtcBusy: Boolean = false,
    val dtcActionFeedback: String? = null,
    val isAppInForeground: Boolean = true,
    val spotifyRecentSearches: List<String> = emptyList(),
    val favoritePlaces: List<FavoritePlace> = emptyList(),
    val recentPlaces: List<RecentPlace> = emptyList(),
    val placeSearch: PlaceSearchState = PlaceSearchState()
)

/**
 * Núcleo de execução persistente do HondaDeck vinculado ao Application Context + ForegroundService.
 * Coordena OBD-II Turbo, Câmera de Ré USB, GPS + Mapas Offline, Spotify/MediaSession,
 * Gatilho de Ignição Bluetooth (Taramps) e Agente Híbrido Gemini.
 */
class HeadUnitRuntime private constructor(private val appContext: Context) : CarAgentHostBridge {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val preferences = HeadUnitPreferences(appContext)
    // Declarado aqui no topo: o init abaixo já lança corrotinas que leem o diário.
    private val fuelLogStore = FuelLogStore(appContext)
    private val placesStore = FavoritePlacesStore(appContext)
    private val spotifySearchPrefs = appContext.getSharedPreferences("hondadeck_spotify", Context.MODE_PRIVATE)
    private val fuelCalculator = FuelConsumptionCalculator()
    private val signalDetector = VideoSignalDetector()
    private val coordinator = HeadUnitCoordinator(
        reverseStateMachine = ReverseStateMachine(
            ReverseTimingConfig(activateAfterFrames = 3, deactivateAfterMs = 1_200L)
        )
    )

    private val _uiState = MutableStateFlow(HeadUnitUiState())
    val uiState: StateFlow<HeadUnitUiState> = _uiState.asStateFlow()

    private val _webEvents = MutableSharedFlow<WebBridgeEvent>(extraBufferCapacity = 32)
    val webEvents: SharedFlow<WebBridgeEvent> = _webEvents.asSharedFlow()

    val tileRouter = WebResourceRouter(appContext) { _uiState.value.mediaState.coverBitmap }

    private val _cameraDebug = MutableStateFlow(CameraDebugState())
    val cameraDebug: StateFlow<CameraDebugState> = _cameraDebug.asStateFlow()

    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val audioMonitor = AudioBtMonitor(appContext)
    private val mediaController = MediaAndNotificationController.get(appContext)
    private val gpsTracker = GpsNavigationTracker(appContext)
    private val routeNavigator = InAppRouteNavigator(scope)
    private val placeSearch = PlaceSearch()
    private val spotifyWeb = SpotifyWebApi(appContext)
    private var spotifyWebAuthAskedAtMs = 0L
    private var spotifySearchJob: Job? = null
    private var placeSearchJob: Job? = null
    private var offlineMapJob: Job? = null
    private var volumeBeforeMutePct: Int = 60
    private var lastObservedAgentMsgId: Long = 0L
    private var lastObservedRouteArrived: Boolean = false

    private val ignitionMonitor = IgnitionBluetoothMonitor(
        appContext = appContext,
        scope = scope,
        onIgnitionTurnedOn = { _ -> handleIgnitionOn() },
        onIgnitionStandbyEntered = { handleIgnitionStandby() }
    )

    private val carAgent = GeminiCarAgent(
        appContext = appContext,
        scope = scope,
        host = this
    )

    private val usbHubCoordinator: UsbHubCoordinator = UsbHubCoordinator(
        context = appContext,
        onCameraAttached = {
            signalDetector.resetHistory()
            coordinator.onUsbCameraAttached(System.currentTimeMillis())
            publishState()
        },
        onCameraDetached = {
            signalDetector.resetHistory()
            coordinator.onUsbCameraDetached()
            publishState()
        }
    )

    private val simulatedCamera = SimulatedCameraSource()
    private val usbCamera = UvcUsbCameraSource(appContext, usbHubCoordinator)

    private var obdTransport: ObdTransport? = null
    private var obdJob: Job? = null
    private var cameraJob: Job? = null
    private var latestValidGrid: LumaGrid? = null
    private var lowVoltageSinceMs: Long? = null
    private var virtualTankRemainingLiters: Double = 50.0
    private var peakRpm: Double = 0.0
    private var peakSpeedKmh: Double = 0.0
    private var peakCoolantTempC: Double? = null
    private var pendingDtcCount: Int = 0

    @Volatile
    private var lastRequestedCameraStream: Boolean? = null

    init {
        val loadedConfig = preferences.load()
        virtualTankRemainingLiters = loadedConfig.virtualTankRemainingLiters
            .coerceIn(0.0, loadedConfig.virtualTankCapacityLiters)
        fuelCalculator.restoreTripState(loadedConfig.tripDistanceKm, loadedConfig.tripFuelLiters)
        applyConfigToEngines(loadedConfig)
        coordinator.updateObdSnapshot(
            coordinator.state.obdSnapshot.copy(
                derived = computeDerived(coordinator.state.obdSnapshot, loadedConfig)
            )
        )

        val initialVol = readCurrentVolumePct()
        if (initialVol > 0) volumeBeforeMutePct = initialVol
        val initialMuted = runCatching { audioManager?.isStreamMute(AudioManager.STREAM_MUSIC) == true }.getOrDefault(false)
        lastObservedAgentMsgId = carAgent.state.value.messages.lastOrNull()?.id ?: 0L

        _uiState.update {
            it.copy(
                config = loadedConfig,
                headUnit = coordinator.state,
                pairedBluetoothDevices = BluetoothDirectory.listBondedDevices(appContext),
                installedMusicApps = mediaController.listInstalledMusicApps(),
                installedMapApps = mediaController.listInstalledMapApps(),
                masterVolumePct = initialVol,
                isAudioMuted = initialMuted,
                favoritePlaces = placesStore.loadFavorites(),
                recentPlaces = placesStore.loadRecentPlaces(),
                spotifyRecentSearches = loadSpotifyRecentSearches()
            )
        }

        usbHubCoordinator.start()
        audioMonitor.start()
        ignitionMonitor.start()
        observeAllFlows()
        startCameraSignalMonitoring(loadedConfig.useSimulatedCamera)

        if (loadedConfig.setupCompleted && loadedConfig.autoConnectOnLaunch) {
            startObdLoop()
        }
    }

    fun setAppForeground(inForeground: Boolean) {
        _uiState.update { it.copy(isAppInForeground = inForeground) }
        carAgent.setAppForeground(inForeground)
        if (inForeground) {
            mediaController.refreshAccessAndSessions()
            carAgent.refreshConnectivityBadge()
            _uiState.update { it.copy(masterVolumePct = readCurrentVolumePct()) }
        }
    }

    fun onPermissionsUpdated() {
        usbCamera.refreshFromUsb()
        gpsTracker.refreshPermissionAndStart()
        mediaController.refreshAccessAndSessions()
        carAgent.onConfigUpdated(_uiState.value.config)
        _uiState.update {
            it.copy(
                pairedBluetoothDevices = BluetoothDirectory.listBondedDevices(appContext),
                installedMusicApps = mediaController.listInstalledMusicApps(),
                installedMapApps = mediaController.listInstalledMapApps(),
                masterVolumePct = readCurrentVolumePct()
            )
        }
        audioMonitor.refreshRoute()
        val cfg = _uiState.value.config
        if (cfg.setupCompleted && cfg.autoConnectOnLaunch && obdJob?.isActive != true) {
            startObdLoop()
        }
    }

    fun completeSetup(newConfig: HeadUnitConfig) {
        val finalized = newConfig.copy(setupCompleted = true)
        preferences.save(finalized)
        applyConfigToEngines(finalized)
        _uiState.update { it.copy(config = finalized) }
        publishState()

        startCameraSignalMonitoring(finalized.useSimulatedCamera)
        restartObdLoop()
    }

    fun reopenSetup() {
        val updated = _uiState.value.config.copy(setupCompleted = false)
        preferences.save(updated)
        applyConfigToEngines(updated)
        _uiState.update { it.copy(config = updated) }
        publishState()
    }

    fun updateConfig(transform: (HeadUnitConfig) -> HeadUnitConfig) {
        val prev = _uiState.value.config
        val updated = transform(prev)
        preferences.save(updated)
        applyConfigToEngines(updated)
        _uiState.update { it.copy(config = updated) }
        publishState()

        if (prev.useSimulatedCamera != updated.useSimulatedCamera) {
            startCameraSignalMonitoring(updated.useSimulatedCamera)
        }
        if (prev.useSimulatedObd != updated.useSimulatedObd || prev.obdDeviceMac != updated.obdDeviceMac) {
            restartObdLoop()
        }
    }

    fun setLauncherModeEnabled(enabled: Boolean) {
        val pm = appContext.packageManager
        val alias = ComponentName(appContext.packageName, "com.autodeck.headunit.LauncherHomeAlias")
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        runCatching {
            pm.setComponentEnabledSetting(alias, state, PackageManager.DONT_KILL_APP)
        }
        updateConfig { it.copy(launcherModeEnabled = enabled) }
    }

    fun selectTab(tab: MainTab) {
        coordinator.selectTab(tab)
        publishState()
    }

    fun dismissCurrentAlert(title: String) {
        coordinator.dismissAlert(title)
        publishState()
    }

    fun onPowerConnectionChanged(connected: Boolean) {
        coordinator.updatePowerConnected(connected)
        publishState()
    }

    fun onIgnitionBluetoothDeviceEvent(mac: String, name: String, connected: Boolean) {
        ignitionMonitor.handleBluetoothEvent(mac, name, connected, _uiState.value.config)
    }

    fun enterStandbyManually() {
        ignitionMonitor.enterStandbyNow()
    }

    fun wakeFromStandbyManually() {
        ignitionMonitor.wakeFromStandbyManually()
    }

    private fun handleIgnitionOn() {
        val cfg = _uiState.value.config
        coordinator.updatePowerConnected(true)
        publishState()

        gpsTracker.refreshPermissionAndStart()
        if (!_uiState.value.isAppInForeground) {
            val intent = Intent(appContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            runCatching { appContext.startActivity(intent) }
        }
        if (cfg.showBootSplashOnIgnition) {
            _uiState.update { it.copy(bootSplashTrigger = it.bootSplashTrigger + 1) }
        }
        if (cfg.autoConnectOnLaunch && obdJob?.isActive != true) {
            startObdLoop()
        }
        if (cfg.autoWarmupMusicOnIgnition) {
            mediaController.warmupPreferredMusicApp(autoPlay = cfg.autoPlayMusicOnIgnition)
        }
    }

    private fun handleIgnitionStandby() {
        mediaController.pause()
        gpsTracker.stop()
        carAgent.stopListening()
        lastRequestedCameraStream = false
        scope.launch(Dispatchers.IO) {
            usbCamera.setReverseOverlayVisible(false)
        }
        coordinator.updatePowerConnected(false)
        publishState()
    }

    // --- Música & Volume ---
    fun toggleMusicPlayPause() = mediaController.togglePlayPause()
    fun skipMusicNext() = mediaController.skipToNext()
    fun skipMusicPrevious() = mediaController.skipToPrevious()
    fun seekMusicTo(posMs: Long) = mediaController.seekTo(posMs)

    fun openPreferredMusicApp(): Boolean = mediaController.launchPreferredMusicApp()

    fun emitWebEvent(name: String, text: String = "", mode: String = "") {
        _webEvents.tryEmit(WebBridgeEvent(name = name, text = text, mode = mode))
    }

    fun setMasterVolumePct(pct: Int) {
        val mgr = audioManager ?: return
        val max = mgr.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val clampedPct = pct.coerceIn(0, 100)
        val target = ((clampedPct / 100f) * max).toInt().coerceIn(0, max)
        runCatching { mgr.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
        if (clampedPct > 0) volumeBeforeMutePct = clampedPct
        _uiState.update { it.copy(masterVolumePct = clampedPct, isAudioMuted = clampedPct == 0) }
    }

    fun toggleAudioMute() {
        val mgr = audioManager ?: return
        val max = mgr.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val currentlyMuted = _uiState.value.isAudioMuted
        if (!currentlyMuted) {
            val curPct = _uiState.value.masterVolumePct
            if (curPct > 0) volumeBeforeMutePct = curPct
            runCatching { mgr.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0) }
            _uiState.update { it.copy(isAudioMuted = true) }
        } else {
            val restorePct = volumeBeforeMutePct.coerceIn(10, 100)
            val target = ((restorePct / 100f) * max).toInt().coerceIn(1, max)
            runCatching { mgr.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
            _uiState.update { it.copy(isAudioMuted = false, masterVolumePct = restorePct) }
        }
    }

    // --- Navegação, Lugares & Mapa Offline ---
    fun openPreferredMapApp(): Boolean {
        val pkg = _uiState.value.config.preferredMapPackage
        if (pkg == "INTERNAL_MAP") {
            selectTab(MainTab.NAVIGATION)
            return true
        }
        return gpsTracker.openPreferredMapApp(pkg)
    }

    /** Navegação curva-a-curva dentro do próprio HondaDeck (mapa + OSRM). */
    fun navigateToDestination(destination: String): Boolean {
        return routeToPlace(destination, lat = null, lon = null)
    }

    /** Busca lugares via Nominatim ordenados por distância da posição atual. */
    fun searchPlaces(query: String) {
        val q = query.trim()
        placeSearchJob?.cancel()
        if (q.length < 2) {
            _uiState.update { it.copy(placeSearch = PlaceSearchState(query = q, loading = false, results = emptyList())) }
            return
        }
        _uiState.update { it.copy(placeSearch = it.placeSearch.copy(query = q, loading = true)) }
        placeSearchJob = scope.launch {
            val gps = _uiState.value.gpsState
            val results = runCatching {
                placeSearch.search(q, gps.latitude, gps.longitude)
            }.getOrDefault(emptyList())
            _uiState.update {
                it.copy(placeSearch = PlaceSearchState(query = q, loading = false, results = results))
            }
        }
    }

    /**
     * Inicia rota interna: com lat/lon roteia direto no InAppRouteNavigator;
     * sem coordenadas, geocodifica pelo nome. Salva o destino nos lugares recentes.
     */
    fun routeToPlace(name: String, lat: Double?, lon: Double?): Boolean {
        val cleanName = name.trim()
        val gps = _uiState.value.gpsState
        val from = RoutePoint(gps.latitude, gps.longitude)
        val hasCoords = lat != null && lon != null && lat.isFinite() && lon.isFinite() && (lat != 0.0 || lon != 0.0)
        if (!hasCoords && cleanName.isBlank()) return false

        val label = cleanName.ifBlank { "Destino" }
        val knownAddress = _uiState.value.placeSearch.results
            .firstOrNull { it.name.equals(label, ignoreCase = true) }?.address
            ?: _uiState.value.favoritePlaces.firstOrNull { it.name.equals(label, ignoreCase = true) }?.address
            ?: ""

        if (hasCoords) {
            routeNavigator.startNavigationToPoint(label, RoutePoint(lat!!, lon!!), from)
            val recents = placesStore.addRecentPlace(RecentPlace(label, knownAddress, lat, lon))
            _uiState.update { it.copy(recentPlaces = recents) }
        } else {
            routeNavigator.startNavigation(label, from)
            val recents = placesStore.addRecentPlace(RecentPlace(label, knownAddress))
            _uiState.update { it.copy(recentPlaces = recents) }
        }
        return true
    }

    fun saveFavoritePlace(
        id: String?,
        name: String,
        address: String,
        lat: Double?,
        lon: Double?,
        voice: String?,
        icon: String?
    ) {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return
        val hasCoords = lat != null && lon != null && lat.isFinite() && lon.isFinite() && (lat != 0.0 || lon != 0.0)
        val item = FavoritePlace(
            id = id?.trim().orEmpty().ifBlank { FavoritePlacesStore.generateId(cleanName) },
            name = cleanName,
            address = address.trim(),
            lat = if (hasCoords) lat!! else 0.0,
            lon = if (hasCoords) lon!! else 0.0,
            voice = voice?.trim().orEmpty(),
            icon = FavoritePlacesStore.normalizeIcon(icon)
        )
        val updated = placesStore.upsertFavorite(item)
        _uiState.update { it.copy(favoritePlaces = updated) }
        emitWebEvent("toast", "Favorito \"$cleanName\" salvo")

        if (!hasCoords && item.address.isNotBlank()) {
            scope.launch {
                val gps = _uiState.value.gpsState
                val hit = runCatching {
                    placeSearch.search(item.address, gps.latitude, gps.longitude).firstOrNull()
                }.getOrNull()
                if (hit != null) {
                    val geocoded = placesStore.upsertFavorite(item.copy(lat = hit.lat, lon = hit.lon))
                    _uiState.update { it.copy(favoritePlaces = geocoded) }
                }
            }
        }
    }

    fun deleteFavoritePlace(id: String) {
        if (id.isBlank()) return
        val updated = placesStore.deleteFavorite(id)
        _uiState.update { it.copy(favoritePlaces = updated) }
        emitWebEvent("toast", "Favorito removido")
    }

    fun saveCurrentLocationAsFavorite(name: String, voice: String?, icon: String?) {
        val gps = _uiState.value.gpsState
        val cleanName = name.trim().ifBlank { "Local atual" }
        val addr = String.format(java.util.Locale.US, "%.5f, %.5f", gps.latitude, gps.longitude)
        saveFavoritePlace(
            id = null,
            name = cleanName,
            address = addr,
            lat = gps.latitude,
            lon = gps.longitude,
            voice = voice,
            icon = icon
        )
    }

    fun stopInAppNavigation() = routeNavigator.stopNavigation()

    /** Abre o mesmo destino no app de mapa externo (Google Maps/Waze), quando o usuário quiser trânsito ao vivo. */
    fun navigateExternally(destination: String): Boolean {
        val pkg = _uiState.value.config.preferredMapPackage
            .takeIf { it != "INTERNAL_MAP" } ?: "com.google.android.apps.maps"
        return gpsTracker.navigateToDestination(destination, pkg)
    }

    fun connectSpotify(activityContext: Context) = mediaController.spotify.connect(activityContext)
    fun setSpotifyAuthRequester(requester: (() -> Unit)?) { mediaController.spotify.authRequester = requester }
    fun onSpotifyAuthorized(activityContext: Context) = mediaController.spotify.onAuthorized(activityContext)
    fun onSpotifyAuthFailed(reason: String) = mediaController.spotify.onAuthFailed(reason)
    fun spotifyToggleShuffle() { mediaController.spotify.toggleShuffle() }
    fun spotifyToggleRepeat() { mediaController.spotify.toggleRepeat() }
    fun spotifyToggleLike() {
        val ms = _uiState.value.mediaState
        mediaController.spotify.toggleLike(ms.trackUri, ms.liked)
    }

    // --- Spotify Web API: busca, playlists "Honda" e tocadas recentemente ---
    fun onSpotifyToken(token: String, expiresInSec: Int) {
        spotifyWeb.setToken(token, expiresInSec)
        refreshSpotifyPlaylists()
        val q = _uiState.value.spotifySearchQuery
        if (q.isNotBlank() && _uiState.value.spotifySearchError != null) searchSpotify(q)
    }

    /** Pede a tela de autorização para obter o token da Web API (só quando o usuário pede ou uma vez ao conectar). */
    fun requestSpotifyWebAuth() {
        mediaController.spotify.authRequester?.invoke()
    }

    fun refreshSpotifyPlaylists() {
        scope.launch {
            var missingToken = false
            runCatching { spotifyWeb.myPlaylists() }
                .onSuccess { all ->
                    _uiState.update {
                        it.copy(
                            spotifyAllPlaylists = all,
                            spotifyHondaPlaylists = SpotifyWebApi.filterHonda(all)
                        )
                    }
                }
                .onFailure { err ->
                    if (err is SpotifyTokenMissingException) missingToken = true
                }

            runCatching { spotifyWeb.recentlyPlayed() }
                .onSuccess { recent ->
                    _uiState.update { it.copy(spotifyRecent = recent) }
                }
                .onFailure { err ->
                    if (err is SpotifyTokenMissingException) missingToken = true
                }

            // Token vencido (dura ~1 h): renova pela tela do Spotify, no máximo a cada 10 min
            val now = System.currentTimeMillis()
            if (missingToken && now - spotifyWebAuthAskedAtMs > 10 * 60_000L) {
                spotifyWebAuthAskedAtMs = now
                withContext(Dispatchers.Main) { requestSpotifyWebAuth() }
            }
        }
    }

    fun searchSpotify(query: String) {
        val q = query.trim()
        spotifySearchJob?.cancel()
        _uiState.update { it.copy(spotifySearchQuery = q, spotifySearchError = null) }
        if (q.length < 2) {
            _uiState.update { it.copy(spotifySearchResults = SpotifySearchResults(), spotifySearchLoading = false) }
            return
        }
        spotifySearchJob = scope.launch {
            delay(350L) // espera o usuário parar de digitar
            recordSpotifyRecentSearch(q)
            _uiState.update { it.copy(spotifySearchLoading = true) }
            runCatching { spotifyWeb.search(q) }
                .onSuccess { res -> _uiState.update { it.copy(spotifySearchLoading = false, spotifySearchResults = res) } }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            spotifySearchLoading = false,
                            spotifySearchError = if (err is SpotifyTokenMissingException) {
                                "Toque em \"Autorizar\" para buscar no Spotify"
                            } else {
                                "Não foi possível buscar agora (${err.message})"
                            }
                        )
                    }
                }
        }
    }

    private fun loadSpotifyRecentSearches(): List<String> {
        val raw = spotifySearchPrefs.getString(KEY_SPOTIFY_RECENT_SEARCHES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> arr.optString(i).trim().takeIf { it.isNotBlank() } }
                .take(MAX_SPOTIFY_RECENT_SEARCHES)
        }.getOrDefault(emptyList())
    }

    private fun recordSpotifyRecentSearch(query: String) {
        val clean = query.trim()
        if (clean.length < 2) return
        val next = (listOf(clean) + _uiState.value.spotifyRecentSearches.filterNot { it.equals(clean, ignoreCase = true) })
            .take(MAX_SPOTIFY_RECENT_SEARCHES)
        val arr = JSONArray()
        next.forEach { arr.put(it) }
        spotifySearchPrefs.edit().putString(KEY_SPOTIFY_RECENT_SEARCHES, arr.toString()).apply()
        _uiState.update { it.copy(spotifyRecentSearches = next) }
    }

    fun playSpotifyUri(uri: String) {
        if (mediaController.spotify.isConnected) {
            mediaController.spotify.play(uri)
        } else {
            mediaController.launchPreferredMusicApp()
        }
    }

    fun downloadOfflineMap(radiusKm: Double = 12.0) {
        if (offlineMapJob?.isActive == true) {
            emitWebEvent("toast", "Download do mapa offline já está em andamento")
            return
        }
        val gps = _uiState.value.gpsState
        emitWebEvent("toast", "Baixando mapa offline (~${radiusKm.toInt()} km)...")
        offlineMapJob = scope.launch {
            gpsTracker.updateOfflineStatus(true, "Baixando mapa offline (~${radiusKm.toInt()} km)...")
            val result = runCatching {
                tileRouter.downloadOfflineRegion(
                    lat = gps.latitude,
                    lon = gps.longitude,
                    radiusKm = radiusKm,
                    minZoom = 11,
                    maxZoom = 16
                ) { _, _, status ->
                    gpsTracker.updateOfflineStatus(true, status)
                }
            }
            result.onSuccess { r ->
                val msg = if (r.failedTiles > 0) {
                    "Mapa offline salvo (${r.downloadedTiles} novos, ${r.failedTiles} pendentes)"
                } else {
                    "Mapa offline salvo (${r.totalTiles} blocos em cache)"
                }
                gpsTracker.updateOfflineStatus(false, msg)
                emitWebEvent("toast", msg)
            }.onFailure { err ->
                val msg = "Falha no mapa offline: ${err.message ?: "sem conexão"}"
                gpsTracker.updateOfflineStatus(false, msg)
                emitWebEvent("error", msg)
            }
        }
    }

    // --- Agente Gemini & WhatsApp ---
    fun triggerVoiceAgent() = carAgent.triggerVoiceCommand()
    fun stopVoiceAgent() = carAgent.stopListening()
    fun sendAgentPrompt(text: String) = carAgent.sendUserPrompt(text, speakReply = true)
    fun readLatestWhatsAppAloud() = carAgent.readLatestWhatsAppAloud()

    fun replyToWhatsApp(item: WhatsAppNotificationItem, replyText: String): Boolean =
        mediaController.replyToMessage(item, replyText)

    fun dismissWhatsApp(key: String) = mediaController.dismissMessage(key)
    fun launchWhatsApp(): Boolean = mediaController.launchWhatsApp()

    // --- CarAgentHostBridge ---
    override fun getConfig(): HeadUnitConfig = _uiState.value.config
    override fun getHeadUnitState(): HeadUnitState = coordinator.state
    override fun getMediaState(): MediaPlaybackState = mediaController.mediaState.value
    override fun getWhatsAppMessages(): List<WhatsAppNotificationItem> = mediaController.messages.value

    override fun controlMusic(action: String, query: String?): String {
        return when (action) {
            "MUSIC_PLAY" -> {
                mediaController.play()
                "Play"
            }
            "MUSIC_PAUSE" -> {
                mediaController.pause()
                "Pausa"
            }
            "MUSIC_NEXT" -> {
                mediaController.skipToNext()
                "Próxima faixa"
            }
            "MUSIC_PREV" -> {
                mediaController.skipToPrevious()
                "Faixa anterior"
            }
            "OPEN_SPOTIFY" -> {
                selectTab(MainTab.MUSIC)
                emitWebEvent("openMode", mode = "music")
                "Spotify"
            }
            "SEARCH" -> {
                val q = query.orEmpty()
                mediaController.searchAndPlayInSpotify(q)
                "Busca: $q"
            }
            else -> "Mídia"
        }
    }

    override fun getFavoritePlaces(): List<FavoriteRef> =
        _uiState.value.favoritePlaces.map { f ->
            FavoriteRef(
                id = f.id,
                name = f.name,
                address = f.address,
                lat = f.lat.takeIf { it != 0.0 },
                lon = f.lon.takeIf { it != 0.0 },
                voice = f.voice
            )
        }

    override fun navigateTo(destination: String?): String =
        navigateTo(destination, favorite = null, forceExternalMaps = false)

    override fun navigateTo(
        destination: String?,
        favorite: FavoriteRef?,
        forceExternalMaps: Boolean
    ): String {
        val cleanDest = destination?.trim().orEmpty()
        if (forceExternalMaps && cleanDest.isNotBlank()) {
            navigateExternally(cleanDest)
            return "Maps externo: $cleanDest"
        }
        if (favorite != null) {
            routeToPlace(favorite.name, favorite.lat, favorite.lon)
            selectTab(MainTab.NAVIGATION)
            emitWebEvent("openMode", mode = "map")
            return "Rota: ${favorite.name}"
        }
        if (cleanDest.isNotBlank()) {
            val normDest = CopilotIntentParser.normalize(cleanDest)
            val matchedFav = _uiState.value.favoritePlaces.firstOrNull { f ->
                CopilotIntentParser.normalize(f.name) == normDest ||
                    CopilotIntentParser.normalize(f.id) == normDest
            }
            if (matchedFav != null) {
                routeToPlace(
                    matchedFav.name,
                    matchedFav.lat.takeIf { it != 0.0 },
                    matchedFav.lon.takeIf { it != 0.0 }
                )
            } else {
                navigateToDestination(cleanDest)
            }
            selectTab(MainTab.NAVIGATION)
            emitWebEvent("openMode", mode = "map")
            return "Rota: ${matchedFav?.name ?: cleanDest}"
        }
        selectTab(MainTab.NAVIGATION)
        emitWebEvent("openMode", mode = "map")
        return "Mapa"
    }

    override fun searchPlacesOnMap(query: String): String {
        val q = query.trim()
        if (q.isNotEmpty()) {
            searchPlaces(q)
        }
        selectTab(MainTab.NAVIGATION)
        emitWebEvent("openMode", mode = "map")
        return if (q.isNotEmpty()) "Busca no mapa: $q" else "Mapa"
    }

    override suspend fun searchPlacesForTool(query: String): String {
        val q = query.trim()
        if (q.isEmpty()) return "Informe o local que deseja procurar."
        val gps = _uiState.value.gpsState
        val results = withContext(Dispatchers.IO) {
            runCatching { placeSearch.search(q, gps.latitude, gps.longitude) }.getOrDefault(emptyList())
        }
        _uiState.update { it.copy(placeSearch = PlaceSearchState(query = q, loading = false, results = results)) }
        selectTab(MainTab.NAVIGATION)
        emitWebEvent("openMode", mode = "map")
        if (results.isEmpty()) return "Nenhum lugar encontrado para \"$q\" perto daqui."
        return results.take(5).mapIndexed { i, r ->
            val dist = r.distanceKm?.let { " (%.1f km)".format(it) }.orEmpty()
            val addr = r.address.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()
            "${i + 1}. ${r.name}$dist$addr"
        }.joinToString("\n")
    }

    override suspend fun searchAndPlayMusic(query: String): String {
        val q = query.trim()
        if (q.isEmpty()) return controlMusic("MUSIC_PLAY", null)

        if (mediaController.spotify.isConnected) {
            val res = withContext(Dispatchers.IO) {
                runCatching { spotifyWeb.search(q) }.getOrNull()
            }
            val targetUri = res?.tracks?.firstOrNull()?.uri
                ?: res?.playlists?.firstOrNull()?.uri
                ?: res?.albums?.firstOrNull()?.uri
                ?: res?.artists?.firstOrNull()?.uri
            if (!targetUri.isNullOrBlank()) {
                mediaController.spotify.play(targetUri)
                selectTab(MainTab.MUSIC)
                emitWebEvent("openMode", mode = "music")
                return "Spotify: $q"
            }
        }
        mediaController.searchAndPlayInSpotify(q)
        return "Busca: $q"
    }

    override fun openReverseCamera(): String {
        openManualCameraPreview()
        return "Câmera de Ré aberta"
    }

    override fun closeReverseCamera(): String {
        exitManualCameraPreview()
        return "Câmera de Ré fechada"
    }

    override fun adjustVolume(direction: VolumeDirection, value: Int?): String {
        val cur = _uiState.value.masterVolumePct
        return when (direction) {
            VolumeDirection.UP -> {
                val next = (cur + 15).coerceAtMost(100)
                setMasterVolumePct(next)
                "Volume $next%"
            }
            VolumeDirection.DOWN -> {
                val next = (cur - 15).coerceAtLeast(0)
                setMasterVolumePct(next)
                "Volume $next%"
            }
            VolumeDirection.SET -> {
                val next = (value ?: 60).coerceIn(0, 100)
                setMasterVolumePct(next)
                "Volume $next%"
            }
            VolumeDirection.MUTE -> {
                if (!_uiState.value.isAudioMuted) toggleAudioMute()
                "Mudo"
            }
            VolumeDirection.UNMUTE -> {
                if (_uiState.value.isAudioMuted) toggleAudioMute()
                "Som ativo"
            }
        }
    }

    override fun switchTab(tab: MainTab): String {
        selectTab(tab)
        val webMode = when (tab) {
            MainTab.HOME -> "home"
            MainTab.NAVIGATION -> "map"
            MainTab.MUSIC -> "music"
            MainTab.SETTINGS, MainTab.OBD_SCANNER -> "settings"
            MainTab.AGENT_VOICE -> "home"
        }
        emitWebEvent("openMode", mode = webMode)
        return "Tela ${tab.name}"
    }

    override fun toggleReverseCamera(): String {
        toggleManualCameraPreview()
        return "Câmera de Ré"
    }

    override fun replyLatestWhatsApp(replyText: String): String {
        val latest = mediaController.messages.value.firstOrNull()
            ?: return "Nenhuma conversa disponível para responder."
        val ok = mediaController.replyToMessage(latest, replyText)
        return if (ok) {
            "Resposta enviada para ${latest.sender}."
        } else {
            "Essa notificação não permite resposta rápida."
        }
    }

    override fun getWeatherSummary(): String? = _uiState.value.weather.spokenSummary()

    override fun markMessagesRead() {
        mediaController.messages.value.firstOrNull()?.let { mediaController.markMessageRead(it.key) }
    }

    // --- Câmera & OBD2 ---
    fun toggleManualCameraPreview() {
        coordinator.toggleManualCameraPreview()
        publishState()
    }

    fun openManualCameraPreview() {
        coordinator.enterManualCameraPreview()
        publishState()
    }

    fun exitManualCameraPreview() {
        coordinator.exitManualCameraPreview()
        publishState()
    }

    fun toggleSimulatedReverse() {
        val next = !simulatedCamera.simulatedReverseActive
        simulatedCamera.simulatedReverseActive = next
        _uiState.update { it.copy(simulatedReverseActive = next) }
    }

    fun calibrateCameraNoSignal(): Boolean {
        val grid = latestValidGrid ?: return false
        if (grid.luma.isEmpty()) return false
        val cal = signalDetector.captureCalibration(grid, toleranceMad = 12.0)
        updateConfig { it.copy(noSignalCalibration = cal) }
        return true
    }

    fun clearNoSignalCalibration() {
        signalDetector.calibration = null
        updateConfig { it.copy(noSignalCalibration = null) }
    }

    fun connectObdNow() {
        if (obdJob?.isActive != true) {
            startObdLoop()
        }
    }

    fun disconnectObd() {
        obdJob?.cancel()
        obdJob = null
        scope.launch {
            obdTransport?.disconnect()
            obdTransport = null
            coordinator.updateObdStatus(LinkStatus.DISCONNECTED)
            coordinator.updateObdSnapshot(coordinator.state.obdSnapshot.copy(isStale = true))
            _uiState.update { it.copy(obdMessage = "OBD-II desconectado manualmente", obdCycleHz = 0.0) }
            publishState()
        }
    }

    fun readDtcCodes() {
        val transport = obdTransport ?: return
        if (!transport.isConnected) return
        scope.launch {
            _uiState.update { it.copy(dtcBusy = true, dtcActionFeedback = null) }
            runCatching {
                val collected = mutableListOf<DtcCode>()
                for (category in DtcCategory.entries) {
                    val raw = transport.sendCommand(category.modeCommand, timeoutMs = 2_500L)
                    collected += ObdResponseParser.parseDtcCodes(raw, category)
                }
                val milRaw = transport.sendCommand("0101", timeoutMs = 1_500L)
                val mil = ObdResponseParser.parseMilStatus0101(milRaw)
                if (mil != null) {
                    coordinator.updateObdSnapshot(
                        coordinator.state.obdSnapshot.copy(milOn = mil.first, milDtcCount = mil.second)
                    )
                }
                pendingDtcCount = collected.count { it.category == DtcCategory.PENDING }
                coordinator.updateDtcCodes(collected)
            }
            _uiState.update { it.copy(dtcBusy = false) }
            publishState()
        }
    }

    fun clearDtcCodes() {
        val transport = obdTransport ?: return
        if (!transport.isConnected) return
        scope.launch {
            _uiState.update { it.copy(dtcBusy = true, dtcActionFeedback = null) }
            val result = runCatching {
                val raw = transport.sendCommand("04", timeoutMs = 3_000L)
                if (ObdResponseParser.isClearDtcConfirmed(raw)) {
                    pendingDtcCount = 0
                    coordinator.updateDtcCodes(emptyList())
                    val refreshed = coordinator.state.obdSnapshot.copy(milOn = false, milDtcCount = 0)
                    coordinator.updateObdSnapshot(
                        refreshed.copy(derived = computeDerived(refreshed, _uiState.value.config))
                    )
                    "Falhas limpas com sucesso."
                } else {
                    "ECU não confirmou limpeza. Deixe a chave ligada com o motor desligado."
                }
            }
            val msg = result.getOrElse { "Erro ao limpar falhas: ${it.message}" }
            _uiState.update { it.copy(dtcBusy = false, dtcActionFeedback = msg) }
            publishState()
        }
    }

    fun resetTripConsumption() {
        fuelCalculator.resetTrip()
        peakRpm = 0.0
        peakSpeedKmh = 0.0
        peakCoolantTempC = null
        val cfg = _uiState.value.config.copy(tripDistanceKm = 0.0, tripFuelLiters = 0.0)
        preferences.save(cfg)
        _uiState.update { it.copy(config = cfg) }
        val snap = coordinator.state.obdSnapshot.copy(averageKmPerL = null)
        coordinator.updateObdSnapshot(snap.copy(derived = computeDerived(snap, cfg)))
        publishState()
    }

    fun markTankFull() {
        setVirtualTankLiters(_uiState.value.config.virtualTankCapacityLiters)
    }

    fun addFuelLiters(liters: Double) {
        if (liters <= 0.0) return
        setVirtualTankLiters(virtualTankRemainingLiters + liters)
    }

    fun setVirtualTankLiters(liters: Double) {
        val prev = _uiState.value.config
        val clamped = liters.coerceIn(0.0, prev.virtualTankCapacityLiters)
        virtualTankRemainingLiters = clamped
        val updated = prev.copy(virtualTankRemainingLiters = clamped)
        preferences.save(updated)
        _uiState.update { it.copy(config = updated) }
        val snap = coordinator.state.obdSnapshot
        coordinator.updateObdSnapshot(snap.copy(derived = computeDerived(snap, updated)))
        publishState()
    }

    // --- Diário de abastecimentos ---
    private fun refreshFuelLogState(entries: List<FuelEntry> = fuelLogStore.load()) {
        val summary = FuelLogCalculator.summarize(entries, _uiState.value.config.fuelCalibrationFactor, System.currentTimeMillis())
        _uiState.update { it.copy(fuelEntries = entries.sortedByDescending { e -> e.timestampMs }, fuelSummary = summary) }
    }

    /**
     * Registra um abastecimento (voz, Gemini ou tela). Atualiza o tanque virtual, recalcula o
     * consumo real e, ao fechar um trecho tanque cheio a tanque cheio, recalibra o cálculo de
     * combustível do OBD para o tanque virtual e a autonomia ficarem mais próximos do real.
     */
    fun logRefuel(liters: Double, pricePerLiter: Double?, odometerKm: Double?, fullTank: Boolean): String {
        if (liters <= 0.0 || liters > 120.0) return "Não entendi os litros. Diga, por exemplo: abasteci 40 litros a 6,29, completei."
        val trip = fuelCalculator.exportTripState()
        val entry = FuelEntry(
            timestampMs = System.currentTimeMillis(),
            odometerKm = odometerKm,
            liters = liters,
            pricePerLiter = pricePerLiter,
            fullTank = fullTank,
            appTripKm = trip.distanceKm,
            appTripFuelL = trip.fuelLiters
        )
        val entries = fuelLogStore.load() + entry
        fuelLogStore.save(entries)

        if (fullTank) markTankFull() else addFuelLiters(liters)

        val cfg = _uiState.value.config
        val summary = FuelLogCalculator.summarize(entries, cfg.fuelCalibrationFactor, entry.timestampMs)
        summary.suggestedCalibrationFactor?.let { f -> updateConfig { it.copy(fuelCalibrationFactor = f) } }
        refreshFuelLogState(entries)

        val parts = mutableListOf("Abastecimento registrado: ${"%.1f".format(liters)} litros")
        entry.totalCost?.let { parts += "total de ${"%.2f".format(it)} reais" }
        if (fullTank) {
            summary.lastKmPerL?.takeIf { odometerKm != null }?.let { parts += "consumo real do último tanque: ${"%.1f".format(it)} km por litro" }
        } else {
            parts += "anotei como parcial"
        }
        if (odometerKm == null) parts += "sem hodômetro, então não deu para calcular o consumo real"
        return parts.joinToString(", ") + "."
    }

    fun deleteFuelEntry(timestampMs: Long) {
        val entries = fuelLogStore.load().filterNot { it.timestampMs == timestampMs }
        fuelLogStore.save(entries)
        refreshFuelLogState(entries)
    }

    override fun logRefuelFromAgent(liters: Double, pricePerLiter: Double?, odometerKm: Double?, fullTank: Boolean): String =
        logRefuel(liters, pricePerLiter, odometerKm, fullTank)

    override fun getFuelLogSummary(): String? {
        val s = _uiState.value.fuelSummary
        if (s.entryCount == 0) return null
        val bits = mutableListOf<String>()
        s.averageKmPerL?.let { bits += "consumo real médio ${"%.1f".format(it)} km/L" }
        s.lastKmPerL?.let { bits += "último tanque ${"%.1f".format(it)} km/L" }
        s.costPerKm?.let { bits += "custo de ${"%.2f".format(it)} reais por km" }
        bits += "gasto nos últimos 30 dias ${"%.2f".format(s.spentLast30Days)} reais"
        s.lastPricePerLiter?.let { bits += "último preço ${"%.2f".format(it)} reais o litro" }
        return bits.joinToString("; ")
    }

    fun setTransmissionType(type: TransmissionType) {
        updateConfig { it.copy(transmissionType = type) }
        val snap = coordinator.state.obdSnapshot
        coordinator.updateObdSnapshot(snap.copy(derived = computeDerived(snap, _uiState.value.config)))
        publishState()
    }

    fun refreshUsbDevices() {
        usbHubCoordinator.refreshDevices(requestMissingPermissions = true)
        usbCamera.refreshFromUsb()
    }

    fun requestUsbPermission(deviceName: String) {
        usbHubCoordinator.requestPermissionForDevice(deviceName)
    }

    fun attachCameraSurface(surface: android.view.Surface?) {
        usbCamera.attachSurface(surface)
    }

    fun selectCameraChannel(channel: Int) {
        val clamped = channel.coerceIn(0, 3)
        updateConfig { it.copy(cameraInputChannel = clamped) }
    }

    fun setCameraStandardNtsc(isNtsc: Boolean) {
        updateConfig { it.copy(cameraIsNtsc = isNtsc) }
    }

    /**
     * Clima pelo Open-Meteo: atualiza a cada 15 min ou ao andar mais de ~10 km.
     * Sem GPS ainda, espera; sem internet, mantém o último dado e tenta de novo em 2 min.
     */
    private suspend fun weatherLoop() {
        var lastLat = Double.NaN
        var lastLon = Double.NaN
        var lastFetchMs = 0L
        while (true) {
            val gps = _uiState.value.gpsState
            if (gps.hasFix) {
                val moved = lastLat.isNaN() ||
                    abs(gps.latitude - lastLat) > 0.09 || abs(gps.longitude - lastLon) > 0.09
                val stale = System.currentTimeMillis() - lastFetchMs > WEATHER_REFRESH_MS
                if (moved || stale) {
                    val fetched = try {
                        WeatherService.fetch(gps.latitude, gps.longitude)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (fetched != null) {
                        lastLat = gps.latitude
                        lastLon = gps.longitude
                        lastFetchMs = System.currentTimeMillis()
                        _uiState.update { it.copy(weather = fetched) }
                    } else {
                        _uiState.update { s ->
                            s.copy(weather = s.weather.copy(statusText = "Clima indisponível (sem internet)"))
                        }
                        delay(WEATHER_RETRY_MS)
                        continue
                    }
                }
            }
            delay(WEATHER_TICK_MS)
        }
    }

    private fun observeAllFlows() {
        scope.launch {
            usbHubCoordinator.devices.collect { list ->
                _uiState.update { it.copy(usbDevices = list) }
                usbCamera.refreshFromUsb()
            }
        }
        scope.launch {
            audioMonitor.status.collect { route ->
                coordinator.updateAudioBluetooth(route.linkStatus, route.routeLabel)
                ignitionMonitor.syncFromAudioRoute(
                    routeConnected = route.linkStatus == LinkStatus.CONNECTED,
                    routeLabel = route.routeLabel,
                    config = _uiState.value.config
                )
                _uiState.update { it.copy(audioRoute = route) }
                publishState()
            }
        }
        scope.launch {
            mediaController.mediaState.collect { media ->
                _uiState.update { it.copy(mediaState = media) }
            }
        }
        scope.launch {
            mediaController.messages.collect { msgs ->
                _uiState.update { it.copy(whatsAppMessages = msgs) }
            }
        }
        scope.launch {
            gpsTracker.state.collect { gps ->
                _uiState.update { it.copy(gpsState = gps) }
                if (gps.hasFix) routeNavigator.onLocation(RoutePoint(gps.latitude, gps.longitude))
            }
        }
        scope.launch {
            routeNavigator.state.collect { route ->
                val justArrived = route.arrived && !lastObservedRouteArrived
                lastObservedRouteArrived = route.arrived
                _uiState.update { it.copy(route = route) }
                if (justArrived) {
                    emitWebEvent("routeArrived", "Você chegou ao destino")
                }
            }
        }
        scope.launch {
            mediaController.spotify.status.collect { st ->
                _uiState.update { it.copy(spotifyStatus = st) }
                if (st == SpotifyLinkStatus.CONNECTED) refreshSpotifyPlaylists()
            }
        }
        scope.launch { weatherLoop() }
        scope.launch(Dispatchers.IO) { refreshFuelLogState() }
        scope.launch {
            ignitionMonitor.status.collect { ign ->
                _uiState.update { it.copy(ignitionStatus = ign) }
            }
        }
        scope.launch {
            carAgent.state.collect { agent ->
                val latestBot = agent.messages.lastOrNull { !it.isUser }
                val isNewReply = latestBot != null && latestBot.id != lastObservedAgentMsgId
                if (latestBot != null) {
                    lastObservedAgentMsgId = latestBot.id
                }
                _uiState.update { it.copy(agentState = agent) }
                if (isNewReply && latestBot.text.isNotBlank()) {
                    emitWebEvent("agentReply", latestBot.text)
                }
            }
        }
    }

    private fun startCameraSignalMonitoring(useSimulated: Boolean) {
        cameraJob?.cancel()
        signalDetector.resetHistory()
        val source = if (useSimulated) simulatedCamera else usbCamera

        cameraJob = scope.launch {
            launch {
                source.hardwareStatus.collect { hw ->
                    _uiState.update { it.copy(cameraHardware = hw) }
                }
            }
            source.signalEvents(intervalMs = 140L).collect { sample ->
                val grid = sample.grid
                latestValidGrid = grid.takeIf { it.luma.isNotEmpty() }
                val prevCamState = coordinator.state.cameraState
                val analysis = sample.hardwareSignalOverride ?: signalDetector.analyze(grid)
                coordinator.onCameraSample(analysis, System.currentTimeMillis())
                _cameraDebug.value = CameraDebugState(analysis = analysis, lastGrid = grid)
                if (coordinator.state.cameraState != prevCamState) {
                    publishState()
                }
            }
        }
    }

    private fun restartObdLoop() {
        obdJob?.cancel()
        obdJob = null
        scope.launch {
            obdTransport?.disconnect()
            obdTransport = null
            startObdLoop()
        }
    }

    private fun startObdLoop() {
        obdJob?.cancel()
        obdJob = scope.launch {
            var attempt = 0
            while (isActive) {
                if (_uiState.value.ignitionStatus.standbyActive) {
                    delay(1_000L)
                    continue
                }

                val cfg = _uiState.value.config
                val transport: ObdTransport = when {
                    cfg.useSimulatedObd -> SimulatedObdTransport()
                    !cfg.obdDeviceMac.isNullOrBlank() -> BluetoothClassicObdTransport(appContext, cfg.obdDeviceMac)
                    else -> {
                        coordinator.updateObdStatus(LinkStatus.DISCONNECTED)
                        _uiState.update {
                            it.copy(
                                obdMessage = "Selecione o OBD-II na Config ou ative o Simulador",
                                obdCycleHz = 0.0
                            )
                        }
                        publishState()
                        delay(2_500L)
                        continue
                    }
                }

                obdTransport = transport
                coordinator.updateObdStatus(LinkStatus.CONNECTING)
                _uiState.update {
                    it.copy(
                        obdMessage = if (cfg.useSimulatedObd) {
                            "Iniciando Simulador OBD-II Turbo..."
                        } else {
                            "Conectando a ${cfg.obdDeviceName ?: cfg.obdDeviceMac}..."
                        }
                    )
                }
                publishState()

                val connectResult = transport.connect()
                if (connectResult.isFailure) {
                    val err = connectResult.exceptionOrNull()?.message ?: "Falha na conexão OBD-II"
                    coordinator.updateObdStatus(LinkStatus.ERROR)
                    _uiState.update { it.copy(obdMessage = "$err (nova tentativa)", obdCycleHz = 0.0) }
                    publishState()
                    delay(ObdPollingPlanner.reconnectDelayMs(attempt++, coordinator.state.powerConnected))
                    continue
                }

                var initOk = true
                var capabilities = SupportedPidCapabilities(
                    supportsMap = cfg.preferSpeedDensity,
                    supportsMaf = !cfg.preferSpeedDensity
                )
                for (initCmd in ObdPollingPlanner.initSequence(cfg.savedObdProtocol)) {
                    val resp = runCatching {
                        transport.sendCommand(initCmd.command, initCmd.timeoutMs)
                    }.getOrNull()

                    if (initCmd.command == "0100" && resp != null) {
                        ObdResponseParser.parseSupportedPids0100(resp)?.let { capabilities = it }
                    }

                    val valid = when {
                        resp == null -> false
                        initCmd.optional -> true
                        initCmd.command.startsWith("AT") ->
                            ObdResponseParser.isValidElmInitResponse(resp)
                        else -> !ObdResponseParser.isErrorResponse(ObdResponseParser.sanitize(resp))
                    }
                    if (!valid && !initCmd.optional) {
                        initOk = false
                        break
                    }
                    delay(80L)
                }

                if (!initOk) {
                    transport.disconnect()
                    coordinator.updateObdStatus(LinkStatus.ERROR)
                    _uiState.update { it.copy(obdMessage = "ELM327 não respondeu", obdCycleHz = 0.0) }
                    publishState()
                    delay(ObdPollingPlanner.reconnectDelayMs(attempt++, coordinator.state.powerConnected))
                    continue
                }

                if (cfg.savedObdProtocol.isNullOrBlank()) {
                    runCatching {
                        val dpn = ObdResponseParser.sanitize(transport.sendCommand("ATDPN", 1_000L))
                        val protoChar = dpn.removePrefix("A").firstOrNull { it in '1'..'9' || it in 'A'..'C' }
                        if (protoChar != null) {
                            updateConfig { it.copy(savedObdProtocol = protoChar.toString()) }
                        }
                    }
                }

                attempt = 0
                coordinator.updateObdStatus(LinkStatus.CONNECTED)
                _uiState.update {
                    it.copy(
                        obdMessage = if (cfg.useSimulatedObd) {
                            "Simulador OBD-II Turbo ativo"
                        } else {
                            "OBD-II conectado (${cfg.obdDeviceName ?: "ELM327"})"
                        }
                    )
                }
                publishState()

                runCatching {
                    val milRaw = transport.sendCommand("0101", timeoutMs = 1_500L)
                    val mil = ObdResponseParser.parseMilStatus0101(milRaw)
                    val stored = ObdResponseParser.parseDtcCodes(
                        transport.sendCommand("03", timeoutMs = 2_000L),
                        DtcCategory.STORED
                    )
                    val pending = ObdResponseParser.parseDtcCodes(
                        transport.sendCommand("07", timeoutMs = 2_000L),
                        DtcCategory.PENDING
                    )
                    pendingDtcCount = pending.size
                    coordinator.updateDtcCodes(stored + pending)
                    if (mil != null) {
                        coordinator.updateObdSnapshot(
                            coordinator.state.obdSnapshot.copy(milOn = mil.first, milDtcCount = mil.second)
                        )
                    }
                    publishState()
                }

                var useSingleFrameSuffix = false
                runCatching {
                    val probeRaw = transport.sendCommand("010C1", timeoutMs = 900L)
                    if (ObdResponseParser.parsePid(ObdPid.RPM, probeRaw) != null) {
                        useSingleFrameSuffix = true
                    }
                }

                var cycle = 0L
                var emptyCycles = 0
                var currentSnap = coordinator.state.obdSnapshot.copy(isStale = false)
                var smoothedHz = 0.0

                while (isActive && transport.isConnected) {
                    if (_uiState.value.ignitionStatus.standbyActive) {
                        delay(500L)
                        continue
                    }

                    val cycleStart = System.currentTimeMillis()
                    val profile = coordinator.state.activeObdProfile
                    val commands = ObdPollingPlanner.nextCommands(
                        profile = profile,
                        cycleIndex = cycle++,
                        capabilities = capabilities
                    )

                    try {
                        var validResponses = 0
                        for (cmd in commands) {
                            val wireCmd = ObdPollingPlanner.formatCommand(cmd, useSingleFrameSuffix)
                            val raw = transport.sendCommand(wireCmd, timeoutMs = 800L)
                            val updated = applyParsedCommand(cmd, raw, currentSnap)
                            if (updated != null) {
                                currentSnap = updated
                                validResponses++
                                if (cmd == ObdPid.RPM.command ||
                                    cmd == ObdPid.SPEED.command ||
                                    cmd == ObdPid.ENGINE_LOAD.command ||
                                    cmd == ObdPid.COOLANT_TEMP.command ||
                                    cmd == "ATRV"
                                ) {
                                    val cfgFast = _uiState.value.config
                                    val fastDerived = computeDerived(currentSnap, cfgFast)
                                    peakRpm = fastDerived.peakRpm
                                    peakSpeedKmh = fastDerived.peakSpeedKmh
                                    peakCoolantTempC = fastDerived.peakCoolantTempC
                                    currentSnap = currentSnap.copy(derived = fastDerived)
                                    coordinator.updateObdSnapshot(currentSnap)
                                    publishState()
                                }
                            }
                            delay(2L)
                        }

                        if (validResponses == 0) {
                            emptyCycles++
                            if (emptyCycles >= 5) {
                                throw IllegalStateException("Sem resposta da ECU por 5 ciclos")
                            }
                        } else {
                            emptyCycles = 0
                        }

                        val now = System.currentTimeMillis()
                        val elapsedMs = (now - cycleStart).coerceAtLeast(15L)
                        val instantHz = 1000.0 / elapsedMs
                        smoothedHz = if (smoothedHz <= 0.1) instantHz else (smoothedHz * 0.7 + instantHz * 0.3)

                        val fuel = fuelCalculator.recordSampleDetailed(
                            speedKmh = currentSnap.speedKmh,
                            mafSensor = currentSnap.mafGramsPerSec,
                            intakeMapKpa = currentSnap.intakeMapKpa,
                            intakeTempC = currentSnap.intakeTempC,
                            rpm = currentSnap.rpm,
                            throttlePct = currentSnap.throttlePct,
                            stftPct = currentSnap.stftPct,
                            ltftPct = currentSnap.ltftPct,
                            timestampMs = now
                        )

                        val cfgNow = _uiState.value.config
                        if (fuel.deltaFuelLiters > 0.0) {
                            virtualTankRemainingLiters = (virtualTankRemainingLiters - fuel.deltaFuelLiters)
                                .coerceIn(0.0, cfgNow.virtualTankCapacityLiters)
                        }

                        val preliminarySnap = currentSnap.copy(
                            instantKmPerL = fuel.instantKmPerL,
                            averageKmPerL = fuel.averageKmPerL ?: currentSnap.averageKmPerL,
                            litersPerHour = fuel.litersPerHour,
                            isStale = false,
                            timestampMs = now
                        )
                        val derived = computeDerived(preliminarySnap, cfgNow)
                        peakRpm = derived.peakRpm
                        peakSpeedKmh = derived.peakSpeedKmh
                        peakCoolantTempC = derived.peakCoolantTempC
                        currentSnap = preliminarySnap.copy(derived = derived)

                        val volts = currentSnap.batteryVoltageV
                        val engineRunning = (currentSnap.rpm ?: 0.0) > 400.0
                        val lowLimit = cfgNow.alertThresholds.batteryLowV
                        val isLow = volts != null && ((engineRunning && volts <= lowLimit) || (!engineRunning && volts < 11.7))
                        val lowDuration = if (isLow) {
                            val start = lowVoltageSinceMs ?: now.also { lowVoltageSinceMs = it }
                            now - start
                        } else {
                            lowVoltageSinceMs = null
                            0L
                        }

                        coordinator.updateObdSnapshot(currentSnap, lowVoltageDurationMs = lowDuration)
                        _uiState.update { it.copy(obdCycleHz = smoothedHz) }
                        publishState()

                        if (cycle % 20L == 0L) {
                            val trip = fuelCalculator.exportTripState()
                            val updatedCfg = cfgNow.copy(
                                tripDistanceKm = trip.distanceKm,
                                tripFuelLiters = trip.fuelLiters,
                                virtualTankRemainingLiters = virtualTankRemainingLiters
                            )
                            preferences.save(updatedCfg)
                            _uiState.update { it.copy(config = updatedCfg) }
                        }

                        val cadenceDelay = when (profile) {
                            ObdPollingProfile.HOME_TURBO -> 4L
                            ObdPollingProfile.SCANNER_DETAILED -> 20L
                            ObdPollingProfile.DOCK_BASIC -> 30L
                        }
                        delay(cadenceDelay)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Exception) {
                        transport.disconnect()
                        break
                    }
                }

                coordinator.updateObdStatus(LinkStatus.DISCONNECTED)
                coordinator.updateObdSnapshot(coordinator.state.obdSnapshot.copy(isStale = true))
                _uiState.update { it.copy(obdCycleHz = 0.0) }
                publishState()
                delay(ObdPollingPlanner.reconnectDelayMs(attempt++, coordinator.state.powerConnected))
            }
        }
    }

    private fun applyParsedCommand(cmd: String, raw: String, snap: ObdSnapshot): ObdSnapshot? {
        if (cmd == "ATRV") {
            val v = ObdResponseParser.parseBatteryVoltage(raw) ?: return null
            return snap.copy(batteryVoltageV = v)
        }
        val pid = ObdPid.entries.firstOrNull { it.command == cmd } ?: return null
        val value = ObdResponseParser.parsePid(pid, raw) ?: return null
        return when (pid) {
            ObdPid.FUEL_SYSTEM_STATUS -> snap.copy(fuelSystemStatusCode = value.toInt())
            ObdPid.COOLANT_TEMP -> snap.copy(coolantTempC = value)
            ObdPid.RPM -> snap.copy(rpm = value)
            ObdPid.SPEED -> snap.copy(speedKmh = value)
            ObdPid.ENGINE_LOAD -> snap.copy(engineLoadPct = value)
            ObdPid.THROTTLE_POS -> snap.copy(throttlePct = value)
            ObdPid.INTAKE_MAP -> snap.copy(intakeMapKpa = value)
            ObdPid.MAF -> snap.copy(mafGramsPerSec = value)
            ObdPid.INTAKE_TEMP -> snap.copy(intakeTempC = value)
            ObdPid.TIMING_ADVANCE -> snap.copy(timingAdvanceDeg = value)
            ObdPid.STFT_BANK1 -> snap.copy(stftPct = value)
            ObdPid.LTFT_BANK1 -> snap.copy(ltftPct = value)
            ObdPid.O2_SENSOR_B1S1_VOLTAGE -> snap.copy(o2SensorB1S1VoltageV = value)
            ObdPid.FUEL_LEVEL -> snap.copy(fuelLevelPct = value)
            ObdPid.CONTROL_MODULE_VOLTAGE -> snap.copy(batteryVoltageV = snap.batteryVoltageV ?: value)
        }
    }

    private fun computeDerived(snapshot: ObdSnapshot, cfg: HeadUnitConfig): DerivedTelemetry {
        val trip = fuelCalculator.exportTripState()
        return CivicTelemetryCalculator.compute(
            snapshot = snapshot,
            tire = TireSpec.CIVIC_2005_POWERCONTACT2_195_65_R15,
            transmission = cfg.transmissionType,
            virtualTankCapacityLiters = cfg.virtualTankCapacityLiters,
            virtualTankRemainingLiters = virtualTankRemainingLiters,
            tripDistanceKm = trip.distanceKm,
            tripFuelUsedLiters = trip.fuelLiters,
            peakRpm = peakRpm,
            peakSpeedKmh = peakSpeedKmh,
            peakCoolantTempC = peakCoolantTempC,
            pendingDtcCount = pendingDtcCount,
            isReverseActive = coordinator.state.isHardwareReverseActive
        )
    }

    private fun applyConfigToEngines(config: HeadUnitConfig) {
        fuelCalculator.fuelType = config.fuelType
        fuelCalculator.engineDisplacementLiters = config.engineDisplacementLiters
        fuelCalculator.calibrationFactor = config.fuelCalibrationFactor
        fuelCalculator.speedCorrectionFactor = config.tireCorrectionFactor
        signalDetector.calibration = config.noSignalCalibration
        mediaController.setPreferredMusicApp(config.preferredMusicPackage, config.preferredMusicLabel)
        carAgent.onConfigUpdated(config)
        usbCamera.selectChannel(config.cameraInputChannel)
        usbCamera.setStandard(config.cameraIsNtsc)
        coordinator.alertThresholds = config.alertThresholds
        coordinator.configure(
            setupCompleted = config.setupCompleted,
            duckAudioOnReverse = config.duckAudioOnReverse,
            obdFastTurboMode = config.obdFastTurboMode
        )
    }

    private fun readCurrentVolumePct(): Int {
        val mgr = audioManager ?: return 70
        val max = mgr.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = mgr.getStreamVolume(AudioManager.STREAM_MUSIC)
        return ((cur * 100f) / max).toInt().coerceIn(0, 100)
    }

    private fun publishState() {
        val state = coordinator.state
        val simCam = _uiState.value.config.useSimulatedCamera
        val wantStream = state.isReverseOverlayVisible && !simCam
        if (wantStream != lastRequestedCameraStream) {
            lastRequestedCameraStream = wantStream
            scope.launch(Dispatchers.IO) {
                usbCamera.setReverseOverlayVisible(wantStream)
            }
        }
        if (state.shouldDuckAudio) {
            audioMonitor.requestReverseDucking()
        } else {
            audioMonitor.releaseReverseDucking()
        }
        _uiState.update { it.copy(headUnit = state) }
    }

    companion object {
        private const val WEATHER_REFRESH_MS = 15 * 60_000L
        private const val WEATHER_RETRY_MS = 2 * 60_000L
        private const val WEATHER_TICK_MS = 30_000L
        private const val KEY_SPOTIFY_RECENT_SEARCHES = "recent_searches_json"
        private const val MAX_SPOTIFY_RECENT_SEARCHES = 8

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: HeadUnitRuntime? = null

        fun get(context: Context): HeadUnitRuntime {
            return instance ?: synchronized(this) {
                instance ?: HeadUnitRuntime(context.applicationContext).also { instance = it }
            }
        }
    }
}

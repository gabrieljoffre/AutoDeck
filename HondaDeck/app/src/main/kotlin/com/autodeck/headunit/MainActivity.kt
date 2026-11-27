package com.autodeck.headunit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autodeck.headunit.service.HeadUnitForegroundService
import com.autodeck.headunit.media.SpotifyWebApi
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import com.autodeck.headunit.ui.HeadUnitViewModel
import com.autodeck.headunit.ui.overlay.CriticalAlertBanner
import com.autodeck.headunit.ui.overlay.ReverseCameraOverlay
import com.autodeck.headunit.ui.overlay.StandbyBlackOverlay
import com.autodeck.headunit.ui.screens.BootSplashOverlay
import com.autodeck.headunit.ui.screens.SetupWizardScreen
import com.autodeck.headunit.ui.theme.AutoDeckTheme
import com.autodeck.headunit.web.WebUiHost

class MainActivity : ComponentActivity() {

    private val viewModel: HeadUnitViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.onPermissionsUpdated()
    }

    /** Tela oficial de autorização do Spotify (escopo app-remote-control). */
    private val spotifyAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val response = AuthorizationClient.getResponse(result.resultCode, result.data)
        when (response.type) {
            AuthorizationResponse.Type.TOKEN -> {
                response.accessToken?.let { viewModel.onSpotifyToken(it, response.expiresIn) }
                viewModel.onSpotifyAuthorized(this)
            }
            AuthorizationResponse.Type.ERROR -> viewModel.onSpotifyAuthFailed(response.error ?: "erro")
            else -> viewModel.onSpotifyAuthFailed("cancelado")
        }
    }

    private fun launchSpotifyAuth() {
        if (BuildConfig.SPOTIFY_CLIENT_ID.isBlank()) return
        val request = AuthorizationRequest.Builder(
            BuildConfig.SPOTIFY_CLIENT_ID,
            AuthorizationResponse.Type.TOKEN,
            BuildConfig.SPOTIFY_REDIRECT_URI
        ).setScopes(SpotifyWebApi.SCOPES)
            .build()
        runCatching { spotifyAuthLauncher.launch(AuthorizationClient.createLoginActivityIntent(this, request)) }
            .onFailure { viewModel.onSpotifyAuthFailed(it.javaClass.simpleName) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.setSpotifyAuthRequester { launchSpotifyAuth() }
        viewModel.setCalendarPermissionRequester { requestCalendarPermission() }
        enableEdgeToEdge()
        runCatching { HeadUnitForegroundService.start(this) }
        applyImmersiveFullscreen()
        requestRuntimePermissionsIfNeeded()

        setContent {
            AutoDeckTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                var showBootSplash by rememberSaveable {
                    mutableStateOf(uiState.config.introMode.equals("VIDEO", ignoreCase = true))
                }
                var lastHandledBootTrigger by rememberSaveable { mutableIntStateOf(0) }

                val standbyActive = uiState.ignitionStatus.standbyActive
                val isVideoIntro = uiState.config.introMode.equals("VIDEO", ignoreCase = true)

                // Mantém tela acesa enquanto ligado e reduz brilho ao mínimo no Standby do Taramps
                LaunchedEffect(standbyActive, uiState.headUnit.powerConnected) {
                    if (!standbyActive && uiState.headUnit.powerConnected) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    val attrs = window.attributes
                    attrs.screenBrightness = if (standbyActive) 0.01f else -1f
                    window.attributes = attrs
                }

                // Ao ligar o Taramps novamente após standby, exibe splash de vídeo só quando introMode == "VIDEO"
                LaunchedEffect(uiState.bootSplashTrigger, isVideoIntro) {
                    if (uiState.bootSplashTrigger > lastHandledBootTrigger) {
                        lastHandledBootTrigger = uiState.bootSplashTrigger
                        if (isVideoIntro && uiState.config.showBootSplashOnIgnition && !uiState.headUnit.isReverseOverlayVisible) {
                            showBootSplash = true
                        }
                    }
                }

                // Câmera de ré tem prioridade imediata mesmo durante a introdução
                LaunchedEffect(uiState.headUnit.isReverseOverlayVisible) {
                    if (uiState.headUnit.isReverseOverlayVisible) {
                        showBootSplash = false
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    if (!uiState.config.setupCompleted) {
                        SetupWizardScreen(
                            uiState = uiState,
                            onRefreshBluetooth = { viewModel.onPermissionsUpdated() },
                            onRequestPermissions = { requestRuntimePermissionsIfNeeded() },
                            onRefreshUsb = { viewModel.refreshUsbDevices() },
                            onCalibrateNoSignal = { viewModel.calibrateCameraNoSignal() },
                            onToggleLauncherMode = { enabled -> viewModel.setLauncherModeEnabled(enabled) },
                            onFinishSetup = { updated -> viewModel.completeSetup(updated) }
                        )
                    } else {
                        WebUiHost(
                            runtime = viewModel.runtime,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // Banner de alerta crítico OBD-II por cima da WebView
                    val visibleAlert = uiState.headUnit.obdAlerts.firstOrNull {
                        it.title != uiState.headUnit.dismissedAlertTitle
                    }
                    if (visibleAlert != null && !uiState.headUnit.isReverseOverlayVisible && !standbyActive) {
                        CriticalAlertBanner(
                            alert = visibleAlert,
                            onDismiss = { viewModel.dismissCurrentAlert(visibleAlert.title) },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 10.dp)
                        )
                    }

                    // Overlay 100% tela cheia da Câmera de Ré (prioridade máxima)
                    if (uiState.headUnit.isReverseOverlayVisible) {
                        ReverseCameraOverlay(
                            uiState = uiState,
                            onAttachCameraSurface = { viewModel.attachCameraSurface(it) },
                            onSelectCameraChannel = { viewModel.selectCameraChannel(it) },
                            onSetCameraStandardMode = { viewModel.setCameraStandardMode(it) },
                            onToggleMirror = {
                                viewModel.updateConfig {
                                    it.copy(guidelines = it.guidelines.copy(mirrorHorizontal = !it.guidelines.mirrorHorizontal))
                                }
                            },
                            onToggleGuidelines = {
                                viewModel.updateConfig {
                                    it.copy(guidelines = it.guidelines.copy(enabled = !it.guidelines.enabled))
                                }
                            },
                            onToggleSimulatedReverse = { viewModel.toggleSimulatedReverse() },
                            onCloseManual = { viewModel.exitManualCameraPreview() }
                        )
                    } else if (standbyActive) {
                        StandbyBlackOverlay(
                            statusLabel = uiState.ignitionStatus.triggerDeviceLabel,
                            onWakeUp = { viewModel.wakeFromStandbyManually() }
                        )
                    }

                    if (isVideoIntro && showBootSplash && !uiState.headUnit.isReverseOverlayVisible && !standbyActive) {
                        BootSplashOverlay(
                            onFinished = { showBootSplash = false }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        viewModel.setSpotifyAuthRequester(null)
        viewModel.setCalendarPermissionRequester(null)
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        viewModel.setAppForeground(true)
        viewModel.connectSpotify(this)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onPermissionsUpdated()
    }

    override fun onStop() {
        viewModel.setAppForeground(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.refreshUsbDevices()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveFullscreen()
        }
    }

    private fun applyImmersiveFullscreen() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun requestRuntimePermissionsIfNeeded() {
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                missing += Manifest.permission.BLUETOOTH_CONNECT
            }
            if (!hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
                missing += Manifest.permission.BLUETOOTH_ADVERTISE
            }
        }
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            missing += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            missing += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (!hasPermission(Manifest.permission.CAMERA)) {
            missing += Manifest.permission.CAMERA
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            missing += Manifest.permission.RECORD_AUDIO
        }
        if (!hasPermission(Manifest.permission.READ_CALENDAR)) {
            missing += Manifest.permission.READ_CALENDAR
        }
        if (!hasPermission(Manifest.permission.WRITE_CALENDAR)) {
            missing += Manifest.permission.WRITE_CALENDAR
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun requestCalendarPermission() {
        val missing = mutableListOf<String>()
        if (!hasPermission(Manifest.permission.READ_CALENDAR)) missing += Manifest.permission.READ_CALENDAR
        if (!hasPermission(Manifest.permission.WRITE_CALENDAR)) missing += Manifest.permission.WRITE_CALENDAR
        if (missing.isNotEmpty()) {
            runCatching { permissionLauncher.launch(missing.toTypedArray()) }
        } else {
            viewModel.onPermissionsUpdated()
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
}

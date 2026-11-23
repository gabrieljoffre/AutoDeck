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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autodeck.headunit.service.HeadUnitForegroundService
import com.autodeck.headunit.ui.HeadUnitViewModel
import com.autodeck.headunit.ui.screens.BootSplashOverlay
import com.autodeck.headunit.ui.screens.HeadUnitScreen
import com.autodeck.headunit.ui.screens.SetupWizardScreen
import com.autodeck.headunit.ui.theme.AutoDeckTheme

class MainActivity : ComponentActivity() {

    private val viewModel: HeadUnitViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.onPermissionsUpdated()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        runCatching { HeadUnitForegroundService.start(this) }
        applyImmersiveFullscreen()
        requestRuntimePermissionsIfNeeded()

        setContent {
            AutoDeckTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                var showBootSplash by rememberSaveable { mutableStateOf(true) }
                var lastHandledBootTrigger by rememberSaveable { mutableIntStateOf(0) }

                val standbyActive = uiState.ignitionStatus.standbyActive

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

                // Ao ligar o Taramps novamente após standby, exibe splash Honda se configurado
                LaunchedEffect(uiState.bootSplashTrigger) {
                    if (uiState.bootSplashTrigger > lastHandledBootTrigger) {
                        lastHandledBootTrigger = uiState.bootSplashTrigger
                        if (uiState.config.showBootSplashOnIgnition && !uiState.headUnit.isReverseOverlayVisible) {
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
                        HeadUnitScreen(
                            uiState = uiState,
                            viewModel = viewModel
                        )
                    }

                    if (showBootSplash && !uiState.headUnit.isReverseOverlayVisible && !standbyActive) {
                        BootSplashOverlay(
                            onFinished = { showBootSplash = false }
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setAppForeground(true)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
}
